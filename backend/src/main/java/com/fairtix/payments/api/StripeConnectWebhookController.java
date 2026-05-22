package com.fairtix.payments.api;

import com.fairtix.audit.application.AuditService;
import com.fairtix.organizations.domain.Organization;
import com.fairtix.organizations.infrastructure.OrganizationRepository;
import com.fairtix.organizations.application.PublicEndpoint;
import com.fairtix.payments.application.StripeConnectService;
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

import java.util.Optional;
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

  @Value("${stripe.enabled:false}")
  private boolean stripeEnabled;

  @Value("${stripe.connect-webhook-secret:}")
  private String webhookSecret;

  @Value("${fairtix.connect.dispute-freeze-threshold-cents:5000}")
  private long disputeFreezeThresholdCents;

  private final OrganizationRepository organizationRepository;
  private final StripeConnectService connectService;
  private final AuditService auditService;
  private final PayoutReportService payoutReportService;

  public StripeConnectWebhookController(OrganizationRepository organizationRepository,
                                        StripeConnectService connectService,
                                        AuditService auditService,
                                        PayoutReportService payoutReportService) {
    this.organizationRepository = organizationRepository;
    this.connectService = connectService;
    this.auditService = auditService;
    this.payoutReportService = payoutReportService;
  }

  @PostConstruct
  void warnIfMisconfigured() {
    if (stripeEnabled && webhookSecret.isBlank()) {
      log.warn("Stripe is enabled but STRIPE_CONNECT_WEBHOOK_SECRET is blank — Connect webhooks rejected");
    }
  }

  @Operation(summary = "Stripe Connect webhook receiver",
      description = "Signed with STRIPE_CONNECT_WEBHOOK_SECRET. Handles account, payout, and dispute events.")
  @PostMapping("/stripe-connect")
  @Transactional
  public ResponseEntity<Void> handle(@RequestBody String payload,
                                     @RequestHeader("Stripe-Signature") String sigHeader) {
    if (!stripeEnabled || webhookSecret.isBlank()) {
      return ResponseEntity.ok().build();
    }
    Event event;
    try {
      event = Webhook.constructEvent(payload, sigHeader, webhookSecret);
    } catch (SignatureVerificationException e) {
      log.warn("Stripe Connect webhook signature verification failed: {}", e.getMessage());
      return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
    }

    switch (event.getType()) {
      case "account.updated" -> handleAccountUpdated(event);
      case "account.application.deauthorized" -> handleAccountDeauthorized(event);
      case "payout.paid" -> handlePayout(event, "STRIPE_PAYOUT_PAID");
      case "payout.failed" -> handlePayoutFailed(event);
      case "charge.dispute.created" -> handleDisputeCreated(event);
      default -> log.debug("Unhandled Stripe Connect webhook type: {}", event.getType());
    }
    return ResponseEntity.ok().build();
  }

  private void handleAccountUpdated(Event event) {
    getObject(event, Account.class).ifPresent(account ->
        organizationRepository.findByStripeConnectAccountId(account.getId()).ifPresent(org -> {
          connectService.applyAccountState(org, account);
          organizationRepository.save(org);
          auditService.log(SYSTEM_ACTOR, "STRIPE_ACCOUNT_UPDATED", "ORGANIZATION", org.getId(),
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
      auditService.log(SYSTEM_ACTOR, "STRIPE_ACCOUNT_DEAUTHORIZED", "ORGANIZATION", org.getId(),
          "stripe_account=" + accountId);
    });
  }

  private void handlePayout(Event event, String auditAction) {
    getObject(event, Payout.class).ifPresent(payout -> {
      Organization org = orgForEvent(event);
      if (org == null) return;
      payoutReportService.syncPayout(org.getId(), payout);
      auditService.log(SYSTEM_ACTOR, auditAction, "ORGANIZATION", org.getId(),
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
      auditService.log(SYSTEM_ACTOR, "STRIPE_PAYOUT_FAILED", "ORGANIZATION", org.getId(),
          "payout=" + payout.getId()
              + " failureCode=" + payout.getFailureCode()
              + " failureMessage=" + payout.getFailureMessage());
      log.error("Stripe payout {} failed for org {}: {} ({})",
          payout.getId(), org.getId(), payout.getFailureMessage(), payout.getFailureCode());
    });
  }

  private void handleDisputeCreated(Event event) {
    getObject(event, Dispute.class).ifPresent(dispute -> {
      Organization org = orgForEvent(event);
      if (org == null) return;
      long amount = dispute.getAmount() == null ? 0L : dispute.getAmount();
      String detail = "dispute=" + dispute.getId()
          + " amount=" + amount
          + " currency=" + dispute.getCurrency()
          + " reason=" + dispute.getReason();
      if (amount >= disputeFreezeThresholdCents) {
        org.setStripePayoutsFrozen(true);
        organizationRepository.save(org);
        auditService.log(SYSTEM_ACTOR, "STRIPE_DISPUTE_FROZE_PAYOUTS", "ORGANIZATION", org.getId(),
            detail + " threshold=" + disputeFreezeThresholdCents);
        log.warn("Froze payouts for org {} after dispute {} ({} cents)",
            org.getId(), dispute.getId(), amount);
      } else {
        auditService.log(SYSTEM_ACTOR, "STRIPE_DISPUTE_CREATED", "ORGANIZATION", org.getId(), detail);
      }
    });
  }

  private Organization orgForEvent(Event event) {
    String accountId = event.getAccount();
    if (accountId == null) return null;
    return organizationRepository.findByStripeConnectAccountId(accountId).orElse(null);
  }

  @SuppressWarnings("unchecked")
  private <T extends StripeObject> Optional<T> getObject(Event event, Class<T> type) {
    return event.getDataObjectDeserializer().getObject()
        .filter(type::isInstance)
        .map(obj -> (T) obj);
  }
}
