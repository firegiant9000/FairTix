package com.fairtix.inventory.application;

import com.fairtix.audit.application.AuditService;
import com.fairtix.events.domain.Event;
import com.fairtix.events.infrastructure.EventRepository;
import com.fairtix.inventory.domain.HoldStatus;
import com.fairtix.inventory.domain.Seat;
import com.fairtix.inventory.domain.SeatHold;
import com.fairtix.inventory.domain.SeatStatus;
import com.fairtix.inventory.infrastructure.SeatHoldRepository;
import com.fairtix.inventory.infrastructure.SeatRepository;
import com.fairtix.queue.application.QueueService;
import com.fairtix.tickets.domain.TicketStatus;
import com.fairtix.tickets.infrastructure.TicketRepository;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class SeatHoldService {

  private final SeatRepository seatRepository;
  private final SeatHoldRepository seatHoldRepository;
  private final EventRepository eventRepository;
  private final AuditService auditService;
  private final QueueService queueService;
  private final TicketRepository ticketRepository;

  @Value("${holds.duration-minutes:10}")
  private int defaultDurationMinutes;

  @Value("${holds.max-duration-minutes:60}")
  private int maxDurationMinutes;

  @Value("${holds.max-active-per-holder:5}")
  private int maxActivePerHolder;

  @Value("${holds.max-seats-per-hold:10}")
  private int maxSeatsPerHold;

  public SeatHoldService(SeatRepository seatRepository,
      SeatHoldRepository seatHoldRepository,
      EventRepository eventRepository,
      AuditService auditService,
      QueueService queueService,
      TicketRepository ticketRepository) {
    this.seatRepository = seatRepository;
    this.seatHoldRepository = seatHoldRepository;
    this.eventRepository = eventRepository;
    this.auditService = auditService;
    this.queueService = queueService;
    this.ticketRepository = ticketRepository;
  }

  /**
   * Places a hold on one or more seats for an event.
   *
   * <p>
   * Guarantees:
   * <ul>
   * <li>Atomic: if any seat is unavailable the whole operation rolls back.</li>
   * <li>Deadlock-safe: seats are locked in consistent UUID order via a single
   * batch {@code SELECT … FOR UPDATE ORDER BY id} query.</li>
   * <li>Duration is clamped to {@code holds.max-duration-minutes}.</li>
   * <li>Active-hold count for the holder is checked before locking.</li>
   * </ul>
   *
   * @param eventId         the target event
   * @param seatIds         the seats to hold (duplicates are de-duped)
   * @param ownerId         authenticated user's ID
   * @param durationMinutes requested hold length; clamped and defaulted
   *                        server-side
   * @return the created {@link SeatHold} records
   */
  @Transactional
  public List<SeatHold> createHold(UUID eventId, List<UUID> seatIds,
      UUID ownerId, Integer durationMinutes) {

    Event event = eventRepository.findById(eventId)
        .orElseThrow(() -> new IllegalArgumentException("Event not found: " + eventId));

    // Queue gate: if the event requires queue admission, verify before proceeding
    if (event.isQueueRequired() && !queueService.isAdmitted(eventId, ownerId)) {
      throw new SeatHoldConflictException("Queue admission required to hold seats for this event");
    }

    // Purchase-cap hold gate: count tickets already purchased + active holds for this event
    Integer cap = event.getMaxTicketsPerUser();
    if (cap != null) {
      long requested = seatIds.stream().distinct().count();
      long purchased = ticketRepository.countByUser_IdAndEvent_IdAndStatusNotIn(ownerId, eventId,
          List.of(TicketStatus.CANCELLED, TicketStatus.REFUNDED));
      long heldAlready = seatHoldRepository.countByOwnerIdAndSeat_Event_IdAndStatus(ownerId, eventId, HoldStatus.ACTIVE);
      if (purchased + heldAlready + requested > cap) {
        throw new SeatHoldConflictException(
            "Purchase cap of " + cap + " ticket(s) per user exceeded for event: " + event.getTitle()
                + " (purchased: " + purchased + ", held: " + heldAlready + ", requested: " + requested + ")");
      }
    }

    // Soft limit: reject before acquiring any locks
    long activeCount = seatHoldRepository.countByOwnerIdAndStatus(ownerId, HoldStatus.ACTIVE);
    long requestedDistinctSeats = seatIds.stream().distinct().count();
    if (activeCount + requestedDistinctSeats > maxActivePerHolder) {
      throw new SeatHoldConflictException(
          "Hold limit reached: " + ownerId + " already has " + activeCount + " active hold(s)");
    }

    // Clamp duration: null → default, >max → max
    int duration = (durationMinutes != null && durationMinutes > 0)
        ? Math.min(durationMinutes, maxDurationMinutes)
        : defaultDurationMinutes;
    Instant expiresAt = Instant.now().plusSeconds(duration * 60L);

    // De-duplicate and sort for consistent lock ordering (deadlock prevention)
    List<UUID> sortedIds = seatIds.stream().distinct().sorted().toList();

    // Enforce configurable max seats per hold before acquiring any locks
    if (sortedIds.size() > maxSeatsPerHold) {
      throw new IllegalArgumentException(
          "Too many seats requested for a single hold: requested " + sortedIds.size()
              + ", maximum is " + maxSeatsPerHold);
    }
    // Lock all seats in one batch query — ORDER BY s.id matches our sort above
    List<Seat> lockedSeats = seatRepository.findAndLockByIdIn(sortedIds);

    // Validate all requested seats were found
    if (lockedSeats.size() != sortedIds.size()) {
      Set<UUID> foundIds = lockedSeats.stream()
          .map(Seat::getId)
          .collect(Collectors.toSet());
      UUID missing = sortedIds.stream()
          .filter(id -> !foundIds.contains(id))
          .findFirst()
          .orElseThrow();
      throw new IllegalArgumentException("Seat not found: " + missing);
    }

    // Validate each seat: correct event, available status
    for (Seat seat : lockedSeats) {
      if (!seat.getEvent().getId().equals(eventId)) {
        throw new IllegalArgumentException(
            "Seat " + seat.getId() + " does not belong to event " + eventId);
      }
      if (seat.getStatus() != SeatStatus.AVAILABLE) {
        throw new SeatHoldConflictException(
            "Seat " + seat.getId() + " is not available (status: " + seat.getStatus() + ")");
      }
    }

    // Transition all seats and create holds in bulk
    lockedSeats.forEach(s -> s.setStatus(SeatStatus.HELD));
    seatRepository.saveAll(lockedSeats);

    // Preserve the caller's original seat order in the response
    Map<UUID, Seat> seatById = lockedSeats.stream()
        .collect(Collectors.toMap(Seat::getId, Function.identity()));
    List<SeatHold> holds = seatIds.stream()
        .distinct()
        .map(id -> new SeatHold(seatById.get(id), ownerId, expiresAt))
        .toList();
    List<SeatHold> saved = seatHoldRepository.saveAll(holds);
    String holdIdsSummary = saved.stream()
        .map(SeatHold::getId).map(UUID::toString)
        .collect(Collectors.joining(", "));
    String seatIdsSummary = saved.stream()
        .map(h -> h.getSeat().getId()).map(UUID::toString)
        .collect(Collectors.joining(", "));
    auditService.logInCurrentTransaction(ownerId, "CREATE", "HOLD", saved.get(0).getId(),
        "Created " + saved.size() + " hold(s) for event " + eventId
            + "; seatIds=[" + seatIdsSummary + "]"
            + "; holdIds=[" + holdIdsSummary + "]"
            + "; expires in " + duration + " min");

    // Mark queue entry as completed if event uses a queue
    if (event.isQueueRequired()) {
      queueService.completeQueueEntry(eventId, ownerId);
    }

    return saved;
  }

  /**
   * Releases an active hold, returning the seat to AVAILABLE.
   *
   * <p>
   * Idempotent: calling release on a hold that is already RELEASED returns
   * the hold unchanged (200 OK) instead of throwing.
   *
   * @param holdId  the hold to release
   * @param ownerId must match the authenticated user who created the hold
   * @return the (possibly unchanged) {@link SeatHold}
   */
  @Transactional
  public SeatHold releaseHold(UUID holdId, UUID ownerId) {
    SeatHold hold = seatHoldRepository.findByIdAndOwnerId(holdId, ownerId)
        .orElseThrow(() -> new SeatHoldNotFoundException("Hold not found: " + holdId));

    // Idempotent: already released is a no-op
    if (hold.getStatus() == HoldStatus.RELEASED) {
      return hold;
    }

    if (hold.getStatus() != HoldStatus.ACTIVE && hold.getStatus() != HoldStatus.CONFIRMED) {
      throw new SeatHoldConflictException(
          "Cannot release hold with status: " + hold.getStatus());
    }

    hold.setStatus(HoldStatus.RELEASED);
    Seat seat = hold.getSeat();
    if (seat.getStatus() == SeatStatus.HELD || seat.getStatus() == SeatStatus.BOOKED) {
      seat.setStatus(SeatStatus.AVAILABLE);
      seatRepository.save(seat);
    }
    SeatHold saved = seatHoldRepository.save(hold);
    auditService.logInCurrentTransaction(ownerId, "RELEASE", "HOLD", holdId,
        "Hold released, seat " + seat.getId() + " returned to AVAILABLE");

    // If the event uses a queue, reinstate the user's queue entry so they can re-select
    UUID eventId = seat.getEvent().getId();
    if (seat.getEvent().isQueueRequired()) {
      queueService.reinstateAfterHoldRelease(eventId, ownerId);
    }

    return saved;
  }

  /**
   * Confirms an active hold, transitioning the seat to BOOKED.
   *
   * <p>
   * Idempotent: calling confirm on a hold that is already CONFIRMED returns
   * the hold unchanged (200 OK) instead of throwing.
   *
   * @param holdId  the hold to confirm
   * @param ownerId must match the authenticated user who created the hold
   * @return the (possibly unchanged) {@link SeatHold}
   */
  @Transactional
  public SeatHold confirmHold(UUID holdId, UUID ownerId) {
    SeatHold hold = seatHoldRepository.findByIdAndOwnerId(holdId, ownerId)
        .orElseThrow(() -> new SeatHoldNotFoundException("Hold not found: " + holdId));

    // Idempotent: already confirmed is a no-op
    if (hold.getStatus() == HoldStatus.CONFIRMED) {
      return hold;
    }

    if (hold.getStatus() != HoldStatus.ACTIVE) {
      throw new SeatHoldConflictException(
          "Cannot confirm hold with status: " + hold.getStatus());
    }

    if (hold.getExpiresAt().isBefore(Instant.now())) {
      throw new SeatHoldConflictException("Hold has expired");
    }

    hold.setStatus(HoldStatus.CONFIRMED);
    Seat seat = hold.getSeat();
    seat.setStatus(SeatStatus.BOOKED);
    seatRepository.save(seat);
    SeatHold saved = seatHoldRepository.save(hold);
    auditService.logInCurrentTransaction(ownerId, "CONFIRM", "HOLD", holdId,
        "Hold confirmed, seat " + seat.getId() + " status set to BOOKED");
    return saved;
  }

  /**
   * Returns hold details for the given holder.
   *
   * @param holdId  the hold id
   * @param ownerId must match the authenticated user who created the hold
   * @return the {@link SeatHold}
   */
  @Transactional
  public SeatHold getHold(UUID holdId, UUID ownerId) {
    return seatHoldRepository.findByIdAndOwnerId(holdId, ownerId)
        .orElseThrow(() -> new SeatHoldNotFoundException("Hold not found: " + holdId));
  }

  /**
   * Lists all holds for the given holder, filtered by status.
   *
   * @param ownerId the authenticated user's ID
   * @param status  the desired hold status (e.g. ACTIVE)
   * @return matching holds, possibly empty
   */
  @Transactional
  public List<SeatHold> listHolds(UUID ownerId, HoldStatus status) {
    return seatHoldRepository.findAllByOwnerIdAndStatus(ownerId, status);
  }
}
