package com.fairtix.branding.dto;

import com.fairtix.organizations.domain.Organization;

/**
 * DTOs for per-org branding (M2-19). The PATCH request uses nullable fields with
 * sentinel semantics: a field omitted from the JSON body is left unchanged; a
 * field present with {@code null} explicitly clears the value. Records can't
 * distinguish missing from null, so the controller layer accepts the raw map
 * upstream of this record for clear semantics — see BrandingController.
 */
public final class BrandingDtos {
  private BrandingDtos() {}

  public record BrandingResponse(
      String logoUrl,
      String primaryColor,
      String emailSenderName,
      String emailReplyTo,
      boolean darkModeEnabled,
      String statementDescriptorSuffix) {

    public static BrandingResponse from(Organization o) {
      return new BrandingResponse(
          o.getLogoUrl(),
          o.getPrimaryColor(),
          o.getEmailSenderName(),
          o.getEmailReplyTo(),
          o.isDarkModeEnabled(),
          o.getStatementDescriptorSuffix());
    }
  }

  public record UpdateBrandingRequest(
      String logoUrl,
      String primaryColor,
      String emailSenderName,
      String emailReplyTo,
      Boolean darkModeEnabled,
      String statementDescriptorSuffix) {}
}
