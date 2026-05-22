package com.fairtix.payments.api;

import com.fairtix.auth.domain.CustomUserPrincipal;
import com.fairtix.organizations.application.OrgScoped;
import com.fairtix.organizations.domain.OrgPermission;
import com.fairtix.organizations.domain.Organization;
import com.fairtix.organizations.infrastructure.OrganizationRepository;
import com.fairtix.payments.application.StripeConnectService;
import com.fairtix.payments.dto.ConnectAccountStatusResponse;
import com.fairtix.payments.dto.ConnectDashboardResponse;
import com.fairtix.payments.dto.ConnectOnboardRequest;
import com.fairtix.payments.dto.ConnectOnboardResponse;
import com.fairtix.payments.dto.ConnectPayoutResponse;
import com.stripe.model.Balance;
import com.stripe.model.Payout;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Tag(name = "Stripe Connect",
    description = "Organizer onboarding to Stripe Connect Standard and connected-account dashboard data")
@RestController
@RequestMapping("/api/organizations/{orgId}/connect")
public class StripeConnectController {

  private final StripeConnectService connectService;
  private final OrganizationRepository organizationRepository;

  public StripeConnectController(StripeConnectService connectService,
                                 OrganizationRepository organizationRepository) {
    this.connectService = connectService;
    this.organizationRepository = organizationRepository;
  }

  @Operation(summary = "Start Stripe Connect onboarding",
      description = "Creates a Stripe Connect Standard account (if needed) and returns "
          + "a one-time Stripe-hosted onboarding URL.")
  @PostMapping("/onboard")
  @OrgScoped(OrgPermission.SETTINGS_WRITE)
  public ConnectOnboardResponse onboard(@PathVariable UUID orgId,
                                        @RequestBody(required = false) ConnectOnboardRequest req,
                                        @AuthenticationPrincipal CustomUserPrincipal principal) {
    if (!connectService.isEnabled()) {
      throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
          "Stripe is not enabled in this environment");
    }
    String country = req != null ? req.country() : null;
    String url = connectService.createOnboardingLink(orgId, principal.getUserId(), country);
    return new ConnectOnboardResponse(url);
  }

  @Operation(summary = "Refresh Connect account status from Stripe")
  @PostMapping("/refresh")
  @OrgScoped(OrgPermission.SETTINGS_WRITE)
  public ConnectAccountStatusResponse refresh(@PathVariable UUID orgId) {
    if (!connectService.isEnabled()) {
      throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
          "Stripe is not enabled in this environment");
    }
    Organization org = connectService.refreshAccountStatus(orgId);
    return ConnectAccountStatusResponse.from(org);
  }

  @Operation(summary = "Cached Connect account status")
  @GetMapping("/status")
  @OrgScoped(OrgPermission.PAYOUTS_READ)
  public ConnectAccountStatusResponse status(@PathVariable UUID orgId) {
    Organization org = organizationRepository.findById(orgId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Organization not found"));
    return ConnectAccountStatusResponse.from(org);
  }

  @Operation(summary = "Connect dashboard panel",
      description = "Balance, recent payouts, and account status for the organizer's payouts page.")
  @GetMapping("/dashboard")
  @OrgScoped(OrgPermission.PAYOUTS_READ)
  public ConnectDashboardResponse dashboard(@PathVariable UUID orgId,
                                            @RequestParam(defaultValue = "10") int payoutLimit) {
    Organization org = organizationRepository.findById(orgId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Organization not found"));
    if (!connectService.isEnabled() || org.getStripeConnectAccountId() == null) {
      return ConnectDashboardResponse.notConnected(ConnectAccountStatusResponse.from(org));
    }
    Balance balance = connectService.retrieveBalance(orgId);
    List<Payout> payouts = connectService.listPayouts(orgId, payoutLimit);
    return ConnectDashboardResponse.connected(
        ConnectAccountStatusResponse.from(org),
        balance,
        payouts.stream().map(ConnectPayoutResponse::from).toList());
  }
}
