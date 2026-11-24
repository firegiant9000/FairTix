package com.fairtix.payments.application;

import com.fairtix.audit.application.AuditService;
import com.fairtix.organizations.domain.Organization;
import com.fairtix.organizations.infrastructure.OrganizationRepository;
import com.fairtix.payments.application.StripePaymentService.ConnectRouting;
import com.stripe.model.Dispute;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Records a new Stripe dispute against the organizer: it adds one to the org's
 * {@code dispute_count} (any amount; never decremented automatically, and it holds the org at
 * the lower sales cap until reviewed) and, at or above the threshold, sets the org's
 * {@code stripe_payouts_frozen} flag. The flag is advisory: it is shown in the Connect status,
 * but nothing holds the connected account's payouts in Stripe. A closed dispute
 * ({@code charge.dispute.closed}) is audited as {@code STRIPE_DISPUTE_CLOSED} with its status
 * and changes neither the count nor the flag.
 *
 * <p>FairTix creates destination charges ({@code on_behalf_of} +
 * {@code transfer_data.destination}). Stripe raises disputes on those against the platform
 * account and sends them to the platform webhook with no {@code account} field (issue #343),
 * so the organizer is found from the disputed PaymentIntent. The Connect webhook's path, which
 * finds the org from the event's account, is kept for direct charges.
 *
 * <p>Callers run inside the webhook's transaction, after the event-ledger insert; audits use
 * {@code logInCurrentTransaction} so they commit or roll back with the ledger row.
 */
@Service
public class StripeDisputeService {

  private static final Logger log = LoggerFactory.getLogger(StripeDisputeService.class);
  private static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);

  @Value("${fairtix.connect.dispute-freeze-threshold-cents:5000}")
  private long disputeFreezeThresholdCents;

  private final OrganizationRepository organizationRepository;
  private final StripePaymentService stripePaymentService;
  private final AuditService auditService;

  public StripeDisputeService(OrganizationRepository organizationRepository,
      StripePaymentService stripePaymentService, AuditService auditService) {
    this.organizationRepository = organizationRepository;
    this.stripePaymentService = stripePaymentService;
    this.auditService = auditService;
  }

  /**
   * A dispute from the platform webhook. The org is the intent's destination account's, or,
   * failing that, the one in its {@code fairtix_org_id} metadata. A dispute with no org behind
   * it (a payment not routed through Connect) is audited as {@code STRIPE_DISPUTE_UNMATCHED}.
   */
  public void recordPlatformDispute(Dispute dispute) {
    Optional<Organization> org = platformOrgFor(dispute);
    if (org.isEmpty()) {
      auditUnmatched(dispute, "");
      return;
    }
    recordDispute(org.get(), dispute);
  }

  /**
   * A dispute closed (won, lost, warning_closed ...), from the platform webhook. Audit only:
   * the dispute count and the freeze flag are left as they are.
   */
  public void recordPlatformDisputeClosed(Dispute dispute) {
    Optional<Organization> org = platformOrgFor(dispute);
    if (org.isEmpty()) {
      auditUnmatched(dispute, " status=" + dispute.getStatus());
      return;
    }
    recordDisputeClosed(org.get(), dispute);
  }

  /** Audits a closed dispute with its status; changes nothing on the org. */
  public void recordDisputeClosed(Organization org, Dispute dispute) {
    auditService.logInCurrentTransaction(SYSTEM_ACTOR, "STRIPE_DISPUTE_CLOSED", "ORGANIZATION", org.getId(),
        detail(dispute) + " status=" + dispute.getStatus());
  }

  /**
   * Counts the dispute on the org (any amount), sets the advisory freeze flag at or above the
   * threshold, and audits the dispute either way.
   */
  public void recordDispute(Organization org, Dispute dispute) {
    organizationRepository.incrementDisputeCount(org.getId(), Instant.now());
    long amount = amountCents(dispute);
    String detail = detail(dispute);
    if (amount >= disputeFreezeThresholdCents) {
      organizationRepository.freezePayouts(org.getId(), Instant.now());
      auditService.logInCurrentTransaction(SYSTEM_ACTOR, "STRIPE_DISPUTE_FROZE_PAYOUTS", "ORGANIZATION", org.getId(),
          detail + " threshold=" + disputeFreezeThresholdCents);
      log.warn("Froze payouts for org {} after dispute {} ({} cents)", org.getId(), dispute.getId(), amount);
    } else {
      auditService.logInCurrentTransaction(SYSTEM_ACTOR, "STRIPE_DISPUTE_CREATED", "ORGANIZATION", org.getId(), detail);
    }
  }

  private Optional<Organization> platformOrgFor(Dispute dispute) {
    String paymentIntentId = dispute.getPaymentIntent();
    return paymentIntentId == null
        ? Optional.empty()
        : orgFor(stripePaymentService.connectRoutingOf(paymentIntentId));
  }

  private void auditUnmatched(Dispute dispute, String suffix) {
    log.warn("Stripe dispute {} on payment intent {} has no organizer",
        dispute.getId(), dispute.getPaymentIntent());
    auditService.logInCurrentTransaction(SYSTEM_ACTOR, "STRIPE_DISPUTE_UNMATCHED", "PAYMENT", null,
        detail(dispute) + " stripe_intent=" + dispute.getPaymentIntent() + suffix);
  }

  private Optional<Organization> orgFor(ConnectRouting routing) {
    if (routing.destinationAccountId() != null) {
      Optional<Organization> byAccount =
          organizationRepository.findByStripeConnectAccountId(routing.destinationAccountId());
      if (byAccount.isPresent()) {
        return byAccount;
      }
    }
    if (routing.organizationId() != null) {
      try {
        return organizationRepository.findById(UUID.fromString(routing.organizationId()));
      } catch (IllegalArgumentException notAUuid) {
        return Optional.empty();
      }
    }
    return Optional.empty();
  }

  private static long amountCents(Dispute dispute) {
    return dispute.getAmount() == null ? 0L : dispute.getAmount();
  }

  private static String detail(Dispute dispute) {
    return "dispute=" + dispute.getId()
        + " amount=" + amountCents(dispute)
        + " currency=" + dispute.getCurrency()
        + " reason=" + dispute.getReason();
  }
}
