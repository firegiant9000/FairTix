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
import com.fairtix.organizations.application.OrgSalesCapService;
import com.fairtix.organizations.domain.Organization;
import com.fairtix.organizations.infrastructure.OrganizationRepository;
import com.fairtix.payments.application.OnlinePaymentIntentService;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.assertj.core.api.Assertions.assertThat;
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

  @MockitoSpyBean
  private OnlinePaymentIntentService onlinePaymentIntentService;

  @MockitoSpyBean
  private OrgSalesCapService salesCapService;

  @MockitoSpyBean
  private OrganizationRepository organizationRepository;

  @MockitoSpyBean
  private com.fairtix.audit.application.AuditService auditService;

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
    doReturn(new StripePaymentService.StripeIntent("pi_test_xyz", "requires_payment_method",
        "pi_test_secret_xyz")).when(stripePaymentService)
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

    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM online_payment_intents WHERE user_id = ? AND status = 'ACTIVE'",
        Integer.class, user.getId())).isEqualTo(1);
  }

  @Test
  void createIntent_duplicateHoldIds_returns400() throws Exception {
    String body = """
        { "holdIds": ["%s", "%s"] }
        """.formatted(confirmedHold.getId(), confirmedHold.getId());

    mockMvc.perform(post("/api/payments/intent")
            .with(WithMockPrincipal.user(user.getId(), user.getEmail()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isBadRequest());

    verify(stripePaymentService, never())
        .createPaymentIntent(anyLong(), anyString(), any(), any(), anyString());
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM online_payment_intents", Integer.class)).isZero();
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

  private void insertActiveIntentRow(String paymentIntentId) {
    entityManager.flush(); // the user row must exist for the FK
    jdbcTemplate.update(
        "INSERT INTO online_payment_intents (id, user_id, hold_set_hash, amount_cents,"
            + " routing_hash, stripe_payment_intent_id, status)"
            + " VALUES (?, ?, ?, 5000, ?, ?, 'ACTIVE')",
        java.util.UUID.randomUUID(), user.getId(), "a".repeat(64), "b".repeat(64),
        paymentIntentId);
  }

  @Test
  void checkout_completesTheActiveOnlineIntentRow() throws Exception {
    doReturn(true).when(stripePaymentService).verifyPaymentSucceeded(anyString(), anyLong(), any());
    insertActiveIntentRow("pi_row_done");

    String body = """
        { "holdIds": ["%s"], "paymentIntentId": "pi_row_done" }
        """.formatted(confirmedHold.getId());

    mockMvc.perform(post("/api/payments/checkout")
            .with(WithMockPrincipal.user(user.getId(), user.getEmail()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isCreated());

    assertThat(jdbcTemplate.queryForMap(
        "SELECT status, invalidated_reason FROM online_payment_intents"
            + " WHERE stripe_payment_intent_id = 'pi_row_done'"))
        .containsEntry("status", "INVALIDATED")
        .containsEntry("invalidated_reason", "COMPLETED");
  }

  @Test
  void intentAfterCompletedCheckout_isRefusedWithoutCreatingAnIntent() throws Exception {
    // Ending the row is safe only because the order consumes the holds: /intent must then
    // refuse them rather than create a second payable intent for a paid hold set.
    doReturn(true).when(stripePaymentService).verifyPaymentSucceeded(anyString(), anyLong(), any());
    insertActiveIntentRow("pi_paid_once");

    String body = """
        { "holdIds": ["%s"], "paymentIntentId": "pi_paid_once" }
        """.formatted(confirmedHold.getId());
    mockMvc.perform(post("/api/payments/checkout")
            .with(WithMockPrincipal.user(user.getId(), user.getEmail()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isCreated());

    mockMvc.perform(post("/api/payments/intent")
            .with(WithMockPrincipal.user(user.getId(), user.getEmail()))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                { "holdIds": ["%s"] }
                """.formatted(confirmedHold.getId())))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("HOLD_CONFLICT"));

    verify(stripePaymentService, never())
        .createPaymentIntent(anyLong(), anyString(), any(), any(), anyString());
  }

  @Test
  void checkout_intentRowInvalidationFails_stillReturns201() throws Exception {
    doReturn(true).when(stripePaymentService).verifyPaymentSucceeded(anyString(), anyLong(), any());
    doThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"))
        .when(onlinePaymentIntentService).markCompleted(anyString());

    String body = """
        { "holdIds": ["%s"], "paymentIntentId": "pi_row_fail" }
        """.formatted(confirmedHold.getId());

    mockMvc.perform(post("/api/payments/checkout")
            .with(WithMockPrincipal.user(user.getId(), user.getEmail()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.orderStatus").value("COMPLETED"));

    verify(onlinePaymentIntentService).markCompleted("pi_row_fail");
  }

  /** Puts the hold's event under an org with a connected account, so checkout records the sale. */
  private java.util.UUID attachConnectOrg() {
    Organization org = new Organization("Ledger Org", "ledger-" + java.util.UUID.randomUUID(), "o@test.com");
    org.setStripeConnectAccountId("acct_test_ledger");
    org = organizationRepository.save(org);
    Event event = confirmedHold.getSeat().getEvent();
    event.setOrganizationId(org.getId());
    eventRepository.save(event);
    entityManager.flush();
    return org.getId();
  }

  @Test
  void checkout_connectOrg_recordsTheSaleOnce_evenWhenReplayed() throws Exception {
    doReturn(true).when(stripePaymentService).verifyPaymentSucceeded(anyString(), anyLong(), any());
    java.util.UUID orgId = attachConnectOrg();

    String body = """
        { "holdIds": ["%s"], "paymentIntentId": "pi_ledger_once" }
        """.formatted(confirmedHold.getId());
    for (int i = 0; i < 2; i++) {
      mockMvc.perform(post("/api/payments/checkout")
              .with(WithMockPrincipal.user(user.getId(), user.getEmail()))
              .contentType(MediaType.APPLICATION_JSON)
              .content(body))
          .andExpect(status().isCreated());
    }

    assertThat(jdbcTemplate.queryForList(
        "SELECT organization_id, amount_cents FROM org_sales_ledger"
            + " WHERE channel = 'ONLINE' AND source_id = 'pi_ledger_once'"))
        .singleElement()
        .satisfies(row -> {
          assertThat(row.get("organization_id")).isEqualTo(orgId);
          assertThat(((Number) row.get("amount_cents")).longValue()).isEqualTo(5_000L);
        });
  }

  @Test
  void checkout_holdsFromTwoOrgs_isRefusedBeforeAnyOrderAndAudited() throws Exception {
    doReturn(true).when(stripePaymentService).verifyPaymentSucceeded(anyString(), anyLong(), any());
    attachConnectOrg();
    Organization otherOrg = organizationRepository.save(
        new Organization("Other Org", "other-" + java.util.UUID.randomUUID(), "x@test.com"));
    Event otherEvent = new Event("Other Event", null, Instant.now().plusSeconds(86400 * 30), null);
    otherEvent.setOrganizationId(otherOrg.getId());
    otherEvent = eventRepository.save(otherEvent);
    Seat otherSeat = new Seat(otherEvent, "C", "1", "1", new BigDecimal("50.00"));
    otherSeat.setStatus(SeatStatus.BOOKED);
    otherSeat = seatRepository.save(otherSeat);
    SeatHold otherHold = new SeatHold(otherSeat, user.getId(), Instant.now().plusSeconds(600));
    otherHold.setStatus(HoldStatus.CONFIRMED);
    otherHold = seatHoldRepository.save(otherHold);

    String body = """
        { "holdIds": ["%s", "%s"], "paymentIntentId": "pi_two_orgs" }
        """.formatted(confirmedHold.getId(), otherHold.getId());
    mockMvc.perform(post("/api/payments/checkout")
            .with(WithMockPrincipal.user(user.getId(), user.getEmail()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isBadRequest());

    verify(orderService, never()).createOrderWithStripePayment(any(), any(), anyString());
    assertThat(jdbcTemplate.queryForList(
        "SELECT details FROM audit_logs WHERE action = 'STRIPE_ORDER_FAILED' AND user_id = ?",
        String.class, user.getId()))
        .singleElement().asString()
        .contains("stripe_intent=pi_two_orgs,")
        .contains("error=ResponseStatusException");
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM audit_logs WHERE action = 'SALES_LEDGER_WRITE_FAILED' AND user_id = ?",
        Integer.class, user.getId())).isZero();
  }

  @Test
  void checkout_orgLookupFailsBeforeTheOrder_stillCreatesItAndRecordsTheSale() throws Exception {
    doReturn(true).when(stripePaymentService).verifyPaymentSucceeded(anyString(), anyLong(), any());
    java.util.UUID orgId = attachConnectOrg();
    Organization org = organizationRepository.findById(orgId).orElseThrow();
    // The first lookup (before the order) fails; the retry after the order succeeds.
    doThrow(new org.springframework.dao.DataAccessResourceFailureException("db blip"))
        .doReturn(java.util.Optional.of(org))
        .when(organizationRepository).findById(orgId);

    String body = """
        { "holdIds": ["%s"], "paymentIntentId": "pi_lookup_blip" }
        """.formatted(confirmedHold.getId());
    mockMvc.perform(post("/api/payments/checkout")
            .with(WithMockPrincipal.user(user.getId(), user.getEmail()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.orderStatus").value("COMPLETED"));

    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM org_sales_ledger WHERE channel = 'ONLINE' AND source_id = 'pi_lookup_blip'",
        Integer.class)).isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM audit_logs WHERE action IN ('STRIPE_ORDER_FAILED', 'SALES_LEDGER_WRITE_FAILED')"
            + " AND user_id = ?", Integer.class, user.getId())).isZero();
  }

  @Test
  void checkout_salesLedgerWriteFails_stillReturns201AndAudits() throws Exception {
    doReturn(true).when(stripePaymentService).verifyPaymentSucceeded(anyString(), anyLong(), any());
    java.util.UUID orgId = attachConnectOrg();
    doThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"))
        .when(salesCapService).recordSale(any(), anyLong(), anyString(), anyString());

    String body = """
        { "holdIds": ["%s"], "paymentIntentId": "pi_ledger_fail" }
        """.formatted(confirmedHold.getId());
    mockMvc.perform(post("/api/payments/checkout")
            .with(WithMockPrincipal.user(user.getId(), user.getEmail()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.orderStatus").value("COMPLETED"));

    java.util.List<String> details = jdbcTemplate.queryForList(
        "SELECT details FROM audit_logs WHERE action = 'SALES_LEDGER_WRITE_FAILED' AND user_id = ?",
        String.class, user.getId());
    assertThat(details).singleElement().asString()
        .contains("stripe_intent=pi_ledger_fail,")
        .contains("orgId=" + orgId + ",")
        .contains("amountCents=5000,")
        .contains("error=DataAccessResourceFailureException");
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM audit_logs WHERE action = 'STRIPE_ORDER_FAILED' AND user_id = ?",
        Integer.class, user.getId())).isZero();
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
    doThrow(new PaymentIntentNotOwnedException("pi_not_owned", user.getId(), "other_user", true))
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

  /**
   * A paid intent created before intents carried their user, submitted after the grace
   * window: it can never complete an order, so its charge goes on record for
   * stripe_charges_without_order, in the format the runbook query parses.
   */
  @Test
  void checkout_paidUnboundIntentPastGrace_writesOrderFailedAuditForRunbook() throws Exception {
    doThrow(new PaymentIntentNotOwnedException("pi_unbound_paid", user.getId(), "unbound", true))
        .when(stripePaymentService).verifyPaymentSucceeded(anyString(), anyLong(), any());

    mockMvc.perform(post("/api/payments/checkout")
            .with(WithMockPrincipal.user(user.getId(), user.getEmail()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(checkoutBody(confirmedHold.getId(), "pi_unbound_paid")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PAYMENT_NOT_USABLE"));

    java.util.List<String> details = jdbcTemplate.queryForList(
        "SELECT details FROM audit_logs WHERE action = 'STRIPE_ORDER_FAILED' AND user_id = ?",
        String.class, user.getId());
    org.assertj.core.api.Assertions.assertThat(details).hasSize(1);
    org.assertj.core.api.Assertions.assertThat(details.get(0))
        .contains("stripe_intent=pi_unbound_paid,")
        .contains("holdIds=[" + confirmedHold.getId() + "]")
        .contains("error=PaymentIntentUnbound");
    org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM orders WHERE user_id = ?", Integer.class, user.getId())).isZero();
  }

  /** A failed audit write must not change the answer: still 409 PAYMENT_NOT_USABLE. */
  @Test
  void checkout_paidUnboundIntent_auditFailure_stillAnswersNotUsable() throws Exception {
    doThrow(new PaymentIntentNotOwnedException("pi_unbound_audit_down", user.getId(), "unbound", true))
        .when(stripePaymentService).verifyPaymentSucceeded(anyString(), anyLong(), any());
    doThrow(new org.springframework.dao.DataAccessResourceFailureException("audit down"))
        .when(auditService).log(any(), eq("STRIPE_ORDER_FAILED"), anyString(), any(), anyString());

    mockMvc.perform(post("/api/payments/checkout")
            .with(WithMockPrincipal.user(user.getId(), user.getEmail()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(checkoutBody(confirmedHold.getId(), "pi_unbound_audit_down")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PAYMENT_NOT_USABLE"));
  }

  /** A paid box-office intent is not this caller's charge: no row, like another user's. */
  @Test
  void checkout_paidOtherChannelIntent_isRejectedWithoutAudit() throws Exception {
    doThrow(new PaymentIntentNotOwnedException("pi_box_office", user.getId(), "other_channel", true))
        .when(stripePaymentService).verifyPaymentSucceeded(anyString(), anyLong(), any());

    mockMvc.perform(post("/api/payments/checkout")
            .with(WithMockPrincipal.user(user.getId(), user.getEmail()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(checkoutBody(confirmedHold.getId(), "pi_box_office")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PAYMENT_NOT_USABLE"));

    Integer orderFailedRows = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM audit_logs WHERE action LIKE 'STRIPE_ORDER_%' AND user_id = ?",
        Integer.class, user.getId());
    org.assertj.core.api.Assertions.assertThat(orderFailedRows).isZero();
  }

  /** An unbound intent that was never paid has no charge to refund. */
  @Test
  void checkout_unpaidUnboundIntentPastGrace_isRejectedWithoutAudit() throws Exception {
    doThrow(new PaymentIntentNotOwnedException("pi_unbound_unpaid", user.getId(), "unbound", false))
        .when(stripePaymentService).verifyPaymentSucceeded(anyString(), anyLong(), any());

    mockMvc.perform(post("/api/payments/checkout")
            .with(WithMockPrincipal.user(user.getId(), user.getEmail()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(checkoutBody(confirmedHold.getId(), "pi_unbound_unpaid")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PAYMENT_NOT_USABLE"));

    Integer orderFailedRows = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM audit_logs WHERE action LIKE 'STRIPE_ORDER_%' AND user_id = ?",
        Integer.class, user.getId());
    org.assertj.core.api.Assertions.assertThat(orderFailedRows).isZero();
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
