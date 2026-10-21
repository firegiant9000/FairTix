package com.fairtix.payments.application;

/**
 * Stripe rejected a retry because the same idempotency key arrived with
 * different parameters (for example a fee change between attempts). Nothing
 * was charged or refunded twice; the caller must start a fresh operation.
 */
public class PaymentRetryConflictException extends RuntimeException {

  public PaymentRetryConflictException(String message, Throwable cause) {
    super(message, cause);
  }
}
