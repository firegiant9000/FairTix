package com.fairtix.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fairtix.events.domain.Event;
import com.fairtix.events.infrastructure.EventRepository;
import com.fairtix.inventory.application.SeatHoldConflictException;
import com.fairtix.inventory.application.SeatHoldService;
import com.fairtix.inventory.domain.Seat;
import com.fairtix.inventory.domain.SeatHold;
import com.fairtix.inventory.infrastructure.SeatRepository;
import com.fairtix.orders.application.OrderService;
import com.fairtix.orders.application.PurchaseCapExceededException;
import com.fairtix.queue.application.QueueConflictException;
import com.fairtix.queue.application.QueueService;
import com.fairtix.queue.domain.QueueStatus;
import com.fairtix.support.PostgresIT;
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
 * The per-user purchase cap ({@code events.max_tickets_per_user}) is one rule on
 * every path: a user's active tickets (not {@code CANCELLED}, not
 * {@code REFUNDED}) plus the seats they are holding ({@code ACTIVE} or
 * {@code CONFIRMED}) never exceed the cap, and two overlapping requests from the
 * same user cannot both get past the check.
 *
 * <p>Red on {@code 8a5328b}: the hold gate and checkout both counted and then
 * wrote with nothing serialising the two steps, so the two forced races each
 * ended over the cap; a confirmed hold stopped counting toward the hold gate; and
 * a refunded ticket did not count at the hold gate but did at checkout and when
 * joining the queue.
 *
 * <p>The races are forced, not left to timing: a third connection holds the row
 * locks on both seats, each request is started and observed waiting on a lock in
 * {@code pg_stat_activity}, and only then are the seats released. Not
 * {@code @Transactional}: every write commits, and {@link PostgresIT} truncates
 * the tables after each test.
 */
class PurchaseCapIT extends PostgresIT {

  @Autowired private UserRepository userRepository;
  @Autowired private EventRepository eventRepository;
  @Autowired private SeatRepository seatRepository;
  @Autowired private SeatHoldService seatHoldService;
  @Autowired private OrderService orderService;
  @Autowired private QueueService queueService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private DataSource dataSource;

  private Event event;
  private Seat seatA;
  private Seat seatB;
  private User buyer;

  @BeforeEach
  void setUp() {
    Event capped = new Event("Capped Concert", null, Instant.now().plusSeconds(86400), (UUID) null);
    capped.setMaxTicketsPerUser(1);
    event = eventRepository.save(capped);
    seatA = seatRepository.save(new Seat(event, "A", "1", "1", new BigDecimal("50.00")));
    seatB = seatRepository.save(new Seat(event, "A", "1", "2", new BigDecimal("50.00")));
    buyer = newUser("buyer@test.com");
  }

  @Test
  @Timeout(60)
  void overlappingHoldsForDifferentSeats_stayWithinTheCap() throws Exception {
    List<Throwable> failures = raceUnderSeatLocks(
        () -> seatHoldService.createHold(event.getId(), List.of(seatA.getId()), buyer.getId(), 10),
        () -> seatHoldService.createHold(event.getId(), List.of(seatB.getId()), buyer.getId(), 10));

    assertThat(liveHolds()).as("seats held by one user under a cap of 1").isEqualTo(1);
    assertThat(failures).hasSize(1);
    assertThat(failures.get(0))
        .isInstanceOf(SeatHoldConflictException.class)
        .hasMessageContaining("Purchase cap");
  }

  @Test
  @Timeout(60)
  void overlappingCheckoutsForDifferentHolds_stayWithinTheCap() throws Exception {
    // Two confirmed holds under a cap of 1: the organizer lowered the cap after
    // the holds were placed.
    setCap(null);
    SeatHold holdA = confirmedHold(seatA);
    SeatHold holdB = confirmedHold(seatB);
    setCap(1);

    List<Throwable> failures = raceUnderSeatLocks(
        () -> orderService.createOrder(buyer.getId(), List.of(holdA.getId())),
        () -> orderService.createOrder(buyer.getId(), List.of(holdB.getId())));

    assertThat(activeTickets()).as("tickets issued to one user under a cap of 1").isEqualTo(1);
    assertThat(failures).hasSize(1);
    assertThat(failures.get(0)).isInstanceOf(PurchaseCapExceededException.class);
  }

  @Test
  void confirmedHold_stillCountsTowardTheCap() {
    confirmedHold(seatA);

    assertThatThrownBy(
        () -> seatHoldService.createHold(event.getId(), List.of(seatB.getId()), buyer.getId(), 10))
        .isInstanceOf(SeatHoldConflictException.class)
        .hasMessageContaining("Purchase cap");
    assertThat(liveHolds()).isEqualTo(1);
  }

  @Test
  void activeTicket_countsOnEveryPath() {
    buy(seatA);

    assertThatThrownBy(
        () -> seatHoldService.createHold(event.getId(), List.of(seatB.getId()), buyer.getId(), 10))
        .isInstanceOf(SeatHoldConflictException.class)
        .hasMessageContaining("Purchase cap");
    requireQueue();
    assertThatThrownBy(() -> queueService.joinQueue(event.getId(), buyer.getId()))
        .isInstanceOf(QueueConflictException.class);
  }

  @Test
  void refundedTicket_doesNotCountAtHoldOrCheckout() {
    buy(seatA);
    refundTicketFor(seatA);

    SeatHold hold = confirmedHold(seatB);
    orderService.createOrder(buyer.getId(), List.of(hold.getId()));

    assertThat(activeTickets()).isEqualTo(1);
  }

  @Test
  void refundedTicket_doesNotBlockJoiningTheQueue() {
    buy(seatA);
    refundTicketFor(seatA);
    requireQueue();

    assertThat(queueService.joinQueue(event.getId(), buyer.getId()).getStatus())
        .isEqualTo(QueueStatus.WAITING);
  }

  /**
   * Holds the row locks on both seats on a third connection, starts {@code first}
   * and waits until it is blocked on a lock, then starts {@code second} and waits
   * until it is blocked too, then releases the seats. The two really overlap, in a
   * fixed order. Returns what they threw.
   */
  private List<Throwable> raceUnderSeatLocks(Callable<Object> first, Callable<Object> second)
      throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    List<Throwable> failures = new ArrayList<>();
    try (Connection lock = dataSource.getConnection()) {
      lock.setAutoCommit(false);
      try (PreparedStatement ps =
          lock.prepareStatement("SELECT id FROM seats WHERE id IN (?, ?) ORDER BY id FOR UPDATE")) {
        ps.setObject(1, seatA.getId());
        ps.setObject(2, seatB.getId());
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

  private SeatHold confirmedHold(Seat seat) {
    SeatHold hold =
        seatHoldService.createHold(event.getId(), List.of(seat.getId()), buyer.getId(), 10).get(0);
    seatHoldService.confirmHold(hold.getId(), buyer.getId());
    return hold;
  }

  private void buy(Seat seat) {
    orderService.createOrder(buyer.getId(), List.of(confirmedHold(seat).getId()));
  }

  /** The database state a completed refund leaves: ticket REFUNDED, seat back on sale. */
  private void refundTicketFor(Seat seat) {
    jdbcTemplate.update("UPDATE tickets SET status = 'REFUNDED' WHERE seat_id = ?", seat.getId());
    jdbcTemplate.update(
        "UPDATE seats SET status = 'AVAILABLE', version = version + 1 WHERE id = ?", seat.getId());
  }

  private void setCap(Integer cap) {
    jdbcTemplate.update(
        "UPDATE events SET max_tickets_per_user = ?, version = version + 1 WHERE id = ?",
        cap, event.getId());
  }

  private void requireQueue() {
    jdbcTemplate.update(
        "UPDATE events SET queue_required = TRUE, version = version + 1 WHERE id = ?", event.getId());
  }

  private int liveHolds() {
    Integer count = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM seat_holds h JOIN seats s ON s.id = h.seat_id"
            + " WHERE h.owner_id = ? AND s.event_id = ? AND h.status IN ('ACTIVE', 'CONFIRMED')",
        Integer.class, buyer.getId(), event.getId());
    return count == null ? 0 : count;
  }

  private int activeTickets() {
    Integer count = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM tickets WHERE user_id = ? AND event_id = ?"
            + " AND status NOT IN ('CANCELLED', 'REFUNDED')",
        Integer.class, buyer.getId(), event.getId());
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
