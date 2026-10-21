package com.fairtix.payments.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Deterministic Stripe idempotency keys. The same logical operation always
 * yields the same key, so a retry after a failed DB commit reaches Stripe as a
 * replay of the original request instead of a second charge or refund.
 *
 * <p>Stripe keeps keys for at least 24 hours and rejects a reused key whose
 * parameters differ, so keyed requests must not carry per-request values
 * (such as the MDC {@code requestId}) in their parameters.
 */
public final class StripeIdempotencyKeys {

  private StripeIdempotencyKeys() {}

  /** One key per refund request; its amount is fixed when the request is created. */
  public static String refund(UUID refundId) {
    return "fairtix-refund-" + refundId;
  }

  /**
   * The same user paying for the same holds at the same amount gets the same
   * intent back; a different hold set or amount is a different payment.
   */
  public static String onlineIntent(UUID userId, Collection<UUID> holdIds, long amountCents) {
    String holds = holdIds.stream().map(UUID::toString).sorted().distinct()
        .collect(Collectors.joining(","));
    return "fairtix-pi-" + sha256Hex(userId + "|" + holds + "|" + amountCents);
  }

  /** One key per pending box-office sale row. */
  public static String boxOfficeIntent(UUID saleId) {
    return "fairtix-bo-" + saleId;
  }

  /** One connected account per organization. */
  public static String connectAccount(UUID orgId) {
    return "fairtix-acct-" + orgId;
  }

  private static String sha256Hex(String input) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(input.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
