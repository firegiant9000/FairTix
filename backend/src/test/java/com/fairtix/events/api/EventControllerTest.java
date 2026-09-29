package com.fairtix.events.api;

import com.fairtix.auth.WithMockPrincipal;
import com.fairtix.events.application.EventService;
import com.fairtix.events.domain.Event;
import com.fairtix.venues.domain.Venue;
import com.fairtix.venues.infrastructure.VenueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for {@link EventController}.
 *
 * <p>GET endpoints are permitAll. POST requires ADMIN role.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Transactional
class EventControllerTest {

  @Autowired
  private WebApplicationContext context;

  @Autowired
  private EventService eventService;

  @Autowired
  private VenueRepository venueRepository;

  private MockMvc mockMvc;
  private Venue testVenue;

  private static final UUID ADMIN_ID = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context)
        .apply(springSecurity())
        .build();
    testVenue = venueRepository.save(new Venue("Main Arena", null, null, null, null, null, null));
  }

  // -------------------------------------------------------------------------
  // POST /api/events — create event
  // -------------------------------------------------------------------------

  @Test
  void createEvent_asAdmin_returns200() throws Exception {
    String body = """
        {
          "title":     "Test Concert",
          "startTime": "2099-06-15T19:00:00Z",
          "venueId":   "%s"
        }
        """.formatted(testVenue.getId());

    mockMvc.perform(post("/api/events")
            .with(WithMockPrincipal.admin(ADMIN_ID, "admin@test.com"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(notNullValue()))
        .andExpect(jsonPath("$.title").value("Test Concert"))
        .andExpect(jsonPath("$.venue.name").value("Main Arena"))
        .andExpect(jsonPath("$.startTime").value("2099-06-15T19:00:00Z"))
        .andExpect(jsonPath("$.organizerId").value(ADMIN_ID.toString()));
  }

  @Test
  void createEvent_asUser_returns403() throws Exception {
    String body = """
        {
          "title":     "Test Concert",
          "startTime": "2099-06-15T19:00:00Z",
          "venueId":   "%s"
        }
        """.formatted(testVenue.getId());

    mockMvc.perform(post("/api/events")
            .with(user("user@test.com").roles("USER"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isForbidden());
  }

  @Test
  void createEvent_unauthenticated_returns401() throws Exception {
    String body = """
        {
          "title":     "Test Concert",
          "startTime": "2099-06-15T19:00:00Z",
          "venueId":   "%s"
        }
        """.formatted(testVenue.getId());

    mockMvc.perform(post("/api/events")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isUnauthorized());
  }

  // -------------------------------------------------------------------------
  // GET /api/events — list events (permitAll)
  // -------------------------------------------------------------------------

  @Test
  void listEvents_unauthenticated_returns200() throws Exception {
    Event ev = eventService.createEvent("Event 1", Instant.parse("2099-06-01T18:00:00Z"), testVenue.getId(), null, false, null, null);
    eventService.publishEvent(ev.getId(), null);

    mockMvc.perform(get("/api/events"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").isArray())
        .andExpect(jsonPath("$.content", hasSize(1)))
        .andExpect(jsonPath("$.content[0].title").value("Event 1"));
  }

  @Test
  void listEvents_pagination_works() throws Exception {
    Venue venueB = venueRepository.save(new Venue("Venue B", null, null, null, null, null, null));
    Venue venueC = venueRepository.save(new Venue("Venue C", null, null, null, null, null, null));
    Event evA = eventService.createEvent("Event A", Instant.parse("2099-06-01T18:00:00Z"), testVenue.getId(), null, false, null, null);
    Event evB = eventService.createEvent("Event B", Instant.parse("2099-06-02T18:00:00Z"), venueB.getId(), null, false, null, null);
    Event evC = eventService.createEvent("Event C", Instant.parse("2099-06-03T18:00:00Z"), venueC.getId(), null, false, null, null);
    eventService.publishEvent(evA.getId(), null);
    eventService.publishEvent(evB.getId(), null);
    eventService.publishEvent(evC.getId(), null);

    mockMvc.perform(get("/api/events")
            .param("page", "0")
            .param("size", "2")
            .param("upcoming", "false"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content", hasSize(2)))
        .andExpect(jsonPath("$.page.totalElements").value(3))
        .andExpect(jsonPath("$.page.totalPages").value(2));
  }

  // -------------------------------------------------------------------------
  // GET /api/events/{id} — get by ID (permitAll)
  // -------------------------------------------------------------------------

  @Test
  void getEvent_existingId_returns200() throws Exception {
    Event event = eventService.createEvent("My Event", Instant.parse("2099-07-01T20:00:00Z"), testVenue.getId(), null, false, null, null);

    mockMvc.perform(get("/api/events/{id}", event.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(event.getId().toString()))
        .andExpect(jsonPath("$.title").value("My Event"))
        .andExpect(jsonPath("$.venue.name").value("Main Arena"));
  }

  @Test
  void getEvent_nonExistentId_returns404() throws Exception {
    mockMvc.perform(get("/api/events/{id}", UUID.randomUUID()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
        .andExpect(jsonPath("$.message").value(containsString("Event not found")));
  }
}
