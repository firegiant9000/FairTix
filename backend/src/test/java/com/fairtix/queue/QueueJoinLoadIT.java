package com.fairtix.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.fairtix.common.LockBusyException;
import com.fairtix.events.domain.Event;
import com.fairtix.events.infrastructure.EventRepository;
import com.fairtix.queue.application.QueueService;
import com.fairtix.support.PostgresIT;
import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Opt-in load measurement for {@code QueueService.joinQueue} (excluded from the default
 * failsafe run; run with {@code -Dit.test=QueueJoinLoadIT}). Prints {@code LOAD|} lines.
 * Assertions guard correctness only, never performance.
 */
class QueueJoinLoadIT extends PostgresIT {

  private static final long DEFAULT_WAIT_MS = 500L;

  @Autowired private UserRepository userRepository;
  @Autowired private EventRepository eventRepository;
  @Autowired private QueueService queueService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private DataSource dataSource;

  private record Result(String label, int burst, long waitMs, long wallMs, int ok, int busy,
      Map<String, Integer> other, long p50, long p95, long max) {}

  @Test
  @Timeout(300)
  void measureJoinBursts() throws Exception {
    int poolSize = dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize();
    List<UUID> users = new ArrayList<>();
    for (int i = 0; i < 500; i++) {
      users.add(newUser("load" + i + "@test.com"));
    }

    List<Result> results = new ArrayList<>();
    results.add(run("capped", users.subList(0, 50), 100_000, DEFAULT_WAIT_MS));
    results.add(run("capped", users.subList(0, 200), 100_000, DEFAULT_WAIT_MS));
    results.add(run("capped", users.subList(0, 500), 100_000, DEFAULT_WAIT_MS));
    results.add(run("capped", users.subList(0, 200), 100_000, 3000L));
    results.add(run("uncapped", users.subList(0, 200), null, DEFAULT_WAIT_MS));

    System.out.println("LOAD|hikariMaxPool=" + poolSize);
    System.out.println("LOAD|label    |burst|waitMs|wallMs|ok |busy|other|ok/sec|p50ms|p95ms|maxms");
    for (Result r : results) {
      double perSec = r.wallMs() == 0 ? 0 : r.ok() * 1000.0 / r.wallMs();
      System.out.printf("LOAD|%-9s|%5d|%6d|%6d|%4d|%4d|%s|%6.1f|%5d|%5d|%5d%n",
          r.label(), r.burst(), r.waitMs(), r.wallMs(), r.ok(), r.busy(), r.other(), perSec,
          r.p50(), r.p95(), r.max());
    }
  }

  private Result run(String label, List<UUID> users, Integer capacity, long waitMs)
      throws Exception {
    Event event = newEvent(capacity);
    ReflectionTestUtils.setField(queueService, "joinLockWaitMs", waitMs);
    int burst = users.size();
    ExecutorService pool = Executors.newFixedThreadPool(burst);
    CountDownLatch ready = new CountDownLatch(burst);
    CountDownLatch start = new CountDownLatch(1);
    List<Long> okLatencies = new ArrayList<>();
    Map<String, Integer> other = new TreeMap<>();
    int busy = 0;
    long wallMs;
    try {
      List<Future<Long>> futures = new ArrayList<>();
      for (UUID user : users) {
        futures.add(pool.submit(() -> {
          ready.countDown();
          start.await();
          long t0 = System.nanoTime();
          queueService.joinQueue(event.getId(), user);
          return (System.nanoTime() - t0) / 1_000_000;
        }));
      }
      ready.await();
      long wallStart = System.nanoTime();
      start.countDown();
      for (Future<Long> future : futures) {
        try {
          okLatencies.add(future.get(200, TimeUnit.SECONDS));
        } catch (java.util.concurrent.ExecutionException ex) {
          Throwable cause = ex.getCause();
          if (cause instanceof LockBusyException) {
            busy++;
          } else {
            other.merge(cause.getClass().getName(), 1, Integer::sum);
          }
        }
      }
      wallMs = (System.nanoTime() - wallStart) / 1_000_000;
    } finally {
      pool.shutdownNow();
      ReflectionTestUtils.setField(queueService, "joinLockWaitMs", DEFAULT_WAIT_MS);
    }

    Collections.sort(okLatencies);
    int ok = okLatencies.size();
    long p50 = percentile(okLatencies, 0.50);
    long p95 = percentile(okLatencies, 0.95);
    long max = ok == 0 ? 0 : okLatencies.get(ok - 1);

    assertThat(other).as("failures other than LockBusyException").isEmpty();
    assertThat(liveEntries(event)).as("live entries == successes").isEqualTo(ok);
    return new Result(label, burst, waitMs, wallMs, ok, busy, other, p50, p95, max);
  }

  private static long percentile(List<Long> sorted, double p) {
    if (sorted.isEmpty()) {
      return 0;
    }
    int idx = (int) Math.ceil(p * sorted.size()) - 1;
    return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
  }

  private Event newEvent(Integer capacity) {
    Event event = new Event("Load Concert", null, Instant.now().plusSeconds(86400), (UUID) null);
    event.updateQueueSettings(true, capacity);
    return eventRepository.save(event);
  }

  private int liveEntries(Event event) {
    Integer count = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM queue_entries WHERE event_id = ? AND status IN ('WAITING', 'ADMITTED')",
        Integer.class, event.getId());
    return count == null ? 0 : count;
  }

  private UUID newUser(String email) {
    User user = new User();
    user.setEmail(email);
    user.setPassword("$2a$10$dummyhashfortest");
    user.setEmailVerified(true);
    return userRepository.save(user).getId();
  }
}
