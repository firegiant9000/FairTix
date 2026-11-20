package com.fairtix.refunds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import com.fairtix.audit.application.AuditService;
import com.fairtix.events.application.EventService;
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
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Refund writes that race other writers of the same rows.
 *
 * <ul>
 *   <li>An admin's review locks the refund row and re-reads it, so a reject that waited on an
 *       event cancellation's approval no longer overwrites it.</li>
 *   <li>{@code finalise} locks each ticket with a query on {@code tickets} alone. An entity
 *       lock joins {@code users}, and PostgreSQL drops a row whose joined column changed while
 *       the lock was awaited: a transfer accepted meanwhile made the ticket "disappear" after
 *       Stripe had already refunded the money.</li>
 * </ul>
 *
 * <p>Red on {@code 0e336858}: the reject overwrote the cancellation's APPROVED with REJECTED,
 * and finalise failed with the ticket not found.
 */
// Its own context (spies on AuditService): close it after the class so its pool doesn't count
// against the shared container's max_connections for the rest of the suite.
@DirtiesContext
class RefundLockingRaceIT extends PostgresIT {

  @Autowired private UserRepository userRepository;
  @Autowired private EventRepository eventRepository;
  @Autowired private SeatRepository seatRepository;
  @Autowired private SeatHoldService seatHoldService;
  @Autowired private OrderService orderService;
  @Autowired private EventService eventService;
  @Autowired private TransferService transferService;
  @Autowired private RefundService refundService;
  @Autowired private RefundRepository refundRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private DataSource dataSource;
  @Autowired private PlatformTransactionManager transactionManager;

  @MockitoSpyBean private AuditService auditService;

  private Event event;
  private User buyer;
  private User recipient;
  private UUID adminId;
  private Order order;
  private UUID ticketId;
  private UUID refundId;

  @BeforeEach
  void setUp() {
    event = eventRepository.save(
        new Event("Refund Lock Concert", null, Instant.now().plusSeconds(86400), (UUID) null));
    buyer = newUser("buyer@test.com");
    recipient = newUser("recipient@test.com");
    adminId = newUser("admin@test.com").getId();
    Seat seat = seatRepository.save(new Seat(event, "A", "1", "1", new BigDecimal("50.00")));
    SeatHold hold = seatHoldService.createHold(event.getId(), List.of(seat.getId()), buyer.getId(), 10).get(0);
    seatHoldService.confirmHold(hold.getId(), buyer.getId());
    order = orderService.createOrderWithPayment(buyer.getId(), List.of(hold.getId()), PaymentStatus.SUCCESS);
    ticketId = jdbcTemplate.queryForObject(
        "SELECT id FROM tickets WHERE order_id = ?", UUID.class, order.getId());
    refundId = refundRepository.save(new RefundRequest(
        order.getId(), buyer.getId(), order.getTotalAmount(), "changed plans")).getId();
  }

  /**
   * The cancellation approves the open refund while an admin's reject of it waits; the reject
   * then finds it APPROVED and is refused instead of overwriting it.
   */
  @Test
  @Timeout(60)
  void cancelStartedFirst_overlappingAReject_keepsTheApproval() throws Exception {
    List<Throwable> failures = raceUnderRefundLock(
        () -> eventService.cancelEvent(event.getId(), adminId, "Venue flooded"),
        () -> refundService.reviewRefund(adminId, refundId, false, "not eligible"));

    assertThat(refundStatus()).isEqualTo("APPROVED");
    assertThat(failures).hasSize(1);
    assertThat(failures.get(0)).isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("APPROVED");
    assertThat(auditRows("REFUND_REJECTED")).isZero();
    assertThat(jdbcTemplate.queryForObject(
        "SELECT status FROM tickets WHERE id = ?", String.class, ticketId)).isEqualTo("CANCELLED");
  }

  /**
   * The reject commits first; the cancellation then approves a new refund for the order, so
   * the cancelled ticket is still refunded.
   */
  @Test
  @Timeout(60)
  void rejectStartedFirst_overlappingTheCancel_endsWithANewApprovedRefund() throws Exception {
    List<Throwable> failures = raceUnderRefundLock(
        () -> refundService.reviewRefund(adminId, refundId, false, "not eligible"),
        () -> eventService.cancelEvent(event.getId(), adminId, "Venue flooded"));

    assertThat(failures).isEmpty();
    assertThat(refundStatus()).isEqualTo("REJECTED");
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM refund_requests WHERE order_id = ? AND status = 'APPROVED'",
        Integer.class, order.getId())).isEqualTo(1);
  }

  /**
   * A transfer is accepted (the ticket row is locked and its owner changed, not yet committed)
   * when the refund is approved and finalised. finalise waits on the ticket, the acceptance
   * commits the new owner, and finalise then completes: the ticket ends REFUNDED.
   */
  @Test
  @Timeout(60)
  void finaliseWaitingOnATicketWhoseTransferCommits_completes() throws Exception {
    UUID requestId = transferService
        .createTransferRequest(ticketId, buyer.getId(), recipient.getEmail()).getId();
    ExecutorService side = Executors.newFixedThreadPool(2);
    AtomicReference<Future<Object>> finalise = new AtomicReference<>();
    AtomicReference<Boolean> finaliseBlocked = new AtomicReference<>(false);
    // Stubbed inside a transaction: the spy wraps the MANDATORY transactional proxy.
    new TransactionTemplate(transactionManager).executeWithoutResult(status ->
        doAnswer(inv -> {
          // The acceptance holds the ticket lock and has passed its "refund in progress" check.
          side.submit(() -> jdbcTemplate.update(
              "UPDATE refund_requests SET status = 'APPROVED' WHERE id = ?", refundId))
              .get(10, TimeUnit.SECONDS);
          finalise.set(side.submit(() -> refundService.finalise(refundId, null, adminId)));
          finaliseBlocked.set(waitForRowLockWaiter("tickets", Duration.ofSeconds(10)));
          return inv.callRealMethod();
        }).when(auditService).logInCurrentTransaction(any(), eq("TRANSFER_ACCEPTED"),
            anyString(), any(), anyString()));

    try {
      transferService.acceptTransfer(requestId, recipient.getId());
      assertThat(finaliseBlocked.get()).as("finalise waited on the ticket").isTrue();
      Object result = finalise.get().get(30, TimeUnit.SECONDS);
      assertThat(((RefundService.FinaliseResult) result).applied()).isTrue();
    } finally {
      side.shutdownNow();
    }

    assertThat(jdbcTemplate.queryForObject(
        "SELECT status FROM tickets WHERE id = ?", String.class, ticketId)).isEqualTo("REFUNDED");
    assertThat(jdbcTemplate.queryForObject(
        "SELECT user_id FROM tickets WHERE id = ?", UUID.class, ticketId)).isEqualTo(recipient.getId());
    assertThat(refundStatus()).isEqualTo("COMPLETED");
  }

  /**
   * Holds the refund row on a third connection, starts {@code first} and waits until it is
   * blocked on a lock, then {@code second} likewise, then releases the row.
   */
  private List<Throwable> raceUnderRefundLock(Callable<Object> first, Callable<Object> second)
      throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    List<Throwable> failures = new ArrayList<>();
    try (Connection lock = dataSource.getConnection()) {
      lock.setAutoCommit(false);
      try (PreparedStatement ps =
          lock.prepareStatement("SELECT id FROM refund_requests WHERE id = ? FOR UPDATE")) {
        ps.setObject(1, refundId);
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

  /**
   * Waits until a backend is blocked waiting for a row of {@code table}. A row-lock waiter
   * holds or awaits a {@code tuple} lock on the row's relation while it waits on the holder,
   * so this proves the wait is on that table's row, not on just any lock.
   */
  private boolean waitForRowLockWaiter(String table, Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      Integer count = jdbcTemplate.queryForObject(
          "SELECT COUNT(*) FROM pg_stat_activity a"
              + " WHERE a.wait_event_type = 'Lock' AND a.datname = current_database()"
              + " AND EXISTS (SELECT 1 FROM pg_locks l JOIN pg_class c ON c.oid = l.relation"
              + "   WHERE l.pid = a.pid AND l.locktype = 'tuple' AND c.relname = ?)",
          Integer.class, table);
      if (count != null && count >= 1) {
        return true;
      }
      Thread.sleep(50);
    }
    return false;
  }

  private String refundStatus() {
    return jdbcTemplate.queryForObject(
        "SELECT status FROM refund_requests WHERE id = ?", String.class, refundId);
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
