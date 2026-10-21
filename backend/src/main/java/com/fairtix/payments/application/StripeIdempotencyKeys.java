package com.fairtix.payments.application;

import java.util.Collection;
import java.util.UUID;

/** Red-commit stub; real implementation follows. */
public final class StripeIdempotencyKeys {

  private StripeIdempotencyKeys() {}

  public static String refund(UUID refundId) {
    return null;
  }

  public static String onlineIntent(UUID userId, Collection<UUID> holdIds, long amountCents) {
    return null;
  }

  public static String boxOfficeIntent(UUID saleId) {
    return null;
  }

  public static String connectAccount(UUID orgId) {
    return null;
  }
}
