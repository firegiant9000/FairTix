package com.fairtix.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.fairtix.events.domain.Event;
import com.fairtix.events.infrastructure.EventRepository;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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

  /**
   * Checkout and the sweep race for one expired CONFIRMED hold. Whichever commits
   * first wins, and the state is one of the two consistent outcomes, never a
   * ticket on a seat that is back on sale.
   */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void sweepRacingCheckout_neverLeavesTicketOnAvailableSeat() throws Exception {
    SeatHold hold = confirmedHold();
    expireMinutesAgo(hold, 20);

    CountDownLatch start = new CountDownLatch(1);
    Callable<Object> checkout = () -> {
      start.await();
      return orderService.createOrderWithPayment(buyer.getId(), List.of(hold.getId()), PaymentStatus.SUCCESS);
    };
    Callable<Object> sweep = () -> {
      start.await();
      scheduler.expireConfirmedHolds();
      return null;
    };

    ExecutorService pool = Executors.newFixedThreadPool(2);
    List<Throwable> failures = new ArrayList<>();
    try {
      List<Future<Object>> futures = List.of(pool.submit(checkout), pool.submit(sweep));
      start.countDown();
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
    // The loser, if the two overlapped, fails on Seat.@Version or the V46 index;
    // a checkout that ran after the sweep sees the hold no longer CONFIRMED.
    assertThat(failures).hasSizeLessThanOrEqualTo(1);
    assertThat(failures).allSatisfy(t -> assertThat(t).isInstanceOfAny(
        ObjectOptimisticLockingFailureException.class,
        DataIntegrityViolationException.class,
        IllegalArgumentException.class));
  }

  // ---- helpers ----

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
