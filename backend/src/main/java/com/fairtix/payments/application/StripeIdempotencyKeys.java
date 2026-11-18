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
   * The same user paying for the same holds at the same amount, routed the same
   * way, gets the same intent back. A different hold set, amount, or Connect
   * routing (account, fee, descriptor) is a different payment, so it gets a new
   * key instead of Stripe's "same key, different parameters" error.
   *
   * <p>The prefix is versioned: {@code fairtix-pi2-} since intents carry the
   * caller in their metadata, so a request made after that change never reuses
   * a key first sent without it.
   */
  public static String onlineIntent(UUID userId, Collection<UUID> holdIds, long amountCents,
      StripePaymentService.ConnectContext connect) {
    return onlineIntent(userId, holdIds, amountCents, connect, null);
  }

  /**
   * Key for a replacement intent: the same payment as {@link #onlineIntent(UUID,
   * Collection, long, StripePaymentService.ConnectContext)} but after the intent
   * {@code replacesIntentId} was abandoned. A null {@code replacesIntentId}
   * returns the original key unchanged.
   */
  public static String onlineIntent(UUID userId, Collection<UUID> holdIds, long amountCents,
      StripePaymentService.ConnectContext connect, String replacesIntentId) {
    String base = userId + "|" + holdsString(holdIds) + "|" + amountCents + "|" + routingString(connect);
    if (replacesIntentId == null) {
      return "fairtix-pi2-" + sha256Hex(base);
    }
    return "fairtix-pi2-" + sha256Hex(base + "|replaces:" + replacesIntentId);
  }

  /** 64-char sha256 hex of the sorted, distinct hold ids. */
  public static String holdSetHash(Collection<UUID> holdIds) {
    return sha256Hex(holdsString(holdIds));
  }

  /** 64-char sha256 hex of the Connect routing (or {@code "direct"} when null). */
  public static String routingHash(StripePaymentService.ConnectContext connect) {
    return sha256Hex(routingString(connect));
  }

  private static String holdsString(Collection<UUID> holdIds) {
    return holdIds.stream().map(UUID::toString).sorted().distinct()
        .collect(Collectors.joining(","));
  }

  private static String routingString(StripePaymentService.ConnectContext connect) {
    return connect == null ? "direct"
        : connect.organizationId() + "|" + connect.connectedAccountId() + "|"
            + connect.applicationFeeAmountCents() + "|" + connect.statementDescriptorSuffix();
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
