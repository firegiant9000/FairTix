package com.fairtix.payments.application;

/**
 * The PaymentIntent succeeded but its charge has since been refunded (fully or
 * partly) or disputed, so it can no longer pay for an order. Stripe leaves
 * {@code status} and {@code amount_received} unchanged after either, so this is
 * detected from the latest charge's {@code amount_refunded} and {@code disputed}.
 */
public class PaymentRefundedException extends RuntimeException {

  private final String paymentIntentId;
  private final String reason;

  /** @param reason {@code "refunded"} or {@code "disputed"}; for logs only. */
  public PaymentRefundedException(String paymentIntentId, String reason) {
    super("PaymentIntent " + paymentIntentId + " has been " + reason);
    this.paymentIntentId = paymentIntentId;
    this.reason = reason;
  }

  public String getPaymentIntentId() {
    return paymentIntentId;
  }

  public String getReason() {
    return reason;
  }
}
