package com.fairtix.payments.application;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic Stripe idempotency keys (exposure gate (b), RM:344). The same
 * logical operation must always produce the same key, so a retry after a
 * commit failure reaches Stripe as a replay rather than a second charge or
 * refund.
 *
 * <p>Red on commit {@code 1178a4b}: no Stripe create carried a key.
 */
class StripeIdempotencyKeysTest {

  private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID HOLD_A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
  private static final UUID HOLD_B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

  @Test
  void refund_isStable() {
    UUID refundId = UUID.randomUUID();
    assertThat(StripeIdempotencyKeys.refund(refundId))
        .isEqualTo("fairtix-refund-" + refundId)
        .isEqualTo(StripeIdempotencyKeys.refund(refundId));
  }

  @Test
  void onlineIntent_ignoresHoldOrder() {
    assertThat(StripeIdempotencyKeys.onlineIntent(USER, List.of(HOLD_A, HOLD_B), 5000L))
        .isEqualTo(StripeIdempotencyKeys.onlineIntent(USER, List.of(HOLD_B, HOLD_A), 5000L));
  }

  @Test
  void onlineIntent_differsByAmount() {
    assertThat(StripeIdempotencyKeys.onlineIntent(USER, List.of(HOLD_A), 5000L))
        .isNotEqualTo(StripeIdempotencyKeys.onlineIntent(USER, List.of(HOLD_A), 5001L));
  }

  @Test
  void onlineIntent_differsByUser() {
    assertThat(StripeIdempotencyKeys.onlineIntent(USER, List.of(HOLD_A), 5000L))
        .isNotEqualTo(StripeIdempotencyKeys.onlineIntent(UUID.randomUUID(), List.of(HOLD_A), 5000L));
  }

  @Test
  void onlineIntent_differsByHoldSet() {
    assertThat(StripeIdempotencyKeys.onlineIntent(USER, List.of(HOLD_A), 5000L))
        .isNotEqualTo(StripeIdempotencyKeys.onlineIntent(USER, List.of(HOLD_A, HOLD_B), 5000L));
  }

  @Test
  void allKeys_haveFairtixPrefixAndFitStripeLimit() {
    UUID id = UUID.randomUUID();
    List<String> keys = List.of(
        StripeIdempotencyKeys.refund(id),
        StripeIdempotencyKeys.onlineIntent(USER, List.of(HOLD_A, HOLD_B), 5000L),
        StripeIdempotencyKeys.boxOfficeIntent(id),
        StripeIdempotencyKeys.connectAccount(id));
    assertThat(keys).allSatisfy(k -> {
      assertThat(k).startsWith("fairtix-");
      assertThat(k.length()).isLessThanOrEqualTo(255);
    });
    assertThat(StripeIdempotencyKeys.boxOfficeIntent(id)).isEqualTo("fairtix-bo-" + id);
    assertThat(StripeIdempotencyKeys.connectAccount(id)).isEqualTo("fairtix-acct-" + id);
  }
}
