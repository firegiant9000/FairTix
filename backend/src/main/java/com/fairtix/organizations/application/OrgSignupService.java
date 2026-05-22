package com.fairtix.organizations.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fairtix.audit.application.AuditService;
import com.fairtix.common.ResourceNotFoundException;
import com.fairtix.organizations.domain.OrgRole;
import com.fairtix.organizations.domain.Organization;
import com.fairtix.organizations.domain.OrganizationMember;
import com.fairtix.organizations.domain.OrganizationStatus;
import com.fairtix.organizations.dto.SignupDtos.SubmitForReviewRequest;
import com.fairtix.organizations.infrastructure.OrganizationMemberRepository;
import com.fairtix.organizations.infrastructure.OrganizationRepository;

/**
 * Phase H (M2-24) signup wizard + admin approval queue.
 *
 * <p>Lifecycle:
 * <pre>
 *   POST /api/organizations            → PENDING        (existing flow)
 *   POST /api/organizations/{id}/submit→ PENDING_REVIEW (this service)
 *   POST /api/admin/organizations/{id}/approve → ACTIVE
 *   POST /api/admin/organizations/{id}/reject  → REJECTED
 * </pre>
 *
 * <p>Approval is the gate for publishing events at the controller level; this
 * service is the single writer for status transitions out of the wizard.
 */
@Service
public class OrgSignupService {

  private final OrganizationRepository organizations;
  private final OrganizationMemberRepository members;
  private final EinCipher einCipher;
  private final AuditService auditService;

  public OrgSignupService(OrganizationRepository organizations,
                          OrganizationMemberRepository members,
                          EinCipher einCipher,
                          AuditService auditService) {
    this.organizations = organizations;
    this.members = members;
    this.einCipher = einCipher;
    this.auditService = auditService;
  }

  /**
   * Called by an OWNER of {@code orgId} after Stripe Connect onboarding is
   * complete. Persists the wizard details and moves the org into
   * {@code PENDING_REVIEW}. EIN is encrypted before write.
   */
  @Transactional
  public Organization submitForReview(UUID orgId, UUID actorUserId, SubmitForReviewRequest req) {
    requireOwner(actorUserId, orgId);
    Organization org = organizations.findById(orgId)
        .orElseThrow(() -> new ResourceNotFoundException("Organization not found: " + orgId));
    if (org.getStatus() == OrganizationStatus.ACTIVE) {
      throw new IllegalStateException("Organization is already active");
    }
    if (org.getStatus() == OrganizationStatus.REJECTED) {
      throw new IllegalStateException("Organization application was rejected; contact support");
    }
    requireNonBlank("legalName", req.legalName());
    requireNonBlank("addressLine1", req.addressLine1());
    requireNonBlank("addressCity", req.addressCity());
    requireNonBlank("addressRegion", req.addressRegion());
    requireNonBlank("addressPostalCode", req.addressPostalCode());
    requireNonBlank("addressCountry", req.addressCountry());
    requireNonBlank("primaryContactName", req.primaryContactName());
    // Hard-block non-US accounts in M2 per plan; multi-currency is M5.
    if (!"US".equalsIgnoreCase(req.addressCountry().trim())) {
      throw new IllegalArgumentException("Only US-based organizations are supported in this release");
    }

    org.setLegalName(req.legalName().trim());
    org.setDba(blankToNull(req.dba()));
    org.setAddressLine1(req.addressLine1().trim());
    org.setAddressLine2(blankToNull(req.addressLine2()));
    org.setAddressCity(req.addressCity().trim());
    org.setAddressRegion(req.addressRegion().trim());
    org.setAddressPostalCode(req.addressPostalCode().trim());
    org.setAddressCountry(req.addressCountry().trim().toUpperCase());
    org.setPrimaryContactName(req.primaryContactName().trim());
    org.setPrimaryContactPhone(blankToNull(req.primaryContactPhone()));
    org.setReferredBy(blankToNull(req.referredBy()));
    if (req.ein() != null && !req.ein().isBlank()) {
      org.setEinEncrypted(einCipher.encrypt(req.ein().trim()));
    }

    org.setStatus(OrganizationStatus.PENDING_REVIEW);
    org.setSubmittedForReviewAt(Instant.now());
    organizations.save(org);

    auditService.log(actorUserId, "ORG_SUBMITTED_FOR_REVIEW", "ORGANIZATION", orgId,
        "legalName=" + org.getLegalName() + " referredBy=" + org.getReferredBy());
    return org;
  }

  @Transactional
  public Organization approve(UUID orgId, UUID adminUserId) {
    Organization org = organizations.findById(orgId)
        .orElseThrow(() -> new ResourceNotFoundException("Organization not found: " + orgId));
    if (org.getStatus() != OrganizationStatus.PENDING_REVIEW) {
      throw new IllegalStateException(
          "Only organizations in PENDING_REVIEW can be approved (was " + org.getStatus() + ")");
    }
    org.setStatus(OrganizationStatus.ACTIVE);
    org.setReviewedAt(Instant.now());
    org.setReviewedByUserId(adminUserId);
    org.setRejectionReason(null);
    organizations.save(org);
    auditService.log(adminUserId, "ORG_APPROVED", "ORGANIZATION", orgId, "");
    return org;
  }

  @Transactional
  public Organization reject(UUID orgId, UUID adminUserId, String reason) {
    Organization org = organizations.findById(orgId)
        .orElseThrow(() -> new ResourceNotFoundException("Organization not found: " + orgId));
    if (org.getStatus() != OrganizationStatus.PENDING_REVIEW) {
      throw new IllegalStateException(
          "Only organizations in PENDING_REVIEW can be rejected (was " + org.getStatus() + ")");
    }
    requireNonBlank("reason", reason);
    org.setStatus(OrganizationStatus.REJECTED);
    org.setReviewedAt(Instant.now());
    org.setReviewedByUserId(adminUserId);
    org.setRejectionReason(reason.trim());
    organizations.save(org);
    auditService.log(adminUserId, "ORG_REJECTED", "ORGANIZATION", orgId, "reason=" + reason.trim());
    return org;
  }

  @Transactional
  public Organization overrideSalesCap(UUID orgId, UUID adminUserId,
                                       Long capCents, Instant until) {
    Organization org = organizations.findById(orgId)
        .orElseThrow(() -> new ResourceNotFoundException("Organization not found: " + orgId));
    if (until == null || until.isBefore(Instant.now())) {
      throw new IllegalArgumentException("validUntil must be in the future");
    }
    org.setDailySalesCapCents(capCents);
    org.setPlanOverridesUntil(until);
    organizations.save(org);
    auditService.log(adminUserId, "ORG_SALES_CAP_OVERRIDE", "ORGANIZATION", orgId,
        "capCents=" + capCents + " until=" + until);
    return org;
  }

  public List<Organization> listForReview() {
    return organizations.findAll().stream()
        .filter(o -> o.getStatus() == OrganizationStatus.PENDING_REVIEW)
        .sorted((a, b) -> {
          Instant ai = a.getSubmittedForReviewAt();
          Instant bi = b.getSubmittedForReviewAt();
          if (ai == null && bi == null) return 0;
          if (ai == null) return 1;
          if (bi == null) return -1;
          return ai.compareTo(bi);
        })
        .toList();
  }

  // --- internals ---

  private void requireOwner(UUID userId, UUID orgId) {
    OrganizationMember m = members.findByOrganizationIdAndUserId(orgId, userId)
        .orElseThrow(() -> new AccessDeniedException("Not a member of this organization"));
    if (m.getRole() != OrgRole.OWNER) {
      throw new AccessDeniedException("Only an OWNER can submit the organization for review");
    }
  }

  private static void requireNonBlank(String field, String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
  }

  private static String blankToNull(String value) {
    return (value == null || value.isBlank()) ? null : value.trim();
  }
}
