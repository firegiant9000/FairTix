package com.fairtix.payments.application;

/**
 * The PaymentIntent succeeded but its charge has since been refunded (fully or
 * partly), so it can no longer pay for an order. Stripe leaves {@code status}
 * and {@code amount_received} unchanged after a refund, so this is detected from
 * the latest charge's {@code amount_refunded}.
 */
public class PaymentRefundedException extends RuntimeException {

  private final String paymentIntentId;

  public PaymentRefundedException(String paymentIntentId) {
    super("PaymentIntent " + paymentIntentId + " has been refunded");
    this.paymentIntentId = paymentIntentId;
  }

  public String getPaymentIntentId() {
    return paymentIntentId;
  }
}
