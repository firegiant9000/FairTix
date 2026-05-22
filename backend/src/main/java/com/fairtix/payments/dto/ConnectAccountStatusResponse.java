package com.fairtix.payments.dto;

import com.fairtix.organizations.domain.Organization;

import java.time.Instant;

public record ConnectAccountStatusResponse(
    String accountId,
    String country,
    boolean chargesEnabled,
    boolean payoutsEnabled,
    boolean detailsSubmitted,
    boolean payoutsFrozen,
    String disabledReason,
    String requirementsJson,
    Instant connectedAt
) {
  public static ConnectAccountStatusResponse from(Organization org) {
    return new ConnectAccountStatusResponse(
        org.getStripeConnectAccountId(),
        org.getStripeConnectCountry(),
        org.isStripeChargesEnabled(),
        org.isStripePayoutsEnabled(),
        org.isStripeDetailsSubmitted(),
        org.isStripePayoutsFrozen(),
        org.getStripeDisabledReason(),
        org.getStripeRequirementsJson(),
        org.getStripeConnectedAt()
    );
  }
}
