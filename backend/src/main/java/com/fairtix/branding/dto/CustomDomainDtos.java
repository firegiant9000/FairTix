package com.fairtix.branding.dto;

import java.time.Instant;
import java.util.UUID;

import com.fairtix.branding.domain.OrgCustomDomain;

public final class CustomDomainDtos {
  private CustomDomainDtos() {}

  public record AddCustomDomainRequest(String hostname) {}

  public record CustomDomainResponse(
      UUID id,
      String hostname,
      String verificationToken,
      String txtRecordName,
      String txtRecordValue,
      boolean verified,
      Instant verifiedAt,
      boolean active,
      Boolean lastHealthOk,
      Instant lastHealthCheckAt) {

    public static CustomDomainResponse from(OrgCustomDomain d) {
      String txtName = "_fairtix-verify." + d.getHostname();
      return new CustomDomainResponse(
          d.getId(),
          d.getHostname(),
          d.getVerificationToken(),
          txtName,
          "fairtix-verify=" + d.getVerificationToken(),
          d.getVerifiedAt() != null,
          d.getVerifiedAt(),
          d.isActive(),
          d.getLastHealthOk(),
          d.getLastHealthCheckAt());
    }
  }
}
