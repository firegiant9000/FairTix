package com.fairtix.organizations.dto;

import java.time.Instant;
import java.util.UUID;

import com.fairtix.organizations.domain.Organization;
import com.fairtix.organizations.domain.OrganizationStatus;

/** DTOs for the Phase H organizer signup wizard and admin approval queue. */
public final class SignupDtos {
  private SignupDtos() {}

  /** Steps 1+3 of the wizard. Email/password already happened via /auth. */
  public record SubmitForReviewRequest(
      String legalName,
      String dba,
      String addressLine1,
      String addressLine2,
      String addressCity,
      String addressRegion,
      String addressPostalCode,
      String addressCountry,
      String primaryContactName,
      String primaryContactPhone,
      /** Plain EIN; encrypted server-side before persistence. */
      String ein,
      String referredBy
  ) {}

  public record RejectRequest(String reason) {}

  public record OverrideCapRequest(Long dailySalesCapCents, Instant validUntil) {}

  public record OrganizationReviewResponse(
      UUID id,
      String name,
      String slug,
      String legalName,
      String dba,
      String contactEmail,
      String primaryContactName,
      String primaryContactPhone,
      String addressLine1,
      String addressLine2,
      String addressCity,
      String addressRegion,
      String addressPostalCode,
      String addressCountry,
      String referredBy,
      OrganizationStatus status,
      Instant submittedForReviewAt,
      Instant reviewedAt,
      UUID reviewedByUserId,
      String rejectionReason,
      boolean stripeChargesEnabled,
      boolean stripeDetailsSubmitted,
      Long dailySalesCapCents,
      Instant planOverridesUntil,
      int successfulPayoutCycles,
      int disputeCount
  ) {
    public static OrganizationReviewResponse from(Organization o) {
      return new OrganizationReviewResponse(
          o.getId(), o.getName(), o.getSlug(),
          o.getLegalName(), o.getDba(), o.getContactEmail(),
          o.getPrimaryContactName(), o.getPrimaryContactPhone(),
          o.getAddressLine1(), o.getAddressLine2(), o.getAddressCity(),
          o.getAddressRegion(), o.getAddressPostalCode(), o.getAddressCountry(),
          o.getReferredBy(),
          o.getStatus(), o.getSubmittedForReviewAt(), o.getReviewedAt(),
          o.getReviewedByUserId(), o.getRejectionReason(),
          o.isStripeChargesEnabled(), o.isStripeDetailsSubmitted(),
          o.getDailySalesCapCents(), o.getPlanOverridesUntil(),
          o.getSuccessfulPayoutCycles(), o.getDisputeCount());
    }
  }

  public record SalesCapStatusResponse(
      long capCents,
      long usedCentsLast24h,
      long remainingCents,
      Long overrideCapCents,
      Instant overrideUntil,
      int successfulPayoutCycles,
      int disputeCount
  ) {}
}
