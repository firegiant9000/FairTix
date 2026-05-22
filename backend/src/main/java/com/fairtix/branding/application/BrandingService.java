package com.fairtix.branding.application;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fairtix.audit.application.AuditService;
import com.fairtix.branding.dto.BrandingDtos.UpdateBrandingRequest;
import com.fairtix.common.ResourceNotFoundException;
import com.fairtix.organizations.domain.Organization;
import com.fairtix.organizations.infrastructure.OrganizationRepository;

@Service
public class BrandingService {

  private final OrganizationRepository organizations;
  private final AuditService auditService;

  public BrandingService(OrganizationRepository organizations, AuditService auditService) {
    this.organizations = organizations;
    this.auditService = auditService;
  }

  public Organization get(UUID orgId) {
    return organizations.findById(orgId)
        .orElseThrow(() -> new ResourceNotFoundException("Organization not found: " + orgId));
  }

  @Transactional
  public Organization update(UUID orgId, UpdateBrandingRequest req, UUID actorUserId) {
    Organization org = get(orgId);
    boolean changed = false;

    if (req.logoUrl() != null) {
      org.setLogoUrl(BrandingValidator.normalizeUrl(req.logoUrl()));
      changed = true;
    }
    if (req.primaryColor() != null) {
      org.setPrimaryColor(BrandingValidator.normalizeColor(req.primaryColor()));
      changed = true;
    }
    if (req.emailSenderName() != null) {
      org.setEmailSenderName(BrandingValidator.normalizeSenderName(req.emailSenderName()));
      changed = true;
    }
    if (req.emailReplyTo() != null) {
      org.setEmailReplyTo(BrandingValidator.normalizeEmail(req.emailReplyTo()));
      changed = true;
    }
    if (req.darkModeEnabled() != null) {
      org.setDarkModeEnabled(req.darkModeEnabled());
      changed = true;
    }
    if (req.statementDescriptorSuffix() != null) {
      org.setStatementDescriptorSuffix(
          BrandingValidator.normalizeStatementDescriptorSuffix(req.statementDescriptorSuffix()));
      changed = true;
    }

    if (changed) {
      auditService.log(actorUserId, "ORG_BRANDING_UPDATED", "ORGANIZATION", orgId,
          "Branding updated for org " + orgId);
    }
    return org;
  }
}
