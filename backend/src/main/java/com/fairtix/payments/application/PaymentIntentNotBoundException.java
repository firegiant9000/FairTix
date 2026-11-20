package com.fairtix.payments.application;

/**
 * Checkout's intent was not created for this user, these holds and this payout routing (or has
 * no stored row). Every retry with the same intent fails the same way, so it is answered with its
 * own code and the client shows a final screen. The message names no other order or user.
 */
public class PaymentIntentNotBoundException extends RuntimeException {

  private final String paymentIntentId;

  public PaymentIntentNotBoundException(String paymentIntentId) {
    super("This payment was not made for these seats. Contact support for a refund.");
    this.paymentIntentId = paymentIntentId;
  }

  public String getPaymentIntentId() {
    return paymentIntentId;
  }
}
