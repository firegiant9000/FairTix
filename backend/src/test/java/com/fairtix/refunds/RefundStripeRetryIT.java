package com.fairtix.refunds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fairtix.auth.WithMockPrincipal;
import com.fairtix.events.domain.Event;
import com.fairtix.events.infrastructure.EventRepository;
import com.fairtix.inventory.domain.Seat;
import com.fairtix.inventory.domain.SeatStatus;
import com.fairtix.inventory.infrastructure.SeatRepository;
import com.fairtix.orders.domain.Order;
import com.fairtix.orders.domain.OrderStatus;
import com.fairtix.orders.infrastructure.OrderRepository;
import com.fairtix.payments.application.StripeIdempotencyKeys;
import com.fairtix.payments.application.StripePaymentService;
import com.fairtix.payments.domain.PaymentRecord;
import com.fairtix.payments.domain.PaymentStatus;
import com.fairtix.payments.infrastructure.PaymentRecordRepository;
import com.fairtix.refunds.application.RefundExecutionService;
import com.fairtix.refunds.application.RefundProviderException;
import com.fairtix.refunds.domain.RefundRequest;
import com.fairtix.refunds.domain.RefundStatus;
import com.fairtix.support.PostgresIT;
import com.fairtix.tickets.domain.Ticket;
import com.fairtix.tickets.infrastructure.TicketRepository;
import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;
import com.stripe.model.Refund;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.WebApplicationContext;

/**
 * Exposure gate (b), RM:344: the Stripe refund runs with no DB transaction open,
 * under a key derived from the refund request, and a failure to commit the DB side
 * after Stripe succeeded can be retried without refunding twice.
 *
 * <p>Stripe is never called. {@code createRefund} is stubbed on a spy, and returns
 * the same {@code re_} id for the same key, as Stripe does for a replayed key. The
 * commit failure is injected when the negative refund {@code PaymentRecord} is saved,
 * after the Stripe call and before the transaction commits.
 *
 * <p>Red on commit {@code 9d3738a}: the Stripe call ran inside the refund
 * transaction, a failed commit rolled the approval back to {@code REQUESTED}, and two
 * concurrent retries both applied the DB side. {@code webhookBeforeFinalise} is a
 * guard for the new ordering and passed before too.
 */
@TestPropertySource(properties = "stripe.enabled=true")
class RefundStripeRetryIT extends PostgresIT {

  @Autowired private RefundExecutionService refundExecutionService;
  @Autowired private UserRepository userRepository;
  @Autowired private EventRepository eventRepository;
  @Autowired private SeatRepository seatRepository;
  @Autowired private OrderRepository orderRepository;
  @Autowired private TicketRepository ticketRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private WebApplicationContext context;

  @MockitoSpyBean private StripePaymentService stripePaymentService;
  @MockitoSpyBean private PaymentRecordRepository paymentRecordRepository;

  private final List<String> sentKeys = new CopyOnWriteArrayList<>();
  private final List<Boolean> txActiveAtStripe = new CopyOnWriteArrayList<>();
  private final Map<String, String> refundIdByKey = new ConcurrentHashMap<>();
  private final AtomicInteger refundCounter = new AtomicInteger();
  private final AtomicBoolean failNextRefundRecord = new AtomicBoolean();

  private User buyer;
  private UUID adminId;
  private Event event;

  @BeforeEach
  void setUp() {
    buyer = user("retry-buyer");
    adminId = user("retry-admin").getId();
    event = eventRepository.save(
        new Event("Refund Retry Concert", null, Instant.now().plusSeconds(86400), (UUID) null));

    doAnswer(inv -> {
      String key = StripeIdempotencyKeys.refund(inv.getArgument(3));
      sentKeys.add(key);
      txActiveAtStripe.add(TransactionSynchronizationManager.isActualTransactionActive());      String id = refundIdByKey.computeIfAbsent(key, k -> "re_test_" + refundCounter.incrementAndGet());
      Refund refund = mock(Refund.class);
      when(refund.getId()).thenReturn(id);
      return refund;
    }).when(stripePaymentService).createRefund(anyString(), anyLong(), any(), any(UUID.class));

    // Only the armed REFUNDED record throws; every other save keeps the spy's delegation.
    doAnswer(inv -> {
      failNextRefundRecord.set(false);
      throw new IllegalStateException("simulated commit failure after the Stripe refund");
    }).when(paymentRecordRepository).save(argThat((PaymentRecord r) ->
        r != null && r.getStatus() == PaymentStatus.REFUNDED && failNextRefundRecord.get()));
  }

  @Test
  void commitFailureAfterStripe_retrySendsSameKey() {
    Order order = stripePaidOrder("75.00", "A1");
    RefundRequest requested = refundExecutionService.request(buyer.getId(), order.getId(), "Can't go");
    assertThat(requested.getStatus()).isEqualTo(RefundStatus.REQUESTED);

    failNextRefundRecord.set(true);
    assertThatThrownBy(() -> refundExecutionService.review(adminId, requested.getId(), true, "ok"))
        .hasMessageContaining("simulated commit failure");

    // The approval survived; nothing of the refund's DB side did.
    assertThat(refundStatus(requested.getId())).isEqualTo("APPROVED");
    assertThat(stripeRefundId(requested.getId())).isNull();
    assertThat(orderStatus(order.getId())).isEqualTo("COMPLETED");
    assertThat(negativeRecords(order.getId())).isZero();

    refundExecutionService.retry(adminId, requested.getId());

    String key = "fairtix-refund-" + requested.getId();
    assertThat(sentKeys).containsExactly(key, key);
    assertThat(stripeRefundId(requested.getId())).isEqualTo("re_test_1");
    assertThat(orderStatus(order.getId())).isEqualTo("REFUNDED");
    assertThat(negativeRecords(order.getId())).isEqualTo(1);
    assertThat(seatStatuses(order.getId())).containsOnly("AVAILABLE");
  }

  @Test
  void stripeCall_runsWithNoTransaction() {
    Order order = stripePaidOrder("75.00", "A2");
    RefundRequest requested = refundExecutionService.request(buyer.getId(), order.getId(), "Can't go");

    refundExecutionService.review(adminId, requested.getId(), true, "ok");

    assertThat(txActiveAtStripe).containsExactly(false);
    assertThat(orderStatus(order.getId())).isEqualTo("REFUNDED");
  }

  @Test
  void stripeFailure_leavesApprovedAndAudits() {
    Order order = stripePaidOrder("75.00", "A3");
    RefundRequest requested = refundExecutionService.request(buyer.getId(), order.getId(), "Can't go");
    doAnswer(inv -> {
      throw new RuntimeException("Failed to create Stripe refund: network");
    }).when(stripePaymentService).createRefund(anyString(), anyLong(), any(), any(UUID.class));

    assertThatThrownBy(() -> refundExecutionService.review(adminId, requested.getId(), true, "ok"))
        .isInstanceOf(RefundProviderException.class);

    assertThat(refundStatus(requested.getId())).isEqualTo("APPROVED");
    assertThat(orderStatus(order.getId())).isEqualTo("COMPLETED");
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM audit_logs WHERE action = 'REFUND_STRIPE_FAILED' AND resource_id = ?",
        Integer.class, requested.getId())).isEqualTo(1);
  }

  @Test
  void userAutoApprove_stripeFailure_returnsApproved() {
    Order order = stripePaidOrder("30.00", "A4");
    doAnswer(inv -> {
      throw new RuntimeException("Failed to create Stripe refund: network");
    }).when(stripePaymentService).createRefund(anyString(), anyLong(), any(), any(UUID.class));

    RefundRequest refund = refundExecutionService.request(buyer.getId(), order.getId(), "Can't go");

    assertThat(refund.getStatus()).isEqualTo(RefundStatus.APPROVED);
    assertThat(refundStatus(refund.getId())).isEqualTo("APPROVED");
    assertThat(orderStatus(order.getId())).isEqualTo("COMPLETED");
  }

  @Test
  void webhookBeforeFinalise_stillFinalisesOnce() {
    Order order = stripePaidOrder("75.00", "A5");
    RefundRequest requested = refundExecutionService.request(buyer.getId(), order.getId(), "Can't go");
    failNextRefundRecord.set(true);
    assertThatThrownBy(() -> refundExecutionService.review(adminId, requested.getId(), true, "ok"))
        .hasMessageContaining("simulated commit failure");
    // charge.refunded arrives before our side commits: the webhook completes the request.
    jdbcTemplate.update("UPDATE refund_requests SET status = 'COMPLETED' WHERE id = ?", requested.getId());

    refundExecutionService.retry(adminId, requested.getId());

    assertThat(refundStatus(requested.getId())).isEqualTo("COMPLETED");
    assertThat(stripeRefundId(requested.getId())).isEqualTo("re_test_1");
    assertThat(orderStatus(order.getId())).isEqualTo("REFUNDED");
    assertThat(negativeRecords(order.getId())).isEqualTo(1);
  }

  @Test
  void concurrentRetry_finalisesOnce() throws Exception {
    Order order = stripePaidOrder("75.00", "A6");
    RefundRequest requested = refundExecutionService.request(buyer.getId(), order.getId(), "Can't go");
    failNextRefundRecord.set(true);
    assertThatThrownBy(() -> refundExecutionService.review(adminId, requested.getId(), true, "ok"))
        .hasMessageContaining("simulated commit failure");

    // Both retries reach Stripe before either finalises; Stripe replays the same refund.
    CountDownLatch bothAtStripe = new CountDownLatch(2);
    doAnswer(inv -> {
      bothAtStripe.countDown();
      bothAtStripe.await(10, TimeUnit.SECONDS);
      String key = StripeIdempotencyKeys.refund(inv.getArgument(3));
      sentKeys.add(key);
      Refund refund = mock(Refund.class);
      when(refund.getId()).thenReturn(refundIdByKey.get(key));
      return refund;
    }).when(stripePaymentService).createRefund(anyString(), anyLong(), any(), any(UUID.class));

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> a = pool.submit(() -> safeRetry(requested.getId()));
      Future<?> b = pool.submit(() -> safeRetry(requested.getId()));
      a.get(30, TimeUnit.SECONDS);
      b.get(30, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }

    assertThat(negativeRecords(order.getId())).isEqualTo(1);
    assertThat(orderStatus(order.getId())).isEqualTo("REFUNDED");
    assertThat(stripeRefundId(requested.getId())).isEqualTo("re_test_1");
  }

  @Test
  void retry_onFinalisedRefund_isRejected() {
    Order order = stripePaidOrder("75.00", "A7");
    RefundRequest requested = refundExecutionService.request(buyer.getId(), order.getId(), "Can't go");
    refundExecutionService.review(adminId, requested.getId(), true, "ok");

    assertThatThrownBy(() -> refundExecutionService.retry(adminId, requested.getId()))
        .isInstanceOf(IllegalStateException.class);
    assertThat(sentKeys).hasSize(1);
    assertThat(negativeRecords(order.getId())).isEqualTo(1);
  }

  /** Review round 1 (#1): money moved but the DB side failed; leave a trace with the re_ id. */
  @Test
  void finaliseFailure_isAuditedWithStripeRefundId() {
    Order order = stripePaidOrder("75.00", "C1");
    RefundRequest requested = refundExecutionService.request(buyer.getId(), order.getId(), "Can't go");
    failNextRefundRecord.set(true);

    assertThatThrownBy(() -> refundExecutionService.review(adminId, requested.getId(), true, "ok"))
        .hasMessageContaining("simulated commit failure");

    List<String> details = jdbcTemplate.queryForList(
        "SELECT details FROM audit_logs WHERE action = 'REFUND_FINALISE_FAILED' AND resource_id = ?",
        String.class, requested.getId());
    assertThat(details).hasSize(1);
    assertThat(details.get(0)).contains("re_test_1");
  }

  /** Review round 1 (#1): the customer's request succeeded even if our bookkeeping didn't. */
  @Test
  void userAutoApprove_finaliseFailure_returnsApproved() {
    Order order = stripePaidOrder("30.00", "C2");
    failNextRefundRecord.set(true);

    RefundRequest refund = refundExecutionService.request(buyer.getId(), order.getId(), "Can't go");

    assertThat(refund.getStatus()).isEqualTo(RefundStatus.APPROVED);
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM audit_logs WHERE action = 'REFUND_FINALISE_FAILED' AND resource_id = ?",
        Integer.class, refund.getId())).isEqualTo(1);
  }

  // --- POST /api/admin/refunds/{id}/retry -------------------------------------

  @Test
  void retryEndpoint_afterCommitFailure_finalises() throws Exception {
    Order order = stripePaidOrder("75.00", "B1");
    RefundRequest requested = refundExecutionService.request(buyer.getId(), order.getId(), "Can't go");
    failNextRefundRecord.set(true);
    assertThatThrownBy(() -> refundExecutionService.review(adminId, requested.getId(), true, "ok"))
        .hasMessageContaining("simulated commit failure");

    mockMvc().perform(post("/api/admin/refunds/{id}/retry", requested.getId())
            .with(WithMockPrincipal.admin(adminId, "admin@test.com")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("APPROVED"));

    assertThat(orderStatus(order.getId())).isEqualTo("REFUNDED");
    assertThat(negativeRecords(order.getId())).isEqualTo(1);
  }

  @Test
  void retryEndpoint_onFinalisedRefund_is409() throws Exception {
    Order order = stripePaidOrder("75.00", "B2");
    RefundRequest requested = refundExecutionService.request(buyer.getId(), order.getId(), "Can't go");
    refundExecutionService.review(adminId, requested.getId(), true, "ok");

    mockMvc().perform(post("/api/admin/refunds/{id}/retry", requested.getId())
            .with(WithMockPrincipal.admin(adminId, "admin@test.com")))
        .andExpect(status().isConflict());
    assertThat(sentKeys).hasSize(1);
  }

  @Test
  void retryEndpoint_nonAdmin_is403() throws Exception {
    Order order = stripePaidOrder("75.00", "B3");
    RefundRequest requested = refundExecutionService.request(buyer.getId(), order.getId(), "Can't go");

    mockMvc().perform(post("/api/admin/refunds/{id}/retry", requested.getId())
            .with(WithMockPrincipal.user(buyer.getId(), buyer.getEmail())))
        .andExpect(status().isForbidden());
    assertThat(sentKeys).isEmpty();
  }

  // --- fixtures -------------------------------------------------------------

  private MockMvc mockMvc() {
    return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  private void safeRetry(UUID refundId) {
    try {
      refundExecutionService.retry(adminId, refundId);
    } catch (IllegalStateException alreadyDone) {
      // the loser of the race may see the refund already finalised
    }
  }

  private User user(String prefix) {
    User u = new User();
    u.setEmail(prefix + "_" + UUID.randomUUID() + "@test.com");
    u.setPassword("$2a$10$dummyhashfortest");
    u.setEmailVerified(true);
    return userRepository.save(u);
  }

  private Order stripePaidOrder(String amount, String seatNumber) {
    BigDecimal price = new BigDecimal(amount);
    Seat seat = seatRepository.save(new Seat(event, "Floor", "A", seatNumber, price));
    seat.setStatus(SeatStatus.SOLD);
    seat = seatRepository.save(seat);
    Order order = orderRepository.save(new Order(buyer, List.of(), price, "USD", OrderStatus.COMPLETED));
    ticketRepository.save(new Ticket(order, buyer, seat, event, price));
    paymentRecordRepository.save(new PaymentRecord(order.getId(), buyer.getId(), price, "USD",
        PaymentStatus.SUCCESS, "pi_test_" + UUID.randomUUID(), null));
    return order;
  }

  private String refundStatus(UUID refundId) {
    return jdbcTemplate.queryForObject(
        "SELECT status FROM refund_requests WHERE id = ?", String.class, refundId);
  }

  private String stripeRefundId(UUID refundId) {
    return jdbcTemplate.queryForObject(
        "SELECT stripe_refund_id FROM refund_requests WHERE id = ?", String.class, refundId);
  }

  private String orderStatus(UUID orderId) {
    return jdbcTemplate.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
  }

  private int negativeRecords(UUID orderId) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM payment_records WHERE order_id = ? AND amount < 0", Integer.class, orderId);
  }

  private List<String> seatStatuses(UUID orderId) {
    return jdbcTemplate.queryForList(
        "SELECT s.status FROM seats s JOIN tickets t ON t.seat_id = s.id WHERE t.order_id = ?",
        String.class, orderId);
  }
}
