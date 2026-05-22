package com.fairtix.organizations.api;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fairtix.auth.domain.CustomUserPrincipal;
import com.fairtix.organizations.application.OrgSalesCapService;
import com.fairtix.organizations.application.OrgSignupService;
import com.fairtix.organizations.domain.Organization;
import com.fairtix.organizations.dto.SignupDtos.OrganizationReviewResponse;
import com.fairtix.organizations.dto.SignupDtos.SalesCapStatusResponse;
import com.fairtix.organizations.dto.SignupDtos.SubmitForReviewRequest;
import com.fairtix.organizations.infrastructure.OrganizationRepository;
import com.fairtix.organizations.infrastructure.OrgSalesLedgerRepository;

/**
 * Organizer-side endpoints for the Phase H signup wizard.
 * The OWNER who created the org submits the wizard for admin review here.
 */
@RestController
@RequestMapping("/api/organizations")
public class OrgSignupController {

  private final OrgSignupService signupService;
  private final OrgSalesCapService salesCapService;
  private final OrganizationRepository organizations;
  private final OrgSalesLedgerRepository ledger;

  public OrgSignupController(OrgSignupService signupService,
                             OrgSalesCapService salesCapService,
                             OrganizationRepository organizations,
                             OrgSalesLedgerRepository ledger) {
    this.signupService = signupService;
    this.salesCapService = salesCapService;
    this.organizations = organizations;
    this.ledger = ledger;
  }

  @PostMapping("/{orgId}/submit-for-review")
  @PreAuthorize("isAuthenticated()")
  public OrganizationReviewResponse submit(@PathVariable UUID orgId,
                                           @RequestBody SubmitForReviewRequest req,
                                           @AuthenticationPrincipal CustomUserPrincipal principal) {
    Organization org = signupService.submitForReview(orgId, principal.getUserId(), req);
    return OrganizationReviewResponse.from(org);
  }

  @org.springframework.web.bind.annotation.GetMapping("/{orgId}/sales-cap")
  @PreAuthorize("isAuthenticated()")
  public SalesCapStatusResponse salesCap(@PathVariable UUID orgId) {
    Organization org = organizations.findById(orgId)
        .orElseThrow(() -> new com.fairtix.common.ResourceNotFoundException(
            "Organization not found: " + orgId));
    long cap = salesCapService.resolveCapCents(org);
    long used = ledger.sumSince(orgId, Instant.now().minus(Duration.ofHours(24)));
    long remaining = cap == OrgSalesCapService.UNLIMITED ? -1L : Math.max(0L, cap - used);
    return new SalesCapStatusResponse(
        cap, used, remaining,
        org.getDailySalesCapCents(),
        org.getPlanOverridesUntil(),
        org.getSuccessfulPayoutCycles(),
        org.getDisputeCount());
  }
}
