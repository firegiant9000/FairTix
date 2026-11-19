package com.fairtix.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fairtix.common.LockBusyException;
import com.fairtix.events.application.EventService;
import com.fairtix.events.domain.Event;
import com.fairtix.events.domain.EventNotOnSaleException;
import com.fairtix.events.infrastructure.EventRepository;
import com.fairtix.holds.application.CompService;
import com.fairtix.holds.dto.IssueCompRequest;
import com.fairtix.inventory.application.SeatHoldService;
import com.fairtix.inventory.domain.Seat;
import com.fairtix.inventory.domain.SeatHold;
import com.fairtix.inventory.infrastructure.SeatRepository;
import com.fairtix.orders.application.OrderService;
import com.fairtix.orders.domain.Order;
import com.fairtix.payments.domain.PaymentStatus;
import com.fairtix.queue.application.QueueService;
import com.fairtix.support.PostgresIT;
import com.fairtix.tickets.application.TransferService;
import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Nothing is sold, held, comped, transferred or queued for on an event that has ended
 * (CANCELLED, COMPLETED or ARCHIVED), and cancelling an event releases its CONFIRMED holds
 * as well as its ACTIVE ones.
 *
 * <p>Red on {@code 0e336858}: no purchase path read {@code events.status}, and the
 * cancellation released only ACTIVE holds, so a CONFIRMED hold could still be checked out
 * into a VALID ticket on a CANCELLED event.
 *
 * <p>The status is set with SQL for the refusal cases, so the holds and tickets the
 * cancellation would clean up are still there and only the status check stands in the way.
 * The races are forced as in {@code EventCancelTransferRaceIT}: a third connection holds the
 * event's sale lock (as a status change does), both sides are seen waiting on it, then it is
 * released. Waiters are served in arrival order.
 */
class EventClosedForSaleIT extends PostgresIT {

  @Autowired private UserRepository userRepository;
  @Autowired private EventRepository eventRepository;
  @Autowired private SeatRepository seatRepository;
  @Autowired private SeatHoldService seatHoldService;
  @Autowired private OrderService orderService;
  @Autowired private EventService eventService;
  @Autowired private TransferService transferService;
  @Autowired private CompService compService;
  @Autowired private QueueService queueService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private DataSource dataSource;

  private Event event;
  private User buyer;
  private UUID adminId;

  @BeforeEach
  void setUp() {
    event = eventRepository.save(
        new Event("Closed Sale Concert", null, Instant.now().plusSeconds(86400), (UUID) null));
    buyer = newUser("buyer@test.com");
    adminId = newUser("admin@test.com").getId();
  }

  @ParameterizedTest
  @ValueSource(strings = {"CANCELLED", "COMPLETED", "ARCHIVED"})
  void hold_onAnEndedEvent_isRefused(String status) {
    UUID seatId = newSeat("1");
    setStatus(status);

    assertThatThrownBy(() -> seatHoldService.createHold(event.getId(), List.of(seatId), buyer.getId(), 10))
        .isInstanceOf(EventNotOnSaleException.class);
    assertThat(count("SELECT COUNT(*) FROM seat_holds")).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"DRAFT", "PUBLISHED", "ACTIVE"})
  void hold_onAnEventNotEnded_isAllowed(String status) {
    UUID seatId = newSeat("1");
    setStatus(status);

    assertThat(seatHoldService.createHold(event.getId(), List.of(seatId), buyer.getId(), 10)).hasSize(1);
  }

  @Test
  void confirmHold_onACancelledEvent_isRefused() {
    SeatHold hold = seatHoldService.createHold(event.getId(), List.of(newSeat("1")), buyer.getId(), 10).get(0);
    setStatus("CANCELLED");

    assertThatThrownBy(() -> seatHoldService.confirmHold(hold.getId(), buyer.getId()))
        .isInstanceOf(EventNotOnSaleException.class);
    assertThat(holdStatus(hold.getId())).isEqualTo("ACTIVE");
  }

  @Test
  void checkout_onACancelledEvent_isRefused() {
    UUID holdId = confirmedHold("1");
    setStatus("CANCELLED");

    assertThatThrownBy(() -> orderService.createOrderWithPayment(
        buyer.getId(), List.of(holdId), PaymentStatus.SUCCESS))
        .isInstanceOf(EventNotOnSaleException.class);
    assertThat(count("SELECT COUNT(*) FROM tickets")).isZero();
    assertThat(count("SELECT COUNT(*) FROM orders")).isZero();
  }

  @Test
  void stripeCheckout_onACancelledEvent_isRefused() {
    UUID holdId = confirmedHold("1");
    setStatus("CANCELLED");

    assertThatThrownBy(() -> orderService.createOrderWithStripePayment(
        buyer.getId(), List.of(holdId), "pi_test_closed"))
        .isInstanceOf(EventNotOnSaleException.class);
    assertThat(count("SELECT COUNT(*) FROM tickets")).isZero();
  }

  @Test
  void paymentIntentHoldExtension_onACancelledEvent_isRefused() {
    UUID holdId = confirmedHold("1");
    setStatus("CANCELLED");

    assertThatThrownBy(() -> seatHoldService.extendConfirmedHoldsForPayment(List.of(holdId), buyer.getId()))
        .isInstanceOf(EventNotOnSaleException.class);
  }

  @Test
  void comp_onACancelledEvent_isRefused() {
    UUID seatId = newSeat("1");
    setStatus("CANCELLED");

    assertThatThrownBy(() -> compService.issueComp(new IssueCompRequest(event.getId(),
        List.of(seatId), "Guest", "guest@test.com", "press", false), adminId))
        .isInstanceOf(EventNotOnSaleException.class);
    assertThat(count("SELECT COUNT(*) FROM tickets")).isZero();
  }

  @Test
  void transferAccept_onACancelledEvent_isRefused() {
    User recipient = newUser("recipient@test.com");
    UUID ticketId = buy("1").tickets().get(0);
    UUID requestId = transferService
        .createTransferRequest(ticketId, buyer.getId(), recipient.getEmail()).getId();
    setStatus("CANCELLED");

    assertThatThrownBy(() -> transferService.acceptTransfer(requestId, recipient.getId()))
        .isInstanceOf(EventNotOnSaleException.class);
    assertThat(jdbcTemplate.queryForObject(
        "SELECT user_id FROM tickets WHERE id = ?", UUID.class, ticketId)).isEqualTo(buyer.getId());
  }

  @Test
  void queueJoin_onACancelledEvent_isRefused() {
    event.updateQueueSettings(true, null);
    event = eventRepository.save(event);
    setStatus("CANCELLED");

    assertThatThrownBy(() -> queueService.joinQueue(event.getId(), buyer.getId()))
        .isInstanceOf(EventNotOnSaleException.class);
    assertThat(count("SELECT COUNT(*) FROM queue_entries")).isZero();
  }

  @Test
  void transferRequest_onACancelledEvent_isRefused() {
    User recipient = newUser("recipient@test.com");
    UUID ticketId = buy("1").tickets().get(0);
    setStatus("CANCELLED");

    assertThatThrownBy(() -> transferService.createTransferRequest(
        ticketId, buyer.getId(), recipient.getEmail()))
        .isInstanceOf(EventNotOnSaleException.class);
    assertThat(count("SELECT COUNT(*) FROM ticket_transfer_requests")).isZero();
  }

  /**
   * A status change holding the sale lock for long (a cancellation of a large event) makes a
   * purchase give up after the bounded wait with a retryable LockBusyException, so it gives
   * its pooled connection back instead of waiting it out.
   */
  @Test
  @Timeout(30)
  void purchaseWaitingOnALongStatusChange_givesUpBusy() throws Exception {
    UUID seatId = newSeat("1");
    try (Connection lock = dataSource.getConnection()) {
      lock.setAutoCommit(false);
      holdSaleLock(lock);
      assertThatThrownBy(() -> seatHoldService.createHold(
          event.getId(), List.of(seatId), buyer.getId(), 10))
          .isInstanceOf(LockBusyException.class);
      lock.rollback();
    }
    assertThat(count("SELECT COUNT(*) FROM seat_holds")).isZero();
  }

  /** A confirmed hold is mid-checkout: the cancellation frees it, so it can't be bought later. */
  @Test
  void cancel_releasesConfirmedHolds() {
    UUID seatId = newSeat("1");
    SeatHold hold = seatHoldService.createHold(event.getId(), List.of(seatId), buyer.getId(), 10).get(0);
    seatHoldService.confirmHold(hold.getId(), buyer.getId());

    eventService.cancelEvent(event.getId(), adminId, "Venue flooded");

    assertThat(holdStatus(hold.getId())).isEqualTo("RELEASED");
    assertThat(jdbcTemplate.queryForObject(
        "SELECT status FROM seats WHERE id = ?", String.class, seatId)).isEqualTo("AVAILABLE");
  }

  /**
   * The cancellation gets the sale lock first: the checkout of a CONFIRMED hold waits, then
   * finds the event cancelled and is refused. No order, no ticket.
   */
  @Test
  @Timeout(60)
  void cancelStartedFirst_overlappingACheckout_refusesTheCheckout() throws Exception {
    UUID holdId = confirmedHold("1");

    List<Throwable> failures = raceUnderEventLock(
        () -> eventService.cancelEvent(event.getId(), adminId, "Venue flooded"),
        () -> orderService.createOrderWithPayment(buyer.getId(), List.of(holdId), PaymentStatus.SUCCESS));

    assertThat(failures).hasSize(1);
    assertThat(failures.get(0)).isInstanceOf(EventNotOnSaleException.class);
    assertThat(count("SELECT COUNT(*) FROM tickets")).isZero();
    assertThat(count("SELECT COUNT(*) FROM orders")).isZero();
    assertThat(holdStatus(holdId)).isEqualTo("RELEASED");
  }

  /**
   * The checkout gets the sale lock first and commits; the cancellation then cancels the new
   * ticket and approves a refund for the order, so the charge is on record and owed back.
   */
  @Test
  @Timeout(60)
  void checkoutStartedFirst_overlappingTheCancel_endsWithTheTicketCancelledAndRefundApproved()
      throws Exception {
    UUID holdId = confirmedHold("1");

    List<Throwable> failures = raceUnderEventLock(
        () -> orderService.createOrderWithPayment(buyer.getId(), List.of(holdId), PaymentStatus.SUCCESS),
        () -> eventService.cancelEvent(event.getId(), adminId, "Venue flooded"));

    assertThat(failures).isEmpty();
    assertThat(count("SELECT COUNT(*) FROM tickets WHERE status = 'VALID'")).isZero();
    assertThat(count("SELECT COUNT(*) FROM tickets WHERE status = 'CANCELLED'")).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM payment_records WHERE status = 'SUCCESS'")).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM refund_requests WHERE status = 'APPROVED'")).isEqualTo(1);
  }

  /** Completion takes the sale lock first: the hold queued behind it is refused. */
  @Test
  @Timeout(60)
  void completeStartedFirst_overlappingAHold_refusesTheHold() throws Exception {
    UUID seatId = newSeat("1");
    setStatus("ACTIVE");

    List<Throwable> failures = raceUnderEventLock(
        () -> eventService.completeEvent(event.getId(), adminId),
        () -> seatHoldService.createHold(event.getId(), List.of(seatId), buyer.getId(), 10));

    assertThat(failures).hasSize(1);
    assertThat(failures.get(0)).isInstanceOf(EventNotOnSaleException.class);
    assertThat(eventStatus()).isEqualTo("COMPLETED");
    assertThat(count("SELECT COUNT(*) FROM seat_holds")).isZero();
  }

  /** A purchase in flight finishes first; the completion waits for it, then completes. */
  @Test
  @Timeout(60)
  void holdInFlight_overlappingTheComplete_finishesBeforeIt() throws Exception {
    UUID seatId = newSeat("1");
    setStatus("ACTIVE");

    List<Throwable> failures = raceUnderEventLock(
        () -> seatHoldService.createHold(event.getId(), List.of(seatId), buyer.getId(), 10),
        () -> eventService.completeEvent(event.getId(), adminId));

    assertThat(failures).isEmpty();
    assertThat(eventStatus()).isEqualTo("COMPLETED");
    assertThat(count("SELECT COUNT(*) FROM seat_holds WHERE status = 'ACTIVE'")).isEqualTo(1);
  }

  /**
   * A purchase holding the sale lock longer than the status-change wait makes the
   * cancellation give up busy: nothing is cancelled, and the admin retries.
   */
  @Test
  @Timeout(30)
  void cancelWaitingOnAPurchaseInFlight_givesUpBusy() throws Exception {
    UUID holdId = confirmedHold("1");
    try (Connection purchase = dataSource.getConnection()) {
      purchase.setAutoCommit(false);
      holdSaleLock(purchase, true);
      assertThatThrownBy(() -> eventService.cancelEvent(event.getId(), adminId, "Venue flooded"))
          .isInstanceOf(LockBusyException.class);
      purchase.rollback();
    }
    assertThat(eventStatus()).isEqualTo("DRAFT");
    assertThat(holdStatus(holdId)).isEqualTo("CONFIRMED");
  }

  private record Bought(Order order, List<UUID> tickets) {}

  private Bought buy(String seatNumber) {
    UUID holdId = confirmedHold(seatNumber);
    Order order = orderService.createOrderWithPayment(buyer.getId(), List.of(holdId), PaymentStatus.SUCCESS);
    return new Bought(order, jdbcTemplate.queryForList(
        "SELECT id FROM tickets WHERE order_id = ?", UUID.class, order.getId()));
  }

  private UUID confirmedHold(String seatNumber) {
    SeatHold hold = seatHoldService.createHold(
        event.getId(), List.of(newSeat(seatNumber)), buyer.getId(), 10).get(0);
    seatHoldService.confirmHold(hold.getId(), buyer.getId());
    return hold.getId();
  }

  private UUID newSeat(String number) {
    return seatRepository.save(new Seat(event, "A", "1", number, new BigDecimal("50.00"))).getId();
  }

  private void setStatus(String status) {
    jdbcTemplate.update("UPDATE events SET status = ? WHERE id = ?", status, event.getId());
  }

  /**
   * Holds the event's sale lock on a third connection, starts {@code first} and waits until it is
   * blocked on a lock, then {@code second} likewise, then releases the row.
   */
  private List<Throwable> raceUnderEventLock(Callable<Object> first, Callable<Object> second)
      throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    List<Throwable> failures = new ArrayList<>();
    try (Connection lock = dataSource.getConnection()) {
      lock.setAutoCommit(false);
      holdSaleLock(lock);
      List<Future<Object>> futures = new ArrayList<>();
      futures.add(pool.submit(first));
      boolean firstBlocked = waitForLockWaiters(1, Duration.ofSeconds(10));
      futures.add(pool.submit(second));
      boolean bothBlocked = waitForLockWaiters(2, Duration.ofSeconds(10));
      lock.commit();
      assertThat(firstBlocked).as("the first transaction waited on the sale lock").isTrue();
      assertThat(bothBlocked).as("both transactions waited on the sale lock").isTrue();
      for (Future<Object> future : futures) {
        try {
          future.get(30, TimeUnit.SECONDS);
        } catch (ExecutionException ex) {
          failures.add(ex.getCause());
        }
      }
    } finally {
      pool.shutdownNow();
    }
    return failures;
  }

  /** Takes the event's sale lock exclusively on {@code lock}, as a status change does. */
  private void holdSaleLock(Connection lock) throws Exception {
    holdSaleLock(lock, false);
  }

  /** Takes the event's sale lock on {@code lock}: shared as a purchase does, or exclusively. */
  private void holdSaleLock(Connection lock, boolean shared) throws Exception {
    String function = shared ? "pg_advisory_xact_lock_shared" : "pg_advisory_xact_lock";
    try (PreparedStatement ps = lock.prepareStatement(
        "SELECT CAST(" + function + "(hashtextextended(?, 0)) AS text)")) {
      ps.setString(1, "event-sale:" + event.getId());
      ps.executeQuery().close();
    }
  }

  private boolean waitForLockWaiters(int waiters, Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      Integer count = jdbcTemplate.queryForObject(
          "SELECT COUNT(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
              + " AND datname = current_database()", Integer.class);
      if (count != null && count >= waiters) {
        return true;
      }
      Thread.sleep(50);
    }
    return false;
  }

  private String eventStatus() {
    return jdbcTemplate.queryForObject(
        "SELECT status FROM events WHERE id = ?", String.class, event.getId());
  }

  private String holdStatus(UUID holdId) {
    return jdbcTemplate.queryForObject(
        "SELECT status FROM seat_holds WHERE id = ?", String.class, holdId);
  }

  private int count(String sql) {
    Integer count = jdbcTemplate.queryForObject(sql, Integer.class);
    return count == null ? 0 : count;
  }

  private User newUser(String email) {
    User user = new User();
    user.setEmail(email);
    user.setPassword("$2a$10$dummyhashfortest");
    user.setEmailVerified(true);
    return userRepository.save(user);
  }
}
