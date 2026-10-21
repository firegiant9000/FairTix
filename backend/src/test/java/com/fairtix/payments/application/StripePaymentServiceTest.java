package com.fairtix.payments.application;

import com.fairtix.audit.application.AuditService;
import com.fairtix.payments.infrastructure.PaymentRecordRepository;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.CardException;
import com.stripe.exception.IdempotencyException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.model.RefundCollection;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.RefundListParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.slf4j.MDC;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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

  @Test
  void verifyPaymentSucceeded_returnsTrueForSucceeded() throws Exception {
    try (MockedStatic<PaymentIntent> mocked = Mockito.mockStatic(PaymentIntent.class)) {
      PaymentIntent mockIntent = mock(PaymentIntent.class);
      when(mockIntent.getStatus()).thenReturn("succeeded");
      when(mockIntent.getAmountReceived()).thenReturn(5000L);
      mocked.when(() -> PaymentIntent.retrieve("pi_test_123")).thenReturn(mockIntent);

      assertTrue(service.verifyPaymentSucceeded("pi_test_123", 5000L));
    }
  }

  @Test
  void verifyPaymentSucceeded_returnsFalseForOther() throws Exception {
    try (MockedStatic<PaymentIntent> mocked = Mockito.mockStatic(PaymentIntent.class)) {
      PaymentIntent mockIntent = mock(PaymentIntent.class);
      when(mockIntent.getStatus()).thenReturn("requires_payment_method");
      mocked.when(() -> PaymentIntent.retrieve("pi_failed")).thenReturn(mockIntent);

      assertFalse(service.verifyPaymentSucceeded("pi_failed", 5000L));
    }
  }

  @Test
  void verifyPaymentSucceeded_returnsFalseWhenAmountDiffers() throws Exception {
    try (MockedStatic<PaymentIntent> mocked = Mockito.mockStatic(PaymentIntent.class)) {
      PaymentIntent mockIntent = mock(PaymentIntent.class);
      when(mockIntent.getStatus()).thenReturn("succeeded");
      when(mockIntent.getAmountReceived()).thenReturn(4999L);
      mocked.when(() -> PaymentIntent.retrieve("pi_short")).thenReturn(mockIntent);

      assertFalse(service.verifyPaymentSucceeded("pi_short", 5000L));
    }
  }

  @Test
  void verifyPaymentSucceeded_cardDeclined_throwsPaymentDeclined() {
    try (MockedStatic<PaymentIntent> mocked = Mockito.mockStatic(PaymentIntent.class)) {
      mocked.when(() -> PaymentIntent.retrieve("pi_declined")).thenThrow(cardDeclined());

      assertThatThrownBy(() -> service.verifyPaymentSucceeded("pi_declined", 5000L))
          .isInstanceOf(PaymentDeclinedException.class)
          .hasMessageContaining("Your card was declined");
    }
  }

  @Test
  void verifyPaymentSucceeded_otherStripeError_wrapsAsRuntimeException() {
    try (MockedStatic<PaymentIntent> mocked = Mockito.mockStatic(PaymentIntent.class)) {
      mocked.when(() -> PaymentIntent.retrieve("pi_down"))
          .thenThrow(new ApiConnectionException("connection reset"));

      assertThatThrownBy(() -> service.verifyPaymentSucceeded("pi_down", 5000L))
          .isExactlyInstanceOf(RuntimeException.class)
          .hasMessageStartingWith("Failed to verify Stripe payment")
          .hasCauseInstanceOf(ApiConnectionException.class);
    }
  }

  @Test
  void createPaymentIntent_cardDeclined_throwsPaymentDeclined() {
    try (MockedStatic<PaymentIntent> mocked = Mockito.mockStatic(PaymentIntent.class)) {
      CardException declined = cardDeclined();
      mocked.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class), any(RequestOptions.class)))
          .thenThrow(declined);

      assertThatThrownBy(() -> service.createPaymentIntent(5000L, "usd", null, "fairtix-pi-t1"))
          .isInstanceOf(PaymentDeclinedException.class)
          .hasMessageContaining("Your card was declined");
    }
  }

  @Test
  void createPaymentIntent_otherStripeError_wrapsAsRuntimeException() {
    try (MockedStatic<PaymentIntent> mocked = Mockito.mockStatic(PaymentIntent.class)) {
      mocked.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class), any(RequestOptions.class)))
          .thenThrow(new ApiConnectionException("connection reset"));

      assertThatThrownBy(() -> service.createPaymentIntent(5000L, "usd", null, "fairtix-pi-t2"))
          .isExactlyInstanceOf(RuntimeException.class)
          .hasMessageStartingWith("Failed to create Stripe payment intent")
          .hasCauseInstanceOf(ApiConnectionException.class);
    }
  }

  // --- Exposure gate (b): idempotency keys (RM:344) ---

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
      when(intent.getClientSecret()).thenReturn("pi_secret");
      mocked.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class), any(RequestOptions.class)))
          .thenReturn(intent);

      assertThat(service.createPaymentIntent(5000L, "usd", null, "fairtix-pi-k1")).isEqualTo("pi_secret");

      ArgumentCaptor<PaymentIntentCreateParams> params =
          ArgumentCaptor.forClass(PaymentIntentCreateParams.class);
      ArgumentCaptor<RequestOptions> opts = ArgumentCaptor.forClass(RequestOptions.class);
      mocked.verify(() -> PaymentIntent.create(params.capture(), opts.capture()));
      assertThat(opts.getValue().getIdempotencyKey()).isEqualTo("fairtix-pi-k1");
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

      assertThatThrownBy(() -> service.createPaymentIntent(5000L, "usd", null, "fairtix-pi-k2"))
          .isInstanceOf(PaymentRetryConflictException.class);
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
