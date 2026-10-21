package com.fairtix.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fairtix.events.domain.Event;
import com.fairtix.events.infrastructure.EventRepository;
import com.fairtix.inventory.application.SeatHoldConflictException;
import com.fairtix.inventory.application.SeatHoldService;
import com.fairtix.inventory.domain.Seat;
import com.fairtix.inventory.domain.SeatHold;
import com.fairtix.inventory.infrastructure.SeatRepository;
import com.fairtix.inventory.scheduler.HoldExpirationScheduler;
import com.fairtix.orders.application.OrderService;
import com.fairtix.payments.domain.PaymentStatus;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Expired {@code CONFIRMED} holds are swept by
 * {@link HoldExpirationScheduler#expireConfirmedHolds()}: once {@code expires_at}
 * is more than {@code holds.confirmed-grace-minutes} (default 15) in the past, a
 * {@code BOOKED} seat returns to {@code AVAILABLE} and the hold becomes
 * {@code EXPIRED}. A hold whose seat is already {@code SOLD} (left
 * {@code CONFIRMED} by the MVP {@code createOrder} path before it released its
 * holds) becomes {@code RELEASED} and the seat stays sold.
 *
 * <p>Red on commit {@code 6408921}: the sweep was an empty stub (before it, only
 * ACTIVE holds were swept), so an expired CONFIRMED hold kept its seat BOOKED
 * forever, and {@code createOrder} left its holds CONFIRMED. Four of the six tests
 * there failed; the grace test passed either way, and the race then was not forced
 * to overlap.
 *
 * <p>Review round 1 added: the payment-intent extension
 * ({@link SeatHoldService#extendConfirmedHoldsForPayment}), the guard that keeps a
 * stale hold from freeing a seat another hold has booked, and a race that a third
 * connection's seat lock forces to overlap.
 *
 * <p>Scheduling is off in the {@code it} profile, so the tests call the sweep
 * directly. Not {@code @Transactional}: every write commits, and
 * {@link PostgresIT} truncates the tables after each test.
 */
class ConfirmedHoldExpiryIT extends PostgresIT {

  @Autowired private UserRepository userRepository;
  @Autowired private EventRepository eventRepository;
  @Autowired private SeatRepository seatRepository;
  @Autowired private SeatHoldService seatHoldService;
  @Autowired private OrderService orderService;
  @Autowired private HoldExpirationScheduler scheduler;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private DataSource dataSource;

  private Event event;
  private Seat seat;
  private User buyer;

  @BeforeEach
  void setUp() {
    event = eventRepository.save(
        new Event("Expiry Concert", null, Instant.now().plusSeconds(86400), (UUID) null));
    seat = seatRepository.save(new Seat(event, "A", "1", "1", new BigDecimal("50.00")));
    buyer = newUser("buyer@test.com");
  }

  @Test
  void expiredConfirmedHold_returnsSeatToSale() {
    SeatHold hold = confirmedHold();
    expireMinutesAgo(hold, 20);

    scheduler.expireConfirmedHolds();

    assertThat(holdStatus(hold)).isEqualTo("EXPIRED");
    assertThat(seatStatus()).isEqualTo("AVAILABLE");
    User next = newUser("next@test.com");
    assertThat(seatHoldService.createHold(event.getId(), List.of(seat.getId()), next.getId(), 10))
        .hasSize(1);
  }

  @Test
  void confirmedHoldInsideGrace_isKept() {
    SeatHold hold = confirmedHold();
    expireMinutesAgo(hold, 5);

    scheduler.expireConfirmedHolds();

    assertThat(holdStatus(hold)).isEqualTo("CONFIRMED");
    assertThat(seatStatus()).isEqualTo("BOOKED");
  }

  @Test
  void confirmedHoldWithSoldSeat_isReleasedNotResold() {
    SeatHold hold = confirmedHold();
    jdbcTemplate.update("UPDATE seats SET status = 'SOLD' WHERE id = ?", seat.getId());
    expireMinutesAgo(hold, 20);

    scheduler.expireConfirmedHolds();

    assertThat(holdStatus(hold)).isEqualTo("RELEASED");
    assertThat(seatStatus()).isEqualTo("SOLD");
  }

  @Test
  void expiry_isAudited() {
    SeatHold hold = confirmedHold();
    expireMinutesAgo(hold, 20);

    scheduler.expireConfirmedHolds();

    assertThat(jdbcTemplate.queryForList(
        "SELECT user_id FROM audit_logs WHERE action = 'EXPIRE' AND resource_type = 'HOLD'"
            + " AND resource_id = ?", UUID.class, hold.getId()))
        .containsExactly(buyer.getId());
  }

  @Test
  void createOrder_releasesHolds() {
    SeatHold hold = confirmedHold();

    orderService.createOrder(buyer.getId(), List.of(hold.getId()));

    assertThat(holdStatus(hold)).isEqualTo("RELEASED");
    assertThat(seatStatus()).isEqualTo("SOLD");
  }

  @Test
  void extendForPayment_pushesExpiryPastTheSweep() {
    SeatHold hold = confirmedHold();
    expireMinutesAgo(hold, 20);
    long versionBefore = seatVersion();

    seatHoldService.extendConfirmedHoldsForPayment(List.of(hold.getId()), buyer.getId());
    scheduler.expireConfirmedHolds();

    assertThat(holdStatus(hold)).isEqualTo("CONFIRMED");
    assertThat(seatStatus()).isEqualTo("BOOKED");
    assertThat(jdbcTemplate.queryForObject(
        "SELECT expires_at > now() + interval '9 minutes' FROM seat_holds WHERE id = ?",
        Boolean.class, hold.getId())).isTrue();
    assertThat(seatVersion()).isGreaterThan(versionBefore);
  }

  @Test
  void extendForPayment_rejectsSweptHold() {
    SeatHold hold = confirmedHold();
    expireMinutesAgo(hold, 20);
    scheduler.expireConfirmedHolds();

    assertThatThrownBy(() ->
        seatHoldService.extendConfirmedHoldsForPayment(List.of(hold.getId()), buyer.getId()))
        .isInstanceOf(SeatHoldConflictException.class);
  }

  @Test
  void extendForPayment_rejectsActiveHold() {
    SeatHold hold = seatHoldService.createHold(event.getId(), List.of(seat.getId()), buyer.getId(), 10)
        .get(0);

    assertThatThrownBy(() ->
        seatHoldService.extendConfirmedHoldsForPayment(List.of(hold.getId()), buyer.getId()))
        .isInstanceOf(SeatHoldConflictException.class);
  }

  /**
   * A stale CONFIRMED hold whose seat was freed (here by direct SQL, standing in
   * for a refund) and then booked by someone else must not free the newer booking.
   */
  @Test
  void staleHoldDoesNotFreeSeatBookedByAnotherHold() {
    SeatHold stale = confirmedHold();
    jdbcTemplate.update("UPDATE seats SET status = 'AVAILABLE' WHERE id = ?", seat.getId());
    User other = newUser("other@test.com");
    SeatHold current = seatHoldService.createHold(event.getId(), List.of(seat.getId()), other.getId(), 10)
        .get(0);
    seatHoldService.confirmHold(current.getId(), other.getId());
    expireMinutesAgo(stale, 20);

    scheduler.expireConfirmedHolds();

    assertThat(holdStatus(stale)).isEqualTo("EXPIRED");
    assertThat(seatStatus()).isEqualTo("BOOKED");
    assertThat(holdStatus(current)).isEqualTo("CONFIRMED");
  }

  /**
   * Checkout and the sweep race for one expired CONFIRMED hold. A third
   * connection holds the seat row lock until both are waiting on it, so the two
   * really overlap. Whichever commits first wins, and the state is one of the two
   * consistent outcomes, never a ticket on a seat that is back on sale.
   */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void sweepRacingCheckout_neverLeavesTicketOnAvailableSeat() throws Exception {
    SeatHold hold = confirmedHold();
    expireMinutesAgo(hold, 20);

    Callable<Object> checkout = () ->
        orderService.createOrderWithPayment(buyer.getId(), List.of(hold.getId()), PaymentStatus.SUCCESS);
    // Checkout blocks on the ticket FK's KEY SHARE lock or its seat UPDATE, the
    // sweep on its seat UPDATE.
    List<Throwable> failures = raceUnderSeatLock(checkout, sweep());

    String holdStatus = holdStatus(hold);
    String seatStatus = seatStatus();
    int tickets = activeTicketsForSeat();
    System.out.println("[ConfirmedHoldExpiryIT] race: hold=" + holdStatus + " seat=" + seatStatus
        + " tickets=" + tickets + " failures=" + failures.stream().map(t -> t.getClass().getName()).toList());

    if (tickets == 1) {
      assertThat(seatStatus).isEqualTo("SOLD");
      assertThat(holdStatus).isEqualTo("RELEASED");
    } else {
      assertThat(tickets).isZero();
      assertThat(seatStatus).isEqualTo("AVAILABLE");
      assertThat(holdStatus).isEqualTo("EXPIRED");
    }
    // The loser fails on Seat.@Version or the V46 index; a checkout that reads
    // after the sweep commits sees the hold or seat no longer CONFIRMED/BOOKED.
    assertThat(failures).hasSizeLessThanOrEqualTo(1);
    assertThat(failures).allSatisfy(t -> assertThat(t).isInstanceOfAny(
        ObjectOptimisticLockingFailureException.class,
        DataIntegrityViolationException.class,
        SeatHoldConflictException.class,
        IllegalArgumentException.class));
  }

  /**
   * The extension reaches the hold row first: it holds that row lock while it
   * waits for the seat, the sweep (which read the seat's old version) queues
   * behind it, and after the extension commits the sweep fails on the bumped seat
   * version and rolls back. The hold stays CONFIRMED with its new expiry.
   */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void extendThenSweep_extensionWins() throws Exception {
    SeatHold hold = confirmedHold();
    expireMinutesAgo(hold, 20);

    List<Throwable> failures = raceUnderSeatLock(extend(hold), sweep());

    assertThat(holdStatus(hold)).isEqualTo("CONFIRMED");
    assertThat(seatStatus()).isEqualTo("BOOKED");
    assertThat(jdbcTemplate.queryForObject(
        "SELECT expires_at > now() + interval '9 minutes' FROM seat_holds WHERE id = ?",
        Boolean.class, hold.getId())).isTrue();
    assertThat(failures).singleElement().isInstanceOf(ObjectOptimisticLockingFailureException.class);
  }

  /**
   * The sweep reaches the hold row first and commits; the extension's conditional
   * update then matches nothing, and its seat lock finds a newer seat version than
   * the copy it loaded with the hold, so it fails (a 409) and the hold stays
   * EXPIRED. A {@code saveAll} of the loaded holds here would have written the
   * hold back to CONFIRMED on an AVAILABLE seat.
   */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void sweepThenExtend_sweepWins() throws Exception {
    SeatHold hold = confirmedHold();
    expireMinutesAgo(hold, 20);

    List<Throwable> failures = raceUnderSeatLock(sweep(), extend(hold));

    assertThat(holdStatus(hold)).isEqualTo("EXPIRED");
    assertThat(seatStatus()).isEqualTo("AVAILABLE");
    assertThat(failures).singleElement().satisfies(t -> assertThat(t).isInstanceOfAny(
        SeatHoldConflictException.class, ObjectOptimisticLockingFailureException.class));
  }

  // ---- helpers ----

  private Callable<Object> sweep() {
    return () -> {
      scheduler.expireConfirmedHolds();
      return null;
    };
  }

  private Callable<Object> extend(SeatHold hold) {
    return () -> {
      seatHoldService.extendConfirmedHoldsForPayment(List.of(hold.getId()), buyer.getId());
      return null;
    };
  }

  /**
   * Holds the seat row lock on a third connection, starts {@code first} and waits
   * until it is blocked on a lock, then starts {@code second} and waits until it
   * is blocked too, then releases the seat. The two really overlap, in a fixed
   * order. Returns what they threw.
   */
  private List<Throwable> raceUnderSeatLock(Callable<Object> first, Callable<Object> second) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    List<Throwable> failures = new ArrayList<>();
    try (Connection lock = dataSource.getConnection()) {
      lock.setAutoCommit(false);
      try (PreparedStatement ps = lock.prepareStatement("SELECT id FROM seats WHERE id = ? FOR UPDATE")) {
        ps.setObject(1, seat.getId());
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

  private SeatHold confirmedHold() {
    SeatHold hold = seatHoldService.createHold(event.getId(), List.of(seat.getId()), buyer.getId(), 10)
        .get(0);
    seatHoldService.confirmHold(hold.getId(), buyer.getId());
    return hold;
  }

  private void expireMinutesAgo(SeatHold hold, int minutes) {
    jdbcTemplate.update("UPDATE seat_holds SET expires_at = now() - make_interval(mins => ?) WHERE id = ?",
        minutes, hold.getId());
  }

  private String holdStatus(SeatHold hold) {
    return jdbcTemplate.queryForObject("SELECT status FROM seat_holds WHERE id = ?", String.class, hold.getId());
  }

  private String seatStatus() {
    return jdbcTemplate.queryForObject("SELECT status FROM seats WHERE id = ?", String.class, seat.getId());
  }

  private long seatVersion() {
    return jdbcTemplate.queryForObject("SELECT version FROM seats WHERE id = ?", Long.class, seat.getId());
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

  private int activeTicketsForSeat() {
    Integer count = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM tickets WHERE seat_id = ? AND status NOT IN ('CANCELLED', 'REFUNDED')",
        Integer.class, seat.getId());
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
