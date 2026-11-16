package com.fairtix.common;

/**
 * A {@link TransactionLock} was still held by another transaction when the wait
 * for it ran out. Nothing was written; the request can be retried.
 */
public class LockBusyException extends RuntimeException {

  public LockBusyException(String message, Throwable cause) {
    super(message, cause);
  }
}
