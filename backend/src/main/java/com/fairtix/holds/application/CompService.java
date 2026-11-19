package com.fairtix.holds.application;

import com.fairtix.audit.application.AuditService;
import com.fairtix.common.ResourceNotFoundException;
import com.fairtix.common.TransactionLock;
import com.fairtix.events.domain.Event;
import com.fairtix.events.infrastructure.EventRepository;
import com.fairtix.holds.dto.IssueCompRequest;
import com.fairtix.inventory.application.SeatHoldConflictException;
import com.fairtix.inventory.domain.Seat;
import com.fairtix.inventory.domain.SeatStatus;
import com.fairtix.inventory.infrastructure.SeatRepository;
import com.fairtix.orders.domain.Order;
import com.fairtix.orders.domain.OrderStatus;
import com.fairtix.orders.application.PurchaseCapExceededException;
import com.fairtix.orders.infrastructure.OrderRepository;
import com.fairtix.tickets.application.PurchaseCapGuard;
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
 * {@code SeatHoldService}. The per-event comp limit and the recipient's purchase
 * cap are counted under advisory locks taken before any seat lock, so parallel
 * issuers can't overshoot either.
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
  private final TransactionLock transactionLock;
  private final PurchaseCapGuard purchaseCapGuard;

  public CompService(EventRepository events,
                     SeatRepository seats,
                     TicketRepository tickets,
                     UserRepository users,
                     OrderRepository orders,
                     AuditService audit,
                     TransactionLock transactionLock,
                     PurchaseCapGuard purchaseCapGuard) {
    this.transactionLock = transactionLock;
    this.purchaseCapGuard = purchaseCapGuard;
    this.events = events;
    this.seats = seats;
    this.tickets = tickets;
    this.users = users;
    this.orders = orders;
    this.audit = audit;
  }

  /**
   * Takes the locks {@link #issueComp} counts under, for a caller that must lock a
   * seat row before calling it ({@code EventHoldService.convertToComp}): advisory
   * locks go before row locks (see {@link TransactionLock}). Advisory locks are
   * re-entrant within a transaction, so {@code issueComp} taking them again is a
   * no-op.
   */
  @Transactional
  public void lockForIssue(UUID eventId, String recipientEmail, UUID issuerUserId) {
    Event event = events.findById(eventId)
        .orElseThrow(() -> new ResourceNotFoundException("Event not found: " + eventId));
    User issuer = users.findById(issuerUserId)
        .orElseThrow(() -> new ResourceNotFoundException("Issuer not found: " + issuerUserId));
    lockAndResolveRecipient(event, recipientEmail, issuer);
  }

  @Transactional
  public List<Ticket> issueComp(IssueCompRequest request, UUID issuerUserId) {
    Event event = events.findById(request.eventId())
        .orElseThrow(() -> new ResourceNotFoundException("Event not found: " + request.eventId()));

    User issuer = users.findById(issuerUserId)
        .orElseThrow(() -> new ResourceNotFoundException("Issuer not found: " + issuerUserId));

    List<UUID> sortedSeatIds = request.seatIds().stream().distinct().sorted().toList();
    long requested = sortedSeatIds.size();
    User holder = lockAndResolveRecipient(event, request.recipientEmail(), issuer);

    Integer cap = event.getCompLimit();
    if (cap != null) {
      long already = tickets.countByEvent_IdAndKind(event.getId(), TicketKind.COMP);
      if (already + requested > cap) {
        throw new CompLimitExceededException(
            "Comp cap of " + cap + " exceeded: already=" + already + ", requested=" + requested);
      }
    }

    // Comps count toward the recipient's purchase cap like tickets they bought. A
    // name-only comp (no recipient email) has no recipient account: the issuer
    // only stands in as the ticket's owner, so no cap applies.
    Integer perUser = event.getMaxTicketsPerUser();
    if (perUser != null && hasRecipientAccount(request.recipientEmail())) {
      long owned = purchaseCapGuard.activeTickets(holder.getId(), event.getId());
      long held = purchaseCapGuard.liveHolds(holder.getId(), event.getId());
      if (owned + held + requested > perUser) {
        throw new PurchaseCapExceededException(
            "Purchase cap of " + perUser + " ticket(s) per user exceeded for event: "
                + event.getTitle() + " (already purchased: " + owned + ", held: " + held
                + ", comps requested: " + requested + ")");
      }
    }

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

  /**
   * Lock order: the event's comp-limit lock, then the recipient's purchase-cap
   * lock, both before any seat row lock. The comp-limit lock serialises
   * count-then-issue for the event's {@code comp_limit}; the cap lock is the one
   * the hold gate, checkout and transfer accept take for (event, recipient). The
   * recipient is resolved in between, since the cap lock is keyed on their id; a
   * stub user written there waits on nothing this transaction holds elsewhere.
   */
  private User lockAndResolveRecipient(Event event, String recipientEmail, User issuer) {
    if (event.getCompLimit() != null) {
      transactionLock.acquire("comp-limit:" + event.getId());
    }
    User holder = resolveRecipient(recipientEmail, issuer);
    if (event.getMaxTicketsPerUser() != null && hasRecipientAccount(recipientEmail)) {
      purchaseCapGuard.lock(event.getId(), holder.getId());
    }
    return holder;
  }

  /** Without an email the comp is issued by name only, owned by the issuer as a stand-in. */
  private static boolean hasRecipientAccount(String recipientEmail) {
    return recipientEmail != null && !recipientEmail.isBlank();
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
