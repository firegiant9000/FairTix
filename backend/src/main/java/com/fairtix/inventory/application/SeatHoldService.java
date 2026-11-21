package com.fairtix.inventory.application;

import com.fairtix.audit.application.AuditService;
import com.fairtix.common.TransactionLock;
import com.fairtix.events.application.EventSaleGuard;
import com.fairtix.events.domain.Event;
import com.fairtix.events.infrastructure.EventRepository;
import com.fairtix.inventory.domain.HoldStatus;
import com.fairtix.inventory.domain.Seat;
import com.fairtix.inventory.domain.SeatHold;
import com.fairtix.inventory.domain.SeatStatus;
import com.fairtix.inventory.infrastructure.HoldSeatRef;
import com.fairtix.inventory.infrastructure.SeatHoldRepository;
import com.fairtix.inventory.infrastructure.SeatRepository;
import com.fairtix.queue.application.QueueService;
import com.fairtix.tickets.application.PurchaseCapGuard;
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
  private final PurchaseCapGuard purchaseCapGuard;
  private final TransactionLock transactionLock;
  private final EventSaleGuard eventSaleGuard;

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
      PurchaseCapGuard purchaseCapGuard,
      TransactionLock transactionLock,
      EventSaleGuard eventSaleGuard) {
    this.transactionLock = transactionLock;
    this.eventSaleGuard = eventSaleGuard;
    this.seatRepository = seatRepository;
    this.seatHoldRepository = seatHoldRepository;
    this.eventRepository = eventRepository;
    this.auditService = auditService;
    this.queueService = queueService;
    this.purchaseCapGuard = purchaseCapGuard;
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
   * <li>The holder's active-hold count is checked under a per-user lock, before
   * any seat is locked, so two overlapping requests cannot both pass it.</li>
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

    // Lock order: this user's hold-limit lock, then the (event, user) cap lock,
    // then seat rows. The hold limit spans every event, so it needs a lock of its
    // own: the cap lock is per event and only taken when the event has a cap. An
    // overlapping hold request by the same user, on any event, waits here and
    // then counts this one's holds.
    transactionLock.acquire("hold-limit:" + ownerId);

    // Queue gate, checked under the hold-limit lock: a second request that waited
    // there sees the admission the first one used up (completeQueueEntry below).
    if (event.isQueueRequired() && !queueService.isAdmitted(eventId, ownerId)) {
      throw new SeatHoldConflictException("Queue admission required to hold seats for this event");
    }

    // Purchase-cap hold gate: active tickets + live holds for this event, counted
    // under the (event, user) lock so an overlapping request from the same user
    // waits here and then sees this one's holds.
    Integer cap = event.getMaxTicketsPerUser();
    if (cap != null) {
      purchaseCapGuard.lock(eventId, ownerId);
      long requested = seatIds.stream().distinct().count();
      long purchased = purchaseCapGuard.activeTickets(ownerId, eventId);
      long heldAlready = purchaseCapGuard.liveHolds(ownerId, eventId);
      if (purchased + heldAlready + requested > cap) {
        throw new SeatHoldConflictException(
            "Purchase cap of " + cap + " ticket(s) per user exceeded for event: " + event.getTitle()
                + " (purchased: " + purchased + ", held: " + heldAlready + ", requested: " + requested + ")");
      }
    }

    // After the advisory locks above, before any seat lock (EventSaleGuard lock order).
    eventSaleGuard.lockOnSale(eventId);

    // Hold limit, counted under the hold-limit lock and before any seat lock.
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

    // Before the hold and seat writes (EventSaleGuard lock order).
    eventSaleGuard.lockOnSale(hold.getSeat().getEvent().getId());

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
   * Called before a Stripe PaymentIntent is created for these holds: each must be
   * the caller's, CONFIRMED, with its seat still BOOKED, and its expiry is pushed
   * out to at least now + {@code holds.duration-minutes}. A confirmed hold
   * otherwise keeps its original expiry, so the CONFIRMED-hold sweep could free
   * the seat while the customer is still paying.
   *
   * <p>Order matters, and the method writes through bulk queries only:
   * <ol>
   *   <li>The holds are read as plain values, never as entities, so no {@code Seat}
   *       is in the persistence context when the seats are locked. A managed seat
   *       would fail the lock query with an optimistic-lock conflict whenever a
   *       concurrent extension for the same holds (a double click) committed
   *       first (#298).</li>
   *   <li>The expiry is set by a conditional update, before any seat lock. It locks
   *       only the hold rows it changes: those still CONFIRMED that expire before the
   *       new expiry. A row the sweep has written but not yet committed is waited for
   *       (the wait the deadlock note below is about), then skipped if it is no longer
   *       CONFIRMED, or updated and locked if the sweep rolled back. It never rewrites
   *       {@code status}, so a hold the sweep just expired stays expired. A concurrent
   *       extension waits here. The rows it skips stay unlocked: one no longer
   *       CONFIRMED is refused in step 3, and one that already expires after the new
   *       expiry is not one the sweep selects from a fresh read.</li>
   *   <li>The seats are locked in sorted order, and the hold and seat states are
   *       re-read from the database (the values read in step 1 may be stale).</li>
   *   <li>The seat versions are bumped, so a checkout or release that read a seat
   *       before this commits fails on its seat update.</li>
   * </ol>
   *
   * <p>The CONFIRMED-hold sweep ends each hold with a conditional update that re-checks
   * status and expiry in the database, so it never puts back an expiry it read before
   * this extension committed (#318). If the sweep ends the hold first, step 2 skips it
   * and step 3 refuses.
   *
   * <p>Lock order does not rule out a deadlock with the CONFIRMED-hold sweep. The sweep
   * takes hold-row locks in id order, but the update in step 2 locks its hold rows in
   * no fixed order, so the two can still deadlock when they share two or more holds. None has been reported;
   * it needs a hold past the sweep's grace cutoff to be extended during a sweep.
   * PostgreSQL then aborts one transaction. An aborted sweep rolls back its page and
   * retries on the next tick. An aborted extension answers a retryable 409
   * ({@code CONCURRENT_MODIFICATION}, from {@code GlobalExceptionHandler}'s
   * {@code PessimisticLockingFailureException} handler, #317) before this request
   * creates or reuses a payment intent; nothing has changed, so a retry is safe.
   *
   * @throws SeatHoldNotFoundException if a hold is missing or not the caller's
   * @throws SeatHoldConflictException if a hold is not CONFIRMED or its seat is
   *                                   no longer BOOKED
   */
  @Transactional
  public void extendConfirmedHoldsForPayment(List<UUID> holdIds, UUID ownerId) {
    List<UUID> ids = holdIds.stream().distinct().toList();
    Map<UUID, HoldSeatRef> refs = seatHoldRepository.findRefsByIdInAndOwnerId(ids, ownerId).stream()
        .collect(Collectors.toMap(HoldSeatRef::id, Function.identity()));
    // Not-found first, then status, so a missing hold wins over an unconfirmed one.
    for (UUID id : ids) {
      if (!refs.containsKey(id)) {
        throw new SeatHoldNotFoundException("Hold not found: " + id);
      }
    }
    for (UUID id : ids) {
      HoldSeatRef ref = refs.get(id);
      if (ref.status() != HoldStatus.CONFIRMED) {
        throw new SeatHoldConflictException(
            "Hold " + id + " is not confirmed (status: " + ref.status() + ")");
      }
    }

    // No payment intent for an ended event. Before the hold-row locks below (EventSaleGuard).
    eventSaleGuard.lockOnSale(refs.values().stream().map(HoldSeatRef::eventId).toList());

    Instant extendTo = Instant.now().plusSeconds(defaultDurationMinutes * 60L);
    seatHoldRepository.extendExpiry(ids, HoldStatus.CONFIRMED, extendTo);

    List<UUID> seatIds = refs.values().stream().map(HoldSeatRef::seatId).distinct().sorted().toList();
    seatRepository.findAndLockByIdIn(seatIds);
    if (seatHoldRepository.countByIdInAndStatusAndSeatStatus(ids, HoldStatus.CONFIRMED, SeatStatus.BOOKED)
        != ids.size()) {
      throw new SeatHoldConflictException(
          "A hold is no longer confirmed or its seat is no longer booked");
    }
    seatRepository.bumpVersionByIdIn(seatIds);

    auditService.logInCurrentTransaction(ownerId, "EXTEND", "HOLD", ids.get(0),
        "Extended " + ids.size() + " CONFIRMED hold(s) for payment to " + extendTo
            + "; holdIds=[" + ids.stream().map(UUID::toString).collect(Collectors.joining(", ")) + "]");
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
