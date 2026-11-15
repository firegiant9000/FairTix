package com.fairtix.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fairtix.events.domain.Event;
import com.fairtix.events.infrastructure.EventRepository;
import com.fairtix.queue.application.QueueConflictException;
import com.fairtix.queue.application.QueueService;
import com.fairtix.queue.domain.QueueStatus;
import com.fairtix.support.PostgresIT;
import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
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
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code events.queue_capacity} bounds the live ({@code WAITING} plus
 * {@code ADMITTED}) queue entries of an event, including when different users
 * join at the same moment.
 *
 * <p>Red on {@code 8a5328b}: {@code joinQueue} counted the live entries and then
 * inserted, with nothing serialising the two steps, so two overlapping joins by
 * different users both passed a capacity of 1. Rejoining after leaving a queue
 * with no capacity set failed on {@code uq_queue_event_user}: the old entry's
 * delete was written after the new entry's insert.
 *
 * <p>The race is forced, not left to timing: a third connection holds a
 * {@code SHARE} lock on {@code queue_entries} (reads pass, inserts wait), each
 * join is started and observed waiting on a lock in {@code pg_stat_activity}, and
 * only then is the table released. Not {@code @Transactional}: every write
 * commits, and {@link PostgresIT} truncates the tables after each test.
 */
class QueueCapacityIT extends PostgresIT {

  @Autowired private UserRepository userRepository;
  @Autowired private EventRepository eventRepository;
  @Autowired private QueueService queueService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private DataSource dataSource;

  @Test
  @Timeout(60)
  void overlappingJoinsByDifferentUsers_stayWithinCapacity() throws Exception {
    Event event = queueEvent(1);
    User first = newUser("first@test.com");
    User second = newUser("second@test.com");

    List<Throwable> failures = raceUnderQueueTableLock(
        () -> queueService.joinQueue(event.getId(), first.getId()),
        () -> queueService.joinQueue(event.getId(), second.getId()));

    assertThat(liveEntries(event)).as("live entries in a queue of capacity 1").isEqualTo(1);
    assertThat(failures).hasSize(1);
    assertThat(failures.get(0))
        .isInstanceOf(QueueConflictException.class)
        .hasMessageContaining("capacity");
  }

  @Test
  void joinsUpToCapacity_thenRejects() {
    Event event = queueEvent(2);
    queueService.joinQueue(event.getId(), newUser("a@test.com").getId());
    queueService.joinQueue(event.getId(), newUser("b@test.com").getId());
    UUID third = newUser("c@test.com").getId();

    assertThatThrownBy(() -> queueService.joinQueue(event.getId(), third))
        .isInstanceOf(QueueConflictException.class)
        .hasMessageContaining("capacity");
    assertThat(liveEntries(event)).isEqualTo(2);
  }

  @Test
  void rejoinAfterLeaving_withNoCapacitySet() {
    Event event = queueEvent(null);
    User user = newUser("rejoin@test.com");
    queueService.joinQueue(event.getId(), user.getId());
    queueService.leaveQueue(event.getId(), user.getId());

    assertThat(queueService.joinQueue(event.getId(), user.getId()).getStatus())
        .isEqualTo(QueueStatus.WAITING);
    assertThat(liveEntries(event)).isEqualTo(1);
  }

  @Test
  void rejoinAfterLeaving_aQueueOfCapacityOne_takesTheFreedSlot() {
    Event event = queueEvent(1);
    User user = newUser("rejoin-capped@test.com");
    queueService.joinQueue(event.getId(), user.getId());
    queueService.leaveQueue(event.getId(), user.getId());

    assertThat(queueService.joinQueue(event.getId(), user.getId()).getStatus())
        .isEqualTo(QueueStatus.WAITING);
    assertThat(liveEntries(event)).isEqualTo(1);
  }

  /**
   * Joins to a capped queue wait for one another on the capacity lock, each
   * holding a pooled connection. With twice as many joiners as connections, a
   * join that needed a second connection while holding the lock (a
   * {@code REQUIRES_NEW} audit write) would stall, and the joins waiting behind
   * it would give up with {@code LockBusyException}.
   */
  @Test
  @Timeout(25)
  void burstOfJoinsLargerThanThePool_allComplete() throws Exception {
    Event event = queueEvent(1000);
    int joiners = 2 * dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize();
    List<UUID> users = new ArrayList<>();
    for (int i = 0; i < joiners; i++) {
      users.add(newUser("burst" + i + "@test.com").getId());
    }

    ExecutorService pool = Executors.newFixedThreadPool(joiners);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<Object>> futures = new ArrayList<>();
      for (UUID user : users) {
        futures.add(pool.submit(() -> {
          start.await();
          return queueService.joinQueue(event.getId(), user);
        }));
      }
      start.countDown();
      for (Future<Object> future : futures) {
        future.get(20, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(liveEntries(event)).isEqualTo(joiners);
  }

  /**
   * Blocks inserts into {@code queue_entries} from a third connection, starts
   * {@code first} and waits until it is blocked on a lock, then starts
   * {@code second} and waits until it is blocked too, then releases the table.
   * Returns what they threw.
   */
  private List<Throwable> raceUnderQueueTableLock(Callable<Object> first, Callable<Object> second)
      throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    List<Throwable> failures = new ArrayList<>();
    try (Connection lock = dataSource.getConnection()) {
      lock.setAutoCommit(false);
      try (Statement st = lock.createStatement()) {
        st.execute("LOCK TABLE queue_entries IN SHARE MODE");
      }
      List<Future<Object>> futures = new ArrayList<>();
      futures.add(pool.submit(first));
      boolean firstBlocked = waitForLockWaiters(1, Duration.ofSeconds(10));
      futures.add(pool.submit(second));
      boolean bothBlocked = waitForLockWaiters(2, Duration.ofSeconds(10));
      lock.commit();
      assertThat(firstBlocked).as("the first join waited on a lock").isTrue();
      assertThat(bothBlocked).as("both joins waited on a lock").isTrue();
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

  private Event queueEvent(Integer capacity) {
    Event event = new Event("Queued Concert", null, Instant.now().plusSeconds(86400), (UUID) null);
    event.updateQueueSettings(true, capacity);
    return eventRepository.save(event);
  }

  private int liveEntries(Event event) {
    Integer count = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM queue_entries WHERE event_id = ? AND status IN ('WAITING', 'ADMITTED')",
        Integer.class, event.getId());
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
