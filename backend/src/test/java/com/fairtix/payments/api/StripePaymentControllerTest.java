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
import com.fairtix.payments.application.PaymentRefundedException;
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

  @MockitoSpyBean
  private StripePaymentService stripePaymentService;

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
        .createPaymentIntent(anyLong(), anyString(), any(),
            org.mockito.ArgumentMatchers.startsWith("fairtix-pi-"));

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
    doReturn(true).when(stripePaymentService).verifyPaymentSucceeded(anyString(), anyLong());

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
    doReturn(true).when(stripePaymentService).verifyPaymentSucceeded(anyString(), anyLong());

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
    doReturn(false).when(stripePaymentService).verifyPaymentSucceeded(anyString(), anyLong());

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
    doReturn(false).when(stripePaymentService).verifyPaymentSucceeded(anyString(), anyLong());

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
        .when(stripePaymentService).verifyPaymentSucceeded(anyString(), anyLong());

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

  /** A lost response: the order was created, then the client resubmits the same intent. */
  @Test
  void checkout_replayedIntent_returnsExistingOrderWithoutFailureAudit() throws Exception {
    doReturn(true).when(stripePaymentService).verifyPaymentSucceeded(anyString(), anyLong());
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
    verify(stripePaymentService, times(1)).verifyPaymentSucceeded(anyString(), anyLong());
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
    doReturn(true).when(stripePaymentService).verifyPaymentSucceeded(anyString(), anyLong());
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
    doReturn(true).when(stripePaymentService).verifyPaymentSucceeded(anyString(), anyLong());
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
