package com.fairtix.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fairtix.audit.application.AuditService;
import com.fairtix.organizations.domain.Organization;
import com.fairtix.organizations.infrastructure.OrganizationRepository;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The Stripe Connect webhook uses the same event ledger as the platform webhook: a
 * redelivered event is answered 200 without being processed again, and the ledger row and
 * the handling's audit rows commit or roll back together.
 *
 * <p>Red before: the Connect endpoint kept no ledger, so a redelivered
 * {@code charge.dispute.created} audited (and froze payouts) a second time, and its audit
 * rows were written in their own transactions, surviving a failed handling.
 */
@TestPropertySource(properties = {
    "stripe.enabled=true",
    "stripe.connect-webhook-secret=" + StripeConnectWebhookLedgerIT.SECRET,
    "fairtix.connect.dispute-freeze-threshold-cents=5000"
})
@DirtiesContext
class StripeConnectWebhookLedgerIT extends PostgresIT {

  static final String SECRET = "whsec_connect_ledger_test";
  private static final String ACCOUNT = "acct_ledger_test";

  @Autowired private WebApplicationContext context;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private OrganizationRepository organizationRepository;

  @MockitoSpyBean private AuditService auditService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    Organization org = new Organization("Ledger Venue", "ledger-venue-" + UUID.randomUUID(),
        "ledger@venue.test");
    org.setStripeConnectAccountId(ACCOUNT);
    organizationRepository.save(org);
  }

  /** A dispute delivered twice: one audit row, one ledger row, both deliveries answered 200. */
  @Test
  void redeliveredDispute_isProcessedOnce() throws Exception {
    String payload = disputeEvent("evt_connect_redelivery", "dp_redelivery", 10_000);

    deliver(payload).andExpect(status().isOk());
    deliver(payload).andExpect(status().isOk());

    assertThat(count("SELECT COUNT(*) FROM audit_logs WHERE action = 'STRIPE_DISPUTE_FROZE_PAYOUTS'"))
        .isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM stripe_webhook_events"
        + " WHERE event_id = 'evt_connect_redelivery'")).isEqualTo(1);
  }

  /**
   * The handling fails after its audit row: the delivery is answered with an error, nothing
   * is recorded or audited, and Stripe's retry is processed once.
   */
  @Test
  void failedHandling_rollsBackAuditAndLedger_andTheRetryIsProcessed() throws Exception {
    AuditService spy = AopTestUtils.getUltimateTargetObject(auditService);
    doThrow(new IllegalStateException("audit write failed"))
        .doCallRealMethod()
        .when(spy)
        .logInCurrentTransaction(any(), eq("STRIPE_DISPUTE_CREATED"), any(), any(), any());
    String payload = disputeEvent("evt_connect_retry", "dp_retry", 1_000);

    deliver(payload).andExpect(result ->
        assertThat(result.getResponse().getStatus()).isGreaterThanOrEqualTo(400));
    assertThat(count("SELECT COUNT(*) FROM stripe_webhook_events")).isZero();
    assertThat(count("SELECT COUNT(*) FROM audit_logs WHERE action = 'STRIPE_DISPUTE_CREATED'"))
        .isZero();

    deliver(payload).andExpect(status().isOk());
    deliver(payload).andExpect(status().isOk());
    assertThat(count("SELECT COUNT(*) FROM stripe_webhook_events")).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM audit_logs WHERE action = 'STRIPE_DISPUTE_CREATED'"))
        .isEqualTo(1);
  }

  /**
   * A handled event whose object can't be read is answered 200 but not recorded, so
   * resending it once the endpoint's API version is fixed still processes it. Review
   * finding: the first version recorded it and the resend was skipped.
   */
  @Test
  void unreadableEvent_isNotRecorded_soAResendIsProcessed() throws Exception {
    String unreadable = """
        { "id": "evt_connect_unreadable", "object": "event", "type": "charge.dispute.created",
          "account": "%s", "api_version": "%s",
          "data": { "object": { "id": "ch_wrong", "object": "charge" } } }
        """.formatted(ACCOUNT, Stripe.API_VERSION);

    deliver(unreadable).andExpect(status().isOk());
    assertThat(count("SELECT COUNT(*) FROM stripe_webhook_events")).isZero();

    deliver(disputeEvent("evt_connect_unreadable", "dp_resent", 1_000)).andExpect(status().isOk());
    assertThat(count("SELECT COUNT(*) FROM stripe_webhook_events")).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM audit_logs WHERE action = 'STRIPE_DISPUTE_CREATED'"))
        .isEqualTo(1);
  }

  /** A redelivered payout changes nothing: same payout row, no further audit rows. */
  @Test
  void redeliveredPayout_isProcessedOnce() throws Exception {
    String payload = """
        { "id": "evt_connect_payout", "object": "event", "type": "payout.paid",
          "account": "%s", "api_version": "%s",
          "data": { "object": { "id": "po_ledger", "object": "payout", "amount": 2500,
            "currency": "usd", "status": "paid" } } }
        """.formatted(ACCOUNT, Stripe.API_VERSION);

    deliver(payload).andExpect(status().isOk());
    int auditRows = count("SELECT COUNT(*) FROM audit_logs WHERE action = 'STRIPE_PAYOUT_PAID'");
    deliver(payload).andExpect(status().isOk());

    assertThat(auditRows).isPositive();
    assertThat(count("SELECT COUNT(*) FROM audit_logs WHERE action = 'STRIPE_PAYOUT_PAID'"))
        .isEqualTo(auditRows);
    assertThat(count("SELECT COUNT(*) FROM stripe_webhook_events"
        + " WHERE event_id = 'evt_connect_payout'")).isEqualTo(1);
  }

  /** Event types the controller does not handle are not recorded. */
  @Test
  void unhandledEventType_isNotRecorded() throws Exception {
    String payload = """
        { "id": "evt_connect_unhandled", "object": "event", "type": "customer.created",
          "account": "%s", "api_version": "%s",
          "data": { "object": { "id": "cus_1", "object": "customer" } } }
        """.formatted(ACCOUNT, Stripe.API_VERSION);

    deliver(payload).andExpect(status().isOk());

    assertThat(count("SELECT COUNT(*) FROM stripe_webhook_events")).isZero();
  }

  private static String disputeEvent(String eventId, String disputeId, long amountCents) {
    return """
        { "id": "%s", "object": "event", "type": "charge.dispute.created",
          "account": "%s", "api_version": "%s",
          "data": { "object": { "id": "%s", "object": "dispute", "amount": %d,
            "currency": "usd", "reason": "fraudulent" } } }
        """.formatted(eventId, ACCOUNT, Stripe.API_VERSION, disputeId, amountCents);
  }

  private ResultActions deliver(String payload) throws Exception {
    return mockMvc.perform(post("/api/webhooks/stripe-connect")
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

  private int count(String sql) {
    return jdbcTemplate.queryForObject(sql, Integer.class);
  }
}
