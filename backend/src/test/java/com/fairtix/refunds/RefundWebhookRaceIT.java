package com.fairtix.refunds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fairtix.events.domain.Event;
import com.fairtix.events.infrastructure.EventRepository;
import com.fairtix.orders.domain.Order;
import com.fairtix.orders.domain.OrderStatus;
import com.fairtix.orders.infrastructure.OrderRepository;
import com.fairtix.payments.domain.PaymentRecord;
import com.fairtix.payments.domain.PaymentStatus;
import com.fairtix.payments.infrastructure.PaymentRecordRepository;
import com.fairtix.refunds.domain.RefundRequest;
import com.fairtix.refunds.infrastructure.RefundRepository;
import com.fairtix.support.PostgresIT;
import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Review round 1 (#2): {@code charge.refunded} racing {@code RefundService.finalise}.
 * The webhook read the refund without a lock and saved the whole row, so when its
 * UPDATE waited on finalise's row lock it then wrote {@code stripe_refund_id = NULL}
 * over the id finalise had just committed.
 *
 * <p>A third connection plays finalise: it locks the refund row and sets the Stripe
 * refund id, the webhook is sent and waits on that lock, then the third connection
 * commits.
 *
 * <p>Red on commit {@code RED_HASH}.
 */
@TestPropertySource(properties = {
    "stripe.enabled=true",
    "stripe.webhook-secret=" + RefundWebhookRaceIT.SECRET
})
class RefundWebhookRaceIT extends PostgresIT {

  static final String SECRET = "whsec_test_race";

  @Autowired private WebApplicationContext context;
  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private UserRepository userRepository;
  @Autowired private EventRepository eventRepository;
  @Autowired private OrderRepository orderRepository;
  @Autowired private PaymentRecordRepository paymentRecordRepository;
  @Autowired private RefundRepository refundRepository;

  @Test
  void webhookWaitingOnFinalise_keepsStripeRefundId() throws Exception {
    User buyer = new User();
    buyer.setEmail("race_" + UUID.randomUUID() + "@test.com");
    buyer.setPassword("$2a$10$dummyhashfortest");
    buyer = userRepository.save(buyer);
    eventRepository.save(new Event("Race", null, Instant.now().plusSeconds(86400), (UUID) null));
    Order order = orderRepository.save(
        new Order(buyer, List.of(), new BigDecimal("75.00"), "USD", OrderStatus.COMPLETED));
    String intent = "pi_test_race_" + UUID.randomUUID();
    paymentRecordRepository.save(new PaymentRecord(order.getId(), buyer.getId(),
        new BigDecimal("75.00"), "USD", PaymentStatus.SUCCESS, intent, null));
    RefundRequest refund = new RefundRequest(order.getId(), buyer.getId(), new BigDecimal("75.00"), "x");
    refund.approve(buyer.getId(), "ok");
    UUID refundId = refundRepository.save(refund).getId();

    MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    String payload = """
        {"id":"evt_test_race","object":"event","api_version":"2024-06-20","type":"charge.refunded",
         "data":{"object":{"id":"ch_test_race","object":"charge","payment_intent":"%s"}}}
        """.formatted(intent);

    ExecutorService pool = Executors.newSingleThreadExecutor();
    try (Connection finalise = dataSource.getConnection()) {
      finalise.setAutoCommit(false);
      try (PreparedStatement ps = finalise.prepareStatement(
          "SELECT id FROM refund_requests WHERE id = ? FOR UPDATE")) {
        ps.setObject(1, refundId);
        ps.executeQuery().close();
      }
      try (PreparedStatement ps = finalise.prepareStatement(
          "UPDATE refund_requests SET stripe_refund_id = 're_test_race' WHERE id = ?")) {
        ps.setObject(1, refundId);
        ps.executeUpdate();
      }

      Future<MvcResult> webhook = pool.submit(() -> mockMvc.perform(post("/api/webhooks/stripe")
              .contentType(MediaType.APPLICATION_JSON)
              .header("Stripe-Signature", sign(payload))
              .content(payload))
          .andReturn());

      assertThat(awaitLockWaiters(1)).as("webhook waits on the finalise row lock").isTrue();
      finalise.commit();
      assertThat(webhook.get(30, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
    } finally {
      pool.shutdownNow();
    }

    assertThat(jdbcTemplate.queryForObject(
        "SELECT stripe_refund_id FROM refund_requests WHERE id = ?", String.class, refundId))
        .isEqualTo("re_test_race");
    assertThat(jdbcTemplate.queryForObject(
        "SELECT status FROM refund_requests WHERE id = ?", String.class, refundId))
        .isEqualTo("COMPLETED");
  }

  private static String sign(String payload) throws Exception {
    long timestamp = Instant.now().getEpochSecond();
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    String signature = HexFormat.of().formatHex(
        mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8)));
    return "t=" + timestamp + ",v1=" + signature;
  }

  private boolean awaitLockWaiters(int waiters) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (System.nanoTime() < deadline) {
      Integer count = jdbcTemplate.queryForObject(
          "SELECT COUNT(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
              + " AND datname = current_database()", Integer.class);
      if (count != null && count >= waiters) {
        return true;
      }
      Thread.sleep(50);
    }
    return false;
  }
}
