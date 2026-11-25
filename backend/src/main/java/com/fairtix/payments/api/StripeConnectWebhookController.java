package com.fairtix.payments.api;

import com.fairtix.audit.application.AuditService;
import com.fairtix.organizations.domain.Organization;
import com.fairtix.organizations.infrastructure.OrganizationRepository;
import com.fairtix.organizations.application.PublicEndpoint;
import com.fairtix.payments.application.StripeConnectService;
import com.fairtix.payments.application.StripeDisputeService;
import com.fairtix.payments.infrastructure.StripeWebhookEventRepository;
import com.fairtix.reports.application.PayoutReportService;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Account;
import com.stripe.model.Dispute;
import com.stripe.model.Event;
import com.stripe.model.Payout;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.stripe.Stripe;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Stripe Connect webhook receiver. Signed with a separate secret than the
 * platform webhook controller — Stripe ships Connect events from a distinct
 * dashboard endpoint with its own signing key.
 */
@Tag(name = "Webhooks", description = "Stripe Connect webhooks (separate signing secret)")
@RestController
@RequestMapping("/api/webhooks")
@PublicEndpoint("Stripe Connect webhook receiver — auth via webhook signature, not org membership")
public class StripeConnectWebhookController {

  private static final Logger log = LoggerFactory.getLogger(StripeConnectWebhookController.class);
  private static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);
  /** Handled event types and the object each carries. */
  private static final Map<String, Class<? extends StripeObject>> OBJECT_TYPES = Map.of(
      "account.updated", Account.class,
      "payout.paid", Payout.class,
      "payout.failed", Payout.class,
      "charge.dispute.created", Dispute.class,
      "charge.dispute.closed", Dispute.class);
  /** Handled types that need only the event's account id, not its object. */
  private static final Set<String> ACCOUNT_ONLY_TYPES = Set.of("account.application.deauthorized");

  @Value("${stripe.enabled:false}")
  private boolean stripeEnabled;

  @Value("${stripe.connect-webhook-secret:}")
  private String webhookSecret;

  private final OrganizationRepository organizationRepository;
  private final StripeConnectService connectService;
  private final AuditService auditService;
  private final PayoutReportService payoutReportService;
  private final StripeWebhookEventRepository webhookEventRepository;
  private final StripeDisputeService disputeService;

  public StripeConnectWebhookController(OrganizationRepository organizationRepository,
                                        StripeConnectService connectService,
                                        AuditService auditService,
                                        PayoutReportService payoutReportService,
                                        StripeWebhookEventRepository webhookEventRepository,
                                        StripeDisputeService disputeService) {
    this.organizationRepository = organizationRepository;
    this.connectService = connectService;
    this.auditService = auditService;
    this.payoutReportService = payoutReportService;
    this.webhookEventRepository = webhookEventRepository;
    this.disputeService = disputeService;
  }

  /**
   * Fails startup when Stripe is on without a Connect signing secret, for the same reason
   * as {@code StripeWebhookController}: the endpoint would answer 200 and drop account,
   * payout and dispute events.
   */
  @PostConstruct
  void requireSecretWhenEnabled() {
    if (stripeEnabled && webhookSecret.isBlank()) {
      throw new IllegalStateException(
          "stripe.enabled=true but STRIPE_CONNECT_WEBHOOK_SECRET is blank; set the Connect webhook signing secret");
    }
  }

  @Operation(summary = "Stripe Connect webhook receiver",
      description = "Signed with STRIPE_CONNECT_WEBHOOK_SECRET. Handles account, payout, and dispute events.")
  @PostMapping("/stripe-connect")
  @Transactional
  public ResponseEntity<Void> handle(@RequestBody String payload,
                                     @RequestHeader(value = "Stripe-Signature", required = false) String sigHeader,
                                     jakarta.servlet.http.HttpServletRequest request) {
    if (!stripeEnabled) {
      return ResponseEntity.ok().build();
    }
    if (sigHeader == null || sigHeader.isBlank()) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
    }
    Event event;
    try {
      event = Webhook.constructEvent(payload, sigHeader, webhookSecret);
    } catch (SignatureVerificationException e) {
      log.warn("Stripe Connect webhook signature verification failed: {}", e.getMessage());
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
    }
    request.setAttribute(StripeWebhookFailureFilter.VERIFIED_ENDPOINT_ATTR, "connect");

    Class<? extends StripeObject> objectType = OBJECT_TYPES.get(event.getType());
    if (objectType == null && !ACCOUNT_ONLY_TYPES.contains(event.getType())) {
      log.debug("Unhandled Stripe Connect webhook type: {}", event.getType());
      return ResponseEntity.ok().build();
    }

    // As on the platform webhook: an event whose object can't be read (logged at ERROR) is
    // not recorded, so resending it once the endpoint's API version is fixed still processes it.
    if (objectType != null && getObject(event, objectType).isEmpty()) {
      return ResponseEntity.ok().build();
    }

    // Same ledger as the platform webhook (Stripe event ids are unique across both), in
    // this transaction and before the handling: a redelivered event finds its row and is
    // not processed again; if the handling fails, the row rolls back with it and Stripe's
    // retry is processed. The handlers audit with logInCurrentTransaction for the same reason.
    if (webhookEventRepository.insertIfAbsent(event.getId(), event.getType()) == 0) {
      log.info("Stripe Connect webhook {} ({}) was already processed; redelivery ignored",
          event.getId(), event.getType());
      return ResponseEntity.ok().build();
    }

    switch (event.getType()) {
      case "account.updated" -> handleAccountUpdated(event);
      case "account.application.deauthorized" -> handleAccountDeauthorized(event);
      case "payout.paid" -> handlePayout(event, "STRIPE_PAYOUT_PAID");
      case "payout.failed" -> handlePayoutFailed(event);
      case "charge.dispute.created" -> handleDisputeCreated(event);
      case "charge.dispute.closed" -> handleDisputeClosed(event);
      default -> throw new IllegalStateException("Unreachable: " + event.getType());
    }
    return ResponseEntity.ok().build();
  }

  private void handleAccountUpdated(Event event) {
    getObject(event, Account.class).ifPresent(account ->
        organizationRepository.findByStripeConnectAccountId(account.getId()).ifPresent(org -> {
          connectService.applyAccountState(org, account);
          organizationRepository.save(org);
          auditService.logInCurrentTransaction(SYSTEM_ACTOR, "STRIPE_ACCOUNT_UPDATED", "ORGANIZATION", org.getId(),
              "charges=" + org.isStripeChargesEnabled()
                  + " payouts=" + org.isStripePayoutsEnabled()
                  + " disabledReason=" + org.getStripeDisabledReason());
        }));
  }

  private void handleAccountDeauthorized(Event event) {
    String accountId = event.getAccount();
    if (accountId == null) return;
    organizationRepository.findByStripeConnectAccountId(accountId).ifPresent(org -> {
      // Soft-disable: clear flags so publish-gate trips. Preserve historical
      // event/order data. Org can re-onboard if they reconnect.
      org.setStripeChargesEnabled(false);
      org.setStripePayoutsEnabled(false);
      org.setStripeDisabledReason("account_deauthorized");
      organizationRepository.save(org);
      auditService.logInCurrentTransaction(SYSTEM_ACTOR, "STRIPE_ACCOUNT_DEAUTHORIZED", "ORGANIZATION", org.getId(),
          "stripe_account=" + accountId);
    });
  }

  private void handlePayout(Event event, String auditAction) {
    getObject(event, Payout.class).ifPresent(payout -> {
      Organization org = orgForEvent(event);
      if (org == null) return;
      payoutReportService.syncPayout(org.getId(), payout);
      auditService.logInCurrentTransaction(SYSTEM_ACTOR, auditAction, "ORGANIZATION", org.getId(),
          "payout=" + payout.getId() + " amount=" + payout.getAmount()
              + " currency=" + payout.getCurrency()
              + " status=" + payout.getStatus());
    });
  }

  private void handlePayoutFailed(Event event) {
    getObject(event, Payout.class).ifPresent(payout -> {
      Organization org = orgForEvent(event);
      if (org == null) return;
      payoutReportService.syncPayout(org.getId(), payout);
      auditService.logInCurrentTransaction(SYSTEM_ACTOR, "STRIPE_PAYOUT_FAILED", "ORGANIZATION", org.getId(),
          "payout=" + payout.getId()
              + " failureCode=" + payout.getFailureCode()
              + " failureMessage=" + payout.getFailureMessage());
      log.error("Stripe payout {} failed for org {}: {} ({})",
          payout.getId(), org.getId(), payout.getFailureMessage(), payout.getFailureCode());
    });
  }

  /**
   * Disputes on direct charges, which are raised on the connected account. FairTix creates
   * destination charges, whose disputes reach the platform webhook instead (issue #343).
   */
  private void handleDisputeCreated(Event event) {
    getObject(event, Dispute.class).ifPresent(dispute -> {
      Organization org = orgForEvent(event);
      if (org == null) {
        disputeService.recordUnmatchedConnectDispute(dispute, event.getAccount(), false);
        return;
      }
      disputeService.recordDispute(org, dispute);
    });
  }

  /** Audit only; the count and the freeze flag are left as they are. */
  private void handleDisputeClosed(Event event) {
    getObject(event, Dispute.class).ifPresent(dispute -> {
      Organization org = orgForEvent(event);
      if (org == null) {
        disputeService.recordUnmatchedConnectDispute(dispute, event.getAccount(), true);
        return;
      }
      disputeService.recordDisputeClosed(org, dispute);
    });
  }

  private Organization orgForEvent(Event event) {
    String accountId = event.getAccount();
    if (accountId == null) return null;
    return organizationRepository.findByStripeConnectAccountId(accountId).orElse(null);
  }

  /**
   * The event's object, if it is a {@code type}. Empty when the payload's API version doesn't
   * match the library's (the endpoint's version was changed in the Stripe Dashboard): every
   * handler would then go silent while answering 200, so that is logged at ERROR, as on the
   * platform webhook.
   */
  @SuppressWarnings("unchecked")
  private <T extends StripeObject> Optional<T> getObject(Event event, Class<T> type) {
    Optional<StripeObject> object = event.getDataObjectDeserializer().getObject();
    if (object.isEmpty()) {
      log.error("Stripe Connect webhook {} ({}) could not be deserialized: event API version {},"
          + " library API version {}; the event was not processed",
          event.getId(), event.getType(), event.getApiVersion(), Stripe.API_VERSION);
    } else if (!type.isInstance(object.get())) {
      log.error("Stripe Connect webhook {} ({}) carries a {}, expected a {}; the event was not processed",
          event.getId(), event.getType(), object.get().getClass().getSimpleName(),
          type.getSimpleName());
    }
    return object
        .filter(type::isInstance)
        .map(obj -> (T) obj);
  }
}
