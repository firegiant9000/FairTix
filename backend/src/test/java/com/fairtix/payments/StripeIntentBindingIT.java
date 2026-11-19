package com.fairtix.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fairtix.audit.application.AuditService;
import com.fairtix.auth.WithMockPrincipal;
import com.fairtix.events.domain.Event;
import com.fairtix.events.infrastructure.EventRepository;
import com.fairtix.inventory.application.SeatHoldService;
import com.fairtix.inventory.domain.Seat;
import com.fairtix.inventory.domain.SeatHold;
import com.fairtix.inventory.infrastructure.SeatRepository;
import com.fairtix.organizations.application.OrganizationService;
import com.fairtix.organizations.domain.Organization;
import com.fairtix.organizations.infrastructure.OrganizationRepository;
import com.fairtix.payments.application.OnlinePaymentIntentService;
import com.fairtix.payments.application.StripePaymentService;
import com.fairtix.payments.application.StripePaymentService.StripeIntent;
import com.fairtix.support.PostgresIT;
import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * A PaymentIntent pays for the hold set and the payout routing it was created for, and nothing
 * else. Intents are created through {@code POST /api/payments/intent}, so the stored row's hashes
 * are the ones production writes; Stripe itself is stubbed on a spy, and verification always
 * reports a succeeded charge of the right amount for the caller.
 */
@TestPropertySource(properties = "stripe.enabled=true")
@DirtiesContext
class StripeIntentBindingIT extends PostgresIT {

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private EventRepository eventRepository;
  @Autowired private SeatRepository seatRepository;
  @Autowired private SeatHoldService seatHoldService;
  @Autowired private OrganizationService organizationService;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @MockitoSpyBean private StripePaymentService stripePaymentService;
  @MockitoSpyBean private OnlinePaymentIntentService onlinePaymentIntentService;
  @MockitoSpyBean private AuditService auditService;

  private MockMvc mockMvc;
  private User buyer;
  private User organizer;
  private Organization orgX;
  private SeatHold holdX;
  private SeatHold holdY;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    buyer = user("binding-buyer@test.com");
    organizer = user("binding-organizer@test.com");
    orgX = connectedOrg("Binding Org X", "acct_test_x");
    Organization orgY = connectedOrg("Binding Org Y", "acct_test_y");
    // Equal totals, so the amount check alone cannot tell the two hold sets apart.
    holdX = confirmedHold(orgX, "X Concert");
    holdY = confirmedHold(orgY, "Y Concert");
    doReturn(true).when(stripePaymentService).verifyPaymentSucceeded(anyString(), anyLong(), any());
  }

  /** The reported misroute: paid for org X's seats, spent on org Y's seats of the same total. */
  @Test
  void intentForHoldsA_isRefusedForHoldsBOfEqualTotal_onAnotherOrgsEvent() throws Exception {
    createIntent(holdX, "pi_test_bind_x");

    checkout("pi_test_bind_x", holdY).andExpect(status().isConflict());

    assertRefusedAndOnRecord("pi_test_bind_x", holdY);
  }

  /** Same holds, but the org's payout account changed after the intent was created. */
  @Test
  void sameHolds_afterTheOrgsConnectAccountChanged_areRefused() throws Exception {
    createIntent(holdX, "pi_test_bind_route");
    orgX.setStripeConnectAccountId("acct_test_x_new");
    organizationRepository.save(orgX);

    checkout("pi_test_bind_route", holdX).andExpect(status().isConflict());

    assertRefusedAndOnRecord("pi_test_bind_route", holdX);
  }

  /** No stored row (a legacy intent, or a row the retention job deleted). */
  @Test
  void intentWithNoStoredRow_isRefused() throws Exception {
    checkout("pi_test_bind_no_row", holdX).andExpect(status().isConflict());

    assertRefusedAndOnRecord("pi_test_bind_no_row", holdX);
  }

  /** A row created for someone else is refused even when Stripe's metadata says otherwise. */
  @Test
  void intentStoredForAnotherUser_isRefused() throws Exception {
    createIntent(holdX, "pi_test_bind_other_user");
    jdbcTemplate.update("UPDATE online_payment_intents SET user_id = ?"
        + " WHERE stripe_payment_intent_id = ?", organizer.getId(), "pi_test_bind_other_user");

    checkout("pi_test_bind_other_user", holdX).andExpect(status().isConflict());

    assertRefusedAndOnRecord("pi_test_bind_other_user", holdX);
  }

  /** The binding lookup fails after the charge succeeded: the charge still goes on record. */
  @Test
  void bindingLookupFailure_afterTheCharge_isOnRecord() throws Exception {
    createIntent(holdX, "pi_test_bind_lookup_fails");
    doThrow(new IllegalStateException("database unavailable"))
        .when(onlinePaymentIntentService).isBoundTo(anyString(), any(), any(), any());

    checkout("pi_test_bind_lookup_fails", holdX).andExpect(result ->
        assertThat(result.getResponse().getStatus()).isGreaterThanOrEqualTo(400));

    assertThat(count("SELECT COUNT(*) FROM orders")).isZero();
    assertThat(jdbcTemplate.queryForList(
        "SELECT details FROM audit_logs WHERE action = 'STRIPE_ORDER_FAILED'", String.class))
        .singleElement().asString()
        .contains("stripe_intent=pi_test_bind_lookup_fails")
        .contains("error=BindingNotChecked (IllegalStateException)");
  }

  /**
   * The binding lookup and then the audit write both fail. The log line is the only trace, so it
   * must name the cause the runbook keys on, and the audit failure must ride on the exception
   * that is actually thrown.
   */
  @Test
  @ExtendWith(OutputCaptureExtension.class)
  void bindingLookupFailure_withTheAuditFailingToo_isLoggedWithItsCause(CapturedOutput output)
      throws Exception {
    createIntent(holdX, "pi_test_bind_no_audit");
    doThrow(new IllegalStateException("database unavailable"))
        .when(onlinePaymentIntentService).isBoundTo(anyString(), any(), any(), any());
    doThrow(new IllegalStateException("audit unavailable"))
        .when(auditService).log(any(), eq("STRIPE_ORDER_FAILED"), any(), any(), anyString());

    Exception thrown = checkout("pi_test_bind_no_audit", holdX)
        .andReturn().getResolvedException();

    assertThat(thrown).isInstanceOf(IllegalStateException.class)
        .hasMessage("database unavailable");
    assertThat(thrown.getSuppressed()).singleElement()
        .extracting(Throwable::getMessage).isEqualTo("audit unavailable");
    assertThat(output.getAll())
        .contains("the audit row could not be written")
        .contains("stripe_intent=pi_test_bind_no_audit")
        .contains("error=BindingNotChecked (IllegalStateException)");
  }

  /**
   * Holds from two organizations are refused with a 400 after the charge succeeded. The paid
   * holds are unknown, so the row carries the label the runbook flags for a check of all holds.
   */
  @Test
  void holdsFromTwoOrgs_afterTheCharge_areOnRecordAsBindingNotChecked() throws Exception {
    createIntent(holdX, "pi_test_bind_two_orgs");

    checkout("pi_test_bind_two_orgs", holdX, holdY).andExpect(status().isBadRequest());

    assertThat(count("SELECT COUNT(*) FROM orders")).isZero();
    assertThat(jdbcTemplate.queryForList(
        "SELECT details FROM audit_logs WHERE action = 'STRIPE_ORDER_FAILED'", String.class))
        .singleElement().asString()
        .contains("stripe_intent=pi_test_bind_two_orgs")
        .contains("error=BindingNotChecked (ResponseStatusException)");
  }

  /**
   * The intent's own holds and routing: the order completes, the row is ended, and a resubmit
   * with the same intent gets the same order back.
   */
  @Test
  void matchingIntent_completes_endsTheRow_andTheReplayReturnsTheOrder() throws Exception {
    createIntent(holdX, "pi_test_bind_ok");

    String first = checkout("pi_test_bind_ok", holdX)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.orderStatus").value("COMPLETED"))
        .andReturn().getResponse().getContentAsString();
    String orderId = JsonPath.read(first, "$.orderId");

    assertThat(jdbcTemplate.queryForObject(
        "SELECT status || ':' || invalidated_reason FROM online_payment_intents"
            + " WHERE stripe_payment_intent_id = 'pi_test_bind_ok'", String.class))
        .isEqualTo("INVALIDATED:COMPLETED");

    checkout("pi_test_bind_ok", holdX)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.orderId").value(orderId));

    assertThat(count("SELECT COUNT(*) FROM orders")).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM tickets")).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM audit_logs WHERE action = 'STRIPE_ORDER_FAILED'"))
        .isZero();
  }

  private void assertRefusedAndOnRecord(String intentId, SeatHold requested) {
    assertThat(count("SELECT COUNT(*) FROM orders")).isZero();
    assertThat(count("SELECT COUNT(*) FROM payment_records")).isZero();
    assertThat(count("SELECT COUNT(*) FROM tickets")).isZero();
    assertThat(jdbcTemplate.queryForObject(
        "SELECT status FROM seat_holds WHERE id = ?", String.class, requested.getId()))
        .isEqualTo("CONFIRMED");
    // A real charge with no order: it belongs on the stripe_charges_without_order record.
    List<String> details = jdbcTemplate.queryForList(
        "SELECT details FROM audit_logs WHERE action = 'STRIPE_ORDER_FAILED' AND user_id = ?",
        String.class, buyer.getId());
    assertThat(details).hasSize(1);
    assertThat(details.get(0))
        .contains("stripe_intent=" + intentId)
        .contains("error=IntentNotBound");
  }

  private void createIntent(SeatHold hold, String intentId) throws Exception {
    doReturn(new StripeIntent(intentId, "requires_payment_method", intentId + "_secret"))
        .when(stripePaymentService)
        .createPaymentIntent(anyLong(), anyString(), any(), any(), anyString());
    mockMvc.perform(post("/api/payments/intent")
            .with(WithMockPrincipal.user(buyer.getId(), buyer.getEmail()))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{ \"holdIds\": [\"%s\"] }".formatted(hold.getId())))
        .andExpect(status().isOk());
    assertThat(count("SELECT COUNT(*) FROM online_payment_intents"
        + " WHERE stripe_payment_intent_id = '" + intentId + "'")).isEqualTo(1);
  }

  private ResultActions checkout(String intentId, SeatHold... holds) throws Exception {
    String ids = java.util.Arrays.stream(holds)
        .map(h -> "\"" + h.getId() + "\"")
        .collect(Collectors.joining(", "));
    return mockMvc.perform(post("/api/payments/checkout")
        .with(WithMockPrincipal.user(buyer.getId(), buyer.getEmail()))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{ \"holdIds\": [%s], \"paymentIntentId\": \"%s\" }".formatted(ids, intentId)));
  }

  private User user(String email) {
    User u = new User();
    u.setEmail(email);
    u.setPassword("$2a$10$dummyhashfortest");
    u.setEmailVerified(true);
    return userRepository.save(u);
  }

  private Organization connectedOrg(String name, String account) {
    Organization org = organizationService.createOrganization(name, "org@test", organizer.getId());
    org.setStripeConnectAccountId(account);
    org.setStripeChargesEnabled(true);
    return organizationRepository.save(org);
  }

  private SeatHold confirmedHold(Organization org, String title) {
    Event event = new Event(title, null, Instant.now().plusSeconds(86400), organizer.getId());
    event.setOrganizationId(org.getId());
    event = eventRepository.save(event);
    Seat seat = seatRepository.save(new Seat(event, "A", "1", "1", new BigDecimal("40.00")));
    SeatHold hold = seatHoldService.createHold(
        event.getId(), List.of(seat.getId()), buyer.getId(), 10).get(0);
    seatHoldService.confirmHold(hold.getId(), buyer.getId());
    return hold;
  }

  private int count(String sql) {
    return jdbcTemplate.queryForObject(sql, Integer.class);
  }
}
