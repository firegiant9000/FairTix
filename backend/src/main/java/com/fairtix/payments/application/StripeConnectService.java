package com.fairtix.payments.application;

import com.fairtix.audit.application.AuditService;
import com.fairtix.common.ResourceNotFoundException;
import com.fairtix.organizations.domain.Organization;
import com.fairtix.organizations.infrastructure.OrganizationRepository;
import com.stripe.exception.StripeException;
import com.stripe.model.Account;
import com.stripe.model.AccountLink;
import com.stripe.model.Balance;
import com.stripe.model.Payout;
import com.stripe.net.RequestOptions;
import com.stripe.param.AccountCreateParams;
import com.stripe.param.AccountLinkCreateParams;
import com.stripe.param.PayoutListParams;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Stripe Connect Standard onboarding and account state sync.
 *
 * Standard accounts (not Express) — Stripe hosts the dashboard; organizers
 * have their own Stripe login. We only mirror the subset of account state
 * needed to gate publishing, drive the payouts panel, and respect freezes.
 */
@Service
public class StripeConnectService {

  private static final Logger log = LoggerFactory.getLogger(StripeConnectService.class);

  // M2 scope: US-only. Multi-currency / cross-border deferred per roadmap §5.
  private static final String SUPPORTED_COUNTRY = "US";

  @Value("${stripe.enabled:false}")
  private boolean stripeEnabled;

  @Value("${fairtix.connect.return-url:http://localhost:3000/organizer/connect/return}")
  private String returnUrl;

  @Value("${fairtix.connect.refresh-url:http://localhost:3000/organizer/connect/refresh}")
  private String refreshUrl;

  private final OrganizationRepository organizationRepository;
  private final AuditService auditService;

  public StripeConnectService(OrganizationRepository organizationRepository,
                              AuditService auditService) {
    this.organizationRepository = organizationRepository;
    this.auditService = auditService;
  }

  @PostConstruct
  void warnIfMisconfigured() {
    if (stripeEnabled && (returnUrl.isBlank() || refreshUrl.isBlank())) {
      log.warn("Stripe Connect enabled but return/refresh URLs are blank — onboarding will fail");
    }
  }

  public boolean isEnabled() {
    return stripeEnabled;
  }

  @Transactional
  public String createOnboardingLink(UUID orgId, UUID actorUserId, String country) {
    if (!stripeEnabled) {
      throw new IllegalStateException("Stripe is not enabled in this environment");
    }
    Organization org = organizationRepository.findById(orgId)
        .orElseThrow(() -> new ResourceNotFoundException("Organization not found: " + orgId));

    String resolvedCountry = (country == null || country.isBlank())
        ? SUPPORTED_COUNTRY : country.toUpperCase();
    if (!SUPPORTED_COUNTRY.equals(resolvedCountry)) {
      throw new IllegalArgumentException(
          "Only US-based connected accounts are supported in this release");
    }

    try {
      String accountId = org.getStripeConnectAccountId();
      if (accountId == null) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("fairtix_org_id", org.getId().toString());
        metadata.put("fairtix_org_slug", org.getSlug());

        AccountCreateParams params = AccountCreateParams.builder()
            .setType(AccountCreateParams.Type.STANDARD)
            .setCountry(resolvedCountry)
            .setEmail(org.getContactEmail())
            .putAllMetadata(metadata)
            .build();
        Account account = Account.create(params);
        accountId = account.getId();
        org.setStripeConnectAccountId(accountId);
        org.setStripeConnectCountry(resolvedCountry);
        organizationRepository.save(org);
        auditService.log(actorUserId, "STRIPE_CONNECT_ACCOUNT_CREATED", "ORGANIZATION",
            org.getId(), "stripe_account=" + accountId);
      }

      AccountLinkCreateParams linkParams = AccountLinkCreateParams.builder()
          .setAccount(accountId)
          .setType(AccountLinkCreateParams.Type.ACCOUNT_ONBOARDING)
          .setReturnUrl(returnUrl + "?org=" + org.getSlug())
          .setRefreshUrl(refreshUrl + "?org=" + org.getSlug())
          .build();
      AccountLink link = AccountLink.create(linkParams);
      return link.getUrl();
    } catch (StripeException e) {
      throw new RuntimeException(
          "Failed to create Stripe Connect onboarding link: " + e.getMessage(), e);
    }
  }

  @Transactional
  public Organization refreshAccountStatus(UUID orgId) {
    Organization org = organizationRepository.findById(orgId)
        .orElseThrow(() -> new ResourceNotFoundException("Organization not found: " + orgId));
    if (org.getStripeConnectAccountId() == null) {
      return org;
    }
    try {
      Account account = Account.retrieve(org.getStripeConnectAccountId());
      applyAccountState(org, account);
      return organizationRepository.save(org);
    } catch (StripeException e) {
      throw new RuntimeException(
          "Failed to refresh Stripe Connect account state: " + e.getMessage(), e);
    }
  }

  public void applyAccountState(Organization org, Account account) {
    boolean wasCharges = org.isStripeChargesEnabled();
    org.setStripeChargesEnabled(Boolean.TRUE.equals(account.getChargesEnabled()));
    org.setStripePayoutsEnabled(Boolean.TRUE.equals(account.getPayoutsEnabled()));
    org.setStripeDetailsSubmitted(Boolean.TRUE.equals(account.getDetailsSubmitted()));
    if (account.getRequirements() != null) {
      org.setStripeDisabledReason(account.getRequirements().getDisabledReason());
      org.setStripeRequirementsJson(account.getRequirements().toJson());
    }
    if (!wasCharges && org.isStripeChargesEnabled() && org.getStripeConnectedAt() == null) {
      org.setStripeConnectedAt(Instant.now());
    }
  }

  public Balance retrieveBalance(UUID orgId) {
    Organization org = organizationRepository.findById(orgId)
        .orElseThrow(() -> new ResourceNotFoundException("Organization not found: " + orgId));
    if (org.getStripeConnectAccountId() == null) {
      return null;
    }
    try {
      RequestOptions reqOpts = RequestOptions.builder()
          .setStripeAccount(org.getStripeConnectAccountId())
          .build();
      return Balance.retrieve(Map.of(), reqOpts);
    } catch (StripeException e) {
      throw new RuntimeException("Failed to retrieve Stripe balance: " + e.getMessage(), e);
    }
  }

  public List<Payout> listPayouts(UUID orgId, int limit) {
    Organization org = organizationRepository.findById(orgId)
        .orElseThrow(() -> new ResourceNotFoundException("Organization not found: " + orgId));
    if (org.getStripeConnectAccountId() == null) {
      return List.of();
    }
    try {
      RequestOptions reqOpts = RequestOptions.builder()
          .setStripeAccount(org.getStripeConnectAccountId())
          .build();
      PayoutListParams params = PayoutListParams.builder()
          .setLimit((long) Math.min(Math.max(limit, 1), 100))
          .build();
      return Payout.list(params, reqOpts).getData();
    } catch (StripeException e) {
      throw new RuntimeException("Failed to list Stripe payouts: " + e.getMessage(), e);
    }
  }
}
