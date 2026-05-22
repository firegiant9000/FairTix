package com.fairtix.holds.application;

import com.fairtix.audit.application.AuditService;
import com.fairtix.common.ResourceNotFoundException;
import com.fairtix.events.domain.Event;
import com.fairtix.events.infrastructure.EventRepository;
import com.fairtix.holds.dto.IssueCompRequest;
import com.fairtix.inventory.application.SeatHoldConflictException;
import com.fairtix.inventory.domain.Seat;
import com.fairtix.inventory.domain.SeatStatus;
import com.fairtix.inventory.infrastructure.SeatRepository;
import com.fairtix.orders.domain.Order;
import com.fairtix.orders.domain.OrderStatus;
import com.fairtix.orders.infrastructure.OrderRepository;
import com.fairtix.tickets.domain.Ticket;
import com.fairtix.tickets.domain.TicketKind;
import com.fairtix.tickets.infrastructure.TicketRepository;
import com.fairtix.users.domain.Role;
import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Issues comp tickets (zero-price, kind=COMP) for an event. Seats are locked
 * in UUID order to share the deadlock prevention strategy used by
 * {@code SeatHoldService}. Per-event comp caps are enforced inside the same
 * transaction that creates the tickets, so parallel issuers can't overshoot.
 */
@Service
public class CompService {

  private static final SecureRandom RANDOM = new SecureRandom();

  private final EventRepository events;
  private final SeatRepository seats;
  private final TicketRepository tickets;
  private final UserRepository users;
  private final OrderRepository orders;
  private final AuditService audit;

  public CompService(EventRepository events,
                     SeatRepository seats,
                     TicketRepository tickets,
                     UserRepository users,
                     OrderRepository orders,
                     AuditService audit) {
    this.events = events;
    this.seats = seats;
    this.tickets = tickets;
    this.users = users;
    this.orders = orders;
    this.audit = audit;
  }

  @Transactional
  public List<Ticket> issueComp(IssueCompRequest request, UUID issuerUserId) {
    Event event = events.findById(request.eventId())
        .orElseThrow(() -> new ResourceNotFoundException("Event not found: " + request.eventId()));

    User issuer = users.findById(issuerUserId)
        .orElseThrow(() -> new ResourceNotFoundException("Issuer not found: " + issuerUserId));

    Integer cap = event.getCompLimit();
    if (cap != null) {
      long already = tickets.countByEvent_IdAndKind(event.getId(), TicketKind.COMP);
      long requested = request.seatIds().stream().distinct().count();
      if (already + requested > cap) {
        throw new CompLimitExceededException(
            "Comp cap of " + cap + " exceeded: already=" + already + ", requested=" + requested);
      }
    }

    List<UUID> sortedSeatIds = request.seatIds().stream().distinct().sorted().toList();
    List<Seat> locked = seats.findAndLockByIdIn(sortedSeatIds);
    if (locked.size() != sortedSeatIds.size()) {
      throw new ResourceNotFoundException("One or more seats not found: " + sortedSeatIds);
    }
    for (Seat s : locked) {
      if (!s.getEvent().getId().equals(event.getId())) {
        throw new IllegalArgumentException("Seat " + s.getId() + " does not belong to event " + event.getId());
      }
      if (s.getStatus() != SeatStatus.AVAILABLE) {
        throw new SeatHoldConflictException(
            "Seat " + s.getId() + " is not available (status: " + s.getStatus() + ")");
      }
    }

    User holder = resolveRecipient(request.recipientEmail(), issuer);

    // Zero-price order anchors the comp tickets so refunds/transfers/audit
    // queries continue to work through the existing infrastructure.
    final Order order = orders.save(new Order(
        holder, Collections.emptyList(), BigDecimal.ZERO, "USD", OrderStatus.COMPLETED));

    String reason = request.reason();
    List<Ticket> issued = locked.stream()
        .map(s -> {
          Ticket t = new Ticket(order, holder, s, event, BigDecimal.ZERO,
              TicketKind.COMP, reason, issuerUserId);
          t.setRecipientName(request.recipientName());
          t.setRecipientEmail(request.recipientEmail());
          t.setWillCall(request.willCall());
          s.setStatus(SeatStatus.SOLD);
          return t;
        })
        .toList();
    tickets.saveAll(issued);
    seats.saveAll(locked);

    String seatList = issued.stream().map(t -> t.getSeat().getId().toString()).collect(Collectors.joining(","));
    audit.log(issuerUserId, "COMP_ISSUED", "TICKET", order.getId(),
        "event=" + event.getId() + " count=" + issued.size()
            + " recipient=" + safe(request.recipientEmail())
            + " reason=" + safe(reason)
            + " seats=[" + seatList + "]");
    return issued;
  }

  private User resolveRecipient(String email, User fallback) {
    if (email == null || email.isBlank()) return fallback;
    String normalized = email.trim().toLowerCase();
    return users.findByEmail(normalized).orElseGet(() -> {
      User stub = new User();
      stub.setEmail(normalized);
      stub.setPassword("comp:" + Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(24)));
      stub.setRole(Role.USER);
      stub.setEmailVerified(false);
      return users.save(stub);
    });
  }

  private static byte[] randomBytes(int n) {
    byte[] b = new byte[n];
    RANDOM.nextBytes(b);
    return b;
  }

  private static String safe(String s) {
    return s == null ? "-" : s.replaceAll("[\\r\\n]", " ");
  }
}
