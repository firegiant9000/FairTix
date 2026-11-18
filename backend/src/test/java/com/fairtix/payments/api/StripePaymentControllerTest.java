package com.fairtix.payments.api;

import com.fairtix.auth.WithMockPrincipal;
import com.fairtix.events.domain.Event;
import com.fairtix.events.infrastructure.EventRepository;
import com.fairtix.inventory.domain.HoldStatus;
import com.fairtix.inventory.domain.Seat;
import com.fairtix.inventory.domain.SeatHold;
import com.fairtix.inventory.domain.SeatStatus;
import com.fairtix.inventory.infrastructure.SeatHoldRepository;
import com.fairtix.inventory.infrastructure.SeatRepository;
import com.fairtix.orders.application.OrderService;
import com.fairtix.orders.domain.Order;
import com.fairtix.orders.domain.OrderStatus;
import com.fairtix.orders.infrastructure.OrderRepository;
import com.fairtix.payments.application.PaymentIntentNotOwnedException;
import com.fairtix.payments.application.PaymentRefundedException;
import com.fairtix.queue.application.QueueService;
import com.fairtix.payments.application.StripePaymentService;
import com.fairtix.payments.application.StripePaymentService.ConnectContext;
import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestPropertySource(properties = "stripe.enabled=true")
@Transactional
class StripePaymentControllerTest {

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private EventRepository eventRepository;
  @Autowired private SeatRepository seatRepository;
  @Autowired private SeatHoldRepository seatHoldRepository;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private OrderRepository orderRepository;
  @Autowired private jakarta.persistence.EntityManager entityManager;

  @MockitoSpyBean
  private StripePaymentService stripePaymentService;

  @MockitoSpyBean
  private OrderService orderService;

  @MockitoSpyBean
  private QueueService queueService;

  private MockMvc mockMvc;
  private User user;
  private SeatHold confirmedHold;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context)
        .apply(springSecurity())
        .build();

    user = new User();
    user.setEmail("stripetest@test.com");
    user.setPassword(passwordEncoder.encode("Test1234!"));
    user.setEmailVerified(true);
    user = userRepository.save(user);

    Event event = new Event("Stripe Test Event", null, Instant.now().plusSeconds(86400 * 30), null);
    event = eventRepository.save(event);

    Seat seat = new Seat(event, "B", "2", "3", new BigDecimal("50.00"));
    seat.setStatus(SeatStatus.BOOKED);
    seat = seatRepository.save(seat);

    confirmedHold = new SeatHold(seat, user.getId(), Instant.now().plusSeconds(600));
    confirmedHold.setStatus(HoldStatus.CONFIRMED);
    confirmedHold = seatHoldRepository.save(confirmedHold);
  }

  @Test
  void createIntent_returnsClientSecret() throws Exception {
    // ConnectContext may be null (no Connect org); the key is always the
    // deterministic online-intent key (exposure gate (b)).
    doReturn("pi_test_secret_xyz").when(stripePaymentService)
        .createPaymentIntent(anyLong(), anyString(), any(), eq(user.getId()),
            org.mockito.ArgumentMatchers.startsWith("fairtix-pi2-"));

    String body = """
        { "holdIds": ["%s"] }
        """.formatted(confirmedHold.getId());

    mockMvc.perform(post("/api/payments/intent")
            .with(WithMockPrincipal.user(user.getId(), user.getEmail()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.clientSecret").value("pi_test_secret_xyz"));
  }

  @Test
  void checkout_withStripeIntentId_succeeds() throws Exception {
    doReturn(true).when(stripePaymentService).verifyPaymentSucceeded(anyString(), anyLong(), any());

    String body = """
        {
          "holdIds": ["%s"],
          "paymentIntentId": "pi_test_123"
        }
        """.formatted(confirmedHold.getId());

    mockMvc.perform(post("/api/payments/checkout")
            .with(WithMockPrincipal.user(user.getId(), user.getEmail()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.paymentStatus").value("SUCCESS"))
        .andExpect(jsonPath("$.orderStatus").value("COMPLETED"));
  }

  @Test
  void checkout_withStripeIntentId_issuesTicket() throws Exception {
    doReturn(true).when(stripePaymentService).verifyPaymentSucceeded(anyString(), anyLong(), any());

    String body = """
        {
          "holdIds": ["%s"],
          "paymentIntentId": "pi_ticket_verify"
        }
        """.formatted(confirmedHold.getId());

    mockMvc.perform(post("/api/payments/checkout")
            .with(WithMockPrincipal.user(user.getId(), user.getEmail()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.paymentStatus").value("SUCCESS"))
        .andExpect(jsonPath("$.orderStatus").value("COMPLETED"));

    mockMvc.perform(get("/api/tickets")
            .with(WithMockPrincipal.user(user.getId(), user.getEmail())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].status").value("VALID"))
        .andExpect(jsonPath("$[0].price").value(50.00));
  }

  @Test
  void checkout_withStripeIntentId_failsWhenNotSucceeded() throws Exception {
    doReturn(false).when(stripePaymentService).verifyPaymentSucceeded(anyString(), anyLong(), any());

    String body = """
        {
          "holdIds": ["%s"],
          "paymentIntentId": "pi_test_456"
        }
        """.formatted(confirmedHold.getId());

    mockMvc.perform(post("/api/payments/checkout")
            .with(WithMockPrincipal.user(user.getId(), user.getEmail()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isPaymentRequired());
  }

  /**
   * A paid intent whose amount no longer matches the holds (price change, swapped
   * hold) must be on record for the runbook check stripe_charges_without_order,
   * in the format its regexes parse.
   */
  @Test
  void checkout_amountMismatch_writesOrderFailedAuditForRunbook() throws Exception {
    doReturn(false).when(stripePaymentService).verifyPaymentSucceeded(anyString(), anyLong(), any());

    String body = """
        {
          "holdIds": ["%s"],
          "paymentIntentId": "pi_amount_mismatch"
        }
        """.formatted(confirmedHold.getId());

    mockMvc.perform(post("/api/payments/checkout")
            .with(WithMockPrincipal.user(user.getId(), user.getEmail()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isPaymentRequired());

    java.util.List<String> details = jdbcTemplate.queryForList(
        "SELECT details FROM audit_logs WHERE action = 'STRIPE_ORDER_FAILED' AND user_id = ?",
        String.class, user.getId());
    org.assertj.core.api.Assertions.assertThat(details).hasSize(1);
    org.assertj.core.api.Assertions.assertThat(details.get(0))
        .contains("stripe_intent=pi_amount_mismatch,")
        .contains("holdIds=[" + confirmedHold.getId() + "]")
        .contains("error=PaymentNotVerified");
    org.assertj.core.api.Assertions.assertThat(
        seatHoldRepository.findById(confirmedHold.getId()).orElseThrow().getStatus())
        .isEqualTo(HoldStatus.CONFIRMED);
  }

  @Test
  void checkout_withRefundedIntent_isRejectedWithoutOrderFailedAudit() throws Exception {
    doThrow(new PaymentRefundedException("pi_refunded", "refunded"))
        .when(stripePaymentService).verifyPaymentSucceeded(anyString(), anyLong(), any());

    String body = """
        {
          "holdIds": ["%s"],
          "paymentIntentId": "pi_refunded"
        }
        """.formatted(confirmedHold.getId());

    mockMvc.perform(post("/api/payments/checkout")
            .with(WithMockPrincipal.user(user.getId(), user.getEmail()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PAYMENT_REFUNDED"));

    Integer orderFailedRows = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM audit_logs WHERE action = 'STRIPE_ORDER_FAILED' AND user_id = ?",
        Integer.class, user.getId());
    org.assertj.core.api.Assertions.assertThat(orderFailedRows).isZero();
    org.assertj.core.api.Assertions.assertThat(
        seatHoldRepository.findById(confirmedHold.getId()).orElseThrow().getStatus())
        .isEqualTo(HoldStatus.CONFIRMED);
  }

  @Test
  void checkout_verifiesIntentForTheCaller() throws Exception {
    doReturn(true).when(stripePaymentService).verifyPaymentSucceeded(anyString(), anyLong(), any());

    mockMvc.perform(post("/api/payments/checkout")
            .with(WithMockPrincipal.user(user.getId(), user.getEmail()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(checkoutBody(confirmedHold.getId(), "pi_caller")))
        .andExpect(status().isCreated());

    verify(stripePaymentService).verifyPaymentSucceeded(eq("pi_caller"), anyLong(), eq(user.getId()));
  }

  /**
   * An intent that isn't the caller's is refused before any order or audit row:
   * it must not show up in stripe_charges_without_order as a charge to refund.
   */
  @Test
  void checkout_intentNotOwnedByCaller_isRejectedWithoutOrderOrAudit() throws Exception {
    doThrow(new PaymentIntentNotOwnedException("pi_not_owned", user.getId(), "other_user"))
        .when(stripePaymentService).verifyPaymentSucceeded(anyString(), anyLong(), any());

    mockMvc.perform(post("/api/payments/checkout")
            .with(WithMockPrincipal.user(user.getId(), user.getEmail()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(checkoutBody(confirmedHold.getId(), "pi_not_owned")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PAYMENT_NOT_USABLE"));

    Integer orderFailedRows = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM audit_logs WHERE action LIKE 'STRIPE_ORDER_%' AND user_id = ?",
        Integer.class, user.getId());
    org.assertj.core.api.Assertions.assertThat(orderFailedRows).isZero();
    org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM orders WHERE user_id = ?", Integer.class, user.getId())).isZero();
    org.assertj.core.api.Assertions.assertThat(
        seatHoldRepository.findById(confirmedHold.getId()).orElseThrow().getStatus())
        .isEqualTo(HoldStatus.CONFIRMED);
  }

  /** A lost response: the order was created, then the client resubmits the same intent. */
  @Test
  void checkout_replayedIntent_returnsExistingOrderWithoutFailureAudit() throws Exception {
    doReturn(true).when(stripePaymentService).verifyPaymentSucceeded(anyString(), anyLong(), any());
    String body = checkoutBody(confirmedHold.getId(), "pi_replay");

    String first = mockMvc.perform(post("/api/payments/checkout")
            .with(WithMockPrincipal.user(user.getId(), user.getEmail()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    String orderId = com.jayway.jsonpath.JsonPath.read(first, "$.orderId");

    mockMvc.perform(post("/api/payments/checkout")
            .with(WithMockPrincipal.user(user.getId(), user.getEmail()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.orderId").value(orderId))
        .andExpect(jsonPath("$.paymentStatus").value("SUCCESS"))
        .andExpect(jsonPath("$.orderStatus").value("COMPLETED"));

    // The replay is answered from the payment record, not a second verification.
    verify(stripePaymentService, times(1)).verifyPaymentSucceeded(anyString(), anyLong(), any());
    Integer orders = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM orders WHERE user_id = ?", Integer.class, user.getId());
    org.assertj.core.api.Assertions.assertThat(orders).isEqualTo(1);
    Integer notCreatedRows = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM audit_logs WHERE action IN ('STRIPE_ORDER_FAILED', 'STRIPE_ORDER_BUSY')"
            + " AND user_id = ?",
        Integer.class, user.getId());
    org.assertj.core.api.Assertions.assertThat(notCreatedRows).isZero();
  }

  @Test
  void checkout_intentUsedByAnotherUser_isRejectedWithoutLeakingOrder() throws Exception {
    doReturn(true).when(stripePaymentService).verifyPaymentSucceeded(anyString(), anyLong(), any());
    String first = mockMvc.perform(post("/api/payments/checkout")
            .with(WithMockPrincipal.user(user.getId(), user.getEmail()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(checkoutBody(confirmedHold.getId(), "pi_shared")))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    String orderId = com.jayway.jsonpath.JsonPath.read(first, "$.orderId");

    User other = new User();
    other.setEmail("stripetest-other@test.com");
    other.setPassword(passwordEncoder.encode("Test1234!"));
    other.setEmailVerified(true);
    other = userRepository.save(other);
    SeatHold otherHold = confirmedHoldFor(other, "4");

    String response = mockMvc.perform(post("/api/payments/checkout")
            .with(WithMockPrincipal.user(other.getId(), other.getEmail()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(checkoutBody(otherHold.getId(), "pi_shared")))
        .andExpect(status().isConflict())
        .andReturn().getResponse().getContentAsString();

    org.assertj.core.api.Assertions.assertThat(response).doesNotContain(orderId);
    org.assertj.core.api.Assertions.assertThat(
        seatHoldRepository.findById(otherHold.getId()).orElseThrow().getStatus())
        .isEqualTo(HoldStatus.CONFIRMED);
  }

  @Test
  void checkout_intentReplayedForDifferentHolds_isRejected() throws Exception {
    doReturn(true).when(stripePaymentService).verifyPaymentSucceeded(anyString(), anyLong(), any());
    mockMvc.perform(post("/api/payments/checkout")
            .with(WithMockPrincipal.user(user.getId(), user.getEmail()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(checkoutBody(confirmedHold.getId(), "pi_other_holds")))
        .andExpect(status().isCreated());

    SeatHold secondHold = confirmedHoldFor(user, "5");
    mockMvc.perform(post("/api/payments/checkout")
            .with(WithMockPrincipal.user(user.getId(), user.getEmail()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(checkoutBody(secondHold.getId(), "pi_other_holds")))
        .andExpect(status().isConflict());

    org.assertj.core.api.Assertions.assertThat(
        seatHoldRepository.findById(secondHold.getId()).orElseThrow().getStatus())
        .isEqualTo(HoldStatus.CONFIRMED);
  }

  /** A refunded order must not be replayed as a confirmation. */
  @Test
  void checkout_replayOfRefundedOrder_answersPaymentRefunded() throws Exception {
    doReturn(true).when(stripePaymentService).verifyPaymentSucceeded(anyString(), anyLong(), any());
    String body = checkoutBody(confirmedHold.getId(), "pi_replay_refunded");
    String first = mockMvc.perform(post("/api/payments/checkout")
            .with(WithMockPrincipal.user(user.getId(), user.getEmail()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    Order order = orderRepository.findById(
        java.util.UUID.fromString(com.jayway.jsonpath.JsonPath.read(first, "$.orderId")))
        .orElseThrow();
    order.setStatus(OrderStatus.REFUNDED);
    orderRepository.saveAndFlush(order);
    // The replay must read the status from the database, not this session's copy.
    entityManager.clear();

    mockMvc.perform(post("/api/payments/checkout")
            .with(WithMockPrincipal.user(user.getId(), user.getEmail()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PAYMENT_REFUNDED"));
  }

  /**
   * Two submits with the same intent race: the other one created the order while
   * this one failed on the released holds. This one answers with that order.
   */
  @Test
  void checkout_losingConcurrentSubmit_returnsTheOrderTheWinnerCreated() throws Exception {
    doReturn(true).when(stripePaymentService).verifyPaymentSucceeded(anyString(), anyLong(), any());
    doAnswer(inv -> {
      inv.callRealMethod();
      throw new IllegalArgumentException("Hold is not confirmed (status: RELEASED)");
    }).when(orderService).createOrderWithStripePayment(any(), any(), anyString());

    mockMvc.perform(post("/api/payments/checkout")
            .with(WithMockPrincipal.user(user.getId(), user.getEmail()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(checkoutBody(confirmedHold.getId(), "pi_race")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.orderStatus").value("COMPLETED"));

    Integer orderFailedRows = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM audit_logs WHERE action = 'STRIPE_ORDER_FAILED' AND user_id = ?",
        Integer.class, user.getId());
    org.assertj.core.api.Assertions.assertThat(orderFailedRows).isZero();
  }

  /**
   * The losing submit's replay finds the intent paid for an order that does not
   * cover these holds: the replay's 409 is the answer, not the original failure.
   */
  @Test
  void checkout_losingSubmitWhoseReplayRefuses_answersWithReplayConflict() throws Exception {
    doReturn(true).when(stripePaymentService).verifyPaymentSucceeded(anyString(), anyLong(), any());
    doAnswer(inv -> {
      inv.callRealMethod();
      throw new IllegalArgumentException("Hold is not confirmed (status: RELEASED)");
    }).when(orderService).createOrderWithStripePayment(any(), any(), anyString());
    doReturn(java.util.Optional.empty()).when(orderService).findOrderForHolds(any(), any());

    mockMvc.perform(post("/api/payments/checkout")
            .with(WithMockPrincipal.user(user.getId(), user.getEmail()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(checkoutBody(confirmedHold.getId(), "pi_race_refused")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message")
            .value("This payment has already been used for a different order"));
  }

  /** The losing submit's replay finds the winner's order already refunded. */
  @Test
  void checkout_losingSubmitWhoseReplayFindsRefundedOrder_answersPaymentRefunded()
      throws Exception {
    doReturn(true).when(stripePaymentService).verifyPaymentSucceeded(anyString(), anyLong(), any());
    doAnswer(inv -> {
      inv.callRealMethod();
      throw new IllegalArgumentException("Hold is not confirmed (status: RELEASED)");
    }).when(orderService).createOrderWithStripePayment(any(), any(), anyString());
    doAnswer(inv -> {
      @SuppressWarnings("unchecked")
      var found = (java.util.Optional<Order>) inv.callRealMethod();
      found.ifPresent(o -> o.setStatus(OrderStatus.REFUNDED));
      return found;
    }).when(orderService).findOrderForHolds(any(), any());

    mockMvc.perform(post("/api/payments/checkout")
            .with(WithMockPrincipal.user(user.getId(), user.getEmail()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(checkoutBody(confirmedHold.getId(), "pi_race_refunded")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PAYMENT_REFUNDED"));
  }

  /** The replay lookup itself fails: the original order failure is the answer. */
  @Test
  void checkout_failedSubmitWhoseReplayLookupFails_answersWithOriginalError() throws Exception {
    doReturn(true).when(stripePaymentService).verifyPaymentSucceeded(anyString(), anyLong(), any());
    doAnswer(inv -> {
      inv.callRealMethod();
      throw new IllegalArgumentException("Hold is not confirmed (status: RELEASED)");
    }).when(orderService).createOrderWithStripePayment(any(), any(), anyString());
    doThrow(new IllegalStateException("lookup failed"))
        .when(orderService).findOrderForHolds(any(), any());

    mockMvc.perform(post("/api/payments/checkout")
            .with(WithMockPrincipal.user(user.getId(), user.getEmail()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(checkoutBody(confirmedHold.getId(), "pi_race_lookup_failed")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("Hold is not confirmed (status: RELEASED)"));
  }

  /**
   * Order creation completes the queue entry, so the replay must run before the
   * queue-clearance check or a resubmit for a queued event gets a 403.
   */
  @Test
  void checkout_replayForQueuedEvent_returnsExistingOrder() throws Exception {
    doReturn(true).when(stripePaymentService).verifyPaymentSucceeded(anyString(), anyLong(), any());
    Event event = confirmedHold.getSeat().getEvent();
    event.updateQueueSettings(true, null);
    eventRepository.save(event);
    doReturn(true).doReturn(false).when(queueService).hasCheckoutClearance(any(), any());
    doNothing().when(queueService).completeQueueEntry(any(), any());

    String body = checkoutBody(confirmedHold.getId(), "pi_replay_queued");
    String first = mockMvc.perform(post("/api/payments/checkout")
            .with(WithMockPrincipal.user(user.getId(), user.getEmail()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    String orderId = com.jayway.jsonpath.JsonPath.read(first, "$.orderId");

    mockMvc.perform(post("/api/payments/checkout")
            .with(WithMockPrincipal.user(user.getId(), user.getEmail()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.orderId").value(orderId));
  }

  private SeatHold confirmedHoldFor(User owner, String seatNumber) {
    Event event = confirmedHold.getSeat().getEvent();
    Seat seat = new Seat(event, "B", "2", seatNumber, new BigDecimal("50.00"));
    seat.setStatus(SeatStatus.BOOKED);
    seat = seatRepository.save(seat);
    SeatHold hold = new SeatHold(seat, owner.getId(), Instant.now().plusSeconds(600));
    hold.setStatus(HoldStatus.CONFIRMED);
    return seatHoldRepository.save(hold);
  }

  private static String checkoutBody(java.util.UUID holdId, String paymentIntentId) {
    return """
        {
          "holdIds": ["%s"],
          "paymentIntentId": "%s"
        }
        """.formatted(holdId, paymentIntentId);
  }
}
