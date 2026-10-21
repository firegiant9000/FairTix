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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fairtix.auth.WithMockPrincipal;
import com.fairtix.events.domain.Event;
import com.fairtix.events.infrastructure.EventRepository;
import com.fairtix.inventory.application.SeatHoldService;
import com.fairtix.inventory.domain.Seat;
import com.fairtix.inventory.domain.SeatHold;
import com.fairtix.inventory.infrastructure.SeatRepository;
import com.fairtix.payments.application.StripePaymentService;
import com.fairtix.support.PostgresIT;
import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * L1-rest payment-failure path on Stripe (RM:127, and the test #260 deferred): the
 * charge is verified, then order creation fails. The order transaction rolls back,
 * and {@code PaymentController} writes {@code STRIPE_ORDER_FAILED} through
 * {@code AuditService.log} ({@code REQUIRES_NEW}), so the captured charge stays on
 * record for a manual refund.
 *
 * <p>Stripe is never called: verification is stubbed on a spy, and the failure is
 * injected where the order records the payment, after the seats were marked
 * {@code SOLD} in the same transaction.
 */
@TestPropertySource(properties = "stripe.enabled=true")
class StripeOrderFailureIT extends PostgresIT {

  static final String INTENT = "pi_test_order_failure";

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private EventRepository eventRepository;
  @Autowired private SeatRepository seatRepository;
  @Autowired private SeatHoldService seatHoldService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @MockitoSpyBean private StripePaymentService stripePaymentService;

  @Test
  void orderFailsAfterVerifiedCharge_rollsBack_andStripeOrderFailedSurvives() throws Exception {
    MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    User buyer = new User();
    buyer.setEmail("stripefail@test.com");
    buyer.setPassword("$2a$10$dummyhashfortest");
    buyer.setEmailVerified(true);
    buyer = userRepository.save(buyer);
    Event event = eventRepository.save(
        new Event("Stripe Failure Concert", null, Instant.now().plusSeconds(86400), (UUID) null));
    Seat seat = seatRepository.save(new Seat(event, "A", "1", "1", new BigDecimal("40.00")));
    SeatHold hold = seatHoldService.createHold(event.getId(), List.of(seat.getId()), buyer.getId(), 10)
        .get(0);
    seatHoldService.confirmHold(hold.getId(), buyer.getId());

    doReturn(true).when(stripePaymentService).verifyPaymentSucceeded(eq(INTENT), anyLong());
    doThrow(new IllegalStateException("simulated failure while recording the payment"))
        .when(stripePaymentService)
        .recordStripePayment(anyString(), any(), any(), any(), anyString());

    mockMvc.perform(post("/api/payments/checkout")
            .with(WithMockPrincipal.user(buyer.getId(), buyer.getEmail()))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                { "holdIds": ["%s"], "paymentIntentId": "%s" }
                """.formatted(hold.getId(), INTENT)))
        // PaymentController rethrows the cause; GlobalExceptionHandler maps
        // IllegalStateException to 409.
        .andExpect(status().isConflict());

    // The order transaction rolled back completely.
    assertThat(count("SELECT COUNT(*) FROM orders")).isZero();
    assertThat(count("SELECT COUNT(*) FROM payment_records")).isZero();
    assertThat(count("SELECT COUNT(*) FROM tickets")).isZero();
    assertThat(jdbcTemplate.queryForObject("SELECT status FROM seats", String.class))
        .isEqualTo("BOOKED");
    assertThat(jdbcTemplate.queryForObject("SELECT status FROM seat_holds", String.class))
        .isEqualTo("CONFIRMED");

    // The captured charge is still on record, in its own committed transaction.
    List<String> details = jdbcTemplate.queryForList(
        "SELECT details FROM audit_logs WHERE action = 'STRIPE_ORDER_FAILED' AND user_id = ?",
        String.class, buyer.getId());
    assertThat(details).hasSize(1);
    assertThat(details.get(0))
        .contains("stripe_intent=" + INTENT)
        .contains(hold.getId().toString())
        .contains("IllegalStateException");
  }

  private int count(String sql) {
    return jdbcTemplate.queryForObject(sql, Integer.class);
  }
}
