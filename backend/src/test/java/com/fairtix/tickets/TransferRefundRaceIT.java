package com.fairtix.tickets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fairtix.events.domain.Event;
import com.fairtix.events.infrastructure.EventRepository;
import com.fairtix.inventory.application.SeatHoldService;
import com.fairtix.inventory.domain.Seat;
import com.fairtix.inventory.domain.SeatHold;
import com.fairtix.inventory.infrastructure.SeatRepository;
import com.fairtix.orders.application.OrderService;
import com.fairtix.orders.domain.Order;
import com.fairtix.payments.domain.PaymentStatus;
import com.fairtix.refunds.application.RefundService;
import com.fairtix.refunds.domain.RefundRequest;
import com.fairtix.refunds.infrastructure.RefundRepository;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Accepting a transfer and refunding the ticket's order cannot leave a refunded
 * ticket alive. Whichever way the two overlap, the ticket ends {@code REFUNDED}
 * with its seat back on sale, and the transfer request is never
 * {@code ACCEPTED}.
 *
 * <p>Red on {@code 625dcbdc}: {@code acceptTransfer} set the ticket {@code VALID}
 * without re-reading it, and {@code RefundService.finalise} left the
 * {@code PENDING} request alive, so an acceptance after (or overlapping) a refund
 * handed the recipient a valid ticket for a seat that was back on sale.
 *
 * <p>The races are forced, not left to timing: a third connection holds the
 * ticket row lock, each side is started and observed waiting on a lock in
 * {@code pg_stat_activity}, and only then is the ticket released. Not
 * {@code @Transactional}: every write commits, and {@link PostgresIT} truncates
 * the tables after each test.
 */
class TransferRefundRaceIT extends PostgresIT {

  @Autowired private UserRepository userRepository;
  @Autowired private EventRepository eventRepository;
  @Autowired private SeatRepository seatRepository;
  @Autowired private SeatHoldService seatHoldService;
  @Autowired private OrderService orderService;
  @Autowired private TransferService transferService;
  @Autowired private RefundService refundService;
  @Autowired private RefundRepository refundRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private DataSource dataSource;

  private Seat seat;
  private User sender;
  private User recipient;
  private UUID ticketId;
  private UUID requestId;
  private UUID refundId;

  @BeforeEach
  void setUp() {
    Event event = eventRepository.save(
        new Event("Refund Race Concert", null, Instant.now().plusSeconds(86400), (UUID) null));
    seat = seatRepository.save(new Seat(event, "A", "1", "1", new BigDecimal("50.00")));
    sender = newUser("sender@test.com");
    recipient = newUser("recipient@test.com");

    SeatHold hold =
        seatHoldService.createHold(event.getId(), List.of(seat.getId()), sender.getId(), 10).get(0);
    seatHoldService.confirmHold(hold.getId(), sender.getId());
    Order order = orderService.createOrderWithPayment(
        sender.getId(), List.of(hold.getId()), PaymentStatus.SUCCESS);
    ticketId = jdbcTemplate.queryForObject(
        "SELECT id FROM tickets WHERE seat_id = ?", UUID.class, seat.getId());
    requestId = transferService
        .createTransferRequest(ticketId, sender.getId(), recipient.getEmail()).getId();

    RefundRequest refund = new RefundRequest(
        order.getId(), sender.getId(), order.getTotalAmount(), "changed plans");
    refund.approve(sender.getId(), "ok");
    refundId = refundRepository.save(refund).getId();
  }

  @Test
  void acceptAfterTheRefund_isRefusedAndTheTicketStaysRefunded() {
    refundService.finalise(refundId, null, sender.getId());

    assertThatThrownBy(() -> transferService.acceptTransfer(requestId, recipient.getId()))
        .isInstanceOf(IllegalStateException.class);

    assertRefundedAndNotTransferred();
    assertThat(requestStatus()).as("the refund cancels the pending transfer").isEqualTo("CANCELLED");
    assertThat(auditRows("TRANSFER_CANCELLED")).isEqualTo(1);
  }

  /**
   * A ticket that stopped being VALID with its request still PENDING (rows from
   * before refunds cancelled transfers): accepting is refused, and the request is
   * resolved so it does not stay acceptable.
   */
  @Test
  void acceptOnATicketThatIsNoLongerValid_resolvesTheRequest() {
    jdbcTemplate.update("UPDATE tickets SET status = 'REFUNDED' WHERE id = ?", ticketId);
    jdbcTemplate.update(
        "UPDATE seats SET status = 'AVAILABLE', version = version + 1 WHERE id = ?", seat.getId());

    assertThatThrownBy(() -> transferService.acceptTransfer(requestId, recipient.getId()))
        .isInstanceOf(IllegalStateException.class);

    assertRefundedAndNotTransferred();
    assertThat(requestStatus()).isEqualTo("CANCELLED");
    assertThat(auditRows("TRANSFER_CANCELLED")).isEqualTo(1);
  }

  /**
   * The webhook marked the refund COMPLETED but finalise has not applied it (it
   * failed and awaits a retry): the ticket is still VALID, and accepting it now
   * would hand over a ticket whose money has gone back to the sender.
   */
  @Test
  void acceptWhileTheRefundIsCompletedButNotFinalised_isRefused() {
    jdbcTemplate.update("UPDATE refund_requests SET status = 'COMPLETED' WHERE id = ?", refundId);

    assertThatThrownBy(() -> transferService.acceptTransfer(requestId, recipient.getId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("refund is in progress");

    assertThat(requestStatus()).isEqualTo("PENDING");
    assertThat(auditRows("TRANSFER_ACCEPTED")).isZero();
    assertThat(jdbcTemplate.queryForObject(
        "SELECT user_id FROM tickets WHERE id = ?", UUID.class, ticketId)).isEqualTo(sender.getId());
  }

  /** The acceptance gets the ticket first, sees the approved refund and backs off. */
  @Test
  @Timeout(60)
  void acceptStartedFirst_overlappingTheRefund_endsRefunded() throws Exception {
    assertAcceptFirstEndsRefunded();
  }

  /** As above with a purchase cap, so the acceptance takes the cap lock before the ticket lock. */
  @Test
  @Timeout(60)
  void acceptStartedFirst_onACappedEvent_endsRefunded() throws Exception {
    jdbcTemplate.update(
        "UPDATE events SET max_tickets_per_user = 2, version = version + 1 WHERE id = ?",
        jdbcTemplate.queryForObject("SELECT event_id FROM tickets WHERE id = ?", UUID.class, ticketId));
    assertAcceptFirstEndsRefunded();
  }

  /** The refund gets the ticket first; the acceptance then finds its request cancelled. */
  @Test
  @Timeout(60)
  void refundStartedFirst_overlappingTheAccept_endsRefunded() throws Exception {
    List<Throwable> failures = raceUnderTicketLock(
        () -> refundService.finalise(refundId, null, sender.getId()),
        () -> transferService.acceptTransfer(requestId, recipient.getId()));

    assertRefundedAndNotTransferred();
    assertThat(requestStatus()).isEqualTo("CANCELLED");
    assertThat(failures).as("only the acceptance fails").hasSize(1);
    assertThat(failures.get(0))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no longer pending");
  }

  private void assertAcceptFirstEndsRefunded() throws Exception {
    List<Throwable> failures = raceUnderTicketLock(
        () -> transferService.acceptTransfer(requestId, recipient.getId()),
        () -> refundService.finalise(refundId, null, sender.getId()));

    assertRefundedAndNotTransferred();
    assertThat(requestStatus()).as("cancelled by the refund that ran second").isEqualTo("CANCELLED");
    assertThat(failures).as("only the acceptance fails").hasSize(1);
    assertThat(failures.get(0))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("refund is in progress");
  }

  private void assertRefundedAndNotTransferred() {
    assertThat(jdbcTemplate.queryForObject(
        "SELECT status FROM tickets WHERE id = ?", String.class, ticketId))
        .as("ticket status").isEqualTo("REFUNDED");
    assertThat(jdbcTemplate.queryForObject(
        "SELECT user_id FROM tickets WHERE id = ?", UUID.class, ticketId))
        .as("ticket owner").isEqualTo(sender.getId());
    assertThat(jdbcTemplate.queryForObject(
        "SELECT status FROM seats WHERE id = ?", String.class, seat.getId()))
        .as("seat status").isEqualTo("AVAILABLE");
    assertThat(auditRows("TRANSFER_ACCEPTED")).isZero();
  }

  /**
   * Holds the ticket's row lock on a third connection, starts {@code first} and
   * waits until it is blocked on a lock, then starts {@code second} and waits
   * until it is blocked too, then releases the ticket. Returns what they threw.
   */
  private List<Throwable> raceUnderTicketLock(Callable<Object> first, Callable<Object> second)
      throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    List<Throwable> failures = new ArrayList<>();
    try (Connection lock = dataSource.getConnection()) {
      lock.setAutoCommit(false);
      try (PreparedStatement ps =
          lock.prepareStatement("SELECT id FROM tickets WHERE id = ? FOR UPDATE")) {
        ps.setObject(1, ticketId);
        ps.executeQuery().close();
      }
      List<Future<Object>> futures = new ArrayList<>();
      futures.add(pool.submit(first));
      boolean firstBlocked = waitForLockWaiters(1, Duration.ofSeconds(10));
      futures.add(pool.submit(second));
      boolean bothBlocked = waitForLockWaiters(2, Duration.ofSeconds(10));
      lock.commit();
      assertThat(firstBlocked).as("the first transaction waited on a lock").isTrue();
      assertThat(bothBlocked).as("both transactions waited on a lock").isTrue();
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

  private String requestStatus() {
    return jdbcTemplate.queryForObject(
        "SELECT status FROM ticket_transfer_requests WHERE id = ?", String.class, requestId);
  }

  private int auditRows(String action) {
    Integer count = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM audit_logs WHERE action = ?", Integer.class, action);
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
