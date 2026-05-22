package com.fairtix.payments.api;

import com.fairtix.audit.application.AuditService;
import com.fairtix.payments.infrastructure.PaymentRecordRepository;
import com.fairtix.refunds.domain.RefundStatus;
import com.fairtix.refunds.infrastructure.RefundRepository;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Charge;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.model.StripeObject;
import com.stripe.net.Webhook;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;
import java.util.UUID;

@Tag(name = "Webhooks", description = "Stripe event webhooks")
@RestController
@RequestMapping("/api/webhooks")
public class StripeWebhookController {

  private static final Logger log = LoggerFactory.getLogger(StripeWebhookController.class);
  private static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);

  @Value("${stripe.enabled:false}")
  private boolean stripeEnabled;

  @Value("${stripe.webhook-secret:}")
  private String webhookSecret;

  private final PaymentRecordRepository paymentRecordRepository;
  private final RefundRepository refundRepository;
  private final AuditService auditService;

  public StripeWebhookController(PaymentRecordRepository paymentRecordRepository,
      RefundRepository refundRepository, AuditService auditService) {
    this.paymentRecordRepository = paymentRecordRepository;
    this.refundRepository = refundRepository;
    this.auditService = auditService;
  }

  @PostConstruct
  void warnIfMisconfigured() {
    if (stripeEnabled && webhookSecret.isBlank()) {
      log.warn("Stripe is enabled but STRIPE_WEBHOOK_SECRET is blank — all incoming webhooks will be rejected");
    }
  }

  @Operation(summary = "Stripe webhook receiver",
      description = "Validates Stripe signature and processes payment events.")
  @PostMapping("/stripe")
  @Transactional
  public ResponseEntity<Void> handleStripeWebhook(
      @RequestBody String payload,
      @RequestHeader("Stripe-Signature") String sigHeader) {

    if (!stripeEnabled || webhookSecret.isBlank()) {
      return ResponseEntity.ok().build();
    }

    Event event;
    try {
      event = Webhook.constructEvent(payload, sigHeader, webhookSecret);
    } catch (SignatureVerificationException e) {
      log.warn("Stripe webhook signature verification failed: {}", e.getMessage());
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
    }

    switch (event.getType()) {
      case "payment_intent.succeeded" -> handlePaymentIntentSucceeded(event);
      case "charge.refunded" -> handleChargeRefunded(event);
      case "payment_intent.payment_failed" -> handlePaymentIntentFailed(event);
      default -> log.debug("Unhandled Stripe webhook event type: {}", event.getType());
    }

    return ResponseEntity.ok().build();
  }

  private void handlePaymentIntentSucceeded(Event event) {
    getObject(event, PaymentIntent.class).ifPresent(pi -> {
      String piId = pi.getId();
      boolean alreadyRecorded = paymentRecordRepository.findByTransactionId(piId).isPresent();
      if (!alreadyRecorded) {
        log.warn("payment_intent.succeeded for {} has no matching order — manual review needed", piId);
        auditService.log(SYSTEM_ACTOR, "STRIPE_UNMATCHED_PAYMENT", "PAYMENT", null,
            "paymentIntentId=" + piId + " succeeded but no FairTix order found");
      }
    });
  }

  private void handleChargeRefunded(Event event) {
    getObject(event, Charge.class).ifPresent(charge -> {
      String piId = charge.getPaymentIntent();
      if (piId == null) return;
      paymentRecordRepository.findByTransactionId(piId).ifPresent(record ->
          refundRepository.findAllByOrderId(record.getOrderId()).stream()
              .filter(r -> r.getStatus() == RefundStatus.APPROVED
                  || r.getStatus() == RefundStatus.REQUESTED
                  || r.getStatus() == RefundStatus.PENDING_MANUAL)
              .findFirst()
              .ifPresent(refundRequest -> {
                refundRequest.complete();
                refundRepository.save(refundRequest);
                auditService.log(SYSTEM_ACTOR, "REFUND_COMPLETED", "REFUND", refundRequest.getId(),
                    "Completed via Stripe charge.refunded for paymentIntent=" + piId);
              })
      );
    });
  }

  private void handlePaymentIntentFailed(Event event) {
    getObject(event, PaymentIntent.class).ifPresent(pi -> {
      String reason = pi.getLastPaymentError() != null
          ? pi.getLastPaymentError().getMessage() : "unknown";
      auditService.log(SYSTEM_ACTOR, "STRIPE_PAYMENT_FAILED", "PAYMENT", null,
          "paymentIntentId=" + pi.getId() + " failed: " + reason);
    });
  }

  @SuppressWarnings("unchecked")
  private <T extends StripeObject> Optional<T> getObject(Event event, Class<T> type) {
    return event.getDataObjectDeserializer().getObject()
        .filter(type::isInstance)
        .map(obj -> (T) obj);
  }
}
