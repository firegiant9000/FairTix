package com.fairtix.refunds.application;

/**
 * Stripe refused or failed the refund call. The refund request stays
 * {@code APPROVED} and can be re-driven with the admin retry endpoint.
 */
public class RefundProviderException extends RuntimeException {

  public RefundProviderException(String message, Throwable cause) {
    super(message, cause);
  }
}
