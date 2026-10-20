package com.fairtix.tickets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fairtix.events.domain.Event;
import com.fairtix.events.infrastructure.EventRepository;
import com.fairtix.inventory.application.SeatHoldConflictException;
import com.fairtix.inventory.application.SeatHoldService;
import com.fairtix.inventory.domain.Seat;
import com.fairtix.inventory.domain.SeatHold;
import com.fairtix.inventory.infrastructure.SeatRepository;
import com.fairtix.orders.application.OrderService;
import com.fairtix.orders.domain.Order;
import com.fairtix.orders.domain.OrderStatus;
import com.fairtix.orders.infrastructure.OrderRepository;
import com.fairtix.payments.domain.PaymentStatus;
import com.fairtix.support.PostgresIT;
import com.fairtix.tickets.domain.Ticket;
import com.fairtix.tickets.domain.TicketStatus;
import com.fairtix.tickets.infrastructure.TicketRepository;
import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * One active ticket per seat ({@code tickets.seat_id}), on PostgreSQL 16.
 *
 * <p>(a) is the database invariant: a second non-cancelled, non-refunded ticket for
 * the same seat must be rejected, while a refunded or cancelled ticket must not
 * block resale. (b) is an honest attempt at the service-level race: two threads
 * check out the same CONFIRMED hold through {@link OrderService} at once.
 *
 * <p>Not {@code @Transactional}: every write commits, and {@link PostgresIT}
 * truncates the tables after each test.
 */
class TicketSeatUniquenessIT extends PostgresIT {

  @Autowired private UserRepository userRepository;
  @Autowired private EventRepository eventRepository;
  @Autowired private SeatRepository seatRepository;
  @Autowired private OrderRepository orderRepository;
  @Autowired private TicketRepository ticketRepository;
  @Autowired private SeatHoldService seatHoldService;
  @Autowired private OrderService orderService;
  @Autowired private JdbcTemplate jdbcTemplate;

  private Event event;
  private Seat seat;

  @BeforeEach
  void setUp() {
    event = eventRepository.save(
        new Event("Seat Uniqueness Concert", null, Instant.now().plusSeconds(86400), (UUID) null));
    seat = seatRepository.save(new Seat(event, "A", "1", "1", new BigDecimal("50.00")));
  }

  // ---- (a) the database invariant ----

  @Test
  void twoValidTicketsForOneSeat_areRejected() {
    ticketRepository.saveAndFlush(validTicketFor(newUser("first@test.com")));

    assertThatThrownBy(() -> ticketRepository.saveAndFlush(validTicketFor(newUser("second@test.com"))))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(activeTicketsForSeat()).isEqualTo(1);
  }

  @Test
  void refundedTicketDoesNotBlockResale() {
    Ticket refunded = validTicketFor(newUser("refunded@test.com"));
    refunded.setStatus(TicketStatus.REFUNDED);
    ticketRepository.saveAndFlush(refunded);

    ticketRepository.saveAndFlush(validTicketFor(newUser("buyer@test.com")));

    assertThat(activeTicketsForSeat()).isEqualTo(1);
  }

  @Test
  void cancelledTicketDoesNotBlockResale() {
    Ticket cancelled = validTicketFor(newUser("cancelled@test.com"));
    cancelled.setStatus(TicketStatus.CANCELLED);
    ticketRepository.saveAndFlush(cancelled);

    ticketRepository.saveAndFlush(validTicketFor(newUser("buyer@test.com")));

    assertThat(activeTicketsForSeat()).isEqualTo(1);
  }

  // ---- (b) the service-level race ----

  /**
   * Two threads check out the same CONFIRMED hold through
   * {@link OrderService#createOrderWithPayment}, released together by one latch.
   * {@code OrderService} reads the seat without a lock, so the only thing between
   * the two transactions is {@code Seat.@Version}.
   */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void concurrentCheckoutOfOneConfirmedHold_issuesAtMostOneTicket() throws Exception {
    User buyer = newUser("racer@test.com");
    SeatHold hold = seatHoldService.createHold(event.getId(), List.of(seat.getId()), buyer.getId(), 10)
        .get(0);
    seatHoldService.confirmHold(hold.getId(), buyer.getId());

    CountDownLatch start = new CountDownLatch(1);
    Callable<Order> checkout = () -> {
      start.await();
      return orderService.createOrderWithPayment(
          buyer.getId(), List.of(hold.getId()), PaymentStatus.SUCCESS);
    };

    ExecutorService pool = Executors.newFixedThreadPool(2);
    List<Throwable> failures = new ArrayList<>();
    int successes = 0;
    try {
      List<Future<Order>> futures = List.of(pool.submit(checkout), pool.submit(checkout));
      start.countDown();
      for (Future<Order> future : futures) {
        try {
          future.get(30, TimeUnit.SECONDS);
          successes++;
        } catch (java.util.concurrent.ExecutionException ex) {
          failures.add(ex.getCause());
        }
      }
    } finally {
      pool.shutdownNow();
    }

    System.out.println("[TicketSeatUniquenessIT] race: successes=" + successes
        + " failures=" + failures.stream().map(t -> t.getClass().getName()).toList());

    assertThat(successes).isEqualTo(1);
    assertThat(activeTicketsForSeat()).isEqualTo(1);
    // The loser either overlapped (Seat.@Version -> optimistic lock) or ran after
    // the winner committed (hold RELEASED / seat SOLD). Known gap, not fixed here:
    // GlobalExceptionHandler has no handler for the optimistic-lock case, so over
    // HTTP the overlapping loser gets a 500, not a 409. See
    // docs/experiments/l1-contention-correctness.md.
    assertThat(failures).singleElement().isInstanceOfAny(
        ObjectOptimisticLockingFailureException.class,
        SeatHoldConflictException.class,
        IllegalArgumentException.class);
  }

  // ---- helpers ----

  private User newUser(String email) {
    User user = new User();
    user.setEmail(email);
    user.setPassword("$2a$10$dummyhashfortest");
    user.setEmailVerified(true);
    return userRepository.save(user);
  }

  private Ticket validTicketFor(User user) {
    Order order = orderRepository.save(
        new Order(user, List.of(), seat.getPrice(), "USD", OrderStatus.COMPLETED));
    return new Ticket(order, user, seat, event, seat.getPrice());
  }

  private int activeTicketsForSeat() {
    Integer count = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM tickets WHERE seat_id = ? AND status NOT IN ('CANCELLED', 'REFUNDED')",
        Integer.class, seat.getId());
    return count == null ? 0 : count;
  }
}
