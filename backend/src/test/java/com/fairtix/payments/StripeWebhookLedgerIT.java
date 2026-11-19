package com.fairtix.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fairtix.audit.application.AuditService;
import com.fairtix.orders.domain.Order;
import com.fairtix.orders.domain.OrderStatus;
import com.fairtix.orders.infrastructure.OrderRepository;
import com.fairtix.payments.domain.PaymentRecord;
import com.fairtix.payments.domain.PaymentStatus;
import com.fairtix.payments.infrastructure.PaymentRecordRepository;
import com.fairtix.payments.infrastructure.StripeWebhookEventRepository;
import com.fairtix.refunds.domain.RefundRequest;
import com.fairtix.refunds.infrastructure.RefundRepository;
import com.fairtix.support.PostgresIT;
import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;
import com.stripe.Stripe;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

/**
 * The Stripe webhook event ledger against PostgreSQL: a redelivered event is answered 200
 * without being processed again, the ledger row commits or rolls back with the handling, and
 * two deliveries of one event at once are processed once.
 */
@TestPropertySource(properties = {
    "stripe.enabled=true",
    "stripe.webhook-secret=" + StripeWebhookLedgerIT.SECRET
})
@DirtiesContext
class StripeWebhookLedgerIT extends PostgresIT {

  static final String SECRET = "whsec_ledger_test";

  @Autowired private WebApplicationContext context;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private StripeWebhookEventRepository repository;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private UserRepository userRepository;
  @Autowired private OrderRepository orderRepository;
  @Autowired private PaymentRecordRepository paymentRecordRepository;
  @Autowired private RefundRepository refundRepository;

  @MockitoSpyBean private AuditService auditService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  /** Stripe delivers the same event twice: one audit row, one ledger row, both answered 200. */
  @Test
  void redeliveredEvent_isProcessedOnce() throws Exception {
    String payload = unmatchedPaymentEvent("evt_ledger_redelivery", "pi_ledger_1");

    deliver(payload).andExpect(status().isOk());
    deliver(payload).andExpect(status().isOk());

    assertThat(count("SELECT COUNT(*) FROM audit_logs WHERE action = 'STRIPE_UNMATCHED_PAYMENT'"))
        .isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM stripe_webhook_events"
        + " WHERE event_id = 'evt_ledger_redelivery'")).isEqualTo(1);
  }

  /**
   * The handling fails, so the delivery is answered with an error and Stripe retries it. The
   * ledger row rolled back with the handling, so the retry is processed, and only it is audited.
   */
  @Test
  void failedHandling_leavesNoLedgerRow_andTheRetryIsProcessed() throws Exception {
    // Stubbed on the spy behind the transactional proxy: calling the MANDATORY method through
    // the proxy from the test thread would fail for want of a transaction.
    AuditService spy = AopTestUtils.getUltimateTargetObject(auditService);
    doThrow(new IllegalStateException("audit write failed"))
        .doCallRealMethod()
        .when(spy)
        .logInCurrentTransaction(any(), eq("STRIPE_UNMATCHED_PAYMENT"), any(), any(), any());
    String payload = unmatchedPaymentEvent("evt_ledger_retry", "pi_ledger_2");

    deliver(payload).andExpect(result ->
        assertThat(result.getResponse().getStatus()).isGreaterThanOrEqualTo(400));
    assertThat(count("SELECT COUNT(*) FROM stripe_webhook_events")).isZero();
    assertThat(count("SELECT COUNT(*) FROM audit_logs WHERE action = 'STRIPE_UNMATCHED_PAYMENT'"))
        .isZero();

    deliver(payload).andExpect(status().isOk());
    assertThat(count("SELECT COUNT(*) FROM stripe_webhook_events")).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM audit_logs WHERE action = 'STRIPE_UNMATCHED_PAYMENT'"))
        .isEqualTo(1);
  }

  /**
   * A handled event whose object can't be read is answered 200 but not recorded, so resending it
   * once the cause is fixed still processes it.
   */
  @Test
  void unreadableEvent_isNotRecorded_soAResendIsProcessed() throws Exception {
    String unreadable = """
        { "id": "evt_ledger_unreadable", "object": "event", "type": "payment_intent.succeeded",
          "api_version": "%s",
          "data": { "object": { "id": "ch_wrong", "object": "charge" } } }
        """.formatted(Stripe.API_VERSION);

    deliver(unreadable).andExpect(status().isOk());
    assertThat(count("SELECT COUNT(*) FROM stripe_webhook_events")).isZero();

    deliver(unmatchedPaymentEvent("evt_ledger_unreadable", "pi_ledger_resent"))
        .andExpect(status().isOk());
    assertThat(count("SELECT COUNT(*) FROM stripe_webhook_events")).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM audit_logs WHERE action = 'STRIPE_UNMATCHED_PAYMENT'"))
        .isEqualTo(1);
  }

  /**
   * charge.refunded completes an approved refund. The completion, its audit row and the ledger
   * row commit together: a failed audit leaves the refund APPROVED and the event unrecorded, the
   * retry completes it once, and a later redelivery changes nothing.
   */
  @Test
  void chargeRefunded_completionAuditAndLedger_commitTogether() throws Exception {
    User buyer = new User();
    buyer.setEmail("ledger-refund@test.com");
    buyer.setPassword("$2a$10$dummyhashfortest");
    buyer = userRepository.save(buyer);
    Order order = orderRepository.save(
        new Order(buyer, List.of(), new BigDecimal("50.00"), "USD", OrderStatus.COMPLETED));
    paymentRecordRepository.save(new PaymentRecord(order.getId(), buyer.getId(),
        new BigDecimal("50.00"), "USD", PaymentStatus.SUCCESS, "pi_ledger_refund", null));
    RefundRequest refund = new RefundRequest(order.getId(), buyer.getId(),
        new BigDecimal("50.00"), "ledger");
    refund.approve(buyer.getId(), "ok");
    UUID refundId = refundRepository.save(refund).getId();

    AuditService spy = AopTestUtils.getUltimateTargetObject(auditService);
    doThrow(new IllegalStateException("audit write failed"))
        .doCallRealMethod()
        .when(spy)
        .logInCurrentTransaction(any(), eq("REFUND_COMPLETED"), any(), any(), any());
    String payload = """
        { "id": "evt_ledger_refund", "object": "event", "type": "charge.refunded",
          "api_version": "%s",
          "data": { "object": { "id": "ch_ledger", "object": "charge",
            "payment_intent": "pi_ledger_refund", "refunded": true, "amount_refunded": 5000 } } }
        """.formatted(Stripe.API_VERSION);

    deliver(payload).andExpect(result ->
        assertThat(result.getResponse().getStatus()).isGreaterThanOrEqualTo(400));
    assertThat(refundStatus(refundId)).isEqualTo("APPROVED");
    assertThat(count("SELECT COUNT(*) FROM stripe_webhook_events")).isZero();
    assertThat(count("SELECT COUNT(*) FROM audit_logs WHERE action = 'REFUND_COMPLETED'")).isZero();

    deliver(payload).andExpect(status().isOk());
    deliver(payload).andExpect(status().isOk());

    assertThat(refundStatus(refundId)).isEqualTo("COMPLETED");
    assertThat(count("SELECT COUNT(*) FROM stripe_webhook_events")).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM audit_logs WHERE action = 'REFUND_COMPLETED'"))
        .isEqualTo(1);
  }

  private String refundStatus(UUID refundId) {
    return jdbcTemplate.queryForObject(
        "SELECT status FROM refund_requests WHERE id = ?", String.class, refundId);
  }

  /** Event types the controller does not handle are not recorded. */
  @Test
  void unhandledEventType_isNotRecorded() throws Exception {
    String payload = """
        { "id": "evt_ledger_unhandled", "object": "event", "type": "customer.created",
          "api_version": "%s", "data": { "object": { "id": "cus_1", "object": "customer" } } }
        """.formatted(Stripe.API_VERSION);

    deliver(payload).andExpect(status().isOk());

    assertThat(count("SELECT COUNT(*) FROM stripe_webhook_events")).isZero();
  }

  /** Two deliveries at once: the second waits on the first's insert, then finds it committed. */
  @Test
  void concurrentInsert_afterTheFirstCommits_returnsZero() throws Exception {
    assertThat(raceInsert("evt_ledger_race_commit", true)).containsExactly(1, 0);
    assertThat(count("SELECT COUNT(*) FROM stripe_webhook_events")).isEqualTo(1);
  }

  /** The first delivery's handling rolls back while the second waits: the second records it. */
  @Test
  void concurrentInsert_afterTheFirstRollsBack_returnsOne() throws Exception {
    assertThat(raceInsert("evt_ledger_race_rollback", false)).containsExactly(1, 1);
    assertThat(count("SELECT COUNT(*) FROM stripe_webhook_events")).isEqualTo(1);
  }

  /** Returns {first, second} insert results, with the second forced to wait on the first. */
  private int[] raceInsert(String eventId, boolean firstCommits) throws Exception {
    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    CountDownLatch inserted = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<Integer> first = pool.submit(() -> tx.execute(status -> {
        int n = repository.insertIfAbsent(eventId, "payment_intent.succeeded");
        inserted.countDown();
        try {
          release.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        if (!firstCommits) {
          status.setRollbackOnly();
        }
        return n;
      }));
      assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();
      Future<Integer> second = pool.submit(() -> tx.execute(status ->
          repository.insertIfAbsent(eventId, "payment_intent.succeeded")));
      boolean blocked = waitForLockWaiters(1, Duration.ofSeconds(10));
      release.countDown();
      assertThat(blocked).as("the second insert waited on the first").isTrue();
      return new int[] {first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)};
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  private boolean waitForLockWaiters(int waiters, Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      Integer n = jdbcTemplate.queryForObject(
          "SELECT COUNT(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
              + " AND datname = current_database()", Integer.class);
      if (n != null && n >= waiters) {
        return true;
      }
      Thread.sleep(50);
    }
    return false;
  }

  private static String unmatchedPaymentEvent(String eventId, String piId) {
    return """
        { "id": "%s", "object": "event", "type": "payment_intent.succeeded",
          "api_version": "%s",
          "data": { "object": { "id": "%s", "object": "payment_intent", "amount": 5000,
            "currency": "usd", "status": "succeeded" } } }
        """.formatted(eventId, Stripe.API_VERSION, piId);
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

  private int count(String sql) {
    return jdbcTemplate.queryForObject(sql, Integer.class);
  }
}
