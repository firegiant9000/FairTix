package com.fairtix.payments.api;

import com.fairtix.audit.application.AuditService;
import com.fairtix.payments.infrastructure.PaymentRecordRepository;
import com.fairtix.payments.infrastructure.StripeWebhookEventRepository;
import com.fairtix.refunds.domain.RefundRequest;
import com.fairtix.refunds.domain.RefundStatus;
import com.fairtix.refunds.infrastructure.RefundRepository;
import com.stripe.Stripe;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Tag(name = "Webhooks", description = "Stripe event webhooks")
@RestController
@RequestMapping("/api/webhooks")
public class StripeWebhookController {

  private static final Logger log = LoggerFactory.getLogger(StripeWebhookController.class);
  private static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);
  /** The event types this controller handles, and the object each one carries. */
  private static final Map<String, Class<? extends StripeObject>> HANDLED_TYPES = Map.of(
      "payment_intent.succeeded", PaymentIntent.class,
      "charge.refunded", Charge.class,
      "payment_intent.payment_failed", PaymentIntent.class);

  @Value("${stripe.enabled:false}")
  private boolean stripeEnabled;

  @Value("${stripe.webhook-secret:}")
  private String webhookSecret;

  private final PaymentRecordRepository paymentRecordRepository;
  private final RefundRepository refundRepository;
  private final AuditService auditService;
  private final StripeWebhookEventRepository webhookEventRepository;

  public StripeWebhookController(PaymentRecordRepository paymentRecordRepository,
      RefundRepository refundRepository, AuditService auditService,
      StripeWebhookEventRepository webhookEventRepository) {
    this.paymentRecordRepository = paymentRecordRepository;
    this.refundRepository = refundRepository;
    this.auditService = auditService;
    this.webhookEventRepository = webhookEventRepository;
  }

  /**
   * Fails startup when Stripe is on without a signing secret. Without one, the endpoint
   * could only answer 200 and drop every event (Stripe does not retry a 200), so refunds,
   * failed payments and unmatched charges would go unrecorded without anyone noticing.
   */
  @PostConstruct
  void requireSecretWhenEnabled() {
    if (stripeEnabled && webhookSecret.isBlank()) {
      throw new IllegalStateException(
          "stripe.enabled=true but STRIPE_WEBHOOK_SECRET is blank; set the webhook signing secret");
    }
  }

  @Operation(summary = "Stripe webhook receiver",
      description = "Validates Stripe signature and processes payment events.")
  @PostMapping("/stripe")
  @Transactional
  public ResponseEntity<Void> handleStripeWebhook(
      @RequestBody String payload,
      @RequestHeader("Stripe-Signature") String sigHeader) {

    if (!stripeEnabled) {
      return ResponseEntity.ok().build();
    }

    Event event;
    try {
      event = Webhook.constructEvent(payload, sigHeader, webhookSecret);
    } catch (SignatureVerificationException e) {
      log.warn("Stripe webhook signature verification failed: {}", e.getMessage());
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
    }

    Class<? extends StripeObject> objectType = HANDLED_TYPES.get(event.getType());
    if (objectType == null) {
      log.debug("Unhandled Stripe webhook event type: {}", event.getType());
      return ResponseEntity.ok().build();
    }

    // An event whose object can't be read (logged at ERROR) is not recorded, so resending it
    // once the endpoint's API version is fixed still processes it.
    if (getObject(event, objectType).isEmpty()) {
      return ResponseEntity.ok().build();
    }

    // In this transaction, before the handling: a redelivered event finds its row and is not
    // processed again; if the handling fails, the row rolls back with it and Stripe's retry is
    // processed. The handlers audit with logInCurrentTransaction for the same reason.
    if (webhookEventRepository.insertIfAbsent(event.getId(), event.getType()) == 0) {
      log.info("Stripe webhook {} ({}) was already processed; redelivery ignored",
          event.getId(), event.getType());
      return ResponseEntity.ok().build();
    }

    switch (event.getType()) {
      case "payment_intent.succeeded" -> handlePaymentIntentSucceeded(event);
      case "charge.refunded" -> handleChargeRefunded(event);
      case "payment_intent.payment_failed" -> handlePaymentIntentFailed(event);
      default -> throw new IllegalStateException("Unreachable: " + event.getType());
    }

    return ResponseEntity.ok().build();
  }

  private void handlePaymentIntentSucceeded(Event event) {
    getObject(event, PaymentIntent.class).ifPresent(pi -> {
      String piId = pi.getId();
      boolean alreadyRecorded = paymentRecordRepository.findByTransactionId(piId).isPresent();
      if (!alreadyRecorded) {
        log.warn("payment_intent.succeeded for {} has no matching order — manual review needed", piId);
        auditService.logInCurrentTransaction(SYSTEM_ACTOR, "STRIPE_UNMATCHED_PAYMENT", "PAYMENT", null,
            "paymentIntentId=" + piId + " succeeded but no FairTix order found");
      }
    });
  }

  private void handleChargeRefunded(Event event) {
    getObject(event, Charge.class).ifPresent(charge -> {
      String piId = charge.getPaymentIntent();
      if (piId == null) return;
      var payment = paymentRecordRepository.findByTransactionId(piId);
      if (payment.isEmpty()) {
        // No order yet: either the intent never got one (a refund from
        // stripe_charges_without_order), or checkout is still creating it and the
        // payment record commits with the order. Put the refund on record so the
        // runbook check refunds_on_live_orders can match it to an order later.
        // refunded=true (Stripe's flag for a full refund) lets the check
        // stripe_charges_without_order drop the charge; keep it the last field.
        auditService.logInCurrentTransaction(SYSTEM_ACTOR, "STRIPE_REFUND_UNMATCHED", "PAYMENT", null,
            "Stripe refund with no payment record yet: stripe_intent=" + piId
                + ", charge=" + charge.getId()
                + ", amount_refunded_cents=" + amountRefundedCents(charge)
                + ", refunded=" + Boolean.TRUE.equals(charge.getRefunded()));
        return;
      }
      payment.ifPresent(record -> {
        List<RefundRequest> requests = refundRepository.findAllByOrderId(record.getOrderId());
        Optional<RefundRequest> active = requests.stream()
            .filter(r -> r.getStatus() == RefundStatus.APPROVED
                || r.getStatus() == RefundStatus.REQUESTED
                || r.getStatus() == RefundStatus.PENDING_MANUAL)
            .findFirst();
        if (active.isPresent()) {
          RefundRequest refundRequest = active.get();
          // Conditional update, not save(): finalise may hold this row and be
          // writing stripe_refund_id, which a full-row save would wipe.
          if (refundRepository.completeIfActive(refundRequest.getId(), Instant.now()) == 1) {
            auditService.logInCurrentTransaction(SYSTEM_ACTOR, "REFUND_COMPLETED", "REFUND", refundRequest.getId(),
                "Completed via Stripe charge.refunded for paymentIntent=" + piId);
          }
          return;
        }
        alertIfRefundUnaccounted(charge, piId, record.getOrderId(), requests);
      });
    });
  }

  /**
   * A refund with no active refund request behind it, typically made in the Stripe
   * Dashboard. (One landing while checkout is still creating the order usually
   * arrives before the payment record commits; that case is audited as
   * {@code STRIPE_REFUND_UNMATCHED} above.) The order is live but its money is
   * gone, so it is logged at ERROR and audited as {@code REFUND_ON_LIVE_ORDER}
   * for the runbook.
   *
   * <p>Stripe's {@code amount_refunded} is cumulative and this event can arrive
   * after our own refund flow has completed its request, so the refund is
   * unaccounted only when Stripe has refunded more than the completed requests
   * for this order add up to.
   */
  private void alertIfRefundUnaccounted(Charge charge, String piId, UUID orderId,
      List<RefundRequest> requests) {
    long refundedCents = amountRefundedCents(charge);
    long accountedCents = requests.stream()
        .filter(r -> r.getStatus() == RefundStatus.COMPLETED)
        .map(RefundRequest::getAmount)
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .movePointRight(2)
        .longValue();
    if (refundedCents <= accountedCents) {
      return;
    }
    log.error("Stripe refunded a live order with no refund request: stripe_intent={}, charge={},"
        + " order={}, amount_refunded_cents={}, accounted_cents={}",
        piId, charge.getId(), orderId, refundedCents, accountedCents);
    auditService.logInCurrentTransaction(SYSTEM_ACTOR, "REFUND_ON_LIVE_ORDER", "ORDER", orderId,
        "Stripe refund with no refund request: stripe_intent=" + piId
            + ", charge=" + charge.getId()
            + ", amount_refunded_cents=" + refundedCents
            + ", accounted_cents=" + accountedCents);
  }

  private void handlePaymentIntentFailed(Event event) {
    getObject(event, PaymentIntent.class).ifPresent(pi -> {
      String reason = pi.getLastPaymentError() != null
          ? pi.getLastPaymentError().getMessage() : "unknown";
      auditService.logInCurrentTransaction(SYSTEM_ACTOR, "STRIPE_PAYMENT_FAILED", "PAYMENT", null,
          "paymentIntentId=" + pi.getId() + " failed: " + reason);
    });
  }

  private static long amountRefundedCents(Charge charge) {
    return charge.getAmountRefunded() == null ? 0L : charge.getAmountRefunded();
  }

  /**
   * The event's object, for a type this controller handles. Empty when the
   * payload's API version doesn't match the library's (the endpoint's version
   * was changed in the Stripe Dashboard): every handler would then go silent
   * while answering 200, so that is logged at ERROR.
   */
  @SuppressWarnings("unchecked")
  private <T extends StripeObject> Optional<T> getObject(Event event, Class<T> type) {
    Optional<StripeObject> object = event.getDataObjectDeserializer().getObject();
    if (object.isEmpty()) {
      log.error("Stripe webhook {} ({}) could not be deserialized: event API version {},"
          + " library API version {}; the event was not processed",
          event.getId(), event.getType(), event.getApiVersion(), Stripe.API_VERSION);
    } else if (!type.isInstance(object.get())) {
      log.error("Stripe webhook {} ({}) carries a {}, expected a {}; the event was not processed",
          event.getId(), event.getType(), object.get().getClass().getSimpleName(),
          type.getSimpleName());
    }
    return object
        .filter(type::isInstance)
        .map(obj -> (T) obj);
  }
}
