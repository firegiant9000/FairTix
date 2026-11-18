package com.fairtix.tickets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fairtix.events.domain.Event;
import com.fairtix.events.infrastructure.EventRepository;
import com.fairtix.inventory.application.SeatHoldService;
import com.fairtix.inventory.domain.Seat;
import com.fairtix.inventory.domain.SeatHold;
import com.fairtix.inventory.infrastructure.SeatRepository;
import com.fairtix.orders.application.OrderService;
import com.fairtix.orders.application.PurchaseCapExceededException;
import com.fairtix.payments.domain.PaymentStatus;
import com.fairtix.support.PostgresIT;
import com.fairtix.tickets.application.TransferService;
import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A ticket accepted through a transfer counts toward the recipient's purchase
 * cap ({@code events.max_tickets_per_user}) like one they bought: the recipient's
 * active tickets plus live holds plus the transferred ticket must not exceed the
 * cap. Before, acceptance reassigned the ticket with no check.
 *
 * <p>Not {@code @Transactional}: every write commits, and {@link PostgresIT}
 * truncates the tables after each test.
 */
class TransferPurchaseCapIT extends PostgresIT {

  @Autowired private UserRepository userRepository;
  @Autowired private EventRepository eventRepository;
  @Autowired private SeatRepository seatRepository;
  @Autowired private SeatHoldService seatHoldService;
  @Autowired private OrderService orderService;
  @Autowired private TransferService transferService;
  @Autowired private JdbcTemplate jdbcTemplate;

  private Event event;
  private Seat seatA;
  private Seat seatB;
  private User sender;
  private User recipient;

  @BeforeEach
  void setUp() {
    Event capped = new Event("Capped Concert", null, Instant.now().plusSeconds(86400), (UUID) null);
    capped.setMaxTicketsPerUser(1);
    event = eventRepository.save(capped);
    seatA = seatRepository.save(new Seat(event, "A", "1", "1", new BigDecimal("50.00")));
    seatB = seatRepository.save(new Seat(event, "A", "1", "2", new BigDecimal("50.00")));
    sender = newUser("sender@test.com");
    recipient = newUser("recipient@test.com");
  }

  @Test
  void recipientWithRoomUnderTheCap_acceptsTheTicket() {
    UUID requestId = transferFromSender(buy(sender, seatA));

    transferService.acceptTransfer(requestId, recipient.getId());

    assertThat(activeTickets(recipient)).isEqualTo(1);
    assertThat(activeTickets(sender)).isZero();
    assertThat(requestStatus(requestId)).isEqualTo("ACCEPTED");
  }

  @Test
  void recipientAlreadyAtTheCap_cannotAccept() {
    UUID requestId = transferFromSender(buy(sender, seatA));
    buy(recipient, seatB);

    assertThatThrownBy(() -> transferService.acceptTransfer(requestId, recipient.getId()))
        .isInstanceOf(PurchaseCapExceededException.class);

    assertThat(activeTickets(recipient)).as("the recipient keeps only the ticket they bought")
        .isEqualTo(1);
    assertThat(activeTickets(sender)).as("the ticket stays with the sender").isEqualTo(1);
    assertThat(requestStatus(requestId)).isEqualTo("PENDING");
    assertThat(auditRows("TRANSFER_ACCEPTED")).isZero();
  }

  @Test
  void recipientHoldingASeatUnderTheCap_cannotAccept() {
    UUID requestId = transferFromSender(buy(sender, seatA));
    seatHoldService.createHold(event.getId(), List.of(seatB.getId()), recipient.getId(), 10);

    assertThatThrownBy(() -> transferService.acceptTransfer(requestId, recipient.getId()))
        .isInstanceOf(PurchaseCapExceededException.class);

    assertThat(activeTickets(recipient)).isZero();
    assertThat(requestStatus(requestId)).isEqualTo("PENDING");
  }

  /** Buys the seat for the user and returns the ticket id. */
  private UUID buy(User user, Seat seat) {
    SeatHold hold =
        seatHoldService.createHold(event.getId(), List.of(seat.getId()), user.getId(), 10).get(0);
    seatHoldService.confirmHold(hold.getId(), user.getId());
    orderService.createOrderWithPayment(
        user.getId(), List.of(hold.getId()), PaymentStatus.SUCCESS);
    return jdbcTemplate.queryForObject(
        "SELECT id FROM tickets WHERE seat_id = ?", UUID.class, seat.getId());
  }

  private UUID transferFromSender(UUID ticketId) {
    return transferService.createTransferRequest(ticketId, sender.getId(), recipient.getEmail()).getId();
  }

  private int activeTickets(User user) {
    Integer count = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM tickets WHERE user_id = ? AND event_id = ?"
            + " AND status NOT IN ('CANCELLED', 'REFUNDED')",
        Integer.class, user.getId(), event.getId());
    return count == null ? 0 : count;
  }

  private String requestStatus(UUID requestId) {
    return jdbcTemplate.queryForObject(
        "SELECT status FROM ticket_transfer_requests WHERE id = ?", String.class, requestId);
  }

  private int auditRows(String action) {
    Integer count = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM audit_logs WHERE action = ?", Integer.class, action);
    return count == null ? 0 : count;
  }

  private User newUser(String email) {
    User user = new User();
    user.setEmail(email);
    user.setPassword("$2a$10$dummyhashfortest");
    user.setEmailVerified(true);
    return userRepository.save(user);
  }
}
