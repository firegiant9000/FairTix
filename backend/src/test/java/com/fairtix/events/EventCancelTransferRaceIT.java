package com.fairtix.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.fairtix.events.application.EventService;
import com.fairtix.events.domain.Event;
import com.fairtix.events.domain.EventNotOnSaleException;
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
 * Cancelling an event locks the event's tickets before it changes them, in ticket-id order,
 * and cancels their pending transfers. An acceptance of a transfer and a refund's
 * {@code finalise} lock the same rows, so each runs entirely before or after the
 * cancellation, and neither is overwritten by it.
 *
 * <p>Red on {@code a6624d02}: {@code EventService.cancelEvent} read the VALID tickets
 * without a lock and wrote every column back, so an acceptance that committed in between
 * was undone (the ticket went back to the sender), a refund that committed in between was
 * overwritten with {@code CANCELLED}, and a cancellation could deadlock with
 * {@code finalise} on a multi-ticket order. A pending transfer stayed pending on the
 * cancelled ticket.
 *
 * <p>The races are forced as in {@code TransferRefundRaceIT}: a third connection holds a
 * ticket row lock, each side is started and seen waiting on a lock in
 * {@code pg_stat_activity}, and only then is the ticket released. Not
 * {@code @Transactional}: every write commits, and {@link PostgresIT} truncates the tables
 * after each test.
 */
class EventCancelTransferRaceIT extends PostgresIT {

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

  private Event event;
  private User sender;
  private User recipient;
  private UUID adminId;

  @BeforeEach
  void setUp() {
    event = eventRepository.save(
        new Event("Cancel Race Concert", null, Instant.now().plusSeconds(86400), (UUID) null));
    sender = newUser("sender@test.com");
    recipient = newUser("recipient@test.com");
    adminId = newUser("admin@test.com").getId();
  }

  @Test
  void cancel_cancelsPendingTransfersOfTheTickets() {
    UUID ticketId = buy(1).tickets().get(0);
    UUID requestId = transferService
        .createTransferRequest(ticketId, sender.getId(), recipient.getEmail()).getId();

    eventService.cancelEvent(event.getId(), adminId, "Venue flooded");

    assertThat(ticketStatus(ticketId)).isEqualTo("CANCELLED");
    assertThat(requestStatus(requestId)).isEqualTo("CANCELLED");
    assertThat(auditRows("TRANSFER_CANCELLED")).isEqualTo(1);
  }

  /**
   * The cancellation gets the event row and the ticket first; the acceptance waits on the
   * event row and then finds the event cancelled.
   */
  @Test
  @Timeout(60)
  void cancelStartedFirst_overlappingTheAccept_endsCancelledAndNotTransferred() throws Exception {
    UUID ticketId = buy(1).tickets().get(0);
    UUID requestId = transferService
        .createTransferRequest(ticketId, sender.getId(), recipient.getEmail()).getId();

    List<Throwable> failures = raceUnderTicketLock(ticketId,
        () -> eventService.cancelEvent(event.getId(), adminId, "Venue flooded"),
        () -> transferService.acceptTransfer(requestId, recipient.getId()));

    assertThat(ticketStatus(ticketId)).isEqualTo("CANCELLED");
    assertThat(ticketOwner(ticketId)).isEqualTo(sender.getId());
    assertThat(requestStatus(requestId)).isEqualTo("CANCELLED");
    assertThat(auditRows("TRANSFER_ACCEPTED")).isZero();
    assertThat(failures).as("only the acceptance fails").hasSize(1);
    assertThat(failures.get(0)).isInstanceOf(EventNotOnSaleException.class);
  }

  /**
   * The acceptance gets the ticket first and commits; the cancellation then cancels the
   * ticket as the recipient now holds it. Before the fix it wrote the ticket back to the
   * sender, so the recipient's accepted ticket vanished from their account.
   */
  @Test
  @Timeout(60)
  void acceptStartedFirst_overlappingTheCancel_endsCancelledWithTheAcceptKept() throws Exception {
    UUID ticketId = buy(1).tickets().get(0);
    UUID requestId = transferService
        .createTransferRequest(ticketId, sender.getId(), recipient.getEmail()).getId();

    List<Throwable> failures = raceUnderTicketLock(ticketId,
        () -> transferService.acceptTransfer(requestId, recipient.getId()),
        () -> eventService.cancelEvent(event.getId(), adminId, "Venue flooded"));

    assertThat(failures).isEmpty();
    assertThat(ticketStatus(ticketId)).isEqualTo("CANCELLED");
    assertThat(ticketOwner(ticketId)).as("the committed acceptance is not undone")
        .isEqualTo(recipient.getId());
    assertThat(requestStatus(requestId)).isEqualTo("ACCEPTED");
  }

  /**
   * The cancellation gets the ticket first; the transfer request waits for it and then finds
   * the ticket cancelled. Red before the fix: the request read the ticket unlocked, its
   * INSERT waited on the cancellation's ticket lock and then succeeded, leaving a PENDING
   * request on a CANCELLED ticket (and emailing the recipient).
   */
  @Test
  @Timeout(60)
  void cancelStartedFirst_overlappingATransferRequest_leavesNoPendingRequest() throws Exception {
    UUID ticketId = buy(1).tickets().get(0);

    List<Throwable> failures = raceUnderTicketLock(ticketId,
        () -> eventService.cancelEvent(event.getId(), adminId, "Venue flooded"),
        () -> transferService.createTransferRequest(ticketId, sender.getId(), recipient.getEmail()));

    assertThat(ticketStatus(ticketId)).isEqualTo("CANCELLED");
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM ticket_transfer_requests WHERE ticket_id = ?", Integer.class,
        ticketId)).as("no request on the cancelled ticket").isZero();
    assertThat(auditRows("TRANSFER_REQUESTED")).isZero();
    assertThat(failures).as("only the request fails").hasSize(1);
    assertThat(failures.get(0)).isInstanceOf(IllegalStateException.class);
  }

  /**
   * The transfer request gets the ticket first and commits; the cancellation then finds the
   * pending request and cancels it with the ticket.
   */
  @Test
  @Timeout(60)
  void transferRequestStartedFirst_overlappingTheCancel_endsWithTheRequestCancelled()
      throws Exception {
    UUID ticketId = buy(1).tickets().get(0);

    List<Throwable> failures = raceUnderTicketLock(ticketId,
        () -> transferService.createTransferRequest(ticketId, sender.getId(), recipient.getEmail()),
        () -> eventService.cancelEvent(event.getId(), adminId, "Venue flooded"));

    assertThat(failures).isEmpty();
    assertThat(ticketStatus(ticketId)).isEqualTo("CANCELLED");
    assertThat(jdbcTemplate.queryForList(
        "SELECT status FROM ticket_transfer_requests WHERE ticket_id = ?", String.class,
        ticketId)).containsExactly("CANCELLED");
  }

  /**
   * A refund of a two-ticket order is finalising (it holds the refund row and waits for the
   * first ticket) when the event is cancelled. Both complete, with no deadlock, and the
   * tickets end REFUNDED, not overwritten with CANCELLED.
   */
  @Test
  @Timeout(60)
  void cancelOverlappingTheFinaliseOfATwoTicketOrder_completesWithoutDeadlock() throws Exception {
    Bought bought = buy(2);
    // Ids on both sides of 0x80: PostgreSQL orders uuids by unsigned bytes (7f.. first),
    // java.util.UUID.compareTo by signed longs (80.. first). Locking in two different
    // orders is what deadlocks, so the test pins ids where the two orders disagree.
    UUID signedFirst = UUID.fromString("80000000-0000-4000-8000-000000000002");
    UUID unsignedFirst = UUID.fromString("7f000000-0000-4000-8000-000000000001");
    jdbcTemplate.update("UPDATE tickets SET id = ? WHERE id = ?", signedFirst, bought.tickets().get(0));
    jdbcTemplate.update("UPDATE tickets SET id = ? WHERE id = ?", unsignedFirst, bought.tickets().get(1));
    bought = new Bought(bought.order(), List.of(signedFirst, unsignedFirst));
    RefundRequest refund = new RefundRequest(
        bought.order().getId(), sender.getId(), bought.order().getTotalAmount(), "changed plans");
    refund.approve(sender.getId(), "ok");
    UUID refundId = refundRepository.save(refund).getId();
    // finalise locks in java.util.UUID order, so this is the ticket it waits on first.
    UUID firstTicket = bought.tickets().stream().sorted().findFirst().orElseThrow();
    assertThat(firstTicket).isEqualTo(signedFirst);

    List<Throwable> failures = raceUnderTicketLock(firstTicket,
        () -> refundService.finalise(refundId, null, sender.getId()),
        () -> eventService.cancelEvent(event.getId(), adminId, "Venue flooded"));

    assertThat(failures).isEmpty();
    for (UUID ticketId : bought.tickets()) {
      assertThat(ticketStatus(ticketId)).as("ticket " + ticketId).isEqualTo("REFUNDED");
    }
    assertThat(jdbcTemplate.queryForObject(
        "SELECT status FROM refund_requests WHERE id = ?", String.class, refundId))
        .isEqualTo("COMPLETED");
    assertThat(jdbcTemplate.queryForObject(
        "SELECT status FROM events WHERE id = ?", String.class, event.getId()))
        .isEqualTo("CANCELLED");
  }

  /**
   * A refund request is completed (as the charge.refunded webhook does) while the
   * cancellation waits on its row. The cancellation re-reads it under the lock and leaves it
   * COMPLETED; before, it wrote back the REQUESTED copy it had read, approved.
   */
  @Test
  @Timeout(60)
  void cancelWaitingOnARefundBeingCompleted_doesNotReopenIt() throws Exception {
    Bought bought = buy(1);
    UUID refundId = refundRepository.save(new RefundRequest(
        bought.order().getId(), sender.getId(), bought.order().getTotalAmount(), "changed plans"))
        .getId();

    ExecutorService pool = Executors.newSingleThreadExecutor();
    try (Connection lock = dataSource.getConnection()) {
      lock.setAutoCommit(false);
      try (PreparedStatement ps = lock.prepareStatement(
          "UPDATE refund_requests SET status = 'COMPLETED' WHERE id = ?")) {
        ps.setObject(1, refundId);
        ps.executeUpdate();
      }
      Future<Object> cancel = pool.submit(
          () -> eventService.cancelEvent(event.getId(), adminId, "Venue flooded"));
      boolean blocked = waitForLockWaiters(1, Duration.ofSeconds(10));
      lock.commit();
      assertThat(blocked).as("the cancellation waited on the refund row").isTrue();
      cancel.get(30, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }

    assertThat(jdbcTemplate.queryForObject(
        "SELECT status FROM refund_requests WHERE id = ?", String.class, refundId))
        .isEqualTo("COMPLETED");
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM refund_requests WHERE order_id = ?", Integer.class,
        bought.order().getId())).isEqualTo(1);
  }

  private record Bought(Order order, List<UUID> tickets) {}

  /** One order for {@code seats} seats, bought by the sender. */
  private Bought buy(int seats) {
    List<UUID> seatIds = new ArrayList<>();
    for (int i = 1; i <= seats; i++) {
      seatIds.add(seatRepository.save(
          new Seat(event, "A", "1", String.valueOf(i), new BigDecimal("50.00"))).getId());
    }
    List<SeatHold> holds = seatHoldService.createHold(event.getId(), seatIds, sender.getId(), 10);
    for (SeatHold hold : holds) {
      seatHoldService.confirmHold(hold.getId(), sender.getId());
    }
    Order order = orderService.createOrderWithPayment(
        sender.getId(), holds.stream().map(SeatHold::getId).toList(), PaymentStatus.SUCCESS);
    List<UUID> tickets = jdbcTemplate.queryForList(
        "SELECT id FROM tickets WHERE order_id = ?", UUID.class, order.getId());
    return new Bought(order, tickets);
  }

  /**
   * Holds the ticket's row lock on a third connection, starts {@code first} and waits until
   * it is blocked on a lock, then starts {@code second} and waits until it is blocked too,
   * then releases the ticket. Returns what they threw.
   */
  private List<Throwable> raceUnderTicketLock(UUID ticketId, Callable<Object> first,
      Callable<Object> second) throws Exception {
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

  private String ticketStatus(UUID ticketId) {
    return jdbcTemplate.queryForObject(
        "SELECT status FROM tickets WHERE id = ?", String.class, ticketId);
  }

  private UUID ticketOwner(UUID ticketId) {
    return jdbcTemplate.queryForObject(
        "SELECT user_id FROM tickets WHERE id = ?", UUID.class, ticketId);
  }

  private String requestStatus(UUID requestId) {
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
