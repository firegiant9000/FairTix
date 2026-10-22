package com.fairtix.payments.application;

import com.fairtix.audit.application.AuditService;
import com.fairtix.payments.infrastructure.PaymentRecordRepository;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.CardException;
import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentCreateParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
      mocked.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class)))
          .thenThrow(cardDeclined());

      assertThatThrownBy(() -> service.createPaymentIntent(5000L, "usd"))
          .isInstanceOf(PaymentDeclinedException.class)
          .hasMessageContaining("Your card was declined");
    }
  }

  @Test
  void createPaymentIntent_otherStripeError_wrapsAsRuntimeException() {
    try (MockedStatic<PaymentIntent> mocked = Mockito.mockStatic(PaymentIntent.class)) {
      mocked.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class)))
          .thenThrow(new ApiConnectionException("connection reset"));

      assertThatThrownBy(() -> service.createPaymentIntent(5000L, "usd"))
          .isExactlyInstanceOf(RuntimeException.class)
          .hasMessageStartingWith("Failed to create Stripe payment intent")
          .hasCauseInstanceOf(ApiConnectionException.class);
    }
  }

  private static CardException cardDeclined() {
    return new CardException("Your card was declined.", "req_test", "card_declined", null,
        "generic_decline", null, 402, null);
  }
}
