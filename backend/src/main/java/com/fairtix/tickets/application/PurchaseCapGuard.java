package com.fairtix.tickets.application;

import com.fairtix.common.TransactionLock;
import com.fairtix.inventory.domain.HoldStatus;
import com.fairtix.inventory.infrastructure.SeatHoldRepository;
import com.fairtix.tickets.domain.TicketStatus;
import com.fairtix.tickets.infrastructure.TicketRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one definition of the per-user purchase cap
 * ({@code events.max_tickets_per_user}), shared by the hold gate, checkout and
 * the queue:
 * <ul>
 *   <li>a ticket counts unless it is {@code CANCELLED} or {@code REFUNDED}, the
 *       same definition of an active ticket as {@code uk_tickets_seat_active};</li>
 *   <li>a hold counts while it is {@code ACTIVE} or {@code CONFIRMED}: the seat
 *       is off sale for this user either way.</li>
 * </ul>
 *
 * <p>Counting and then writing is only safe if nothing else can count in
 * between, so a path that enforces the cap calls {@link #lock} first. The lock
 * is a {@link TransactionLock} on (event, user): it works across application
 * instances, and commit or rollback releases it.
 */
@Component
public class PurchaseCapGuard {

  private static final List<TicketStatus> TICKETS_NOT_COUNTED =
      List.of(TicketStatus.CANCELLED, TicketStatus.REFUNDED);
  private static final List<HoldStatus> HOLDS_COUNTED =
      List.of(HoldStatus.ACTIVE, HoldStatus.CONFIRMED);

  private final TicketRepository ticketRepository;
  private final SeatHoldRepository seatHoldRepository;
  private final TransactionLock transactionLock;

  public PurchaseCapGuard(TicketRepository ticketRepository,
                          SeatHoldRepository seatHoldRepository,
                          TransactionLock transactionLock) {
    this.ticketRepository = ticketRepository;
    this.seatHoldRepository = seatHoldRepository;
    this.transactionLock = transactionLock;
  }

  /**
   * Serialises this user's cap checks for the event until the current
   * transaction ends. Call it before counting and before taking any row lock
   * (see {@link TransactionLock}). A second request from the same user waits
   * here and then counts what the first one committed; if the first one takes
   * too long, the second fails with {@code LockBusyException} and nothing is
   * written.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void lock(UUID eventId, UUID userId) {
    transactionLock.acquire("purchase-cap:" + eventId + ":" + userId);
  }

  /** Tickets the user owns for the event that count toward the cap. */
  public long activeTickets(UUID userId, UUID eventId) {
    return ticketRepository.countByUser_IdAndEvent_IdAndStatusNotIn(
        userId, eventId, TICKETS_NOT_COUNTED);
  }

  /** Seats the user is holding for the event that count toward the cap. */
  public long liveHolds(UUID userId, UUID eventId) {
    return seatHoldRepository.countByOwnerIdAndSeat_Event_IdAndStatusIn(
        userId, eventId, HOLDS_COUNTED);
  }
}
