package com.fairtix.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.fairtix.events.application.EventService;
import com.fairtix.events.domain.Event;
import com.fairtix.events.infrastructure.EventRepository;
import com.fairtix.inventory.domain.Seat;
import com.fairtix.inventory.domain.SeatStatus;
import com.fairtix.inventory.infrastructure.SeatRepository;
import com.fairtix.orders.domain.Order;
import com.fairtix.orders.domain.OrderStatus;
import com.fairtix.orders.infrastructure.OrderRepository;
import com.fairtix.support.PostgresIT;
import com.fairtix.tickets.domain.Ticket;
import com.fairtix.tickets.infrastructure.TicketRepository;
import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Opt-in timing of {@code EventService.cancelEvent} on a large event: excluded from failsafe,
 * run with {@code ./mvnw verify -Dit.test=EventCancelLoadIT} (and
 * {@code -Dcancel.load.tickets=N}, default 2000). One order per two tickets. It prints the
 * wall time of the cancellation alone; seeding is not timed. A laptop measurement, not a
 * production figure.
 */
class EventCancelLoadIT extends PostgresIT {

  private static final int TICKETS = Integer.getInteger("cancel.load.tickets", 2000);

  @Autowired private EventService eventService;
  @Autowired private UserRepository userRepository;
  @Autowired private EventRepository eventRepository;
  @Autowired private SeatRepository seatRepository;
  @Autowired private OrderRepository orderRepository;
  @Autowired private TicketRepository ticketRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void cancelLargeEvent() {
    UUID adminId = user("load-admin").getId();
    Event event = eventRepository.save(
        new Event("Load Cancel", null, Instant.now().plusSeconds(86400), (UUID) null));
    BigDecimal price = new BigDecimal("40.00");

    List<User> buyers = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      buyers.add(user("load-buyer" + i));
    }
    List<Seat> seats = new ArrayList<>();
    for (int i = 0; i < TICKETS; i++) {
      Seat seat = new Seat(event, "Floor", "R" + (i / 100), String.valueOf(i % 100), price);
      seat.setStatus(SeatStatus.SOLD);
      seats.add(seat);
    }
    seats = seatRepository.saveAll(seats);
    List<Ticket> tickets = new ArrayList<>();
    for (int i = 0; i < TICKETS; i += 2) {
      User buyer = buyers.get((i / 2) % buyers.size());
      Order order = orderRepository.save(
          new Order(buyer, List.of(), price.add(price), "USD", OrderStatus.COMPLETED));
      tickets.add(new Ticket(order, buyer, seats.get(i), event, price));
      if (i + 1 < TICKETS) {
        tickets.add(new Ticket(order, buyer, seats.get(i + 1), event, price));
      }
    }
    ticketRepository.saveAll(tickets);

    long start = System.nanoTime();
    eventService.cancelEvent(event.getId(), adminId, "Load test");
    long millis = (System.nanoTime() - start) / 1_000_000;
    System.out.printf("EventCancelLoadIT: cancelled %d tickets in %d ms%n", TICKETS, millis);

    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM tickets WHERE event_id = ? AND status = 'CANCELLED'",
        Integer.class, event.getId())).isEqualTo(TICKETS);
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM refund_requests WHERE status = 'APPROVED'", Integer.class))
        .isEqualTo((TICKETS + 1) / 2);
  }

  private User user(String prefix) {
    User u = new User();
    u.setEmail(prefix + "_" + UUID.randomUUID() + "@test.com");
    u.setPassword("$2a$10$dummyhashfortest");
    u.setEmailVerified(true);
    return userRepository.save(u);
  }
}
