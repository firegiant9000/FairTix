package com.fairtix.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fairtix.auth.WithMockPrincipal;
import com.fairtix.events.domain.Event;
import com.fairtix.events.infrastructure.EventRepository;
import com.fairtix.inventory.application.SeatHoldService;
import com.fairtix.inventory.domain.Seat;
import com.fairtix.inventory.domain.SeatHold;
import com.fairtix.inventory.infrastructure.SeatRepository;
import com.fairtix.inventory.scheduler.HoldExpirationScheduler;
import com.fairtix.support.PostgresIT;
import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * L1-rest payment-failure paths on the simulated gateway (RM:127), through
 * {@code POST /api/payments/checkout} on PostgreSQL. A failed or cancelled payment
 * cancels the order and returns the seats to {@code BOOKED}; the holds stay
 * {@code CONFIRMED}, so the buyer can retry, and the confirmed-hold sweep returns the
 * seats to sale once the grace period has passed.
 *
 * <p>Not {@code @Transactional}: each request commits as in production, and
 * {@link PostgresIT} truncates the tables after each test. The Stripe variant is
 * {@link StripeOrderFailureIT}.
 */
class PaymentFailurePathsIT extends PostgresIT {

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private EventRepository eventRepository;
  @Autowired private SeatRepository seatRepository;
  @Autowired private SeatHoldService seatHoldService;
  @Autowired private HoldExpirationScheduler scheduler;
  @Autowired private JdbcTemplate jdbcTemplate;

  private MockMvc mockMvc;
  private User buyer;
  private List<UUID> holdIds;
  private List<UUID> seatIds;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

    buyer = new User();
    buyer.setEmail("payfail@test.com");
    buyer.setPassword("$2a$10$dummyhashfortest");
    buyer.setEmailVerified(true);
    buyer = userRepository.save(buyer);

    Event event = eventRepository.save(
        new Event("Payment Failure Concert", null, Instant.now().plusSeconds(86400), (UUID) null));
    List<Seat> seats = seatRepository.saveAll(List.of(
        new Seat(event, "A", "1", "1", new BigDecimal("40.00")),
        new Seat(event, "A", "1", "2", new BigDecimal("40.00"))));
    seatIds = seats.stream().map(Seat::getId).toList();

    List<SeatHold> holds = seatHoldService.createHold(event.getId(), seatIds, buyer.getId(), 10);
    holdIds = holds.stream().map(SeatHold::getId).toList();
    holdIds.forEach(id -> seatHoldService.confirmHold(id, buyer.getId()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"FAILURE", "CANCELLED"})
  void failedPayment_cancelsOrder_returnsSeatsToBooked_keepsHoldsConfirmed(String outcome)
      throws Exception {
    checkout(outcome)
        .andExpect(status().isPaymentRequired())
        .andExpect(jsonPath("$.paymentStatus").value(outcome))
        .andExpect(jsonPath("$.orderStatus").value("CANCELLED"));

    assertThat(count("SELECT COUNT(*) FROM orders WHERE status = 'CANCELLED'")).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM orders WHERE status <> 'CANCELLED'")).isZero();
    assertThat(count("SELECT COUNT(*) FROM payment_records WHERE status = '" + outcome + "'"))
        .isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM tickets")).isZero();
    assertThat(seatStatuses()).containsOnly("BOOKED");
    assertThat(holdStatuses()).containsOnly("CONFIRMED");

    assertThat(jdbcTemplate.queryForList(
        "SELECT details FROM audit_logs WHERE action = 'PAYMENT_FAILED'", String.class))
        .containsExactly("outcome=" + outcome);
    assertThat(count("SELECT COUNT(*) FROM audit_logs WHERE action = 'CANCEL'"
        + " AND resource_type = 'ORDER'")).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM audit_logs WHERE action = 'PAYMENT_PROCESSED'")).isZero();
  }

  @Test
  void retryAfterFailedPayment_succeedsOnTheSameHolds() throws Exception {
    checkout("FAILURE").andExpect(status().isPaymentRequired());

    checkout("SUCCESS")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.orderStatus").value("COMPLETED"));

    assertThat(count("SELECT COUNT(*) FROM orders WHERE status = 'CANCELLED'")).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM orders WHERE status = 'COMPLETED'")).isEqualTo(1);
    assertThat(seatStatuses()).containsOnly("SOLD");
    assertThat(holdStatuses()).containsOnly("RELEASED");
    assertThat(jdbcTemplate.queryForList(
        "SELECT seat_id FROM tickets WHERE status NOT IN ('CANCELLED', 'REFUNDED')", UUID.class))
        .containsExactlyInAnyOrderElementsOf(seatIds);
  }

  @Test
  void failedPayment_holdsSweptAfterGrace_seatsBackOnSale() throws Exception {
    checkout("FAILURE").andExpect(status().isPaymentRequired());
    // Past holds.confirmed-grace-minutes (default 15).
    jdbcTemplate.update(
        "UPDATE seat_holds SET expires_at = now() - make_interval(mins => 20) WHERE owner_id = ?",
        buyer.getId());

    scheduler.expireConfirmedHolds();

    assertThat(holdStatuses()).containsOnly("EXPIRED");
    assertThat(seatStatuses()).containsOnly("AVAILABLE");
    checkout("SUCCESS").andExpect(status().isBadRequest());
    assertThat(count("SELECT COUNT(*) FROM tickets")).isZero();
  }

  private ResultActions checkout(String outcome) throws Exception {
    String body = """
        { "holdIds": ["%s", "%s"], "simulatedOutcome": "%s" }
        """.formatted(holdIds.get(0), holdIds.get(1), outcome);
    return mockMvc.perform(post("/api/payments/checkout")
        .with(WithMockPrincipal.user(buyer.getId(), buyer.getEmail()))
        .contentType(MediaType.APPLICATION_JSON)
        .content(body));
  }

  private int count(String sql) {
    return jdbcTemplate.queryForObject(sql, Integer.class);
  }

  private List<String> seatStatuses() {
    return jdbcTemplate.queryForList("SELECT status FROM seats", String.class);
  }

  private List<String> holdStatuses() {
    return jdbcTemplate.queryForList("SELECT status FROM seat_holds", String.class);
  }
}
