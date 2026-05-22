package com.fairtix.payments.application;

import com.fairtix.audit.application.AuditService;
import com.fairtix.payments.domain.PaymentRecord;
import com.fairtix.payments.domain.PaymentStatus;
import com.fairtix.payments.infrastructure.PaymentRecordRepository;
import com.stripe.Stripe;
import com.stripe.exception.CardException;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class StripePaymentService {

  private static final Logger log = LoggerFactory.getLogger(StripePaymentService.class);

  @Value("${stripe.enabled:false}")
  private boolean stripeEnabled;

  @Value("${stripe.secret-key:}")
  private String secretKey;

  private final PaymentRecordRepository paymentRecordRepository;
  private final AuditService auditService;

  public StripePaymentService(PaymentRecordRepository paymentRecordRepository,
      AuditService auditService) {
    this.paymentRecordRepository = paymentRecordRepository;
    this.auditService = auditService;
  }

  @PostConstruct
  void init() {
    Stripe.apiKey = secretKey;
    if (stripeEnabled && secretKey.isBlank()) {
      log.warn("stripe.enabled=true but stripe.secret-key is blank; payment endpoints will fail at runtime");
    }
  }

  public String createPaymentIntent(long amountCents, String currency) {
    try {
      PaymentIntentCreateParams.Builder builder = PaymentIntentCreateParams.builder()
          .setAmount(amountCents)
          .setCurrency(currency);
      String requestId = MDC.get("requestId");
      if (requestId != null && !requestId.isBlank()) {
        builder.putMetadata("requestId", requestId);
      }
      PaymentIntent intent = PaymentIntent.create(builder.build());
      return intent.getClientSecret();
    } catch (CardException e) {
      throw new PaymentDeclinedException(
          e.getUserMessage() != null ? e.getUserMessage() : e.getMessage());
    } catch (StripeException e) {
      throw new RuntimeException("Failed to create Stripe payment intent: " + e.getMessage(), e);
    }
  }

  public boolean verifyPaymentSucceeded(String paymentIntentId, long expectedAmountCents) {
    try {
      PaymentIntent intent = PaymentIntent.retrieve(paymentIntentId);
      return "succeeded".equals(intent.getStatus())
          && intent.getAmountReceived() == expectedAmountCents;
    } catch (CardException e) {
      throw new PaymentDeclinedException(
          e.getUserMessage() != null ? e.getUserMessage() : e.getMessage());
    } catch (StripeException e) {
      throw new RuntimeException("Failed to verify Stripe payment: " + e.getMessage(), e);
    }
  }

  public Refund createRefund(String paymentIntentId, long amountCents, String reason) {
    try {
      RefundCreateParams.Builder builder = RefundCreateParams.builder()
          .setPaymentIntent(paymentIntentId)
          .setAmount(amountCents);
      if (reason != null && !reason.isBlank()) {
        builder.putMetadata("reason", reason);
      }
      String requestId = MDC.get("requestId");
      if (requestId != null && !requestId.isBlank()) {
        builder.putMetadata("requestId", requestId);
      }
      return Refund.create(builder.build());
    } catch (StripeException e) {
      throw new RuntimeException("Failed to create Stripe refund: " + e.getMessage(), e);
    }
  }

  public boolean isStripeEnabled() {
    return stripeEnabled;
  }

  public PaymentRecord recordStripePayment(String paymentIntentId, UUID orderId, UUID userId,
      BigDecimal amount, String currency) {
    PaymentRecord record = new PaymentRecord(
        orderId, userId, amount, currency, PaymentStatus.SUCCESS, paymentIntentId, null);
    PaymentRecord saved = paymentRecordRepository.save(record);
    auditService.log(userId, "PAYMENT_PROCESSED", "PAYMENT", saved.getId(),
        "stripe_intent=" + paymentIntentId);
    return saved;
  }
}
