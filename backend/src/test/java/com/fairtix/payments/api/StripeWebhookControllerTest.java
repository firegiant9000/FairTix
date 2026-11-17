package com.fairtix.payments.api;

import com.fairtix.audit.application.AuditService;
import com.fairtix.payments.domain.PaymentRecord;
import com.fairtix.payments.domain.PaymentStatus;
import com.fairtix.payments.infrastructure.PaymentRecordRepository;
import com.fairtix.refunds.domain.RefundRequest;
import com.fairtix.refunds.domain.RefundStatus;
import com.fairtix.refunds.infrastructure.RefundRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import com.stripe.Stripe;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestPropertySource(properties = {
    "stripe.enabled=true",
    "stripe.webhook-secret=whsec_test123"
})
class StripeWebhookControllerTest {

  private static final String WEBHOOK_SECRET = "whsec_test123";

  @Autowired private WebApplicationContext context;
  @MockitoBean private PaymentRecordRepository paymentRecordRepository;
  @MockitoBean private RefundRepository refundRepository;
  @MockitoBean private AuditService auditService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context)
        .apply(springSecurity())
        .build();
  }

  // --- helpers ---

  private static String stripeSignature(String payload, String secret) throws Exception {
    long ts = Instant.now().getEpochSecond();
    String signed = ts + "." + payload;
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    String sig = HexFormat.of().formatHex(mac.doFinal(signed.getBytes(StandardCharsets.UTF_8)));
    return "t=" + ts + ",v1=" + sig;
  }

  private static String paymentIntentEvent(String type, String piId) {
    return """
        {
          "id": "evt_test",
          "object": "event",
          "type": "%s",
          "api_version": "%s",
          "data": {
            "object": {
              "id": "%s",
              "object": "payment_intent",
              "amount": 5000,
              "currency": "usd",
              "status": "succeeded"
            }
          }
        }
        """.formatted(type, Stripe.API_VERSION, piId);
  }

  private static String chargeRefundedEvent(String piId) {
    return chargeRefundedEvent(piId, 5000);
  }

  private static String chargeRefundedEvent(String piId, long amountRefundedCents) {
    return """
        {
          "id": "evt_test_charge",
          "object": "event",
          "type": "charge.refunded",
          "api_version": "%s",
          "data": {
            "object": {
              "id": "ch_test",
              "object": "charge",
              "payment_intent": "%s",
              "refunded": true,
              "amount_refunded": %d
            }
          }
        }
        """.formatted(Stripe.API_VERSION, piId, amountRefundedCents);
  }

  private void postSigned(String payload) throws Exception {
    mockMvc.perform(post("/api/webhooks/stripe")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Stripe-Signature", stripeSignature(payload, WEBHOOK_SECRET))
            .content(payload))
        .andExpect(status().isOk());
  }

  private static RefundRequest completedRefund(UUID orderId, String amount) {
    RefundRequest refund = new RefundRequest(orderId, UUID.randomUUID(),
        new BigDecimal(amount), "Event cancelled");
    refund.approve(UUID.randomUUID(), "Approved");
    refund.complete();
    return refund;
  }

  // --- tests ---

  @Test
  void invalidSignature_returns400() throws Exception {
    mockMvc.perform(post("/api/webhooks/stripe")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Stripe-Signature", "t=0,v1=invalid")
            .content("{\"type\":\"payment_intent.succeeded\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void paymentIntentSucceeded_existingRecord_returns200WithoutAuditWarning() throws Exception {
    String piId = "pi_existing_123";
    String payload = paymentIntentEvent("payment_intent.succeeded", piId);
    String sigHeader = stripeSignature(payload, WEBHOOK_SECRET);

    PaymentRecord record = new PaymentRecord(
        UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("50.00"),
        "usd", PaymentStatus.SUCCESS, piId, null);
    when(paymentRecordRepository.findByTransactionId(piId)).thenReturn(Optional.of(record));

    mockMvc.perform(post("/api/webhooks/stripe")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Stripe-Signature", sigHeader)
            .content(payload))
        .andExpect(status().isOk());

    verify(auditService, never()).log(eq(new UUID(0L, 0L)), eq("STRIPE_UNMATCHED_PAYMENT"), anyString(), any(), anyString());
  }

  @Test
  void paymentIntentSucceeded_noRecord_auditsUnmatchedPayment() throws Exception {
    String piId = "pi_unmatched_789";
    String payload = paymentIntentEvent("payment_intent.succeeded", piId);
    when(paymentRecordRepository.findByTransactionId(piId)).thenReturn(Optional.empty());

    mockMvc.perform(post("/api/webhooks/stripe")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Stripe-Signature", stripeSignature(payload, WEBHOOK_SECRET))
            .content(payload))
        .andExpect(status().isOk());

    verify(auditService).log(eq(new UUID(0L, 0L)), eq("STRIPE_UNMATCHED_PAYMENT"), eq("PAYMENT"),
        isNull(), contains("paymentIntentId=" + piId));
  }

  @Test
  void paymentIntentFailed_auditsReason_andChangesNoState() throws Exception {
    String piId = "pi_failed_321";
    String payload = """
        {
          "id": "evt_test_failed",
          "object": "event",
          "type": "payment_intent.payment_failed",
          "api_version": "%s",
          "data": {
            "object": {
              "id": "%s",
              "object": "payment_intent",
              "amount": 5000,
              "currency": "usd",
              "status": "requires_payment_method",
              "last_payment_error": { "message": "Your card was declined." }
            }
          }
        }
        """.formatted(Stripe.API_VERSION, piId);

    mockMvc.perform(post("/api/webhooks/stripe")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Stripe-Signature", stripeSignature(payload, WEBHOOK_SECRET))
            .content(payload))
        .andExpect(status().isOk());

    verify(auditService).log(eq(new UUID(0L, 0L)), eq("STRIPE_PAYMENT_FAILED"), eq("PAYMENT"),
        isNull(), eq("paymentIntentId=" + piId + " failed: Your card was declined."));
    // Audit only: no order, payment or refund state changes on a failed intent.
    verify(paymentRecordRepository, never()).save(any());
    verify(refundRepository, never()).save(any());
  }

  @Test
  void chargeRefunded_completesApprovedRefundRequest() throws Exception {
    String piId = "pi_refund_456";
    UUID orderId = UUID.randomUUID();
    String payload = chargeRefundedEvent(piId);
    String sigHeader = stripeSignature(payload, WEBHOOK_SECRET);

    PaymentRecord record = new PaymentRecord(
        orderId, UUID.randomUUID(), new BigDecimal("50.00"),
        "usd", PaymentStatus.SUCCESS, piId, null);
    when(paymentRecordRepository.findByTransactionId(piId)).thenReturn(Optional.of(record));

    RefundRequest refundRequest = new RefundRequest(orderId, UUID.randomUUID(),
        new BigDecimal("50.00"), "Event cancelled");
    refundRequest.approve(UUID.randomUUID(), "Approved");
    when(refundRepository.findAllByOrderId(orderId)).thenReturn(List.of(refundRequest));
    when(refundRepository.completeIfActive(any(), any())).thenReturn(1);

    mockMvc.perform(post("/api/webhooks/stripe")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Stripe-Signature", sigHeader)
            .content(payload))
        .andExpect(status().isOk());

    // A conditional update, never a full-row save (see RefundWebhookRaceIT).
    verify(refundRepository).completeIfActive(eq(refundRequest.getId()), any());
    verify(refundRepository, never()).save(any());
    verify(auditService).log(eq(new UUID(0L, 0L)), eq("REFUND_COMPLETED"), eq("REFUND"), any(), anyString());
  }

  @Test
  void chargeRefunded_noLongerActive_isNotAudited() throws Exception {
    String piId = "pi_refund_789";
    UUID orderId = UUID.randomUUID();
    String payload = chargeRefundedEvent(piId);
    String sigHeader = stripeSignature(payload, WEBHOOK_SECRET);
    when(paymentRecordRepository.findByTransactionId(piId)).thenReturn(Optional.of(new PaymentRecord(
        orderId, UUID.randomUUID(), new BigDecimal("50.00"), "usd", PaymentStatus.SUCCESS, piId, null)));
    RefundRequest refundRequest = new RefundRequest(orderId, UUID.randomUUID(),
        new BigDecimal("50.00"), "Event cancelled");
    refundRequest.approve(UUID.randomUUID(), "Approved");
    when(refundRepository.findAllByOrderId(orderId)).thenReturn(List.of(refundRequest));
    when(refundRepository.completeIfActive(any(), any())).thenReturn(0);

    mockMvc.perform(post("/api/webhooks/stripe")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Stripe-Signature", sigHeader)
            .content(payload))
        .andExpect(status().isOk());

    verify(auditService, never()).log(any(), eq("REFUND_COMPLETED"), any(), any(), anyString());
    verify(auditService, never()).log(any(), eq("REFUND_ON_LIVE_ORDER"), any(), any(), anyString());
  }

  /** A refund nobody requested (Dashboard, or one landing during order creation). */
  @Test
  void chargeRefunded_withoutRefundRequest_alertsRefundOnLiveOrder() throws Exception {
    String piId = "pi_live_order_refund";
    UUID orderId = UUID.randomUUID();
    when(paymentRecordRepository.findByTransactionId(piId)).thenReturn(Optional.of(new PaymentRecord(
        orderId, UUID.randomUUID(), new BigDecimal("50.00"), "usd", PaymentStatus.SUCCESS, piId, null)));
    when(refundRepository.findAllByOrderId(orderId)).thenReturn(List.of());

    postSigned(chargeRefundedEvent(piId));

    verify(auditService).log(eq(new UUID(0L, 0L)), eq("REFUND_ON_LIVE_ORDER"), eq("ORDER"),
        eq(orderId), contains("stripe_intent=" + piId));
    verify(refundRepository, never()).completeIfActive(any(), any());
  }

  /** Our own refund flow completed the request before Stripe's event arrived. */
  @Test
  void chargeRefunded_coveredByCompletedRefundRequest_isNotAlerted() throws Exception {
    String piId = "pi_our_refund";
    UUID orderId = UUID.randomUUID();
    when(paymentRecordRepository.findByTransactionId(piId)).thenReturn(Optional.of(new PaymentRecord(
        orderId, UUID.randomUUID(), new BigDecimal("50.00"), "usd", PaymentStatus.SUCCESS, piId, null)));
    when(refundRepository.findAllByOrderId(orderId))
        .thenReturn(List.of(completedRefund(orderId, "50.00")));

    postSigned(chargeRefundedEvent(piId, 5000));

    verify(auditService, never()).log(any(), eq("REFUND_ON_LIVE_ORDER"), any(), any(), anyString());
  }

  /** Stripe refunded more than our completed requests account for. */
  @Test
  void chargeRefunded_beyondCompletedRefundRequests_alerts() throws Exception {
    String piId = "pi_extra_refund";
    UUID orderId = UUID.randomUUID();
    when(paymentRecordRepository.findByTransactionId(piId)).thenReturn(Optional.of(new PaymentRecord(
        orderId, UUID.randomUUID(), new BigDecimal("50.00"), "usd", PaymentStatus.SUCCESS, piId, null)));
    when(refundRepository.findAllByOrderId(orderId))
        .thenReturn(List.of(completedRefund(orderId, "20.00")));

    postSigned(chargeRefundedEvent(piId, 5000));

    verify(auditService).log(eq(new UUID(0L, 0L)), eq("REFUND_ON_LIVE_ORDER"), eq("ORDER"),
        eq(orderId), contains("amount_refunded_cents=5000"));
  }

  /**
   * No payment record yet: the order may still be committing. The refund goes on
   * record as unmatched (refunds_on_live_orders matches it to an order later), not
   * as an alert on an order.
   */
  @Test
  void chargeRefunded_withoutPaymentRecord_isRecordedAsUnmatched() throws Exception {
    String piId = "pi_no_order";
    when(paymentRecordRepository.findByTransactionId(piId)).thenReturn(Optional.empty());

    postSigned(chargeRefundedEvent(piId, 5000));

    verify(auditService).log(eq(new UUID(0L, 0L)), eq("STRIPE_REFUND_UNMATCHED"), eq("PAYMENT"),
        isNull(), eq("Stripe refund with no payment record yet: stripe_intent=" + piId
            + ", charge=ch_test, amount_refunded_cents=5000"));
    verify(auditService, never()).log(any(), eq("REFUND_ON_LIVE_ORDER"), any(), any(), anyString());
  }
}
