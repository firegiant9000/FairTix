package com.fairtix.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fairtix.organizations.domain.Organization;
import com.fairtix.organizations.infrastructure.OrganizationRepository;
import com.fairtix.payments.application.StripePaymentService;
import com.fairtix.payments.application.StripePaymentService.ConnectRouting;
import com.fairtix.support.PostgresIT;
import com.stripe.Stripe;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Issue #343. FairTix creates destination charges ({@code on_behalf_of} +
 * {@code transfer_data.destination}), and Stripe sends disputes on those to the platform
 * endpoint, not the Connect one. The platform webhook finds the organizer from the disputed
 * PaymentIntent and applies the same freeze rule as the Connect webhook.
 *
 * <p>Red before: the platform webhook ignored {@code charge.dispute.created}, so no organizer
 * was ever frozen.
 */
@TestPropertySource(properties = {
    "stripe.enabled=true",
    "stripe.webhook-secret=" + StripePlatformDisputeIT.SECRET,
    "fairtix.connect.dispute-freeze-threshold-cents=5000"
})
@DirtiesContext
class StripePlatformDisputeIT extends PostgresIT {

  static final String SECRET = "whsec_platform_dispute_test";
  private static final String ACCOUNT = "acct_dispute_test";

  @Autowired private WebApplicationContext context;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private OrganizationRepository organizationRepository;

  @MockitoBean private StripePaymentService stripePaymentService;

  private MockMvc mockMvc;
  private Organization org;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    org = new Organization("Dispute Venue", "dispute-venue-" + UUID.randomUUID(), "dispute@venue.test");
    org.setStripeConnectAccountId(ACCOUNT);
    org = organizationRepository.save(org);
  }

  @Test
  void disputeOverThreshold_freezesTheDestinationOrg() throws Exception {
    when(stripePaymentService.connectRoutingOf("pi_big"))
        .thenReturn(new ConnectRouting(ACCOUNT, org.getId().toString()));

    deliver(disputeEvent("evt_big", "dp_big", "pi_big", 10_000)).andExpect(status().isOk());

    assertThat(frozen()).isTrue();
    assertThat(auditRows("STRIPE_DISPUTE_FROZE_PAYOUTS", org.getId())).isEqualTo(1);
  }

  @Test
  void disputeUnderThreshold_isAuditedWithoutFreezing() throws Exception {
    when(stripePaymentService.connectRoutingOf("pi_small"))
        .thenReturn(new ConnectRouting(ACCOUNT, org.getId().toString()));

    deliver(disputeEvent("evt_small", "dp_small", "pi_small", 1_000)).andExpect(status().isOk());

    assertThat(frozen()).isFalse();
    assertThat(auditRows("STRIPE_DISPUTE_CREATED", org.getId())).isEqualTo(1);
  }

  /** No destination on the intent (or an unknown one): the org id FairTix put in metadata. */
  @Test
  void orgFoundFromMetadata_whenTheDestinationIsUnknown() throws Exception {
    when(stripePaymentService.connectRoutingOf("pi_meta"))
        .thenReturn(new ConnectRouting("acct_unknown", org.getId().toString()));

    deliver(disputeEvent("evt_meta", "dp_meta", "pi_meta", 10_000)).andExpect(status().isOk());

    assertThat(frozen()).isTrue();
  }

  /** A payment not routed through Connect has no organizer to freeze; it is put on record. */
  @Test
  void disputeOnANonConnectPayment_isAuditedAsUnmatched() throws Exception {
    when(stripePaymentService.connectRoutingOf("pi_platform"))
        .thenReturn(new ConnectRouting(null, null));

    deliver(disputeEvent("evt_platform", "dp_platform", "pi_platform", 10_000))
        .andExpect(status().isOk());

    assertThat(frozen()).isFalse();
    assertThat(count("SELECT COUNT(*) FROM audit_logs WHERE action = 'STRIPE_DISPUTE_UNMATCHED'"
        + " AND details LIKE '%pi_platform%'")).isEqualTo(1);
  }

  @Test
  void redeliveredDispute_isProcessedOnce() throws Exception {
    when(stripePaymentService.connectRoutingOf("pi_again"))
        .thenReturn(new ConnectRouting(ACCOUNT, org.getId().toString()));
    String payload = disputeEvent("evt_again", "dp_again", "pi_again", 10_000);

    deliver(payload).andExpect(status().isOk());
    deliver(payload).andExpect(status().isOk());

    assertThat(auditRows("STRIPE_DISPUTE_FROZE_PAYOUTS", org.getId())).isEqualTo(1);
  }

  /**
   * Stripe can't be reached to look the intent up: answer with an error and record nothing,
   * so Stripe's retry is processed.
   */
  @Test
  void stripeLookupFailure_recordsNothing_soTheRetryIsProcessed() throws Exception {
    when(stripePaymentService.connectRoutingOf("pi_flaky"))
        .thenThrow(new RuntimeException("Stripe unavailable"))
        .thenReturn(new ConnectRouting(ACCOUNT, org.getId().toString()));
    String payload = disputeEvent("evt_flaky", "dp_flaky", "pi_flaky", 10_000);

    deliver(payload).andExpect(result ->
        assertThat(result.getResponse().getStatus()).isGreaterThanOrEqualTo(500));
    assertThat(count("SELECT COUNT(*) FROM stripe_webhook_events")).isZero();
    assertThat(frozen()).isFalse();

    deliver(payload).andExpect(status().isOk());
    assertThat(frozen()).isTrue();
  }

  private boolean frozen() {
    return organizationRepository.findById(org.getId()).orElseThrow().isStripePayoutsFrozen();
  }

  private int auditRows(String action, UUID orgId) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM audit_logs WHERE action = ? AND resource_id = ?",
        Integer.class, action, orgId);
  }

  private int count(String sql) {
    return jdbcTemplate.queryForObject(sql, Integer.class);
  }

  /** A platform-scope event: no {@code account} field. */
  private static String disputeEvent(String eventId, String disputeId, String paymentIntentId,
      long amountCents) {
    return """
        { "id": "%s", "object": "event", "type": "charge.dispute.created",
          "api_version": "%s",
          "data": { "object": { "id": "%s", "object": "dispute", "amount": %d,
            "currency": "usd", "reason": "fraudulent", "charge": "ch_%s",
            "payment_intent": "%s" } } }
        """.formatted(eventId, Stripe.API_VERSION, disputeId, amountCents, disputeId, paymentIntentId);
  }

  private ResultActions deliver(String payload) throws Exception {
    return mockMvc.perform(post("/api/webhooks/stripe")
        .contentType(MediaType.APPLICATION_JSON)
        .header("Stripe-Signature", signature(payload))
        .content(payload));
  }

  private static String signature(String payload) throws Exception {
    long ts = Instant.now().getEpochSecond();
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    String sig = HexFormat.of().formatHex(
        mac.doFinal((ts + "." + payload).getBytes(StandardCharsets.UTF_8)));
    return "t=" + ts + ",v1=" + sig;
  }
}
