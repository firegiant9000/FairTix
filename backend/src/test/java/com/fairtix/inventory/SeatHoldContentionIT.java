package com.fairtix.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.fairtix.events.domain.Event;
import com.fairtix.events.infrastructure.EventRepository;
import com.fairtix.inventory.application.SeatHoldConflictException;
import com.fairtix.inventory.application.SeatHoldService;
import com.fairtix.inventory.domain.Seat;
import com.fairtix.inventory.domain.SeatHold;
import com.fairtix.inventory.infrastructure.SeatRepository;
import com.fairtix.orders.application.OrderService;
import com.fairtix.payments.domain.PaymentStatus;
import com.fairtix.support.PostgresIT;
import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * L1 item 3 (without the deadlock variant): N=200 distinct users race for an
 * M=50-seat section through {@link SeatHoldService#createHold}. Each asks for 1-4
 * seats drawn from the whole section with a fixed seed, so requests overlap
 * heavily. The invariant is zero oversells: at most one ACTIVE hold per seat, and
 * after every winner confirms and checks out, at most one active ticket per seat.
 * No retries.
 *
 * <p>Concurrency is bounded at (Hikari pool size - 1) worker threads, released
 * together by one latch, with the 200 tasks queued behind them. {@code createHold}
 * holds one pooled connection and opens a second for the {@code REQUIRES_NEW}
 * {@code AuditService.log}; with 200 threads on the default pool of 10, every
 * connection ended up waiting for a second one, and 197 of 200 calls failed with
 * {@code CannotCreateTransactionException} after Hikari's 30 s timeout (still with
 * zero oversells). That pool starvation is recorded as a finding for L1-rest, not
 * fixed here; see {@code docs/experiments/l1-contention-correctness.md}.
 *
 * <p>A background thread samples {@code pg_locks} and {@code pg_stat_activity}
 * every 50 ms on its own unpooled connection and records the maxima. This is a
 * correctness test, not a performance measurement.
 *
 * <p>{@code holds.max-active-per-holder} is the production default (5) rather
 * than the test suite's 2, so a 3- or 4-seat request is not rejected by the soft
 * limit before it ever contends for a seat.
 */
@TestPropertySource(properties = {
    "holds.max-active-per-holder=5",
    // Pinned to Hikari's (and production's) default so the worker count below is stable.
    "spring.datasource.hikari.maximum-pool-size=10",
    // Shorter than the 30 s default so pool starvation, if it comes back, fails fast.
    "spring.datasource.hikari.connection-timeout=5000"
})
class SeatHoldContentionIT extends PostgresIT {

  static final int USERS = 200;
  static final int SEATS = 50;
  static final int MAX_SEATS_PER_REQUEST = 4;
  static final long SEED = 20261019L;

  @Autowired private UserRepository userRepository;
  @Autowired private EventRepository eventRepository;
  @Autowired private SeatRepository seatRepository;
  @Autowired private SeatHoldService seatHoldService;
  @Autowired private OrderService orderService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private DataSource dataSource;
  @Autowired private JdbcConnectionDetails connectionDetails;

  @Test
  @Timeout(value = 180, unit = TimeUnit.SECONDS)
  void twoHundredUsersRaceForFiftySeats_withoutOversell() throws Exception {
    Event event = eventRepository.save(
        new Event("Contention Concert", null, Instant.now().plusSeconds(86400), (UUID) null));
    assertThat(event.isQueueRequired()).isFalse();
    assertThat(event.getMaxTicketsPerUser()).isNull();

    List<Seat> seats = new ArrayList<>();
    for (int i = 0; i < SEATS; i++) {
      seats.add(new Seat(event, "FLOOR", String.valueOf(i / 10 + 1), String.valueOf(i % 10 + 1),
          new BigDecimal("40.00")));
    }
    seats = seatRepository.saveAll(seats);

    List<User> users = new ArrayList<>();
    for (int i = 0; i < USERS; i++) {
      User user = new User();
      user.setEmail("contender" + i + "@test.com");
      user.setPassword("$2a$10$dummyhashfortest");
      user.setEmailVerified(true);
      users.add(user);
    }
    users = userRepository.saveAll(users);

    // Requests are fixed before any thread starts, so the workload is reproducible.
    Random random = new Random(SEED);
    List<List<UUID>> requests = new ArrayList<>();
    for (int i = 0; i < USERS; i++) {
      List<UUID> pool = new ArrayList<>(seats.stream().map(Seat::getId).toList());
      Collections.shuffle(pool, random);
      requests.add(List.copyOf(pool.subList(0, 1 + random.nextInt(MAX_SEATS_PER_REQUEST))));
    }
    int requestedSeats = requests.stream().mapToInt(List::size).sum();

    int hikariPoolSize = dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize();
    // Twice the pool: if createHold ever needs a second connection again (as the
    // REQUIRES_NEW audit write did), more than (pool - 1) callers starve the pool.
    // See the class Javadoc.
    int workers = 2 * hikariPoolSize;
    assertThat(workers).as("more workers than the starvation-free bound").isGreaterThan(hikariPoolSize - 1);

    // ---- race ----
    LockSampler sampler = new LockSampler(connectionDetails);
    Thread samplerThread = new Thread(sampler, "pg-lock-sampler");

    CountDownLatch ready = new CountDownLatch(workers);
    CountDownLatch start = new CountDownLatch(1);
    Map<UUID, List<SeatHold>> winners = new ConcurrentHashMap<>();
    Map<String, AtomicInteger> failuresByClass = new ConcurrentHashMap<>();
    AtomicReference<Exception> firstUnexpected = new AtomicReference<>();

    ExecutorService pool = Executors.newFixedThreadPool(workers);
    long elapsedMillis;
    samplerThread.start();
    try {
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < USERS; i++) {
        UUID ownerId = users.get(i).getId();
        List<UUID> seatIds = requests.get(i);
        futures.add(pool.submit(() -> {
          ready.countDown();
          start.await();
          try {
            winners.put(ownerId, seatHoldService.createHold(event.getId(), seatIds, ownerId, 10));
          } catch (Exception ex) {
            failuresByClass.computeIfAbsent(ex.getClass().getName(), k -> new AtomicInteger())
                .incrementAndGet();
            if (!(ex instanceof SeatHoldConflictException)) {
              firstUnexpected.compareAndSet(null, ex);
            }
          }
          return null;
        }));
      }
      assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
      long startedAt = System.nanoTime();
      start.countDown();
      for (Future<?> future : futures) {
        future.get(120, TimeUnit.SECONDS);
      }
      elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    } finally {
      pool.shutdownNow();
      sampler.stop();
      samplerThread.join(5_000);
    }

    int conflicts = count(failuresByClass, SeatHoldConflictException.class);
    Map<String, Integer> unexpected = new TreeMap<>();
    failuresByClass.forEach((name, n) -> {
      if (!name.equals(SeatHoldConflictException.class.getName())) {
        unexpected.put(name, n.get());
      }
    });
    int heldSeats = winners.values().stream().mapToInt(List::size).sum();

    System.out.printf(
        "[SeatHoldContentionIT] users=%d seats=%d seed=%d requestedSeats=%d hikariPool=%d workers=%d image=%s"
            + " successes=%d conflicts=%d unexpected=%s heldSeats=%d maxUngrantedLocks=%d"
            + " maxLockWaiters=%d samples=%d raceMillis=%d%n",
        USERS, SEATS, SEED, requestedSeats, hikariPoolSize, workers, POSTGRES_IMAGE,
        winners.size(), conflicts, unexpected, heldSeats, sampler.maxUngranted.get(),
        sampler.maxLockWaiters.get(), sampler.samples.get(), elapsedMillis);

    if (firstUnexpected.get() != null) {
      firstUnexpected.get().printStackTrace(System.out);
    }

    // ---- holds: zero oversells ----
    assertThat(unexpected).as("exceptions other than SeatHoldConflictException").isEmpty();
    assertThat(winners.size() + conflicts).isEqualTo(USERS);
    assertThat(heldSeats).isPositive().isLessThanOrEqualTo(SEATS);
    assertThat(jdbcTemplate.queryForList(
        "SELECT seat_id FROM seat_holds WHERE status = 'ACTIVE' GROUP BY seat_id HAVING COUNT(*) > 1",
        UUID.class)).as("seats with more than one ACTIVE hold").isEmpty();
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM seat_holds WHERE status = 'ACTIVE'", Integer.class)).isEqualTo(heldSeats);
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM seats WHERE status = 'HELD'", Integer.class)).isEqualTo(heldSeats);
    assertThat(sampler.samples.get()).isPositive();

    // ---- every winner confirms and checks out ----
    for (Map.Entry<UUID, List<SeatHold>> winner : winners.entrySet()) {
      List<UUID> holdIds = winner.getValue().stream().map(SeatHold::getId).toList();
      for (UUID holdId : holdIds) {
        seatHoldService.confirmHold(holdId, winner.getKey());
      }
      orderService.createOrderWithPayment(winner.getKey(), holdIds, PaymentStatus.SUCCESS);
    }

    // ---- tickets: zero oversells ----
    assertThat(jdbcTemplate.queryForList(
        "SELECT seat_id FROM tickets WHERE status NOT IN ('CANCELLED', 'REFUNDED')"
            + " GROUP BY seat_id HAVING COUNT(*) > 1",
        UUID.class)).as("seats with more than one active ticket").isEmpty();
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM tickets WHERE status NOT IN ('CANCELLED', 'REFUNDED')", Integer.class))
        .isEqualTo(heldSeats);
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM seats WHERE status = 'SOLD'", Integer.class)).isEqualTo(heldSeats);
  }

  private static int count(Map<String, AtomicInteger> byClass, Class<?> type) {
    AtomicInteger n = byClass.get(type.getName());
    return n == null ? 0 : n.get();
  }

  /** Samples lock waits every 50 ms on its own connection, outside the Hikari pool. */
  static final class LockSampler implements Runnable {
    final AtomicInteger maxUngranted = new AtomicInteger();
    final AtomicInteger maxLockWaiters = new AtomicInteger();
    final AtomicInteger samples = new AtomicInteger();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final JdbcConnectionDetails details;

    LockSampler(JdbcConnectionDetails details) {
      this.details = details;
    }

    void stop() {
      running.set(false);
    }

    @Override
    public void run() {
      try (Connection connection = DriverManager.getConnection(
              details.getJdbcUrl(), details.getUsername(), details.getPassword());
          Statement statement = connection.createStatement()) {
        while (running.get()) {
          maxUngranted.accumulateAndGet(
              scalar(statement, "SELECT COUNT(*) FROM pg_locks WHERE NOT granted"), Math::max);
          maxLockWaiters.accumulateAndGet(
              scalar(statement, "SELECT COUNT(*) FROM pg_stat_activity"
                  + " WHERE wait_event_type = 'Lock' AND datname = current_database()"), Math::max);
          samples.incrementAndGet();
          Thread.sleep(50);
        }
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
      } catch (Exception ex) {
        throw new IllegalStateException("lock sampler failed", ex);
      }
    }

    private static int scalar(Statement statement, String sql) throws Exception {
      try (ResultSet rs = statement.executeQuery(sql)) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }
}
