package com.fairtix.payments.domain;

public enum IntentInvalidationReason {
  CANCELED,
  AMOUNT_CHANGED,
  ROUTING_CHANGED,
  /** Checkout created the order: the holds are consumed and the row is never looked up again. */
  COMPLETED,
  /**
   * Set by hand only, from the runbook: an old paid intent without an order was refunded, or the
   * owner decided not to refund it. Stops the retention job checking it every night.
   */
  REVIEWED
}
