package com.fairtix.events.application;

import com.fairtix.common.ResourceNotFoundException;
import com.fairtix.common.TransactionLock;
import com.fairtix.events.domain.Event;
import com.fairtix.events.domain.EventNotOnSaleException;
import com.fairtix.events.domain.EventStatus;
import com.fairtix.events.infrastructure.EventRepository;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refuses purchases (holds, checkout, payment intents, box-office sales, comps, transfers,
 * queue joins) for an event that has ended: CANCELLED, COMPLETED or ARCHIVED. DRAFT,
 * PUBLISHED and ACTIVE events stay sellable.
 *
 * <p>A path that writes calls {@link #lockOnSale}: it takes the event's sale lock in shared
 * mode, then reads the status. {@code EventService.cancelEvent} and {@code completeEvent} take
 * the same lock exclusively ({@link #lockForStatusChange}) before changing the status. So a
 * purchase runs entirely before the status change (and a cancellation then sees, cancels and
 * refunds what it wrote) or entirely after it (and is refused).
 *
 * <p>The lock is a {@link TransactionLock} on {@code event-sale:<id>}, not a lock on the event
 * row: its wait is bounded (a long cancellation makes waiting purchases fail with
 * {@code LockBusyException}, a retryable 409, instead of holding pooled connections), waiters
 * are served first come, first served (so a stream of purchases can't starve a cancellation),
 * and a purchase writes nothing to the event row.
 *
 * <p>Waits: a status change holds the lock for as long as the cancellation takes, and every
 * waiter keeps a pooled connection meanwhile. So a purchase that has not charged anyone yet
 * waits briefly ({@code fairtix.event-sale-lock.purchase-wait-ms}, 500 ms) and the client
 * retries; one whose charge has already succeeded ({@link #lockOnSaleAfterCharge}) waits the
 * general {@code fairtix.transaction-lock.wait-ms}, because giving up there sends the charge
 * to the refund runbook. The status change itself waits longest
 * ({@code fairtix.event-sale-lock.status-change-wait-ms}, 20 s), so purchases in flight don't
 * make an admin's cancellation fail busy; new purchases queue behind it and give up quickly.
 *
 * <p>Lock order: it is an advisory lock, so it goes with the other advisory locks (purchase cap,
 * hold limit, comp limit), after them and before any row lock. The one exception is the
 * box-office card confirm, which takes it after its sale row lock; a status change never locks
 * sale rows, so that order can't deadlock.
 */
@Component
public class EventSaleGuard {

  private static final Set<EventStatus> ENDED =
      EnumSet.of(EventStatus.CANCELLED, EventStatus.COMPLETED, EventStatus.ARCHIVED);

  private final EventRepository events;
  private final TransactionLock transactionLock;
  private final long purchaseWaitMillis;
  private final long statusChangeWaitMillis;

  public EventSaleGuard(EventRepository events, TransactionLock transactionLock,
      @Value("${fairtix.event-sale-lock.purchase-wait-ms:500}") long purchaseWaitMillis,
      @Value("${fairtix.event-sale-lock.status-change-wait-ms:20000}") long statusChangeWaitMillis) {
    // 0 would be lock_timeout = 0, which PostgreSQL reads as "wait forever".
    if (purchaseWaitMillis <= 0 || statusChangeWaitMillis <= 0) {
      throw new IllegalArgumentException("fairtix.event-sale-lock waits must be positive");
    }
    this.events = events;
    this.transactionLock = transactionLock;
    this.purchaseWaitMillis = purchaseWaitMillis;
    this.statusChangeWaitMillis = statusChangeWaitMillis;
  }

  /**
   * Holds the event's sale lock (shared) until the transaction ends and refuses an ended event.
   * For a purchase that has charged no one yet: the wait is short.
   *
   * @throws EventNotOnSaleException if the event is CANCELLED, COMPLETED or ARCHIVED
   * @throws ResourceNotFoundException if there is no such event
   * @throws com.fairtix.common.LockBusyException if a status change held the lock too long
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void lockOnSale(UUID eventId) {
    lockOnSale(eventId, purchaseWaitMillis);
  }

  /** {@link #lockOnSale(UUID)} for each event, in sorted order. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void lockOnSale(Collection<UUID> eventIds) {
    eventIds.stream().distinct().sorted().forEach(this::lockOnSale);
  }

  /**
   * As {@link #lockOnSale(UUID)}, for a purchase whose card charge has already succeeded: it
   * waits the longer {@code fairtix.transaction-lock.wait-ms}, since a refusal here leaves a
   * charge for the refund runbook.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void lockOnSaleAfterCharge(UUID eventId) {
    lockOnSale(eventId, -1);
  }

  /** {@link #lockOnSaleAfterCharge(UUID)} for each event, in sorted order. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void lockOnSaleAfterCharge(Collection<UUID> eventIds) {
    eventIds.stream().distinct().sorted().forEach(this::lockOnSaleAfterCharge);
  }

  /** {@code waitMillis} below zero: {@link TransactionLock}'s configured wait. */
  private void lockOnSale(UUID eventId, long waitMillis) {
    if (waitMillis < 0) {
      transactionLock.acquireShared(lockKey(eventId));
    } else {
      transactionLock.acquireShared(lockKey(eventId), waitMillis);
    }
    // Read after the lock, from the database: a status change that held it has committed, and
    // the persistence context may hold an older copy of the event.
    String status = events.findStatusById(eventId)
        .orElseThrow(() -> new ResourceNotFoundException("Event not found: " + eventId));
    requireOnSale(eventId, EventStatus.valueOf(status));
  }

  /**
   * Holds the event's sale lock exclusively until the transaction ends: purchases already
   * holding it finish first, and new ones wait. Take it before reading anything the status
   * change depends on, and before any row lock. It waits longer than a purchase does, so
   * purchases in flight don't make the status change fail busy.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void lockForStatusChange(UUID eventId) {
    transactionLock.acquire(lockKey(eventId), statusChangeWaitMillis);
  }

  /**
   * The same check without a lock, for a path that moves no money and that every later step
   * checks again under the lock (queue join, transfer request).
   */
  public void requireOnSale(Event event) {
    requireOnSale(event.getId(), event.getStatus());
  }

  private static void requireOnSale(UUID eventId, EventStatus status) {
    if (ENDED.contains(status)) {
      throw new EventNotOnSaleException(eventId, status);
    }
  }

  private static String lockKey(UUID eventId) {
    return "event-sale:" + eventId;
  }
}
