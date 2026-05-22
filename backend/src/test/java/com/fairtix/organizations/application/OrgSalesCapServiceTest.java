package com.fairtix.organizations.application;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fairtix.organizations.domain.Organization;
import com.fairtix.organizations.infrastructure.OrganizationRepository;
import com.fairtix.organizations.infrastructure.OrgSalesLedgerRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrgSalesCapServiceTest {

  private OrganizationRepository organizations;
  private OrgSalesLedgerRepository ledger;
  private OrgSalesCapService service;
  private Organization org;
  private UUID orgId;

  @BeforeEach
  void setUp() {
    organizations = mock(OrganizationRepository.class);
    ledger = mock(OrgSalesLedgerRepository.class);
    service = new OrgSalesCapService(organizations, ledger);

    org = new Organization("Test", "test", "a@b.test");
    orgId = UUID.randomUUID();
    when(organizations.findById(orgId)).thenReturn(Optional.of(org));
  }

  @Test
  void newOrgCappedAt1k() {
    when(ledger.sumSince(eq(orgId), any())).thenReturn(0L);

    service.checkCanCharge(orgId, 99_99L); // $99.99 fine

    assertThatThrownBy(() -> service.checkCanCharge(orgId, 100_001L))
        .isInstanceOf(SalesCapExceededException.class);
  }

  @Test
  void afterOneSuccessfulCycleCappedAt10k() {
    org.setSuccessfulPayoutCycles(1);
    when(ledger.sumSince(eq(orgId), any())).thenReturn(0L);

    service.checkCanCharge(orgId, 999_999L);

    assertThatThrownBy(() -> service.checkCanCharge(orgId, 1_000_001L))
        .isInstanceOf(SalesCapExceededException.class);
  }

  @Test
  void afterThreeCyclesUnlimited() {
    org.setSuccessfulPayoutCycles(3);
    // Even with $50k used, $50k more is allowed.
    when(ledger.sumSince(eq(orgId), any())).thenReturn(5_000_000L);
    service.checkCanCharge(orgId, 5_000_000L);
  }

  @Test
  void disputeHoldsAt10kTierEvenAfterMultipleCycles() {
    org.setSuccessfulPayoutCycles(10);
    org.incrementDisputeCount();
    when(ledger.sumSince(eq(orgId), any())).thenReturn(0L);

    assertThatThrownBy(() -> service.checkCanCharge(orgId, 1_000_001L))
        .isInstanceOf(SalesCapExceededException.class);
  }

  @Test
  void overrideTakesPrecedenceWhileValid() {
    org.setSuccessfulPayoutCycles(0);
    org.setDailySalesCapCents(50_000_00L);
    org.setPlanOverridesUntil(Instant.now().plus(1, ChronoUnit.HOURS));
    when(ledger.sumSince(eq(orgId), any())).thenReturn(0L);

    service.checkCanCharge(orgId, 49_999_00L);

    assertThatThrownBy(() -> service.checkCanCharge(orgId, 50_001_00L))
        .isInstanceOf(SalesCapExceededException.class);
  }

  @Test
  void expiredOverrideFallsBackToTier() {
    org.setSuccessfulPayoutCycles(0);
    org.setDailySalesCapCents(50_000_00L);
    org.setPlanOverridesUntil(Instant.now().minus(1, ChronoUnit.HOURS));
    when(ledger.sumSince(eq(orgId), any())).thenReturn(0L);

    assertThatThrownBy(() -> service.checkCanCharge(orgId, 200_000L))
        .isInstanceOf(SalesCapExceededException.class);
  }

  @Test
  void nullOrgIdIsNoOp() {
    service.checkCanCharge(null, 999_999_999L);
  }

  @Test
  void exceptionCarriesUsageDetails() {
    // TIER_NEW_CENTS is $1,000 (= 100_000 cents). Pick numbers that actually
    // exceed it: $990 already used + $20 requested = $1,010 > $1,000 cap.
    long used = 99_000L;       // $990.00
    long requested = 2_000L;   // $20.00 → pushes total to $1,010 over the $1k cap
    when(ledger.sumSince(eq(orgId), any())).thenReturn(used);
    assertThatThrownBy(() -> service.checkCanCharge(orgId, requested))
        .isInstanceOfSatisfying(SalesCapExceededException.class, ex -> {
          assertThat(ex.getCapCents()).isEqualTo(OrgSalesCapService.TIER_NEW_CENTS);
          assertThat(ex.getUsedCents()).isEqualTo(used);
          assertThat(ex.getRequestedCents()).isEqualTo(requested);
        });
  }
}
