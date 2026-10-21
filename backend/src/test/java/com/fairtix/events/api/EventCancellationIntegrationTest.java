package com.fairtix.events.api;

import com.fairtix.events.application.EventService;
import com.fairtix.events.domain.Event;
import com.fairtix.events.infrastructure.EventRepository;
import com.fairtix.inventory.domain.HoldStatus;
import com.fairtix.inventory.domain.Seat;
import com.fairtix.inventory.domain.SeatHold;
import com.fairtix.inventory.infrastructure.SeatHoldRepository;
import com.fairtix.inventory.infrastructure.SeatRepository;
import com.fairtix.notifications.application.EmailService;
import com.fairtix.orders.domain.Order;
import com.fairtix.orders.domain.OrderStatus;
import com.fairtix.orders.infrastructure.OrderRepository;
import com.fairtix.tickets.domain.Ticket;
import com.fairtix.tickets.domain.TicketStatus;
import com.fairtix.tickets.infrastructure.TicketRepository;
import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * Cross-module integration test: event cancellation propagates to tickets and triggers
 * the cancellation email notification (#145 wiring).
 *
 * Not @Transactional so that afterCommit fires and email delivery can be verified.
 * Each @BeforeEach uses a UUID-based email to prevent unique-constraint conflicts.
 */
@SpringBootTest
class EventCancellationIntegrationTest {

    @Autowired private EventService eventService;
    @Autowired private EventRepository eventRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private SeatHoldRepository seatHoldRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private TicketRepository ticketRepository;
    @Autowired private UserRepository userRepository;

    @MockitoSpyBean
    private EmailService emailService;

    private User testUser;
    private UUID adminId;
    private Event testEvent;
    private Seat testSeat;
    private Ticket testTicket;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setEmail(UUID.randomUUID() + "@cancellation.test");
        testUser.setPassword("$2a$10$dummyhashfortest");
        testUser.setEmailVerified(true);
        testUser = userRepository.save(testUser);

        // The caller becomes refund_requests.reviewed_by, which references users(id);
        // EventController passes the authenticated user's id.
        User admin = new User();
        admin.setEmail(UUID.randomUUID() + "@cancellation-admin.test");
        admin.setPassword("$2a$10$dummyhashfortest");
        adminId = userRepository.save(admin).getId();

        testEvent = eventRepository.save(
                new Event("Cancellation Test Concert", null, Instant.now().plusSeconds(86400), (UUID) null));

        testSeat = seatRepository.save(
                new Seat(testEvent, "GA", "1", "1", new BigDecimal("50.00")));

        Order order = orderRepository.save(
                new Order(testUser, List.of(), new BigDecimal("50.00"), "USD", OrderStatus.COMPLETED));

        testTicket = ticketRepository.save(
                new Ticket(order, testUser, testSeat, testEvent, new BigDecimal("50.00")));
    }

    @Test
    void cancelEvent_withTicketHolder_cancelsTicketAndSendsEmail() {
        eventService.cancelEvent(testEvent.getId(), adminId, "Venue unavailable");

        Ticket refreshed = ticketRepository.findById(testTicket.getId()).orElseThrow();
        // COMPLETED orders trigger processRefund → REFUNDED; no-order tickets become CANCELLED
        assertThat(refreshed.getStatus()).isEqualTo(TicketStatus.REFUNDED);

        verify(emailService).sendEmail(
                eq(testUser.getEmail()),
                contains("Event Cancelled"),
                anyString());
    }

    @Test
    void cancelEvent_withActiveHold_releasesHold() {
        Seat holdSeat = seatRepository.save(
                new Seat(testEvent, "GA", "1", "2", new BigDecimal("50.00")));
        SeatHold activeHold = seatHoldRepository.save(
                new SeatHold(holdSeat, testUser.getId(), Instant.now().plusSeconds(600)));

        eventService.cancelEvent(testEvent.getId(), adminId, "Venue unavailable");

        SeatHold refreshed = seatHoldRepository.findById(activeHold.getId()).orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo(HoldStatus.RELEASED);
    }
}
