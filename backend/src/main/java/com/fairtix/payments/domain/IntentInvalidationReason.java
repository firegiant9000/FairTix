package com.fairtix.payments.domain;

public enum IntentInvalidationReason {
  CANCELED,
  AMOUNT_CHANGED,
  ROUTING_CHANGED,
  /** Checkout created the order: the holds are consumed and the row is never looked up again. */
  COMPLETED
}
