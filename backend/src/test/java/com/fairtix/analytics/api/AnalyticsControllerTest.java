package com.fairtix.analytics.api;

import com.fairtix.events.application.EventService;
import com.fairtix.inventory.application.SeatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Instant;

import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Transactional
class AnalyticsControllerTest {

  @Autowired
  private WebApplicationContext context;

  @Autowired
  private EventService eventService;

  @Autowired
  private SeatService seatService;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context)
        .apply(springSecurity())
        .build();

    // H2 is shared across test classes within a JVM. Some upstream integration
    // suites (notably QueueAdmissionEmailIntegrationTest) are intentionally
    // non-@Transactional so the scheduler's commit is visible, which means
    // they leave committed events/seats/orders behind. Wipe the analytics
    // surface deterministically before each assertion. REFERENTIAL_INTEGRITY
    // FALSE bypasses the FK web (orders → events, tickets → seats, etc.)
    // without forcing us to enumerate the exact deletion order.
    jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
    try {
      jdbcTemplate.execute("DELETE FROM seat_holds");
      jdbcTemplate.execute("DELETE FROM tickets");
      jdbcTemplate.execute("DELETE FROM order_holds");
      jdbcTemplate.execute("DELETE FROM orders");
      jdbcTemplate.execute("DELETE FROM queue_entries");
      jdbcTemplate.execute("DELETE FROM seats");
      jdbcTemplate.execute("DELETE FROM event_performers");
      jdbcTemplate.execute("DELETE FROM events");
      // Auth tests commit users non-transactionally; analytics asserts user
      // counts so we wipe them too. Dependents (audit_log, notification_prefs,
      // organization_members, etc.) keep orphan user_id refs — those columns
      // don't affect analytics totals and the FK check is back on after this.
      jdbcTemplate.execute("DELETE FROM users");
    } finally {
      jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE");
    }
  }

  @Test
  void getDashboard_asAdmin_returns200WithExpectedShape() throws Exception {
    // Seed some data so the response has non-empty fields
    var event = eventService.createEvent("Test Show", Instant.parse("2026-08-01T20:00:00Z"), null, null, false, null, null);
    seatService.createSeat(event.getId(), "VIP", "A", "1", new BigDecimal("75.00"));
    seatService.createSeat(event.getId(), "VIP", "A", "2", new BigDecimal("75.00"));

    mockMvc.perform(get("/api/analytics/dashboard")
            .with(user("admin@test.com").roles("ADMIN"))
            .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.overview").value(notNullValue()))
        .andExpect(jsonPath("$.overview.totalEvents").value(1))
        .andExpect(jsonPath("$.overview.totalSeats").value(2))
        .andExpect(jsonPath("$.overview.totalUsers").value(0))
        .andExpect(jsonPath("$.seatsByStatus").value(hasKey("AVAILABLE")))
        .andExpect(jsonPath("$.seatsByStatus").value(hasKey("HELD")))
        .andExpect(jsonPath("$.seatsByStatus").value(hasKey("BOOKED")))
        .andExpect(jsonPath("$.seatsByStatus").value(hasKey("SOLD")))
        .andExpect(jsonPath("$.holdsByStatus").value(hasKey("ACTIVE")))
        .andExpect(jsonPath("$.holdConfirmationRate").value(0.0))
        .andExpect(jsonPath("$.holdsPerDay").isArray())
        .andExpect(jsonPath("$.eventsByVenue").isArray())
        .andExpect(jsonPath("$.topEventsByBookings").isArray())
        .andExpect(jsonPath("$.usersByRole").exists());
  }

  @Test
  void getDashboard_asUser_returns403() throws Exception {
    mockMvc.perform(get("/api/analytics/dashboard")
            .with(user("user@test.com").roles("USER")))
        .andExpect(status().isForbidden());
  }

  @Test
  void getDashboard_unauthenticated_returns401() throws Exception {
    mockMvc.perform(get("/api/analytics/dashboard"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void getDashboard_emptyDatabase_returns200WithZeros() throws Exception {
    mockMvc.perform(get("/api/analytics/dashboard")
            .with(user("admin@test.com").roles("ADMIN"))
            .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.overview.totalEvents").value(0))
        .andExpect(jsonPath("$.overview.totalSeats").value(0))
        .andExpect(jsonPath("$.overview.bookedSeats").value(0))
        .andExpect(jsonPath("$.overview.activeHolds").value(0))
        .andExpect(jsonPath("$.overview.soldSeats").value(0))
        .andExpect(jsonPath("$.holdConfirmationRate").value(0.0));
  }
}
