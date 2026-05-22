package com.fairtix.holds.application;

import com.fairtix.audit.application.AuditService;
import com.fairtix.common.ResourceNotFoundException;
import com.fairtix.events.domain.Event;
import com.fairtix.events.infrastructure.EventRepository;
import com.fairtix.holds.domain.EventHold;
import com.fairtix.holds.domain.HoldCategory;
import com.fairtix.holds.dto.CreateEventHoldRequest;
import com.fairtix.holds.dto.IssueCompRequest;
import com.fairtix.holds.infrastructure.EventHoldRepository;
import com.fairtix.inventory.application.SeatHoldConflictException;
import com.fairtix.inventory.domain.Seat;
import com.fairtix.inventory.domain.SeatStatus;
import com.fairtix.inventory.infrastructure.SeatRepository;
import com.fairtix.tickets.domain.Ticket;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Manages promoter-side seat reservations: artist/press/house holds. These do
 * not appear in sold counts, don't charge a card, and don't count against
 * per-user purchase caps. They can be released (seat returns to AVAILABLE) or
 * converted into a comp ticket (seat stays held but is now a real, scannable
 * comp).
 *
 * <p>Distinct from {@link com.fairtix.inventory.application.SeatHoldService},
 * which manages the short-lived Redis-backed cart hold.
 */
@Service
public class EventHoldService {

  private final EventRepository events;
  private final SeatRepository seats;
  private final EventHoldRepository holds;
  private final CompService compService;
  private final AuditService audit;

  public EventHoldService(EventRepository events,
                          SeatRepository seats,
                          EventHoldRepository holds,
                          CompService compService,
                          AuditService audit) {
    this.events = events;
    this.seats = seats;
    this.holds = holds;
    this.compService = compService;
    this.audit = audit;
  }

  @Transactional
  public List<EventHold> create(CreateEventHoldRequest request, UUID actorUserId) {
    Event event = events.findById(request.eventId())
        .orElseThrow(() -> new ResourceNotFoundException("Event not found: " + request.eventId()));

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

    List<EventHold> created = locked.stream()
        .map(s -> {
          EventHold h = new EventHold(event, s, request.category(), request.note(),
              actorUserId, request.autoReleaseAt());
          s.setStatus(SeatStatus.HELD);
          return h;
        })
        .toList();
    holds.saveAll(created);
    seats.saveAll(locked);

    String seatList = created.stream().map(h -> h.getSeat().getId().toString()).collect(Collectors.joining(","));
    audit.log(actorUserId, "EVENT_HOLD_CREATE", "EVENT_HOLD", event.getId(),
        "category=" + request.category() + " count=" + created.size() + " seats=[" + seatList + "]");
    return created;
  }

  @Transactional
  public List<EventHold> listActive(UUID eventId, HoldCategory category) {
    if (category == null) return holds.findActiveByEvent(eventId);
    return holds.findActiveByEventAndCategory(eventId, category);
  }

  @Transactional
  public EventHold release(UUID holdId, UUID actorUserId) {
    EventHold hold = holds.findById(holdId)
        .orElseThrow(() -> new ResourceNotFoundException("Hold not found: " + holdId));
    if (!hold.isActive()) {
      throw new IllegalStateException("Hold is not active (released or converted)");
    }
    Seat seat = seats.findAndLockById(hold.getSeat().getId())
        .orElseThrow(() -> new ResourceNotFoundException("Seat not found: " + hold.getSeat().getId()));
    if (seat.getStatus() == SeatStatus.HELD) {
      seat.setStatus(SeatStatus.AVAILABLE);
      seats.save(seat);
    }
    hold.markReleased(actorUserId);
    holds.save(hold);
    audit.log(actorUserId, "EVENT_HOLD_RELEASE", "EVENT_HOLD", holdId,
        "seat=" + seat.getId() + " category=" + hold.getCategory());
    return hold;
  }

  /**
   * Bulk-release every active hold in a category. Useful for the "release all
   * unclaimed press holds 1hr before doors" workflow.
   */
  @Transactional
  public int releaseAllInCategory(UUID eventId, HoldCategory category, UUID actorUserId) {
    List<EventHold> active = holds.findActiveByEventAndCategory(eventId, category);
    for (EventHold h : active) {
      release(h.getId(), actorUserId);
    }
    audit.log(actorUserId, "EVENT_HOLD_BULK_RELEASE", "EVENT", eventId,
        "category=" + category + " count=" + active.size());
    return active.size();
  }

  /**
   * Convert an active hold to a comp ticket. The seat stays held (becomes
   * SOLD on the comp side), the hold row is marked converted with a pointer
   * to the issued ticket.
   *
   * <p>Pre-condition: the hold's seat must currently be HELD. We flip it back
   * to AVAILABLE in the same transaction so the CompService seat-status guard
   * permits the new ticket. Both steps are inside one transaction, so external
   * observers never see the seat as AVAILABLE while a hold exists.
   */
  @Transactional
  public Ticket convertToComp(UUID holdId, String recipientName, String recipientEmail,
                              String reason, boolean willCall, UUID actorUserId) {
    EventHold hold = holds.findById(holdId)
        .orElseThrow(() -> new ResourceNotFoundException("Hold not found: " + holdId));
    if (!hold.isActive()) {
      throw new IllegalStateException("Hold is not active (released or converted)");
    }
    Seat seat = seats.findAndLockById(hold.getSeat().getId())
        .orElseThrow(() -> new ResourceNotFoundException("Seat not found: " + hold.getSeat().getId()));
    if (seat.getStatus() != SeatStatus.HELD) {
      throw new SeatHoldConflictException("Seat is not HELD (state: " + seat.getStatus() + ")");
    }
    // CompService re-locks and re-checks the seat. Reset to AVAILABLE so its
    // invariant passes; we're still inside the outer transaction.
    seat.setStatus(SeatStatus.AVAILABLE);
    seats.save(seat);

    IssueCompRequest compRequest = new IssueCompRequest(
        hold.getEvent().getId(), List.of(seat.getId()),
        recipientName, recipientEmail, reason, willCall);
    List<Ticket> issued = compService.issueComp(compRequest, actorUserId);
    Ticket ticket = issued.get(0);

    hold.markConverted(ticket.getId());
    holds.save(hold);
    audit.log(actorUserId, "EVENT_HOLD_CONVERT_TO_COMP", "EVENT_HOLD", holdId,
        "ticket=" + ticket.getId() + " category=" + hold.getCategory());
    return ticket;
  }

  /** Scheduled job entrypoint: release any holds whose auto_release_at has passed. */
  @Transactional
  public int releaseDueHolds(Instant now, UUID systemUserId) {
    List<EventHold> due = holds.findDueForAutoRelease(now);
    for (EventHold h : due) {
      release(h.getId(), systemUserId);
    }
    return due.size();
  }
}
