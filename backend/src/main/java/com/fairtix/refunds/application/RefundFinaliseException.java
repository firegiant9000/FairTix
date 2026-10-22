package com.fairtix.refunds.application;

/**
 * The refund's DB side failed to commit after the payment-provider call. The
 * failure is audited with the Stripe refund id, and the admin retry endpoint
 * replays the same idempotency key.
 */
public class RefundFinaliseException extends RuntimeException {

  public RefundFinaliseException(String message, Throwable cause) {
    super(message, cause);
  }
}
