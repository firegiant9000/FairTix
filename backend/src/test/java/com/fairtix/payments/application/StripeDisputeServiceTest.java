package com.fairtix.payments.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fairtix.audit.application.AuditService;
import com.fairtix.organizations.domain.Organization;
import com.fairtix.organizations.infrastructure.OrganizationRepository;
import com.fairtix.payments.application.StripePaymentService.ConnectRouting;
import com.stripe.model.Dispute;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class StripeDisputeServiceTest {

  private static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);

  private OrganizationRepository organizationRepository;
  private StripePaymentService stripePaymentService;
  private AuditService auditService;
  private StripeDisputeService service;
  private Organization org;

  @BeforeEach
  void setUp() {
    organizationRepository = mock(OrganizationRepository.class);
    stripePaymentService = mock(StripePaymentService.class);
    auditService = mock(AuditService.class);
    service = new StripeDisputeService(organizationRepository, stripePaymentService, auditService);
    ReflectionTestUtils.setField(service, "disputeFreezeThresholdCents", 5000L);
    org = new Organization("Venue", "venue-" + UUID.randomUUID(), "v@venue.test");
    ReflectionTestUtils.setField(org, "id", UUID.randomUUID());
  }

  @Test
  void createdBelowThreshold_countsWithoutFreezing() {
    service.recordDispute(org, dispute("dp_1", 1_000, "needs_response"));

    verify(organizationRepository).incrementDisputeCount(eq(org.getId()), any(Instant.class));
    verify(organizationRepository, never()).freezePayouts(any(), any());
    verify(auditService).logInCurrentTransaction(eq(SYSTEM_ACTOR), eq("STRIPE_DISPUTE_CREATED"),
        eq("ORGANIZATION"), eq(org.getId()), contains("dispute=dp_1"));
  }

  @Test
  void createdAtThreshold_countsAndFreezes() {
    service.recordDispute(org, dispute("dp_2", 5_000, "needs_response"));

    verify(organizationRepository).incrementDisputeCount(eq(org.getId()), any(Instant.class));
    verify(organizationRepository).freezePayouts(eq(org.getId()), any(Instant.class));
    verify(auditService).logInCurrentTransaction(eq(SYSTEM_ACTOR), eq("STRIPE_DISPUTE_FROZE_PAYOUTS"),
        eq("ORGANIZATION"), eq(org.getId()), contains("dispute=dp_2"));
  }

  @Test
  void twoDisputes_countTwice() {
    service.recordDispute(org, dispute("dp_a", 100, "needs_response"));
    service.recordDispute(org, dispute("dp_b", 200, "needs_response"));

    verify(organizationRepository, times(2)).incrementDisputeCount(eq(org.getId()), any(Instant.class));
  }

  @Test
  void platformCreated_withNoOrg_isUnmatchedAndCountsNothing() {
    when(stripePaymentService.connectRoutingOf("pi_x")).thenReturn(new ConnectRouting(null, null));

    service.recordPlatformDispute(dispute("dp_x", 9_000, "needs_response", "pi_x"));

    verify(organizationRepository, never()).incrementDisputeCount(any(), any());
    verify(auditService).logInCurrentTransaction(eq(SYSTEM_ACTOR), eq("STRIPE_DISPUTE_UNMATCHED"),
        eq("PAYMENT"), eq(null), contains("stripe_intent=pi_x"));
  }

  @Test
  void closed_auditsStatus_andLeavesCountAndFreezeAlone() {
    service.recordDisputeClosed(org, dispute("dp_c", 9_000, "won"));

    verify(auditService).logInCurrentTransaction(eq(SYSTEM_ACTOR), eq("STRIPE_DISPUTE_CLOSED"),
        eq("ORGANIZATION"), eq(org.getId()), contains("dispute=dp_c"));
    verify(auditService).logInCurrentTransaction(eq(SYSTEM_ACTOR), eq("STRIPE_DISPUTE_CLOSED"),
        eq("ORGANIZATION"), eq(org.getId()), contains(" status=won"));
    verify(organizationRepository, never()).incrementDisputeCount(any(), any());
    verify(organizationRepository, never()).freezePayouts(any(), any());
  }

  @Test
  void platformClosed_resolvesOrgFromTheIntent() {
    when(stripePaymentService.connectRoutingOf("pi_c"))
        .thenReturn(new ConnectRouting("acct_1", null));
    when(organizationRepository.findByStripeConnectAccountId("acct_1")).thenReturn(Optional.of(org));

    service.recordPlatformDisputeClosed(dispute("dp_pc", 100, "lost", "pi_c"));

    verify(auditService).logInCurrentTransaction(eq(SYSTEM_ACTOR), eq("STRIPE_DISPUTE_CLOSED"),
        eq("ORGANIZATION"), eq(org.getId()), contains(" status=lost"));
    verify(organizationRepository, never()).incrementDisputeCount(any(), any());
  }

  @Test
  void platformClosed_withNoOrg_isUnmatchedWithStatus() {
    when(stripePaymentService.connectRoutingOf("pi_u")).thenReturn(new ConnectRouting(null, null));

    service.recordPlatformDisputeClosed(dispute("dp_u", 100, "warning_closed", "pi_u"));

    verify(auditService).logInCurrentTransaction(eq(SYSTEM_ACTOR), eq("STRIPE_DISPUTE_UNMATCHED"),
        eq("PAYMENT"), eq(null), contains(" status=warning_closed"));
    verify(organizationRepository, never()).incrementDisputeCount(any(), any());
  }

  private static Dispute dispute(String id, long amount, String status) {
    return dispute(id, amount, status, null);
  }

  private static Dispute dispute(String id, long amount, String status, String paymentIntent) {
    Dispute d = new Dispute();
    d.setId(id);
    d.setAmount(amount);
    d.setCurrency("usd");
    d.setReason("fraudulent");
    d.setStatus(status);
    d.setPaymentIntent(paymentIntent);
    return d;
  }
}
