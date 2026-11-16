package com.fairtix.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.fairtix.common.LockBusyException;
import com.fairtix.events.domain.Event;
import com.fairtix.events.infrastructure.EventRepository;
import com.fairtix.inventory.domain.Seat;
import com.fairtix.inventory.infrastructure.SeatRepository;
import com.fairtix.queue.application.QueueConflictException;
import com.fairtix.queue.application.QueueService;
import com.fairtix.queue.domain.QueueEntry;
import com.fairtix.support.PostgresIT;
import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Queue behaviour around the join and admission paths that the capacity fix left
 * open: admission by two callers at once, the position counter after Redis loses
 * it, two joins by one user at the same moment, and a burst of joins much larger
 * than the connection pool.
 *
 * <p>Not {@code @Transactional}: every write commits, and {@link PostgresIT}
 * truncates the tables and flushes Redis after each test.
 */
class QueueConsistencyIT extends PostgresIT {

  @Autowired private UserRepository userRepository;
  @Autowired private EventRepository eventRepository;
  @Autowired private SeatRepository seatRepository;
  @Autowired private QueueService queueService;
  @Autowired private RedissonClient redissonClient;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private DataSource dataSource;

  /**
   * Admission reads the seat and admitted counts and then writes. While another
   * transaction is admitting for the event (here: a plain connection holding the
   * same lock, which is what a second application instance looks like to the
   * database), a second call admits nobody; once the first is done, it admits.
   */
  @Test
  void admission_isSkippedWhileAnotherTransactionAdmitsTheSameEvent() throws Exception {
    Event event = queueEvent(null, 1);
    UUID user = newUser("waiting@test.com");
    queueService.joinQueue(event.getId(), user);

    try (Connection other = dataSource.getConnection()) {
      other.setAutoCommit(false);
      try (PreparedStatement st = other.prepareStatement(
          "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
        st.setString(1, "queue-admit:" + event.getId());
        st.execute();
      }

      assertThat(queueService.admitNextBatch(event.getId())).isEmpty();
      assertThat(statusOf(event, user)).isEqualTo("WAITING");

      other.commit();
    }

    assertThat(queueService.admitNextBatch(event.getId())).hasSize(1);
    assertThat(statusOf(event, user)).isEqualTo("ADMITTED");
  }

  /**
   * The position counter lives in Redis only. After Redis loses it, the next
   * joiner must still be placed behind everyone already in the queue; admission
   * is by position, so a restart at 1 would admit the newcomer first.
   */
  @Test
  void positionCounterLostFromRedis_newJoinerStillGoesToTheBack() {
    Event event = queueEvent(null, 0);
    int highest = 0;
    for (int i = 0; i < 3; i++) {
      QueueEntry entry = queueService.joinQueue(event.getId(), newUser("early" + i + "@test.com"));
      highest = Math.max(highest, entry.getPosition());
    }

    redissonClient.getAtomicLong("queue:position:" + event.getId()).delete();

    QueueEntry late = queueService.joinQueue(event.getId(), newUser("late@test.com"));
    assertThat(late.getPosition()).isGreaterThan(highest);
    QueueEntry later = queueService.joinQueue(event.getId(), newUser("later@test.com"));
    assertThat(later.getPosition()).isGreaterThan(late.getPosition());
  }

  /**
   * Two joins by one user to a queue with no capacity (so no lock) both get past
   * the "already in the queue" lookup. One wins; the other is told it is already
   * in the queue, not handed a generic constraint error. The overlap is forced: a
   * third connection blocks inserts into {@code queue_entries} until both joins
   * are waiting.
   */
  @Test
  @Timeout(60)
  void twoJoinsByOneUserAtOnce_secondIsToldItIsAlreadyInTheQueue() throws Exception {
    Event event = queueEvent(null, 0);
    UUID user = newUser("twice@test.com");

    ExecutorService pool = Executors.newFixedThreadPool(2);
    List<Throwable> failures = new ArrayList<>();
    try (Connection lock = dataSource.getConnection()) {
      lock.setAutoCommit(false);
      try (Statement st = lock.createStatement()) {
        st.execute("LOCK TABLE queue_entries IN SHARE MODE");
      }
      List<Future<QueueEntry>> futures = new ArrayList<>();
      futures.add(pool.submit(() -> queueService.joinQueue(event.getId(), user)));
      futures.add(pool.submit(() -> queueService.joinQueue(event.getId(), user)));
      boolean bothBlocked = waitForLockWaiters(2, Duration.ofSeconds(10));
      lock.commit();
      assertThat(bothBlocked).as("both joins waited on a lock").isTrue();
      for (Future<QueueEntry> future : futures) {
        try {
          future.get(30, TimeUnit.SECONDS);
        } catch (ExecutionException ex) {
          failures.add(ex.getCause());
        }
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(liveEntries(event)).isEqualTo(1);
    assertThat(failures).hasSize(1);
    assertThat(failures.get(0))
        .isInstanceOf(QueueConflictException.class)
        .hasMessageContaining("already in the queue");
  }

  /**
   * A burst of joins to one capped event, ten times the size of the connection
   * pool. Joins run one at a time on the capacity lock, each waiter holding a
   * pooled connection for at most the short join wait. Every join either gets in
   * or is refused with a retryable "busy" answer; none hangs or fails any other
   * way, and the entries on record are exactly the joins that succeeded.
   */
  @Test
  @Timeout(60)
  void burstTenTimesThePool_everyJoinEitherGetsInOrIsToldToRetry() throws Exception {
    Event event = queueEvent(10_000, 0);
    int joiners = 10 * dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize();
    List<UUID> users = new ArrayList<>();
    for (int i = 0; i < joiners; i++) {
      users.add(newUser("burst" + i + "@test.com"));
    }

    ExecutorService pool = Executors.newFixedThreadPool(joiners);
    CountDownLatch start = new CountDownLatch(1);
    int joined = 0;
    List<Throwable> failures = new ArrayList<>();
    try {
      List<Future<QueueEntry>> futures = new ArrayList<>();
      for (UUID user : users) {
        futures.add(pool.submit(() -> {
          start.await();
          return queueService.joinQueue(event.getId(), user);
        }));
      }
      start.countDown();
      for (Future<QueueEntry> future : futures) {
        try {
          future.get(45, TimeUnit.SECONDS);
          joined++;
        } catch (ExecutionException ex) {
          failures.add(ex.getCause());
        }
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(failures).allSatisfy(
        failure -> assertThat(failure).isInstanceOf(LockBusyException.class));
    assertThat(joined).as("joins that got in").isPositive();
    assertThat(liveEntries(event)).isEqualTo(joined);
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

  private Event queueEvent(Integer capacity, int seats) {
    Event event = new Event("Queued Concert", null, Instant.now().plusSeconds(86400), (UUID) null);
    event.updateQueueSettings(true, capacity);
    event = eventRepository.save(event);
    for (int i = 0; i < seats; i++) {
      seatRepository.save(new Seat(event, "A", "1", String.valueOf(i + 1), new BigDecimal("40.00")));
    }
    return event;
  }

  private String statusOf(Event event, UUID userId) {
    return jdbcTemplate.queryForObject(
        "SELECT status FROM queue_entries WHERE event_id = ? AND user_id = ?",
        String.class, event.getId(), userId);
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
