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
    acquire(key, waitMillis);
  }

  /**
   * As {@link #acquire(String)}, with a wait limit of the caller's choosing: a
   * path where many requests queue on one key keeps the wait short, so the
   * waiters give their pooled connections back quickly.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void acquire(String key, long waitMillis) {
    acquire(key, waitMillis, "pg_advisory_xact_lock");
  }

  /**
   * As {@link #acquire(String)}, in shared mode: any number of transactions hold it together,
   * and {@link #acquire(String)} on the same key waits for all of them. PostgreSQL queues
   * advisory locks first come, first served, so a waiting exclusive request also holds back
   * shared requests that arrive after it, and a stream of sharers can't starve it.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void acquireShared(String key) {
    acquireShared(key, waitMillis);
  }

  /** As {@link #acquireShared(String)}, with a wait limit of the caller's choosing. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void acquireShared(String key, long waitMillis) {
    acquire(key, waitMillis, "pg_advisory_xact_lock_shared");
  }

  private void acquire(String key, long waitMillis, String function) {
    // lock_timeout is put back afterwards so that the bound applies to this wait
    // only, not to the row locks the rest of the transaction takes.
    String previousTimeout = (String) entityManager
        .createNativeQuery("SELECT current_setting('lock_timeout')")
        .getSingleResult();
    setLockTimeout(waitMillis + "ms");
    try {
      // The cast is there because the function returns void, which has no JDBC mapping.
      entityManager
          .createNativeQuery("SELECT CAST(" + function + "(hashtextextended(:key, 0)) AS text)")
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

  /**
   * Takes the lock on {@code key} only if it is free, without waiting. For work
   * that should be skipped, not queued, when another transaction is already
   * doing it.
   *
   * @return whether this transaction now holds the lock
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean tryAcquire(String key) {
    return (Boolean) entityManager
        .createNativeQuery("SELECT pg_try_advisory_xact_lock(hashtextextended(:key, 0))")
        .setParameter("key", key)
        .getSingleResult();
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
