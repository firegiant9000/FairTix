package com.fairtix.orders.api;

import com.fairtix.auth.WithMockPrincipal;
import com.fairtix.events.domain.Event;
import com.fairtix.events.infrastructure.EventRepository;
import com.fairtix.inventory.domain.Seat;
import com.fairtix.inventory.domain.SeatStatus;
import com.fairtix.inventory.infrastructure.SeatRepository;
import com.fairtix.support.PostgresIT;
import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * {@link PurchaseFlowIntegrationTest} on PostgreSQL 16 with the Flyway schema:
 * create hold -> confirm hold -> checkout (payment) -> verify tickets + seat status.
 * Since H2 was removed (L1-rest C) the original runs on PostgreSQL too, so the two
 * overlap; this copy adds the real Redis container. Each test rolls back, and
 * {@link PostgresIT} truncates the committed {@code REQUIRES_NEW} audit rows.
 */
@Transactional
class PurchaseFlowPostgresIT extends PostgresIT {

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private EventRepository eventRepository;
  @Autowired private SeatRepository seatRepository;

  private MockMvc mockMvc;
  private User testUser;
  private Event testEvent;
  private Seat seatA;
  private Seat seatB;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context)
        .apply(springSecurity())
        .build();

    testUser = new User();
    testUser.setEmail("purchaseflow@test.com");
    testUser.setPassword("$2a$10$dummyhashfortest");
    testUser.setEmailVerified(true);
    testUser = userRepository.save(testUser);

    testEvent = eventRepository.save(
        new Event("Purchase Flow Concert", null, Instant.now().plusSeconds(86400), (UUID) null));

    seatA = seatRepository.save(new Seat(testEvent, "VIP", "1", "1", new BigDecimal("75.00")));
    seatB = seatRepository.save(new Seat(testEvent, "VIP", "1", "2", new BigDecimal("75.00")));
  }

  @Test
  void fullPurchaseFlow_success() throws Exception {
    // 1. Create hold on two seats
    String holdBody = """
        {
          "seatIds": ["%s", "%s"],
          "durationMinutes": 15
        }
        """.formatted(seatA.getId(), seatB.getId());

    MvcResult holdResult = mockMvc.perform(post("/api/events/{eventId}/holds", testEvent.getId())
            .with(WithMockPrincipal.user(testUser.getId(), testUser.getEmail()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(holdBody))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.length()").value(2))
        .andReturn();

    // Extract hold IDs
    String holdJson = holdResult.getResponse().getContentAsString();
    // Parse hold IDs from the JSON array
    String holdId1 = com.jayway.jsonpath.JsonPath.read(holdJson, "$[0].id");
    String holdId2 = com.jayway.jsonpath.JsonPath.read(holdJson, "$[1].id");

    // Verify seats are now HELD
    assertThat(seatRepository.findById(seatA.getId()).get().getStatus()).isEqualTo(SeatStatus.HELD);
    assertThat(seatRepository.findById(seatB.getId()).get().getStatus()).isEqualTo(SeatStatus.HELD);

    // 2. Confirm both holds
    mockMvc.perform(post("/api/holds/{holdId}/confirm", holdId1)
            .with(WithMockPrincipal.user(testUser.getId(), testUser.getEmail())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CONFIRMED"));

    mockMvc.perform(post("/api/holds/{holdId}/confirm", holdId2)
            .with(WithMockPrincipal.user(testUser.getId(), testUser.getEmail())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CONFIRMED"));

    // Verify seats are now BOOKED
    assertThat(seatRepository.findById(seatA.getId()).get().getStatus()).isEqualTo(SeatStatus.BOOKED);
    assertThat(seatRepository.findById(seatB.getId()).get().getStatus()).isEqualTo(SeatStatus.BOOKED);

    // 3. Checkout with simulated payment (SUCCESS)
    String checkoutBody = """
        {
          "holdIds": ["%s", "%s"],
          "simulatedOutcome": "SUCCESS"
        }
        """.formatted(holdId1, holdId2);

    mockMvc.perform(post("/api/payments/checkout")
            .with(WithMockPrincipal.user(testUser.getId(), testUser.getEmail()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(checkoutBody))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.paymentStatus").value("SUCCESS"))
        .andExpect(jsonPath("$.orderStatus").value("COMPLETED"))
        .andExpect(jsonPath("$.amount").value(150.00))
        .andExpect(jsonPath("$.transactionId").isNotEmpty());

    // 4. Verify seats are now SOLD
    assertThat(seatRepository.findById(seatA.getId()).get().getStatus()).isEqualTo(SeatStatus.SOLD);
    assertThat(seatRepository.findById(seatB.getId()).get().getStatus()).isEqualTo(SeatStatus.SOLD);

    // 5. Verify tickets were issued
    mockMvc.perform(get("/api/tickets")
            .with(WithMockPrincipal.user(testUser.getId(), testUser.getEmail())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].status").value("VALID"))
        .andExpect(jsonPath("$[0].price").value(75.00));

    // 6. Verify order exists
    mockMvc.perform(get("/api/orders")
            .with(WithMockPrincipal.user(testUser.getId(), testUser.getEmail())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].status").value("COMPLETED"))
        .andExpect(jsonPath("$[0].totalAmount").value(150.00));
  }

  @Test
  void fullPurchaseFlow_paymentFailure_rollsBackSeats() throws Exception {
    // 1. Create and confirm hold
    String holdBody = """
        { "seatIds": ["%s"], "durationMinutes": 15 }
        """.formatted(seatA.getId());

    MvcResult holdResult = mockMvc.perform(post("/api/events/{eventId}/holds", testEvent.getId())
            .with(WithMockPrincipal.user(testUser.getId(), testUser.getEmail()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(holdBody))
        .andExpect(status().isCreated())
        .andReturn();

    String holdId = com.jayway.jsonpath.JsonPath.read(
        holdResult.getResponse().getContentAsString(), "$[0].id");

    mockMvc.perform(post("/api/holds/{holdId}/confirm", holdId)
            .with(WithMockPrincipal.user(testUser.getId(), testUser.getEmail())))
        .andExpect(status().isOk());

    // 2. Checkout with FAILURE outcome
    String checkoutBody = """
        { "holdIds": ["%s"], "simulatedOutcome": "FAILURE" }
        """.formatted(holdId);

    mockMvc.perform(post("/api/payments/checkout")
            .with(WithMockPrincipal.user(testUser.getId(), testUser.getEmail()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(checkoutBody))
        .andExpect(status().isPaymentRequired());

    // 3. Verify seat is rolled back to BOOKED (not SOLD)
    assertThat(seatRepository.findById(seatA.getId()).get().getStatus()).isEqualTo(SeatStatus.BOOKED);

    // 4. Verify no tickets were issued
    mockMvc.perform(get("/api/tickets")
            .with(WithMockPrincipal.user(testUser.getId(), testUser.getEmail())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void purchaseFlow_unconfirmedHold_rejectsCheckout() throws Exception {
    // Create hold but do NOT confirm it
    String holdBody = """
        { "seatIds": ["%s"], "durationMinutes": 15 }
        """.formatted(seatA.getId());

    MvcResult holdResult = mockMvc.perform(post("/api/events/{eventId}/holds", testEvent.getId())
            .with(WithMockPrincipal.user(testUser.getId(), testUser.getEmail()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(holdBody))
        .andExpect(status().isCreated())
        .andReturn();

    String holdId = com.jayway.jsonpath.JsonPath.read(
        holdResult.getResponse().getContentAsString(), "$[0].id");

    // Try checkout without confirming -- should fail
    String checkoutBody = """
        { "holdIds": ["%s"], "simulatedOutcome": "SUCCESS" }
        """.formatted(holdId);

    mockMvc.perform(post("/api/payments/checkout")
            .with(WithMockPrincipal.user(testUser.getId(), testUser.getEmail()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(checkoutBody))
        .andExpect(status().isBadRequest());
  }

  @Test
  void purchaseFlow_otherUsersHold_rejectsCheckout() throws Exception {
    // Create hold as testUser
    String holdBody = """
        { "seatIds": ["%s"], "durationMinutes": 15 }
        """.formatted(seatA.getId());

    MvcResult holdResult = mockMvc.perform(post("/api/events/{eventId}/holds", testEvent.getId())
            .with(WithMockPrincipal.user(testUser.getId(), testUser.getEmail()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(holdBody))
        .andExpect(status().isCreated())
        .andReturn();

    String holdId = com.jayway.jsonpath.JsonPath.read(
        holdResult.getResponse().getContentAsString(), "$[0].id");

    // Confirm the hold as testUser
    mockMvc.perform(post("/api/holds/{holdId}/confirm", holdId)
            .with(WithMockPrincipal.user(testUser.getId(), testUser.getEmail())))
        .andExpect(status().isOk());

    // Try checkout as a different user
    User otherUser = new User();
    otherUser.setEmail("otheruser@test.com");
    otherUser.setPassword("$2a$10$dummyhashfortest");
    otherUser.setEmailVerified(true);
    otherUser = userRepository.save(otherUser);

    String checkoutBody = """
        { "holdIds": ["%s"], "simulatedOutcome": "SUCCESS" }
        """.formatted(holdId);

    mockMvc.perform(post("/api/payments/checkout")
            .with(WithMockPrincipal.user(otherUser.getId(), otherUser.getEmail()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(checkoutBody))
        .andExpect(status().isBadRequest());
  }
}
