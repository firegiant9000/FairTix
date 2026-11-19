package com.fairtix.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fairtix.audit.application.AuditService;
import com.fairtix.auth.WithMockPrincipal;
import com.fairtix.common.LockBusyException;
import com.fairtix.events.domain.Event;
import com.fairtix.events.infrastructure.EventRepository;
import com.fairtix.inventory.application.SeatHoldService;
import com.fairtix.inventory.domain.Seat;
import com.fairtix.inventory.domain.SeatHold;
import com.fairtix.inventory.infrastructure.SeatRepository;
import com.fairtix.orders.application.OrderService;
import com.fairtix.payments.application.StripeIdempotencyKeys;
import com.fairtix.payments.application.StripePaymentService;
import com.fairtix.payments.domain.OnlinePaymentIntent;
import com.fairtix.payments.infrastructure.OnlinePaymentIntentRepository;
import com.fairtix.support.PostgresIT;
import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Stripe checkout once the charge has succeeded: the two ways the order could
 * still fail for reasons that have nothing to do with the order itself.
 *
 * <p>Stripe is never called: verification is stubbed on a spy.
 */
@TestPropertySource(properties = "stripe.enabled=true")
class StripeCheckoutAfterChargeIT extends PostgresIT {

  static final String INTENT = "pi_test_after_charge";

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private EventRepository eventRepository;
  @Autowired private SeatRepository seatRepository;
  @Autowired private SeatHoldService seatHoldService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private DataSource dataSource;
  @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;
  @Autowired private OnlinePaymentIntentRepository onlinePaymentIntentRepository;

  @MockitoSpyBean private StripePaymentService stripePaymentService;
  @MockitoSpyBean private OrderService orderService;
  @MockitoSpyBean private AuditService auditService;

  private User buyer;
  private SeatHold hold;

  @BeforeEach
  void setUp() {
    User user = new User();
    user.setEmail("aftercharge@test.com");
    user.setPassword("$2a$10$dummyhashfortest");
    user.setEmailVerified(true);
    buyer = userRepository.save(user);
    Event event = eventRepository.save(
        new Event("After Charge Concert", null, Instant.now().plusSeconds(86400), (UUID) null));
    Seat seat = seatRepository.save(new Seat(event, "A", "1", "1", new BigDecimal("40.00")));
    hold = seatHoldService.createHold(event.getId(), List.of(seat.getId()), buyer.getId(), 10).get(0);
    seatHoldService.confirmHold(hold.getId(), buyer.getId());
    doReturn(true).when(stripePaymentService).verifyPaymentSucceeded(eq(INTENT), anyLong(), any());
    // The row /api/payments/intent stores for this user and hold (no Connect org: direct routing).
    onlinePaymentIntentRepository.save(new OnlinePaymentIntent(buyer.getId(),
        StripeIdempotencyKeys.holdSetHash(List.of(hold.getId())), 4000L,
        StripeIdempotencyKeys.routingHash(null), INTENT));
  }

  /**
   * The purchase-cap lock is busy on the first two attempts (another request from
   * the same user is still running). The customer has paid, so checkout tries
   * again instead of answering "try again": the third attempt creates the order.
   */
  @Test
  void capLockBusyAfterTheCharge_isRetried_andTheOrderCompletes() throws Exception {
    doThrow(new LockBusyException("busy", null))
        .doThrow(new LockBusyException("busy", null))
        .doCallRealMethod()
        .when(orderService).createOrderWithStripePayment(any(), any(), anyString());

    checkout().andExpect(status().isCreated());

    verify(orderService, times(3)).createOrderWithStripePayment(any(), any(), anyString());
    assertThat(count("SELECT COUNT(*) FROM orders WHERE status = 'COMPLETED'")).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM tickets")).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM audit_logs WHERE action = 'STRIPE_ORDER_FAILED'")).isZero();
  }

  /**
   * A lock that stays busy for every attempt ends in the retryable 409. The
   * captured charge is on record as STRIPE_ORDER_BUSY, not as a failed order: the
   * holds are still confirmed, so the customer's next attempt can complete it.
   */
  @Test
  void capLockBusyOnEveryAttempt_endsIn409_withTheChargeOnRecordAsBusy() throws Exception {
    doThrow(new LockBusyException("busy", null))
        .when(orderService).createOrderWithStripePayment(any(), any(), anyString());

    checkout().andExpect(status().isConflict());

    verify(orderService, times(3)).createOrderWithStripePayment(any(), any(), anyString());
    assertThat(count("SELECT COUNT(*) FROM orders")).isZero();
    assertThat(count("SELECT COUNT(*) FROM audit_logs WHERE action = 'STRIPE_ORDER_BUSY'")).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM audit_logs WHERE action = 'STRIPE_ORDER_FAILED'")).isZero();
    assertThat(jdbcTemplate.queryForObject(
        "SELECT details FROM audit_logs WHERE action = 'STRIPE_ORDER_BUSY'", String.class))
        .contains("stripe_intent=" + INTENT);
    assertThat(count("SELECT COUNT(*) FROM seat_holds WHERE status = 'CONFIRMED'")).isEqualTo(1);
  }

  /**
   * Two submits with the same intent race. The other request's order commits, and
   * this request's own order creation then fails. It answers with the committed
   * order instead of an error, and writes no refund row.
   */
  @Test
  void losingConcurrentSubmit_returnsTheCommittedOrder() throws Exception {
    // The winner commits in a transaction of its own, as the other request would;
    // only then does this request's attempt fail and roll back.
    var winner = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    winner.setPropagationBehavior(
        org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    doAnswer(inv -> {
      winner.execute(status -> {
        try {
          return inv.callRealMethod();
        } catch (Throwable t) {
          throw new IllegalStateException(t);
        }
      });
      throw new IllegalArgumentException("Hold is not confirmed (status: RELEASED)");
    }).when(orderService).createOrderWithStripePayment(any(), any(), anyString());

    checkout()
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.orderStatus").value("COMPLETED"));

    assertThat(count("SELECT COUNT(*) FROM orders WHERE status = 'COMPLETED'")).isEqualTo(1);
    assertThat(count(
        "SELECT COUNT(*) FROM audit_logs WHERE action IN ('STRIPE_ORDER_FAILED', 'STRIPE_ORDER_BUSY')"))
        .isZero();
  }

  /**
   * The audit write fails too (for example the pool is exhausted). The customer
   * still gets the retryable 409 for the original error, not a 500 for the audit.
   */
  @Test
  void auditFailureAfterBusy_stillAnswersTheOriginal409() throws Exception {
    doThrow(new LockBusyException("busy", null))
        .when(orderService).createOrderWithStripePayment(any(), any(), anyString());
    doThrow(new IllegalStateException("no connection"))
        .when(auditService).log(any(), eq("STRIPE_ORDER_BUSY"), any(), any(), any());

    checkout()
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("REQUEST_IN_PROGRESS"));
  }

  /**
   * The customer tries again after the busy 409 and the order completes. The busy
   * row stays, but the intent now has a payment record, so the runbook's refund
   * check leaves it out.
   */
  @Test
  void retryAfterBusy409_completesTheOrder() throws Exception {
    doThrow(new LockBusyException("busy", null))
        .doThrow(new LockBusyException("busy", null))
        .doThrow(new LockBusyException("busy", null))
        .doCallRealMethod()
        .when(orderService).createOrderWithStripePayment(any(), any(), anyString());

    checkout().andExpect(status().isConflict());
    checkout().andExpect(status().isCreated());

    assertThat(count("SELECT COUNT(*) FROM orders WHERE status = 'COMPLETED'")).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM payment_records WHERE transaction_id = '" + INTENT + "'"))
        .isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM audit_logs WHERE action = 'STRIPE_ORDER_FAILED'")).isZero();
  }

  /**
   * A double submit, or a resubmit after a lost response: the first checkout
   * creates the order, the second with the same intent gets that order back.
   * Nothing is owed to the customer, so no refund row is written.
   */
  @Test
  void secondCheckoutForAFulfilledIntent_returnsTheOrderWithoutARefundRow() throws Exception {
    String first = checkout().andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    String orderId = com.jayway.jsonpath.JsonPath.read(first, "$.orderId");
    checkout().andExpect(status().isCreated())
        .andExpect(jsonPath("$.orderId").value(orderId));

    assertThat(count("SELECT COUNT(*) FROM orders WHERE status = 'COMPLETED'")).isEqualTo(1);
    assertThat(count(
        "SELECT COUNT(*) FROM audit_logs WHERE action IN ('STRIPE_ORDER_FAILED', 'STRIPE_ORDER_BUSY')"))
        .isZero();
  }

  /**
   * Checkout runs on one pooled connection. Every other connection in the pool is
   * taken; the order still completes, and its audit rows are written. With an
   * audit write in a transaction of its own, checkout needed a second connection
   * here and waited for Hikari's connection timeout (30 s) before failing.
   */
  @Test
  @Timeout(20)
  void checkout_needsOnlyOnePooledConnection() throws Exception {
    int poolSize = dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize();
    List<Connection> taken = new ArrayList<>();
    try {
      for (int i = 0; i < poolSize - 1; i++) {
        taken.add(dataSource.getConnection());
      }

      orderService.createOrderWithStripePayment(buyer.getId(), List.of(hold.getId()), INTENT);
    } finally {
      for (Connection connection : taken) {
        connection.close();
      }
    }

    assertThat(count("SELECT COUNT(*) FROM orders WHERE status = 'COMPLETED'")).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM audit_logs WHERE action = 'PAYMENT_PROCESSED'")).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM audit_logs WHERE action = 'TICKETS_ISSUED'")).isEqualTo(1);
  }

  private org.springframework.test.web.servlet.ResultActions checkout() throws Exception {
    MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    return mockMvc.perform(post("/api/payments/checkout")
        .with(WithMockPrincipal.user(buyer.getId(), buyer.getEmail()))
        .contentType(MediaType.APPLICATION_JSON)
        .content("""
            { "holdIds": ["%s"], "paymentIntentId": "%s" }
            """.formatted(hold.getId(), INTENT)));
  }

  private int count(String sql) {
    return jdbcTemplate.queryForObject(sql, Integer.class);
  }
}
