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
    return createPaymentIntent(amountCents, currency, null);
  }

  /**
   * Connect-aware PaymentIntent.
   *
   * When {@code connect} is non-null, the intent is routed to the connected
   * account via {@code on_behalf_of} + {@code transfer_data.destination}, and
   * the platform takes {@code application_fee_amount} cents off the top.
   * Statement descriptor suffix surfaces the org name on the customer's
   * statement (FAIRTIX*ORGNAME).
   */
  public String createPaymentIntent(long amountCents, String currency, ConnectContext connect) {
    try {
      PaymentIntentCreateParams.Builder builder = PaymentIntentCreateParams.builder()
          .setAmount(amountCents)
          .setCurrency(currency);
      if (connect != null && connect.connectedAccountId() != null) {
        builder.setOnBehalfOf(connect.connectedAccountId())
            .setTransferData(PaymentIntentCreateParams.TransferData.builder()
                .setDestination(connect.connectedAccountId())
                .build());
        if (connect.applicationFeeAmountCents() > 0) {
          builder.setApplicationFeeAmount(connect.applicationFeeAmountCents());
        }
        String suffix = sanitizeStatementDescriptor(connect.statementDescriptorSuffix());
        if (suffix != null) {
          builder.setStatementDescriptorSuffix(suffix);
        }
        builder.putMetadata("fairtix_org_id", connect.organizationId());
      }
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

  // Stripe limits statement descriptor suffix to 22 chars; ASCII, no <>"'*\
  static String sanitizeStatementDescriptor(String raw) {
    if (raw == null) return null;
    String cleaned = raw.replaceAll("[<>\"'*\\\\]", "").replaceAll("\\s+", " ").trim();
    if (cleaned.isEmpty()) return null;
    return cleaned.length() > 22 ? cleaned.substring(0, 22) : cleaned;
  }

  public record ConnectContext(
      String organizationId,
      String connectedAccountId,
      long applicationFeeAmountCents,
      String statementDescriptorSuffix
  ) {}

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
      // If the original intent was routed via Connect (transfer_data.destination
      // + application_fee_amount), reverse the transfer and pro-rata refund the
      // application fee — otherwise the connected account is left short. Missing
      // either flag is silently catastrophic for organizer settlements, so this
      // branch is the single source of truth.
      PaymentIntent original = PaymentIntent.retrieve(paymentIntentId);
      boolean isConnectIntent = original.getTransferData() != null
          && original.getTransferData().getDestination() != null;

      RefundCreateParams.Builder builder = RefundCreateParams.builder()
          .setPaymentIntent(paymentIntentId)
          .setAmount(amountCents);
      if (isConnectIntent) {
        builder.setReverseTransfer(true);
        if (original.getApplicationFeeAmount() != null && original.getApplicationFeeAmount() > 0) {
          builder.setRefundApplicationFee(true);
        }
      }
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
