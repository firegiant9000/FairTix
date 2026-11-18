package com.fairtix.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.fairtix.events.domain.Event;
import com.fairtix.events.infrastructure.EventRepository;
import com.fairtix.inventory.application.SeatHoldConflictException;
import com.fairtix.inventory.application.SeatHoldService;
import com.fairtix.inventory.domain.Seat;
import com.fairtix.inventory.infrastructure.SeatRepository;
import com.fairtix.queue.application.QueueService;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code holds.max-active-per-holder} is one limit per user across every event,
 * and two overlapping hold requests from the same user cannot both get past it.
 *
 * <p>Red on {@code 625dcbdc}: the count ran under the purchase-cap lock only,
 * which is taken only for capped events and is keyed per (event, user), so two
 * holds on different (here uncapped) events both counted the same number and
 * both went through.
 *
 * <p>The race is forced, not left to timing: a third connection holds the row
 * locks on both seats, each request is started and observed waiting on a lock in
 * {@code pg_stat_activity}, and only then are the seats released. Not
 * {@code @Transactional}: every write commits, and {@link PostgresIT} truncates
 * the tables after each test.
 */
class HoldLimitIT extends PostgresIT {

  @Autowired private UserRepository userRepository;
  @Autowired private EventRepository eventRepository;
  @Autowired private SeatRepository seatRepository;
  @Autowired private SeatHoldService seatHoldService;
  @Autowired private QueueService queueService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private DataSource dataSource;

  @Value("${holds.max-active-per-holder}")
  private int maxActivePerHolder;

  @Test
  @Timeout(60)
  void overlappingHoldsOnDifferentEvents_stayWithinTheHoldLimit() throws Exception {
    Event first = newEvent("First Concert");
    Event second = newEvent("Second Concert");
    User user = newUser("holder@test.com");
    // One short of the limit, on the first event.
    for (int i = 0; i < maxActivePerHolder - 1; i++) {
      Seat seat = newSeat(first, "F" + i);
      seatHoldService.createHold(first.getId(), List.of(seat.getId()), user.getId(), 10);
    }
    Seat lastOnFirst = newSeat(first, "F-last");
    Seat onSecond = newSeat(second, "S1");

    List<Throwable> failures = raceUnderSeatLocks(lastOnFirst, onSecond,
        () -> seatHoldService.createHold(first.getId(), List.of(lastOnFirst.getId()), user.getId(), 10),
        () -> seatHoldService.createHold(second.getId(), List.of(onSecond.getId()), user.getId(), 10));

    assertThat(activeHolds(user)).as("active holds under a limit of " + maxActivePerHolder)
        .isEqualTo(maxActivePerHolder);
    assertThat(failures).hasSize(1);
    assertThat(failures.get(0))
        .isInstanceOf(SeatHoldConflictException.class)
        .hasMessageContaining("Hold limit reached");
  }

  /**
   * One queue admission is one hold: the first hold completes the admission, and
   * an overlapping second request, checked under the same lock, sees that.
   */
  @Test
  @Timeout(60)
  void overlappingHoldsOnOneAdmission_onlyOneGetsThrough() throws Exception {
    Event queued = new Event("Queued Concert", null, Instant.now().plusSeconds(86400), (UUID) null);
    queued.updateQueueSettings(true, null);
    Event event = eventRepository.save(queued);
    Seat a = newSeat(event, "Q1");
    Seat b = newSeat(event, "Q2");
    User user = newUser("queued@test.com");
    queueService.joinQueue(event.getId(), user.getId());
    assertThat(queueService.admitNextBatch(event.getId())).hasSize(1);

    List<Throwable> failures = raceUnderSeatLocks(a, b,
        () -> seatHoldService.createHold(event.getId(), List.of(a.getId()), user.getId(), 10),
        () -> seatHoldService.createHold(event.getId(), List.of(b.getId()), user.getId(), 10));

    assertThat(activeHolds(user)).as("holds from one admission").isEqualTo(1);
    assertThat(failures).hasSize(1);
    assertThat(failures.get(0))
        .isInstanceOf(SeatHoldConflictException.class)
        .hasMessageContaining("Queue admission required");
  }

  private List<Throwable> raceUnderSeatLocks(Seat a, Seat b, Callable<Object> firstCall,
      Callable<Object> secondCall) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    List<Throwable> failures = new ArrayList<>();
    try (Connection lock = dataSource.getConnection()) {
      lock.setAutoCommit(false);
      try (PreparedStatement ps =
          lock.prepareStatement("SELECT id FROM seats WHERE id IN (?, ?) ORDER BY id FOR UPDATE")) {
        ps.setObject(1, a.getId());
        ps.setObject(2, b.getId());
        ps.executeQuery().close();
      }
      List<Future<Object>> futures = new ArrayList<>();
      futures.add(pool.submit(firstCall));
      boolean firstBlocked = waitForLockWaiters(1, Duration.ofSeconds(10));
      futures.add(pool.submit(secondCall));
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

  private int activeHolds(User user) {
    Integer count = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM seat_holds WHERE owner_id = ? AND status = 'ACTIVE'",
        Integer.class, user.getId());
    return count == null ? 0 : count;
  }

  private Event newEvent(String title) {
    return eventRepository.save(new Event(title, null, Instant.now().plusSeconds(86400), (UUID) null));
  }

  private Seat newSeat(Event event, String number) {
    return seatRepository.save(new Seat(event, "A", "1", number, new BigDecimal("40.00")));
  }

  private User newUser(String email) {
    User user = new User();
    user.setEmail(email);
    user.setPassword("$2a$10$dummyhashfortest");
    user.setEmailVerified(true);
    return userRepository.save(user);
  }
}
