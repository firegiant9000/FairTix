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
 * The hold gate and checkout share one (event, user) lock, so a checkout and a
 * new hold by the same user cannot interleave: with a cap of 1 and one confirmed
 * hold, a checkout of that hold overlapping a hold on a second seat ends with one
 * ticket and no live hold, never a ticket plus a hold.
 *
 * <p>The overlap is forced: a third connection holds the row locks on both seats,
 * the checkout is started and observed waiting (on its seat update, with the cap
 * lock taken), then the hold is started and observed waiting (on the cap lock),
 * and only then are the seats released.
 */
class PurchaseCapHoldVersusCheckoutIT extends PostgresIT {

  @Autowired private UserRepository userRepository;
  @Autowired private EventRepository eventRepository;
  @Autowired private SeatRepository seatRepository;
  @Autowired private SeatHoldService seatHoldService;
  @Autowired private OrderService orderService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private DataSource dataSource;

  private Event event;
  private Seat seatA;
  private Seat seatB;
  private UUID buyerId;

  @BeforeEach
  void setUp() {
    Event capped = new Event("Capped Concert", null, Instant.now().plusSeconds(86400), (UUID) null);
    capped.setMaxTicketsPerUser(1);
    event = eventRepository.save(capped);
    seatA = seatRepository.save(new Seat(event, "A", "1", "1", new BigDecimal("50.00")));
    seatB = seatRepository.save(new Seat(event, "A", "1", "2", new BigDecimal("50.00")));
    User buyer = new User();
    buyer.setEmail("buyer@test.com");
    buyer.setPassword("$2a$10$dummyhashfortest");
    buyer.setEmailVerified(true);
    buyerId = userRepository.save(buyer).getId();
  }

  /**
   * The check that runs before a Stripe PaymentIntent is created: an order that
   * checkout would refuse for the cap is refused before the customer pays. Two
   * confirmed holds under a cap of 1 (the organizer lowered the cap after the
   * holds were placed); the first is bought, the second can no longer be paid for.
   */
  @Test
  void capCheckBeforePayment_refusesWhatCheckoutWouldRefuse() {
    setCap(null);
    SeatHold holdA = confirmedHold(seatA);
    SeatHold holdB = confirmedHold(seatB);
    setCap(1);

    orderService.assertWithinPurchaseCaps(buyerId, List.of(holdA.getId()));
    orderService.createOrder(buyerId, List.of(holdA.getId()));

    assertThatThrownBy(() -> orderService.assertWithinPurchaseCaps(buyerId, List.of(holdB.getId())))
        .isInstanceOf(PurchaseCapExceededException.class);
  }

  @Test
  @Timeout(60)
  void checkoutOverlappingANewHold_staysWithinTheCap() throws Exception {
    SeatHold holdA = confirmedHold(seatA);

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
      futures.add(pool.submit(() -> orderService.createOrder(buyerId, List.of(holdA.getId()))));
      boolean checkoutBlocked = waitForLockWaiters(1, Duration.ofSeconds(10));
      futures.add(pool.submit(
          () -> seatHoldService.createHold(event.getId(), List.of(seatB.getId()), buyerId, 10)));
      boolean bothBlocked = waitForLockWaiters(2, Duration.ofSeconds(10));
      lock.commit();
      assertThat(checkoutBlocked).as("the checkout waited on a lock").isTrue();
      assertThat(bothBlocked).as("the checkout and the hold both waited on a lock").isTrue();
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

    assertThat(count("SELECT COUNT(*) FROM tickets WHERE user_id = ? AND event_id = ?"
        + " AND status NOT IN ('CANCELLED', 'REFUNDED')", buyerId, event.getId()))
        .as("tickets issued").isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM seat_holds h JOIN seats s ON s.id = h.seat_id"
        + " WHERE h.owner_id = ? AND s.event_id = ? AND h.status IN ('ACTIVE', 'CONFIRMED')",
        buyerId, event.getId()))
        .as("live holds left next to the ticket").isZero();
    assertThat(failures).hasSize(1);
    assertThat(failures.get(0))
        .isInstanceOf(SeatHoldConflictException.class)
        .hasMessageContaining("Purchase cap");
  }

  private SeatHold confirmedHold(Seat seat) {
    SeatHold hold =
        seatHoldService.createHold(event.getId(), List.of(seat.getId()), buyerId, 10).get(0);
    seatHoldService.confirmHold(hold.getId(), buyerId);
    return hold;
  }

  private void setCap(Integer cap) {
    jdbcTemplate.update(
        "UPDATE events SET max_tickets_per_user = ?, version = version + 1 WHERE id = ?",
        cap, event.getId());
  }

  private int count(String sql, Object... args) {
    Integer count = jdbcTemplate.queryForObject(sql, Integer.class, args);
    return count == null ? 0 : count;
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
}
