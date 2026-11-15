package com.fairtix.common;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.SQLException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * A named lock that the current database transaction holds until it commits or
 * rolls back: a PostgreSQL transaction-level advisory lock. It serialises a
 * count-then-write across application instances, with nothing to release and no
 * row to lock.
 *
 * <p>Rules for callers:
 * <ul>
 *   <li>Take it before any row lock and before any write. A transaction that
 *       always takes it first cannot be part of a deadlock cycle through it.</li>
 *   <li>Count after taking it. Under the default READ COMMITTED isolation each
 *       statement sees what the previous holder committed.</li>
 *   <li>A waiter keeps its pooled connection, so the wait is bounded
 *       ({@code fairtix.transaction-lock.wait-ms}). When it runs out the
 *       transaction fails with {@link LockBusyException} and the caller retries;
 *       it never runs without the lock.</li>
 * </ul>
 *
 * <p>The key is hashed to 64 bits. A collision only makes two unrelated
 * transactions wait for each other.
 */
@Component
public class TransactionLock {

  /** PostgreSQL {@code lock_not_available}: what a {@code lock_timeout} raises. */
  private static final String LOCK_NOT_AVAILABLE = "55P03";

  @PersistenceContext
  private EntityManager entityManager;

  private final long waitMillis;

  public TransactionLock(@Value("${fairtix.transaction-lock.wait-ms:3000}") long waitMillis) {
    this.waitMillis = waitMillis;
  }

  /**
   * Takes the lock on {@code key}, waiting up to the configured time for another
   * transaction to finish with it.
   *
   * @throws LockBusyException if the wait ran out; the transaction is then
   *     unusable and rolls back
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void acquire(String key) {
    // lock_timeout is put back afterwards so that the bound applies to this wait
    // only, not to the row locks the rest of the transaction takes.
    String previousTimeout = (String) entityManager
        .createNativeQuery("SELECT current_setting('lock_timeout')")
        .getSingleResult();
    setLockTimeout(waitMillis + "ms");
    try {
      // The cast is there because the function returns void, which has no JDBC mapping.
      entityManager
          .createNativeQuery("SELECT CAST(pg_advisory_xact_lock(hashtextextended(:key, 0)) AS text)")
          .setParameter("key", key)
          .getSingleResult();
    } catch (RuntimeException ex) {
      if (isLockTimeout(ex)) {
        throw new LockBusyException(
            "Another request is still being processed; try again in a moment", ex);
      }
      throw ex;
    }
    setLockTimeout(previousTimeout);
  }

  private void setLockTimeout(String value) {
    entityManager
        .createNativeQuery("SELECT set_config('lock_timeout', :value, true)")
        .setParameter("value", value)
        .getSingleResult();
  }

  private static boolean isLockTimeout(Throwable ex) {
    for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sql && LOCK_NOT_AVAILABLE.equals(sql.getSQLState())) {
        return true;
      }
    }
    return false;
  }
}
