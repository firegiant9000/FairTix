package com.fairtix.organizations.api;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fairtix.auth.domain.CustomUserPrincipal;
import com.fairtix.organizations.application.OrgSignupService;
import com.fairtix.organizations.domain.Organization;
import com.fairtix.organizations.dto.SignupDtos.OrganizationReviewResponse;
import com.fairtix.organizations.dto.SignupDtos.OverrideCapRequest;
import com.fairtix.organizations.dto.SignupDtos.RejectRequest;

/**
 * Phase H admin approval queue (M2-24) + sales-cap overrides (M2-25).
 *
 * <p>All endpoints require {@code ROLE_ADMIN} — these are platform-admin
 * actions, not org-scoped. Gated under {@code /api/admin/**} so the existing
 * SecurityConfig allow-list applies.
 */
@RestController
@RequestMapping("/api/admin/organizations")
@PreAuthorize("hasRole('ADMIN')")
public class AdminOrgApprovalController {

  private final OrgSignupService signupService;

  public AdminOrgApprovalController(OrgSignupService signupService) {
    this.signupService = signupService;
  }

  @GetMapping("/review-queue")
  public List<OrganizationReviewResponse> reviewQueue() {
    return signupService.listForReview().stream()
        .map(OrganizationReviewResponse::from)
        .toList();
  }

  @PostMapping("/{orgId}/approve")
  public OrganizationReviewResponse approve(@PathVariable UUID orgId,
                                            @AuthenticationPrincipal CustomUserPrincipal principal) {
    Organization org = signupService.approve(orgId, principal.getUserId());
    return OrganizationReviewResponse.from(org);
  }

  @PostMapping("/{orgId}/reject")
  public OrganizationReviewResponse reject(@PathVariable UUID orgId,
                                           @RequestBody RejectRequest req,
                                           @AuthenticationPrincipal CustomUserPrincipal principal) {
    Organization org = signupService.reject(orgId, principal.getUserId(), req.reason());
    return OrganizationReviewResponse.from(org);
  }

  @PostMapping("/{orgId}/sales-cap-override")
  public ResponseEntity<OrganizationReviewResponse> overrideCap(
      @PathVariable UUID orgId,
      @RequestBody OverrideCapRequest req,
      @AuthenticationPrincipal CustomUserPrincipal principal) {
    Organization org = signupService.overrideSalesCap(
        orgId, principal.getUserId(), req.dailySalesCapCents(), req.validUntil());
    return ResponseEntity.ok(OrganizationReviewResponse.from(org));
  }
}
