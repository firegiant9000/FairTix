package com.fairtix.tickets.api;

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
import com.fairtix.payments.domain.PaymentStatus;
import com.fairtix.tickets.infrastructure.TicketRepository;
import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Transactional
class TicketControllerTest {

  @Autowired
  private WebApplicationContext context;

  @Autowired
  private UserRepository userRepository;

  @Autowired
  private EventRepository eventRepository;

  @Autowired
  private SeatRepository seatRepository;

  @Autowired
  private SeatHoldRepository seatHoldRepository;

  @Autowired
  private OrderService orderService;

  @Autowired
  private TicketRepository ticketRepository;

  private MockMvc mockMvc;
  private User testUser;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context)
        .apply(springSecurity())
        .build();

    testUser = new User();
    testUser.setEmail("tickettest@example.com");
    testUser.setPassword("$2a$10$dummyhashfortest");
    testUser = userRepository.save(testUser);
  }

  @Test
  void listTickets_unauthenticated_returns401() throws Exception {
    mockMvc.perform(get("/api/tickets"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void listTickets_returnsEmptyWhenNone() throws Exception {
    mockMvc.perform(get("/api/tickets")
            .with(WithMockPrincipal.user(testUser.getId(), testUser.getEmail())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void listTickets_returnsTicketsAfterOrder() throws Exception {
    // Set up: event → seat → confirmed hold → order → tickets
    Event event = eventRepository.save(new Event("Ticket Concert", null, Instant.now().plusSeconds(86400), null));
    Seat seat = seatRepository.save(new Seat(event, "B", "2", "205", new BigDecimal("35.00")));
    seat.setStatus(SeatStatus.BOOKED);
    seat = seatRepository.save(seat);

    SeatHold hold = new SeatHold(seat, testUser.getId(), Instant.now().plusSeconds(600));
    hold.setStatus(HoldStatus.CONFIRMED);
    hold = seatHoldRepository.save(hold);

    // Create the order (which issues tickets)
    orderService.createOrderWithPayment(
        testUser.getId(), List.of(hold.getId()), PaymentStatus.SUCCESS);

    mockMvc.perform(get("/api/tickets")
            .with(WithMockPrincipal.user(testUser.getId(), testUser.getEmail())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].eventTitle").value("Ticket Concert"))
        .andExpect(jsonPath("$[0].seatSection").value("B"))
        .andExpect(jsonPath("$[0].seatRow").value("2"))
        .andExpect(jsonPath("$[0].seatNumber").value("205"))
        .andExpect(jsonPath("$[0].price").value(35.00))
        .andExpect(jsonPath("$[0].status").value("VALID"));
  }

  @Test
  void getTicket_notFound_returns404() throws Exception {
    mockMvc.perform(get("/api/tickets/{ticketId}", UUID.randomUUID())
            .with(WithMockPrincipal.user(testUser.getId(), testUser.getEmail())))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
  }

  @Test
  void getTicket_belongingToOtherUser_returns404() throws Exception {
    // Create another user to test ownership isolation
    User otherUser = new User();
    otherUser.setEmail("other@example.com");
    otherUser.setPassword("$2a$10$dummyhashfortest");
    otherUser = userRepository.save(otherUser);

    // Create order/ticket for testUser
    Event event = eventRepository.save(new Event("Private Show", null, Instant.now().plusSeconds(86400), null));
    Seat seat = seatRepository.save(new Seat(event, "C", "1", "1", new BigDecimal("35.00")));
    seat.setStatus(SeatStatus.BOOKED);
    seat = seatRepository.save(seat);

    SeatHold hold = new SeatHold(seat, testUser.getId(), Instant.now().plusSeconds(600));
    hold.setStatus(HoldStatus.CONFIRMED);
    hold = seatHoldRepository.save(hold);

    orderService.createOrderWithPayment(
        testUser.getId(), List.of(hold.getId()), PaymentStatus.SUCCESS);

    // otherUser should see empty tickets, not testUser's
    mockMvc.perform(get("/api/tickets")
            .with(WithMockPrincipal.user(otherUser.getId(), otherUser.getEmail())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void getCalendar_returns200_withIcsContent() throws Exception {
    Event event = eventRepository.save(new Event("ICS Concert", null, Instant.now().plusSeconds(86400), null));
    Seat seat = seatRepository.save(new Seat(event, "A", "1", "101", new BigDecimal("50.00")));
    seat.setStatus(SeatStatus.BOOKED);
    seat = seatRepository.save(seat);

    SeatHold hold = new SeatHold(seat, testUser.getId(), Instant.now().plusSeconds(600));
    hold.setStatus(HoldStatus.CONFIRMED);
    hold = seatHoldRepository.save(hold);

    orderService.createOrderWithPayment(
        testUser.getId(), List.of(hold.getId()), PaymentStatus.SUCCESS);

    java.util.UUID ticketId = ticketRepository.findAllByUser_IdOrderByIssuedAtDesc(testUser.getId())
        .get(0).getId();

    mockMvc.perform(get("/api/tickets/{ticketId}/calendar.ics", ticketId)
            .with(WithMockPrincipal.user(testUser.getId(), testUser.getEmail())))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("text/calendar")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("BEGIN:VCALENDAR")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("SUMMARY:ICS Concert")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("@fairtix.com")));
  }

  @Test
  void getCalendar_notFound_returns404() throws Exception {
    mockMvc.perform(get("/api/tickets/{ticketId}/calendar.ics", UUID.randomUUID())
            .with(WithMockPrincipal.user(testUser.getId(), testUser.getEmail())))
        .andExpect(status().isNotFound());
  }

  @Test
  void getCalendar_wrongUser_returns404() throws Exception {
    User otherUser = new User();
    otherUser.setEmail("calendar-other@example.com");
    otherUser.setPassword("$2a$10$dummyhashfortest");
    otherUser = userRepository.save(otherUser);

    Event event = eventRepository.save(new Event("Private ICS", null, Instant.now().plusSeconds(86400), null));
    Seat seat = seatRepository.save(new Seat(event, "D", "2", "202", new BigDecimal("25.00")));
    seat.setStatus(SeatStatus.BOOKED);
    seat = seatRepository.save(seat);

    SeatHold hold = new SeatHold(seat, testUser.getId(), Instant.now().plusSeconds(600));
    hold.setStatus(HoldStatus.CONFIRMED);
    hold = seatHoldRepository.save(hold);

    orderService.createOrderWithPayment(
        testUser.getId(), List.of(hold.getId()), PaymentStatus.SUCCESS);

    java.util.UUID ticketId = ticketRepository.findAllByUser_IdOrderByIssuedAtDesc(testUser.getId())
        .get(0).getId();

    mockMvc.perform(get("/api/tickets/{ticketId}/calendar.ics", ticketId)
            .with(WithMockPrincipal.user(otherUser.getId(), otherUser.getEmail())))
        .andExpect(status().isNotFound());
  }
}
