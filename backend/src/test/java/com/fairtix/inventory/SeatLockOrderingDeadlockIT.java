package com.fairtix.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.fairtix.events.domain.Event;
import com.fairtix.events.infrastructure.EventRepository;
import com.fairtix.inventory.domain.Seat;
import com.fairtix.inventory.infrastructure.SeatRepository;
import com.fairtix.support.PostgresIT;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * L1 item 3, the no-ordering variant (RM:141): proves that sorted lock acquisition is
 * what keeps {@link com.fairtix.inventory.application.SeatHoldService#createHold} free
 * of deadlocks. The service always sorts the seat IDs and
 * {@code SeatRepository.findAndLockByIdIn} adds {@code ORDER BY s.id}, so it cannot be
 * made to deadlock; these tests take the same row locks one seat at a time with
 * {@code SELECT ... FOR UPDATE} in a caller-chosen order instead.
 *
 * <ul>
 *   <li>{@link #unorderedLocking_deadlocks}: two transactions lock the same two seats in
 *       opposite orders, so PostgreSQL detects the cycle (SQLState {@code 40P01}) after
 *       {@code deadlock_timeout} and aborts exactly one of them.
 *   <li>{@link #sortedLocking_neverDeadlocks}: the same two transactions with sorted
 *       order queue behind each other and both commit.
 *   <li>{@link #unorderedContention_countsDeadlocks}: the {@link SeatHoldContentionIT}
 *       workload (200 requests, 50 seats, the same seed) with shuffled lock order. It
 *       counts deadlocks and still finds no seat claimed twice, because a deadlock
 *       loser rolls back.
 * </ul>
 *
 * <p>Spring translates {@code 40P01} to a {@link PessimisticLockingFailureException}
 * subclass (the deadlock-loser translation); the tests assert that type plus the
 * SQLState rather than the deprecated subclass name.
 */
@TestPropertySource(properties = {
    "spring.datasource.hikari.maximum-pool-size=10",
    "spring.datasource.hikari.connection-timeout=5000"
})
class SeatLockOrderingDeadlockIT extends PostgresIT {

  static final String DEADLOCK_SQLSTATE = "40P01";
  static final int REQUESTS = 200;
  static final int SEATS = 50;
  static final int MAX_SEATS_PER_REQUEST = 4;
  static final long SEED = 20261019L;

  @Autowired private EventRepository eventRepository;
  @Autowired private SeatRepository seatRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlatformTransactionManager transactionManager;

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void unorderedLocking_deadlocks() throws Exception {
    List<UUID> seats = createSeats(2);
    UUID s1 = seats.get(0);
    UUID s2 = seats.get(1);
    CyclicBarrier bothHoldFirstLock = new CyclicBarrier(2);

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> a = pool.submit(() -> lockInOrder(List.of(s1, s2), bothHoldFirstLock));
      Future<?> b = pool.submit(() -> lockInOrder(List.of(s2, s1), bothHoldFirstLock));
      List<Throwable> failures = new ArrayList<>();
      for (Future<?> f : List.of(a, b)) {
        try {
          f.get(20, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException ex) {
          failures.add(ex.getCause());
        }
      }

      System.out.printf("[SeatLockOrderingDeadlockIT] pair failures=%s%n",
          failures.stream().map(t -> t.getClass().getName()).toList());
      assertThat(failures).as("exactly one deadlock loser").hasSize(1);
      assertThat(failures.get(0)).isInstanceOf(PessimisticLockingFailureException.class);
      assertThat(isDeadlock(failures.get(0))).as("SQLState 40P01 in the cause chain").isTrue();
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void sortedLocking_neverDeadlocks() throws Exception {
    List<UUID> sorted = createSeats(2).stream().sorted().toList();
    CountDownLatch aHoldsFirst = new CountDownLatch(1);
    CountDownLatch bIsWaiting = new CountDownLatch(1);

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> a = pool.submit(() -> inTransaction(() -> {
        lockSeat(sorted.get(0));
        aHoldsFirst.countDown();
        await(bIsWaiting);
        lockSeat(sorted.get(1));
      }));
      await(aHoldsFirst);
      // B asks for the same first seat, so it queues behind A instead of taking seat 2.
      Future<?> b = pool.submit(() -> inTransaction(() -> sorted.forEach(this::lockSeat)));
      waitForLockWaiters(1);
      bIsWaiting.countDown();

      a.get(20, TimeUnit.SECONDS);
      b.get(20, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @Timeout(value = 180, unit = TimeUnit.SECONDS)
  void unorderedContention_countsDeadlocks() throws Exception {
    List<UUID> seats = createSeats(SEATS);

    Random random = new Random(SEED);
    List<List<UUID>> requests = new ArrayList<>();
    for (int i = 0; i < REQUESTS; i++) {
      List<UUID> pool = new ArrayList<>(seats);
      Collections.shuffle(pool, random);
      // Shuffled and never sorted: this is the ordering the service must not use.
      requests.add(List.copyOf(pool.subList(0, 1 + random.nextInt(MAX_SEATS_PER_REQUEST))));
    }

    int workers = 20;
    CountDownLatch ready = new CountDownLatch(workers);
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger successes = new AtomicInteger();
    AtomicInteger conflicts = new AtomicInteger();
    AtomicInteger deadlocks = new AtomicInteger();
    AtomicInteger claimedSeats = new AtomicInteger();
    Map<String, AtomicInteger> unexpected = new ConcurrentHashMap<>();

    ExecutorService pool = Executors.newFixedThreadPool(workers);
    try {
      List<Future<?>> futures = new ArrayList<>();
      for (List<UUID> request : requests) {
        futures.add(pool.submit(() -> {
          ready.countDown();
          start.await();
          try {
            inTransaction(() -> claimSeats(request));
            successes.incrementAndGet();
            claimedSeats.addAndGet(request.size());
          } catch (SeatAlreadyClaimed ex) {
            conflicts.incrementAndGet();
          } catch (Exception ex) {
            if (isDeadlock(ex)) {
              deadlocks.incrementAndGet();
            } else {
              unexpected.computeIfAbsent(ex.getClass().getName(), k -> new AtomicInteger())
                  .incrementAndGet();
            }
          }
          return null;
        }));
      }
      assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      for (Future<?> future : futures) {
        future.get(120, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }

    System.out.printf(
        "[SeatLockOrderingDeadlockIT] requests=%d seats=%d seed=%d workers=%d successes=%d"
            + " conflicts=%d deadlocks=%d unexpected=%s claimedSeats=%d%n",
        REQUESTS, SEATS, SEED, workers, successes.get(), conflicts.get(), deadlocks.get(),
        unexpected, claimedSeats.get());

    assertThat(unexpected).as("failures other than conflicts and deadlocks").isEmpty();
    assertThat(successes.get() + conflicts.get() + deadlocks.get()).isEqualTo(REQUESTS);
    assertThat(deadlocks.get()).as("deadlocks with the sorted ordering removed").isPositive();
    // Deadlock losers roll back, so the claimed seats still match the winners exactly.
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM seats WHERE status = 'HELD'", Integer.class))
        .isEqualTo(claimedSeats.get());
  }

  // ---- helpers ----

  private List<UUID> createSeats(int count) {
    Event event = eventRepository.save(
        new Event("Deadlock Concert", null, Instant.now().plusSeconds(86400), (UUID) null));
    List<Seat> seats = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      seats.add(new Seat(event, "FLOOR", String.valueOf(i / 10 + 1), String.valueOf(i % 10 + 1),
          new BigDecimal("40.00")));
    }
    return seatRepository.saveAll(seats).stream().map(Seat::getId).toList();
  }

  /** Locks the first seat, waits until the other transaction holds its first seat, then the rest. */
  private Void lockInOrder(List<UUID> order, CyclicBarrier bothHoldFirstLock) {
    inTransaction(() -> {
      lockSeat(order.get(0));
      try {
        bothHoldFirstLock.await(10, TimeUnit.SECONDS);
      } catch (Exception ex) {
        throw new IllegalStateException("barrier", ex);
      }
      order.subList(1, order.size()).forEach(this::lockSeat);
    });
    return null;
  }

  /** The same row lock {@code findAndLockByIdIn} takes, one seat at a time, in the given order. */
  private void lockSeat(UUID seatId) {
    jdbcTemplate.queryForObject("SELECT id FROM seats WHERE id = ? FOR UPDATE", UUID.class, seatId);
  }

  /** Locks every seat in request order, then claims them all, or none if any is taken. */
  private void claimSeats(List<UUID> seatIds) {
    seatIds.forEach(this::lockSeat);
    for (UUID seatId : seatIds) {
      String status = jdbcTemplate.queryForObject(
          "SELECT status FROM seats WHERE id = ?", String.class, seatId);
      if (!"AVAILABLE".equals(status)) {
        throw new SeatAlreadyClaimed();
      }
    }
    for (UUID seatId : seatIds) {
      jdbcTemplate.update("UPDATE seats SET status = 'HELD' WHERE id = ?", seatId);
    }
  }

  private void inTransaction(Runnable work) {
    new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
  }

  private void waitForLockWaiters(int expected) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      Integer waiters = jdbcTemplate.queryForObject(
          "SELECT COUNT(*) FROM pg_stat_activity"
              + " WHERE wait_event_type = 'Lock' AND datname = current_database()",
          Integer.class);
      if (waiters != null && waiters >= expected) {
        return;
      }
      Thread.sleep(20);
    }
    throw new IllegalStateException("expected " + expected + " lock waiter(s)");
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("latch timed out");
      }
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(ex);
    }
  }

  static boolean isDeadlock(Throwable ex) {
    for (Throwable t = ex; t != null; t = t.getCause()) {
      if (t instanceof SQLException sql && DEADLOCK_SQLSTATE.equals(sql.getSQLState())) {
        return true;
      }
    }
    return false;
  }

  /** A seat in the request was already claimed by an earlier winner; the transaction rolls back. */
  static final class SeatAlreadyClaimed extends RuntimeException {
  }
}
