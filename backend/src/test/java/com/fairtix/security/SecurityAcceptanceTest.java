package com.fairtix.security;

import com.fairtix.auth.WithMockPrincipal;
import com.fairtix.events.domain.Event;
import com.fairtix.events.infrastructure.EventRepository;
import com.fairtix.inventory.domain.HoldStatus;
import com.fairtix.inventory.domain.Seat;
import com.fairtix.inventory.domain.SeatHold;
import com.fairtix.inventory.domain.SeatStatus;
import com.fairtix.inventory.infrastructure.SeatHoldRepository;
import com.fairtix.inventory.infrastructure.SeatRepository;
import com.fairtix.users.domain.Role;
import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;
import com.fairtix.venues.domain.Venue;
import com.fairtix.venues.infrastructure.VenueRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Instant;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Consolidated security acceptance tests verifying authentication,
 * authorization, and cross-user isolation across all protected endpoints.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Transactional
class SecurityAcceptanceTest {

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private EventRepository eventRepository;
  @Autowired private SeatRepository seatRepository;
  @Autowired private SeatHoldRepository seatHoldRepository;
  @Autowired private VenueRepository venueRepository;

  private MockMvc mockMvc;
  private User regularUser;
  private User adminUser;
  private Event testEvent;
  private Venue testVenue;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context)
        .apply(springSecurity())
        .build();

    regularUser = new User();
    regularUser.setEmail("secuser@test.com");
    regularUser.setPassword("$2a$10$dummyhashfortest");
    regularUser.setEmailVerified(true);
    regularUser = userRepository.save(regularUser);

    adminUser = new User();
    adminUser.setEmail("secadmin@test.com");
    adminUser.setPassword("$2a$10$dummyhashfortest");
    adminUser.setRole(Role.ADMIN);
    adminUser.setEmailVerified(true);
    adminUser = userRepository.save(adminUser);

    testVenue = venueRepository.save(new Venue("Test Venue", null, null, null, null, null, null));

    testEvent = eventRepository.save(
        new Event("Security Test Event", testVenue, Instant.now().plusSeconds(86400), (UUID) null));
  }

  @Nested
  class UnauthenticatedAccess {

    @Test
    void publicEndpoints_areAccessible() throws Exception {
      mockMvc.perform(get("/api/events"))
          .andExpect(status().isOk());

      mockMvc.perform(get("/api/events/{id}", testEvent.getId()))
          .andExpect(status().isOk());
    }

    @Test
    void holdEndpoints_requireAuth() throws Exception {
      mockMvc.perform(post("/api/events/{id}/holds", testEvent.getId())
              .contentType(MediaType.APPLICATION_JSON)
              .content("""
                  { "seatIds": ["00000000-0000-0000-0000-000000000001"], "durationMinutes": 10 }
                  """))
          .andExpect(status().isUnauthorized());

      mockMvc.perform(get("/api/holds"))
          .andExpect(status().isUnauthorized());
    }

    @Test
    void orderEndpoints_requireAuth() throws Exception {
      mockMvc.perform(post("/api/orders")
              .contentType(MediaType.APPLICATION_JSON)
              .content("""
                  { "holdIds": ["00000000-0000-0000-0000-000000000001"] }
                  """))
          .andExpect(status().isUnauthorized());

      mockMvc.perform(get("/api/orders"))
          .andExpect(status().isUnauthorized());
    }

    @Test
    void ticketEndpoints_requireAuth() throws Exception {
      mockMvc.perform(get("/api/tickets"))
          .andExpect(status().isUnauthorized());
    }

    @Test
    void paymentEndpoints_requireAuth() throws Exception {
      mockMvc.perform(post("/api/payments/checkout")
              .contentType(MediaType.APPLICATION_JSON)
              .content("""
                  { "holdIds": ["00000000-0000-0000-0000-000000000001"] }
                  """))
          .andExpect(status().isUnauthorized());
    }

    @Test
    void adminEndpoints_requireAuth() throws Exception {
      mockMvc.perform(get("/api/admin/users"))
          .andExpect(status().isUnauthorized());
    }
  }

  @Nested
  class AdminAuthorization {

    @Test
    void regularUser_cannotAccessAdminEndpoints_urlLevel() throws Exception {
      mockMvc.perform(get("/api/admin/users")
              .with(WithMockPrincipal.user(regularUser.getId(), regularUser.getEmail())))
          .andExpect(status().isForbidden());
    }

    @Test
    void regularUser_cannotPromoteUsers() throws Exception {
      mockMvc.perform(patch("/api/admin/users/{id}/promote", regularUser.getId())
              .with(WithMockPrincipal.user(regularUser.getId(), regularUser.getEmail())))
          .andExpect(status().isForbidden());
    }

    @Test
    void regularUser_cannotDeleteUsers() throws Exception {
      mockMvc.perform(delete("/api/admin/users/{id}", regularUser.getId())
              .with(WithMockPrincipal.user(regularUser.getId(), regularUser.getEmail())))
          .andExpect(status().isForbidden());
    }

    @Test
    void admin_canAccessAdminEndpoints() throws Exception {
      mockMvc.perform(get("/api/admin/users")
              .with(WithMockPrincipal.admin(adminUser.getId(), adminUser.getEmail())))
          .andExpect(status().isOk());
    }

    @Test
    void admin_canCreateEvents() throws Exception {
      String body = """
          {
            "title": "Admin Event",
            "venueId": "%s",
            "startTime": "%s"
          }
          """.formatted(testVenue.getId(), Instant.now().plusSeconds(86400).toString());

      mockMvc.perform(post("/api/events")
              .with(WithMockPrincipal.admin(adminUser.getId(), adminUser.getEmail()))
              .contentType(MediaType.APPLICATION_JSON)
              .content(body))
          .andExpect(status().isCreated());
    }

    @Test
    void regularUser_cannotCreateEvents() throws Exception {
      String body = """
          {
            "title": "User Event",
            "venueId": "%s",
            "startTime": "%s"
          }
          """.formatted(testVenue.getId(), Instant.now().plusSeconds(86400).toString());

      mockMvc.perform(post("/api/events")
              .with(WithMockPrincipal.user(regularUser.getId(), regularUser.getEmail()))
              .contentType(MediaType.APPLICATION_JSON)
              .content(body))
          .andExpect(status().isForbidden());
    }
  }

  @Nested
  class CrossUserIsolation {

    @Test
    void userCannotAccessOtherUsersHolds() throws Exception {
      // Create a hold for regularUser
      Seat seat = seatRepository.save(
          new Seat(testEvent, "A", "1", "1", new BigDecimal("50.00")));
      SeatHold hold = new SeatHold(seat, regularUser.getId(), Instant.now().plusSeconds(600));
      hold = seatHoldRepository.save(hold);

      // Another user tries to access it
      User otherUser = new User();
      otherUser.setEmail("other@test.com");
      otherUser.setPassword("$2a$10$dummyhashfortest");
      otherUser = userRepository.save(otherUser);

      mockMvc.perform(get("/api/holds/{id}", hold.getId())
              .with(WithMockPrincipal.user(otherUser.getId(), otherUser.getEmail())))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value("HOLD_NOT_FOUND"));
    }

    @Test
    void userCannotConfirmOtherUsersHolds() throws Exception {
      Seat seat = seatRepository.save(
          new Seat(testEvent, "A", "1", "2", new BigDecimal("50.00")));
      SeatHold hold = new SeatHold(seat, regularUser.getId(), Instant.now().plusSeconds(600));
      hold = seatHoldRepository.save(hold);

      User otherUser = new User();
      otherUser.setEmail("other2@test.com");
      otherUser.setPassword("$2a$10$dummyhashfortest");
      otherUser = userRepository.save(otherUser);

      mockMvc.perform(post("/api/holds/{id}/confirm", hold.getId())
              .with(WithMockPrincipal.user(otherUser.getId(), otherUser.getEmail())))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value("HOLD_NOT_FOUND"));
    }

    @Test
    void userCannotReleaseOtherUsersHolds() throws Exception {
      Seat seat = seatRepository.save(
          new Seat(testEvent, "A", "1", "3", new BigDecimal("50.00")));
      SeatHold hold = new SeatHold(seat, regularUser.getId(), Instant.now().plusSeconds(600));
      hold = seatHoldRepository.save(hold);

      User otherUser = new User();
      otherUser.setEmail("other3@test.com");
      otherUser.setPassword("$2a$10$dummyhashfortest");
      otherUser = userRepository.save(otherUser);

      mockMvc.perform(post("/api/holds/{id}/release", hold.getId())
              .with(WithMockPrincipal.user(otherUser.getId(), otherUser.getEmail())))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value("HOLD_NOT_FOUND"));
    }

    @Test
    void userCannotAccessOtherUsersOrders() throws Exception {
      // Other user tries to access orders of regularUser
      User otherUser = new User();
      otherUser.setEmail("other4@test.com");
      otherUser.setPassword("$2a$10$dummyhashfortest");
      otherUser = userRepository.save(otherUser);

      // Each user should only see their own orders (empty for both)
      mockMvc.perform(get("/api/orders")
              .with(WithMockPrincipal.user(otherUser.getId(), otherUser.getEmail())))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void userCannotCheckoutOtherUsersHolds() throws Exception {
      Seat seat = seatRepository.save(
          new Seat(testEvent, "A", "1", "4", new BigDecimal("50.00")));
      seat.setStatus(SeatStatus.BOOKED);
      seat = seatRepository.save(seat);

      SeatHold hold = new SeatHold(seat, regularUser.getId(), Instant.now().plusSeconds(600));
      hold.setStatus(HoldStatus.CONFIRMED);
      hold = seatHoldRepository.save(hold);

      User otherUser = new User();
      otherUser.setEmail("other5@test.com");
      otherUser.setPassword("$2a$10$dummyhashfortest");
      otherUser.setEmailVerified(true);
      otherUser = userRepository.save(otherUser);

      String body = """
          { "holdIds": ["%s"], "simulatedOutcome": "SUCCESS" }
          """.formatted(hold.getId());

      mockMvc.perform(post("/api/payments/checkout")
              .with(WithMockPrincipal.user(otherUser.getId(), otherUser.getEmail()))
              .contentType(MediaType.APPLICATION_JSON)
              .content(body))
          .andExpect(status().isBadRequest());
    }
  }

  @Nested
  class InputValidation {

    @Test
    void holdCreation_rejectsEmptySeatIds() throws Exception {
      mockMvc.perform(post("/api/events/{id}/holds", testEvent.getId())
              .with(WithMockPrincipal.user(regularUser.getId(), regularUser.getEmail()))
              .contentType(MediaType.APPLICATION_JSON)
              .content("""
                  { "seatIds": [], "durationMinutes": 10 }
                  """))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    /** The unpaid order path is gone: only GET is mapped on /api/orders. */
    @Test
    void orderCreation_withoutPayment_isNotAnEndpoint() throws Exception {
      mockMvc.perform(post("/api/orders")
              .with(WithMockPrincipal.user(regularUser.getId(), regularUser.getEmail()))
              .contentType(MediaType.APPLICATION_JSON)
              .content("""
                  { "holdIds": ["00000000-0000-0000-0000-000000000001"] }
                  """))
          .andExpect(status().isMethodNotAllowed())
          .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void paymentCheckout_rejectsEmptyHoldIds() throws Exception {
      mockMvc.perform(post("/api/payments/checkout")
              .with(WithMockPrincipal.user(regularUser.getId(), regularUser.getEmail()))
              .contentType(MediaType.APPLICATION_JSON)
              .content("""
                  { "holdIds": [] }
                  """))
          .andExpect(status().isBadRequest());
    }
  }

  @Nested
  class SeatConflictHandling {

    @Test
    void doubleHold_returns409() throws Exception {
      Seat seat = seatRepository.save(
          new Seat(testEvent, "B", "1", "1", new BigDecimal("30.00")));

      String body = """
          { "seatIds": ["%s"], "durationMinutes": 10 }
          """.formatted(seat.getId());

      // First hold succeeds
      mockMvc.perform(post("/api/events/{id}/holds", testEvent.getId())
              .with(WithMockPrincipal.user(regularUser.getId(), regularUser.getEmail()))
              .contentType(MediaType.APPLICATION_JSON)
              .content(body))
          .andExpect(status().isCreated());

      // Second hold on the same seat fails with 409
      User otherUser = new User();
      otherUser.setEmail("conflict@test.com");
      otherUser.setPassword("$2a$10$dummyhashfortest");
      otherUser = userRepository.save(otherUser);

      mockMvc.perform(post("/api/events/{id}/holds", testEvent.getId())
              .with(WithMockPrincipal.user(otherUser.getId(), otherUser.getEmail()))
              .contentType(MediaType.APPLICATION_JSON)
              .content(body))
          .andExpect(status().isConflict());
    }
  }
}
