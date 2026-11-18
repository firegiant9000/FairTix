package com.fairtix.payments.application;

import java.util.UUID;

/**
 * The PaymentIntent submitted at checkout was not created for the calling user:
 * its {@code fairtix_user_id} metadata names someone else, or it has none and is
 * older than the grace window for intents created before that metadata existed.
 */
public class PaymentIntentNotOwnedException extends RuntimeException {

  private final String paymentIntentId;
  private final UUID userId;
  private final String reason;

  /** @param reason {@code "other_user"} or {@code "unbound"}; for logs only. */
  public PaymentIntentNotOwnedException(String paymentIntentId, UUID userId, String reason) {
    super("PaymentIntent " + paymentIntentId + " is not usable by user " + userId + ": " + reason);
    this.paymentIntentId = paymentIntentId;
    this.userId = userId;
    this.reason = reason;
  }

  public String getPaymentIntentId() {
    return paymentIntentId;
  }

  public UUID getUserId() {
    return userId;
  }

  public String getReason() {
    return reason;
  }
}
