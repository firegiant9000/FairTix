package com.fairtix.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fairtix.support.PostgresIT;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link TransactionLock} against PostgreSQL: a second transaction waits for the
 * first, the wait is bounded, and the bound does not leak into the rest of the
 * transaction. The other holder is a plain JDBC connection taking the same
 * advisory lock, as a second application instance would.
 */
class TransactionLockIT extends PostgresIT {

  @Autowired private TransactionLock transactionLock;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbcTemplate;
  @PersistenceContext private EntityManager entityManager;

  @Test
  @Timeout(30)
  void secondTransaction_waitsForTheFirst_thenTakesTheLock() throws Exception {
    String key = "it:" + UUID.randomUUID();
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try (Connection holder = holdFromAnotherConnection(key)) {
      Future<?> waiter = pool.submit(() -> inTransaction(() -> transactionLock.acquire(key)));

      assertThat(waitForLockWaiters(1, Duration.ofSeconds(2)))
          .as("the second transaction waited on the lock").isTrue();
      assertThat(waiter.isDone()).isFalse();

      holder.commit();
      waiter.get(10, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @Timeout(30)
  void waitThatRunsOut_failsWithLockBusy() throws Exception {
    String key = "it:" + UUID.randomUUID();
    try (Connection holder = holdFromAnotherConnection(key)) {
      long started = System.nanoTime();

      assertThatThrownBy(() -> inTransaction(() -> transactionLock.acquire(key)))
          .isInstanceOf(LockBusyException.class);

      assertThat(Duration.ofNanos(System.nanoTime() - started))
          .as("gave up after the configured wait, well before Hikari's 30 s")
          .isLessThan(Duration.ofSeconds(15));
      holder.rollback();
    }
    // The failed wait left nothing behind: the lock is free again.
    inTransaction(() -> transactionLock.acquire(key));
  }

  @Test
  void lockTimeout_appliesToTheLockWaitOnly() {
    inTransaction(() -> {
      String before = currentLockTimeout();
      transactionLock.acquire("it:" + UUID.randomUUID());
      assertThat(currentLockTimeout()).isEqualTo(before);
    });
  }

  private void inTransaction(Runnable work) {
    new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
  }

  private String currentLockTimeout() {
    return (String) entityManager
        .createNativeQuery("SELECT current_setting('lock_timeout')")
        .getSingleResult();
  }

  /** Takes the same lock on a connection of its own; the caller commits or closes it. */
  private Connection holdFromAnotherConnection(String key) throws Exception {
    Connection connection = dataSource.getConnection();
    connection.setAutoCommit(false);
    try (PreparedStatement st = connection.prepareStatement(
        "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
      st.setString(1, key);
      st.execute();
    }
    return connection;
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
