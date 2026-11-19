package com.fairtix.payments.application;

import com.fairtix.audit.application.AuditService;
import com.fairtix.payments.infrastructure.PaymentRecordRepository;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.CardException;
import com.stripe.exception.IdempotencyException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.model.Charge;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.model.RefundCollection;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCancelParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PaymentIntentRetrieveParams;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.RefundListParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.slf4j.MDC;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class StripePaymentServiceTest {

  @Mock private PaymentRecordRepository paymentRecordRepository;
  @Mock private AuditService auditService;

  private StripePaymentService service;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    service = new StripePaymentService(paymentRecordRepository, auditService);
  }

  /** Stubs the expanded retrieve that {@code verifyPaymentSucceeded} uses. */
  private static void stubVerifyRetrieve(MockedStatic<PaymentIntent> mocked, String id,
      PaymentIntent intent) {
    mocked.when(() -> PaymentIntent.retrieve(eq(id), any(PaymentIntentRetrieveParams.class),
        isNull())).thenReturn(intent);
  }

  private static PaymentIntent succeededIntent(long amountReceived, Long amountRefunded) {
    PaymentIntent intent = mock(PaymentIntent.class);
    when(intent.getStatus()).thenReturn("succeeded");
    when(intent.getAmountReceived()).thenReturn(amountReceived);
    if (amountRefunded != null) {
      Charge charge = mock(Charge.class);
      when(charge.getAmountRefunded()).thenReturn(amountRefunded);
      when(intent.getLatestChargeObject()).thenReturn(charge);
    }
    return intent;
  }

  @Test
  void verifyPaymentSucceeded_returnsTrueForSucceeded() throws Exception {
    try (MockedStatic<PaymentIntent> mocked = Mockito.mockStatic(PaymentIntent.class)) {
      stubVerifyRetrieve(mocked, "pi_test_123", succeededIntent(5000L, 0L));

      assertTrue(service.verifyPaymentSucceeded("pi_test_123", 5000L));
    }
  }

  @Test
  void verifyPaymentSucceeded_expandsLatestCharge() throws Exception {
    try (MockedStatic<PaymentIntent> mocked = Mockito.mockStatic(PaymentIntent.class)) {
      stubVerifyRetrieve(mocked, "pi_expand", succeededIntent(5000L, 0L));

      service.verifyPaymentSucceeded("pi_expand", 5000L);

      ArgumentCaptor<PaymentIntentRetrieveParams> params =
          ArgumentCaptor.forClass(PaymentIntentRetrieveParams.class);
      mocked.verify(() -> PaymentIntent.retrieve(eq("pi_expand"), params.capture(), isNull()));
      assertThat(params.getValue().getExpand()).containsExactly("latest_charge");
    }
  }

  @Test
  void verifyPaymentSucceeded_fullyRefunded_throwsPaymentRefunded() {
    try (MockedStatic<PaymentIntent> mocked = Mockito.mockStatic(PaymentIntent.class)) {
      stubVerifyRetrieve(mocked, "pi_refunded", succeededIntent(5000L, 5000L));

      assertThatThrownBy(() -> service.verifyPaymentSucceeded("pi_refunded", 5000L))
          .isInstanceOf(PaymentRefundedException.class);
    }
  }

  @Test
  void verifyPaymentSucceeded_partiallyRefunded_throwsPaymentRefunded() {
    try (MockedStatic<PaymentIntent> mocked = Mockito.mockStatic(PaymentIntent.class)) {
      stubVerifyRetrieve(mocked, "pi_partial", succeededIntent(5000L, 1L));

      assertThatThrownBy(() -> service.verifyPaymentSucceeded("pi_partial", 5000L))
          .isInstanceOf(PaymentRefundedException.class);
    }
  }

  @Test
  void verifyPaymentSucceeded_disputed_throwsPaymentRefunded() {
    try (MockedStatic<PaymentIntent> mocked = Mockito.mockStatic(PaymentIntent.class)) {
      PaymentIntent intent = succeededIntent(5000L, 0L);
      when(intent.getLatestChargeObject().getDisputed()).thenReturn(true);
      stubVerifyRetrieve(mocked, "pi_disputed", intent);

      assertThatThrownBy(() -> service.verifyPaymentSucceeded("pi_disputed", 5000L))
          .isInstanceOf(PaymentRefundedException.class)
          .hasMessageContaining("disputed");
    }
  }

  @Test
  void verifyPaymentSucceeded_withoutLatestCharge_returnsTrue() throws Exception {
    try (MockedStatic<PaymentIntent> mocked = Mockito.mockStatic(PaymentIntent.class)) {
      stubVerifyRetrieve(mocked, "pi_no_charge", succeededIntent(5000L, null));

      assertTrue(service.verifyPaymentSucceeded("pi_no_charge", 5000L));
    }
  }

  @Test
  void verifyPaymentSucceeded_returnsFalseForOther() throws Exception {
    try (MockedStatic<PaymentIntent> mocked = Mockito.mockStatic(PaymentIntent.class)) {
      PaymentIntent mockIntent = mock(PaymentIntent.class);
      when(mockIntent.getStatus()).thenReturn("requires_payment_method");
      stubVerifyRetrieve(mocked, "pi_failed", mockIntent);

      assertFalse(service.verifyPaymentSucceeded("pi_failed", 5000L));
    }
  }

  @Test
  void verifyPaymentSucceeded_returnsFalseWhenAmountDiffers() throws Exception {
    try (MockedStatic<PaymentIntent> mocked = Mockito.mockStatic(PaymentIntent.class)) {
      stubVerifyRetrieve(mocked, "pi_short", succeededIntent(4999L, 0L));

      assertFalse(service.verifyPaymentSucceeded("pi_short", 5000L));
    }
  }

  @Test
  void verifyPaymentSucceeded_cardDeclined_throwsPaymentDeclined() {
    try (MockedStatic<PaymentIntent> mocked = Mockito.mockStatic(PaymentIntent.class)) {
      mocked.when(() -> PaymentIntent.retrieve(eq("pi_declined"),
          any(PaymentIntentRetrieveParams.class), isNull())).thenThrow(cardDeclined());

      assertThatThrownBy(() -> service.verifyPaymentSucceeded("pi_declined", 5000L))
          .isInstanceOf(PaymentDeclinedException.class)
          .hasMessageContaining("Your card was declined");
    }
  }

  @Test
  void verifyPaymentSucceeded_otherStripeError_wrapsAsRuntimeException() {
    try (MockedStatic<PaymentIntent> mocked = Mockito.mockStatic(PaymentIntent.class)) {
      mocked.when(() -> PaymentIntent.retrieve(eq("pi_down"),
          any(PaymentIntentRetrieveParams.class), isNull()))
          .thenThrow(new ApiConnectionException("connection reset"));

      assertThatThrownBy(() -> service.verifyPaymentSucceeded("pi_down", 5000L))
          .isExactlyInstanceOf(RuntimeException.class)
          .hasMessageStartingWith("Failed to verify Stripe payment")
          .hasCauseInstanceOf(ApiConnectionException.class);
    }
  }

  // --- The intent belongs to the user who created it ---

  private static final UUID OWNER = UUID.randomUUID();
  private static final Instant NOW = Instant.parse("2026-11-18T12:00:00Z");

  private static PaymentIntent boundIntent(String ownerId) {
    PaymentIntent intent = succeededIntent(5000L, 0L);
    when(intent.getMetadata()).thenReturn(
        ownerId == null ? Map.of() : Map.of("fairtix_user_id", ownerId));
    return intent;
  }

  @Test
  void verifyForUser_intentOfCaller_returnsTrue() throws Exception {
    try (MockedStatic<PaymentIntent> mocked = Mockito.mockStatic(PaymentIntent.class)) {
      stubVerifyRetrieve(mocked, "pi_own", boundIntent(OWNER.toString()));

      assertTrue(service.verifyPaymentSucceeded("pi_own", 5000L, OWNER));
    }
  }

  @Test
  void verifyForUser_intentOfAnotherUser_throwsNotOwned() {
    try (MockedStatic<PaymentIntent> mocked = Mockito.mockStatic(PaymentIntent.class)) {
      stubVerifyRetrieve(mocked, "pi_other", boundIntent(UUID.randomUUID().toString()));

      assertThatThrownBy(() -> service.verifyPaymentSucceeded("pi_other", 5000L, OWNER))
          .isInstanceOf(PaymentIntentNotOwnedException.class);
    }
  }

  /** Ownership is checked first: another user's intent never reads as "not verified". */
  @Test
  void verifyForUser_unsucceededIntentOfAnotherUser_throwsNotOwned() {
    try (MockedStatic<PaymentIntent> mocked = Mockito.mockStatic(PaymentIntent.class)) {
      PaymentIntent intent = mock(PaymentIntent.class);
      when(intent.getStatus()).thenReturn("requires_payment_method");
      when(intent.getMetadata()).thenReturn(Map.of("fairtix_user_id", UUID.randomUUID().toString()));
      stubVerifyRetrieve(mocked, "pi_other_open", intent);

      assertThatThrownBy(() -> service.verifyPaymentSucceeded("pi_other_open", 5000L, OWNER))
          .isInstanceOf(PaymentIntentNotOwnedException.class);
    }
  }

  /** No grace for an intent without an owner, however recently it was created. */
  @Test
  void verifyForUser_recentUnboundIntent_throwsNotOwned() {
    try (MockedStatic<PaymentIntent> mocked = Mockito.mockStatic(PaymentIntent.class)) {
      PaymentIntent intent = boundIntent(null);
      when(intent.getCreated()).thenReturn(Instant.now().minusSeconds(60).getEpochSecond());
      stubVerifyRetrieve(mocked, "pi_unbound_recent", intent);

      assertThatThrownBy(() -> service.verifyPaymentSucceeded("pi_unbound_recent", 5000L, OWNER))
          .isInstanceOfSatisfying(PaymentIntentNotOwnedException.class, ex -> {
            org.assertj.core.api.Assertions.assertThat(ex.getReason()).isEqualTo("unbound");
            org.assertj.core.api.Assertions.assertThat(ex.isPaid()).isTrue();
          });
    }
  }

  @Test
  void verifyForUser_oldUnboundIntent_throwsNotOwned() {
    try (MockedStatic<PaymentIntent> mocked = Mockito.mockStatic(PaymentIntent.class)) {
      PaymentIntent intent = boundIntent(null);
      when(intent.getCreated()).thenReturn(NOW.minus(Duration.ofHours(25)).getEpochSecond());
      stubVerifyRetrieve(mocked, "pi_unbound_old", intent);

      assertThatThrownBy(() -> service.verifyPaymentSucceeded("pi_unbound_old", 5000L, OWNER))
          .isInstanceOfSatisfying(PaymentIntentNotOwnedException.class, ex -> {
            org.assertj.core.api.Assertions.assertThat(ex.getReason()).isEqualTo("unbound");
            org.assertj.core.api.Assertions.assertThat(ex.isPaid()).isTrue();
          });
    }
  }

  /** Not paid: checkout has no charge to put on record. */
  @Test
  void verifyForUser_unpaidUnboundIntent_throwsNotOwnedNotPaid() {
    try (MockedStatic<PaymentIntent> mocked = Mockito.mockStatic(PaymentIntent.class)) {
      PaymentIntent intent = boundIntent(null);
      when(intent.getStatus()).thenReturn("requires_payment_method");
      stubVerifyRetrieve(mocked, "pi_unbound_unpaid", intent);

      assertThatThrownBy(() -> service.verifyPaymentSucceeded("pi_unbound_unpaid", 5000L, OWNER))
          .isInstanceOfSatisfying(PaymentIntentNotOwnedException.class, ex -> {
            org.assertj.core.api.Assertions.assertThat(ex.getReason()).isEqualTo("unbound");
            org.assertj.core.api.Assertions.assertThat(ex.isPaid()).isFalse();
          });
    }
  }

  /** No metadata at all is unbound, not another channel's: a paid one goes on record. */
  @Test
  void verifyForUser_nullMetadata_throwsUnbound() {
    try (MockedStatic<PaymentIntent> mocked = Mockito.mockStatic(PaymentIntent.class)) {
      PaymentIntent intent = boundIntent(null);
      when(intent.getMetadata()).thenReturn(null);
      stubVerifyRetrieve(mocked, "pi_unbound_nometadata", intent);

      assertThatThrownBy(() -> service.verifyPaymentSucceeded("pi_unbound_nometadata", 5000L, OWNER))
          .isInstanceOfSatisfying(PaymentIntentNotOwnedException.class, ex -> {
            org.assertj.core.api.Assertions.assertThat(ex.getReason()).isEqualTo("unbound");
            org.assertj.core.api.Assertions.assertThat(ex.isPaid()).isTrue();
          });
    }
  }

  /**
   * A box-office intent carries no user: online checkout refuses it as another
   * channel's, with or without an organizer's Connect routing.
   */
  @ParameterizedTest
  @MethodSource("boxOfficeMetadata")
  void verifyForUser_boxOfficeIntent_throwsNotOwned(Map<String, String> metadata) {
    try (MockedStatic<PaymentIntent> mocked = Mockito.mockStatic(PaymentIntent.class)) {
      PaymentIntent intent = boundIntent(null);
      when(intent.getMetadata()).thenReturn(metadata);
      stubVerifyRetrieve(mocked, "pi_box_office", intent);

      assertThatThrownBy(() -> service.verifyPaymentSucceeded("pi_box_office", 5000L, OWNER))
          .isInstanceOf(PaymentIntentNotOwnedException.class)
          .hasMessageContaining("other_channel");
    }
  }

  /** Box-office metadata as createCardPresent writes it, without and with Connect routing. */
  static Stream<Map<String, String>> boxOfficeMetadata() {
    return Stream.of(
        Map.of("fairtix_channel", "box_office"),
        Map.of("fairtix_channel", "box_office",
            "fairtix_org_id", UUID.randomUUID().toString()));
  }

  /** The box-office path has no customer account, so it does no owner check. */
  @Test
  void verifyWithoutUser_ignoresIntentOwner() throws Exception {
    try (MockedStatic<PaymentIntent> mocked = Mockito.mockStatic(PaymentIntent.class)) {
      stubVerifyRetrieve(mocked, "pi_bo", boundIntent(UUID.randomUUID().toString()));

      assertTrue(service.verifyPaymentSucceeded("pi_bo", 5000L));
    }
  }

  @Test
  void createPaymentIntent_recordsCallerInMetadata() throws Exception {
    try (MockedStatic<PaymentIntent> mocked = Mockito.mockStatic(PaymentIntent.class)) {
      PaymentIntent intent = mock(PaymentIntent.class);
      when(intent.getClientSecret()).thenReturn("pi_secret");
      mocked.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class), any(RequestOptions.class)))
          .thenReturn(intent);

      service.createPaymentIntent(5000L, "usd", null, OWNER, "fairtix-pi2-m1");

      ArgumentCaptor<PaymentIntentCreateParams> params =
          ArgumentCaptor.forClass(PaymentIntentCreateParams.class);
      mocked.verify(() -> PaymentIntent.create(params.capture(), any(RequestOptions.class)));
      assertThat(params.getValue().getMetadata()).containsEntry("fairtix_user_id", OWNER.toString());
    }
  }

  @Test
  void createPaymentIntent_cardDeclined_throwsPaymentDeclined() {
    try (MockedStatic<PaymentIntent> mocked = Mockito.mockStatic(PaymentIntent.class)) {
      CardException declined = cardDeclined();
      mocked.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class), any(RequestOptions.class)))
          .thenThrow(declined);

      assertThatThrownBy(() -> service.createPaymentIntent(5000L, "usd", null, OWNER, "fairtix-pi2-t1"))
          .isInstanceOf(PaymentDeclinedException.class)
          .hasMessageContaining("Your card was declined");
    }
  }

  @Test
  void createPaymentIntent_otherStripeError_wrapsAsRuntimeException() {
    try (MockedStatic<PaymentIntent> mocked = Mockito.mockStatic(PaymentIntent.class)) {
      mocked.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class), any(RequestOptions.class)))
          .thenThrow(new ApiConnectionException("connection reset"));

      assertThatThrownBy(() -> service.createPaymentIntent(5000L, "usd", null, OWNER, "fairtix-pi2-t2"))
          .isExactlyInstanceOf(RuntimeException.class)
          .hasMessageStartingWith("Failed to create Stripe payment intent")
          .hasCauseInstanceOf(ApiConnectionException.class);
    }
  }

  // --- Exposure gate (b): idempotency keys (RM:344). Red on commit 1178a4b:
  // no key was sent, requestId went into keyed params, and Stripe's idempotency
  // error surfaced as a generic RuntimeException (500). ---

  @Test
  void createRefund_sendsRefundKey_noRequestIdMetadata() throws Exception {
    UUID refundId = UUID.randomUUID();
    MDC.put("requestId", "req-abc");
    try (MockedStatic<PaymentIntent> pi = Mockito.mockStatic(PaymentIntent.class);
         MockedStatic<Refund> refunds = Mockito.mockStatic(Refund.class)) {
      pi.when(() -> PaymentIntent.retrieve("pi_r1")).thenReturn(mock(PaymentIntent.class));
      RefundCollection none = emptyRefunds();
      refunds.when(() -> Refund.list(any(RefundListParams.class))).thenReturn(none);
      Refund created = mock(Refund.class);
      refunds.when(() -> Refund.create(any(RefundCreateParams.class), any(RequestOptions.class)))
          .thenReturn(created);

      assertThat(service.createRefund("pi_r1", 2500L, "changed mind", refundId)).isSameAs(created);

      ArgumentCaptor<RefundCreateParams> params = ArgumentCaptor.forClass(RefundCreateParams.class);
      ArgumentCaptor<RequestOptions> opts = ArgumentCaptor.forClass(RequestOptions.class);
      refunds.verify(() -> Refund.create(params.capture(), opts.capture()));
      assertThat(opts.getValue().getIdempotencyKey()).isEqualTo("fairtix-refund-" + refundId);
      @SuppressWarnings("unchecked")
      Map<String, String> metadata = (Map<String, String>) params.getValue().getMetadata();
      assertThat(metadata)
          .containsEntry("fairtix_refund_id", refundId.toString())
          .doesNotContainKey("requestId");
    } finally {
      MDC.remove("requestId");
    }
  }

  @Test
  void createRefund_reusesExistingRefundByMetadata() throws Exception {
    UUID refundId = UUID.randomUUID();
    try (MockedStatic<PaymentIntent> pi = Mockito.mockStatic(PaymentIntent.class);
         MockedStatic<Refund> refunds = Mockito.mockStatic(Refund.class)) {
      pi.when(() -> PaymentIntent.retrieve("pi_r2")).thenReturn(mock(PaymentIntent.class));
      Refund other = mock(Refund.class);
      when(other.getMetadata()).thenReturn(Map.of("fairtix_refund_id", UUID.randomUUID().toString()));
      Refund existing = mock(Refund.class);
      when(existing.getMetadata()).thenReturn(Map.of("fairtix_refund_id", refundId.toString()));
      RefundCollection page = mock(RefundCollection.class);
      when(page.getData()).thenReturn(List.of(other, existing));
      refunds.when(() -> Refund.list(any(RefundListParams.class))).thenReturn(page);

      assertThat(service.createRefund("pi_r2", 2500L, null, refundId)).isSameAs(existing);
      refunds.verify(() -> Refund.create(any(RefundCreateParams.class), any(RequestOptions.class)),
          never());
      refunds.verify(() -> Refund.create(any(RefundCreateParams.class)), never());
    }
  }

  /** Review round 1 (#5): a failed or canceled Stripe refund is not a refund. */
  @Test
  void createRefund_existingFailedRefund_isNotReused() throws Exception {
    UUID refundId = UUID.randomUUID();
    try (MockedStatic<PaymentIntent> pi = Mockito.mockStatic(PaymentIntent.class);
         MockedStatic<Refund> refunds = Mockito.mockStatic(Refund.class)) {
      pi.when(() -> PaymentIntent.retrieve("pi_r4")).thenReturn(mock(PaymentIntent.class));
      Refund failed = mock(Refund.class);
      when(failed.getId()).thenReturn("re_failed");
      when(failed.getStatus()).thenReturn("failed");
      when(failed.getMetadata()).thenReturn(Map.of("fairtix_refund_id", refundId.toString()));
      RefundCollection page = mock(RefundCollection.class);
      when(page.getData()).thenReturn(List.of(failed));
      refunds.when(() -> Refund.list(any(RefundListParams.class))).thenReturn(page);

      assertThatThrownBy(() -> service.createRefund("pi_r4", 2500L, null, refundId))
          .hasMessageContaining("re_failed")
          .hasMessageContaining("failed");
    }
  }

  @Test
  void createRefund_createdRefundCanceled_throws() throws Exception {
    try (MockedStatic<PaymentIntent> pi = Mockito.mockStatic(PaymentIntent.class);
         MockedStatic<Refund> refunds = Mockito.mockStatic(Refund.class)) {
      pi.when(() -> PaymentIntent.retrieve("pi_r5")).thenReturn(mock(PaymentIntent.class));
      RefundCollection none = emptyRefunds();
      refunds.when(() -> Refund.list(any(RefundListParams.class))).thenReturn(none);
      Refund canceled = mock(Refund.class);
      when(canceled.getId()).thenReturn("re_canceled");
      when(canceled.getStatus()).thenReturn("canceled");
      refunds.when(() -> Refund.create(any(RefundCreateParams.class), any(RequestOptions.class)))
          .thenReturn(canceled);

      assertThatThrownBy(() -> service.createRefund("pi_r5", 2500L, null, UUID.randomUUID()))
          .hasMessageContaining("canceled");
    }
  }

  @Test
  void createRefund_idempotencyError_mapsToRetryConflict() {
    try (MockedStatic<PaymentIntent> pi = Mockito.mockStatic(PaymentIntent.class);
         MockedStatic<Refund> refunds = Mockito.mockStatic(Refund.class)) {
      pi.when(() -> PaymentIntent.retrieve("pi_r3")).thenReturn(mock(PaymentIntent.class));
      RefundCollection none = emptyRefunds();
      refunds.when(() -> Refund.list(any(RefundListParams.class))).thenReturn(none);
      refunds.when(() -> Refund.create(any(RefundCreateParams.class), any(RequestOptions.class)))
          .thenThrow(idempotencyError());
      refunds.when(() -> Refund.create(any(RefundCreateParams.class)))
          .thenThrow(idempotencyError());

      assertThatThrownBy(() -> service.createRefund("pi_r3", 2500L, null, UUID.randomUUID()))
          .isInstanceOf(PaymentRetryConflictException.class);
    }
  }

  @Test
  void createPaymentIntent_sendsKey_noRequestIdMetadata() throws Exception {
    MDC.put("requestId", "req-abc");
    try (MockedStatic<PaymentIntent> mocked = Mockito.mockStatic(PaymentIntent.class)) {
      PaymentIntent intent = mock(PaymentIntent.class);
      when(intent.getId()).thenReturn("pi_k1");
      when(intent.getClientSecret()).thenReturn("pi_secret");
      mocked.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class), any(RequestOptions.class)))
          .thenReturn(intent);

      StripePaymentService.StripeIntent created =
          service.createPaymentIntent(5000L, "usd", null, OWNER, "fairtix-pi2-k1");
      assertThat(created.clientSecret()).isEqualTo("pi_secret");
      assertThat(created.id()).isEqualTo("pi_k1");

      ArgumentCaptor<PaymentIntentCreateParams> params =
          ArgumentCaptor.forClass(PaymentIntentCreateParams.class);
      ArgumentCaptor<RequestOptions> opts = ArgumentCaptor.forClass(RequestOptions.class);
      mocked.verify(() -> PaymentIntent.create(params.capture(), opts.capture()));
      assertThat(opts.getValue().getIdempotencyKey()).isEqualTo("fairtix-pi2-k1");
      Map<String, String> metadata = params.getValue().getMetadata();
      assertThat(metadata == null ? Map.<String, String>of() : metadata).doesNotContainKey("requestId");
    } finally {
      MDC.remove("requestId");
    }
  }

  @Test
  void createCardPresent_sendsKey_noRequestIdMetadata() throws Exception {
    MDC.put("requestId", "req-abc");
    try (MockedStatic<PaymentIntent> mocked = Mockito.mockStatic(PaymentIntent.class)) {
      PaymentIntent intent = mock(PaymentIntent.class);
      mocked.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class), any(RequestOptions.class)))
          .thenReturn(intent);

      assertThat(service.createCardPresentPaymentIntent(5000L, "usd", null, "fairtix-bo-k1"))
          .isSameAs(intent);

      ArgumentCaptor<PaymentIntentCreateParams> params =
          ArgumentCaptor.forClass(PaymentIntentCreateParams.class);
      ArgumentCaptor<RequestOptions> opts = ArgumentCaptor.forClass(RequestOptions.class);
      mocked.verify(() -> PaymentIntent.create(params.capture(), opts.capture()));
      assertThat(opts.getValue().getIdempotencyKey()).isEqualTo("fairtix-bo-k1");
      assertThat(params.getValue().getMetadata())
          .containsEntry("fairtix_channel", "box_office")
          .doesNotContainKey("requestId");
    } finally {
      MDC.remove("requestId");
    }
  }

  @Test
  void createPaymentIntent_idempotencyError_mapsToRetryConflict() {
    try (MockedStatic<PaymentIntent> mocked = Mockito.mockStatic(PaymentIntent.class)) {
      mocked.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class), any(RequestOptions.class)))
          .thenThrow(idempotencyError());
      mocked.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class)))
          .thenThrow(idempotencyError());

      assertThatThrownBy(() -> service.createPaymentIntent(5000L, "usd", null, OWNER, "fairtix-pi2-k2"))
          .isInstanceOf(PaymentRetryConflictException.class);
    }
  }

  private static PaymentIntent stubIntent(String id, String status, String secret) {
    PaymentIntent intent = mock(PaymentIntent.class);
    when(intent.getId()).thenReturn(id);
    when(intent.getStatus()).thenReturn(status);
    when(intent.getClientSecret()).thenReturn(secret);
    return intent;
  }

  @Test
  void retrievePaymentIntent_mapsIdStatusSecret() {
    try (MockedStatic<PaymentIntent> mocked = Mockito.mockStatic(PaymentIntent.class)) {
      PaymentIntent intent = stubIntent("pi_1", "requires_payment_method", "sec_1");
      mocked.when(() -> PaymentIntent.retrieve("pi_1")).thenReturn(intent);

      assertThat(service.retrievePaymentIntent("pi_1")).isEqualTo(
          new StripePaymentService.StripeIntent("pi_1", "requires_payment_method", "sec_1"));
    }
  }

  @Test
  void cancelPaymentIntent_cancelsAsAbandoned() throws Exception {
    try (MockedStatic<PaymentIntent> mocked = Mockito.mockStatic(PaymentIntent.class)) {
      PaymentIntent open = stubIntent("pi_1", "requires_payment_method", "sec_1");
      PaymentIntent canceled = stubIntent("pi_1", "canceled", "sec_1");
      when(open.cancel(any(PaymentIntentCancelParams.class))).thenReturn(canceled);
      mocked.when(() -> PaymentIntent.retrieve("pi_1")).thenReturn(open);

      assertThat(service.cancelPaymentIntent("pi_1").status()).isEqualTo("canceled");

      ArgumentCaptor<PaymentIntentCancelParams> params =
          ArgumentCaptor.forClass(PaymentIntentCancelParams.class);
      org.mockito.Mockito.verify(open).cancel(params.capture());
      assertThat(params.getValue().getCancellationReason())
          .isEqualTo(PaymentIntentCancelParams.CancellationReason.ABANDONED);
    }
  }

  @Test
  void cancelPaymentIntent_unexpectedState_returnsCurrentIntent() throws Exception {
    try (MockedStatic<PaymentIntent> mocked = Mockito.mockStatic(PaymentIntent.class)) {
      PaymentIntent open = stubIntent("pi_1", "requires_payment_method", "sec_1");
      PaymentIntent succeeded = stubIntent("pi_1", "succeeded", "sec_1");
      when(open.cancel(any(PaymentIntentCancelParams.class))).thenThrow(
          new InvalidRequestException("cannot cancel", null, "req_1",
              "payment_intent_unexpected_state", 400, null));
      mocked.when(() -> PaymentIntent.retrieve("pi_1")).thenReturn(open, succeeded);

      assertThat(service.cancelPaymentIntent("pi_1").status()).isEqualTo("succeeded");
    }
  }

  @Test
  void cancelPaymentIntent_otherError_throws() throws Exception {
    try (MockedStatic<PaymentIntent> mocked = Mockito.mockStatic(PaymentIntent.class)) {
      PaymentIntent open = stubIntent("pi_1", "requires_payment_method", "sec_1");
      when(open.cancel(any(PaymentIntentCancelParams.class)))
          .thenThrow(new ApiConnectionException("connection reset"));
      mocked.when(() -> PaymentIntent.retrieve("pi_1")).thenReturn(open);

      assertThatThrownBy(() -> service.cancelPaymentIntent("pi_1"))
          .isExactlyInstanceOf(RuntimeException.class)
          .hasCauseInstanceOf(ApiConnectionException.class);
    }
  }

  @Test
  void cancelPaymentIntent_otherInvalidRequestCode_throws() throws Exception {
    try (MockedStatic<PaymentIntent> mocked = Mockito.mockStatic(PaymentIntent.class)) {
      PaymentIntent open = stubIntent("pi_1", "requires_payment_method", "sec_1");
      InvalidRequestException missing = new InvalidRequestException("no such intent", null,
          "req_1", "resource_missing", 404, null);
      when(open.cancel(any(PaymentIntentCancelParams.class))).thenThrow(missing);
      mocked.when(() -> PaymentIntent.retrieve("pi_1")).thenReturn(open);

      assertThatThrownBy(() -> service.cancelPaymentIntent("pi_1"))
          .isExactlyInstanceOf(RuntimeException.class)
          .hasCause(missing);
    }
  }

  private static RefundCollection emptyRefunds() {
    RefundCollection page = mock(RefundCollection.class);
    when(page.getData()).thenReturn(List.of());
    return page;
  }

  private static IdempotencyException idempotencyError() {
    return new IdempotencyException(
        "Keys for idempotent requests can only be used with the same parameters", "req_test",
        "idempotency_error", 400);
  }

  private static CardException cardDeclined() {
    return new CardException("Your card was declined.", "req_test", "card_declined", null,
        "generic_decline", null, 402, null);
  }
}
