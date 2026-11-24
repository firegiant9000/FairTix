package com.fairtix.queue;

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
import com.fairtix.queue.application.QueueService;
import com.fairtix.support.PostgresIT;
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
 * Queue admission against a real database: admission is read from the database
 * only, and holds and paid orders move the queue entry to COMPLETED.
 *
 * <p>Not {@code @Transactional}: every write commits, and {@link PostgresIT}
 * truncates the tables after each test.
 */
class QueueAdmissionIT extends PostgresIT {

  @Autowired private UserRepository userRepository;
  @Autowired private EventRepository eventRepository;
  @Autowired private SeatRepository seatRepository;
  @Autowired private SeatHoldService seatHoldService;
  @Autowired private OrderService orderService;
  @Autowired private QueueService queueService;
  @Autowired private JdbcTemplate jdbcTemplate;

  private Event event;
  private Seat seat;
  private UUID buyer;

  @BeforeEach
  void setUp() {
    Event queued = new Event("Queue Concert", null, Instant.now().plusSeconds(86400), (UUID) null);
    queued.updateQueueSettings(true, null);
    event = eventRepository.save(queued);
    seat = seatRepository.save(new Seat(event, "A", "1", "1", new BigDecimal("40.00")));
    buyer = newUser("buyer@test.com");
  }

  @Test
  void paidOrder_completesTheAdmittedEntry() {
    admit(buyer);
    SeatHold hold =
        seatHoldService.createHold(event.getId(), List.of(seat.getId()), buyer, 10).get(0);
    seatHoldService.confirmHold(hold.getId(), buyer);
    // Back to ADMITTED, as after releasing another hold (reinstateAfterHoldRelease):
    // checkout then completes the entry.
    setEntry("ADMITTED", "now() + interval '10 minutes'");
    assertThat(entryStatus()).isEqualTo("ADMITTED");

    orderService.createOrderWithStripePayment(buyer, List.of(hold.getId()), "pi_test_queue_admission");

    assertThat(count("SELECT COUNT(*) FROM orders WHERE status = 'COMPLETED'")).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM tickets WHERE status = 'VALID'")).isEqualTo(1);
    assertThat(entryStatus()).isEqualTo("COMPLETED");
  }

  @Test
  void hold_completesTheAdmittedEntry() {
    admit(buyer);
    assertThat(entryStatus()).isEqualTo("ADMITTED");

    seatHoldService.createHold(event.getId(), List.of(seat.getId()), buyer, 10);

    assertThat(count("SELECT COUNT(*) FROM seat_holds WHERE status = 'ACTIVE'")).isEqualTo(1);
    assertThat(entryStatus()).isEqualTo("COMPLETED");
  }

  @Test
  void expiredAdmission_grantsNoClearance() {
    admit(buyer);
    setEntry("ADMITTED", "now() - interval '1 minute'");

    queueService.expireAdmissions(event.getId());

    assertThat(entryStatus()).isEqualTo("EXPIRED");
    assertNoClearance();
  }

  @Test
  void waitingUser_grantsNoClearance() {
    queueService.joinQueue(event.getId(), buyer);

    assertThat(entryStatus()).isEqualTo("WAITING");
    assertNoClearance();
  }

  private void assertNoClearance() {
    assertThat(queueService.isAdmitted(event.getId(), buyer)).as("isAdmitted").isFalse();
    assertThat(queueService.hasCheckoutClearance(event.getId(), buyer))
        .as("hasCheckoutClearance").isFalse();
    assertThatThrownBy(
        () -> seatHoldService.createHold(event.getId(), List.of(seat.getId()), buyer, 10))
        .isInstanceOf(SeatHoldConflictException.class)
        .hasMessageContaining("Queue admission required");
  }

  private void admit(UUID userId) {
    queueService.joinQueue(event.getId(), userId);
    assertThat(queueService.admitNextBatch(event.getId())).hasSize(1);
  }

  private void setEntry(String status, String expiresAtSql) {
    jdbcTemplate.update("UPDATE queue_entries SET status = ?, expires_at = " + expiresAtSql
        + " WHERE event_id = ? AND user_id = ?", status, event.getId(), buyer);
  }

  private String entryStatus() {
    return jdbcTemplate.queryForObject(
        "SELECT status FROM queue_entries WHERE event_id = ? AND user_id = ?",
        String.class, event.getId(), buyer);
  }

  private int count(String sql) {
    Integer count = jdbcTemplate.queryForObject(sql, Integer.class);
    return count == null ? 0 : count;
  }

  private UUID newUser(String email) {
    User user = new User();
    user.setEmail(email);
    user.setPassword("$2a$10$dummyhashfortest");
    user.setEmailVerified(true);
    return userRepository.save(user).getId();
  }
}
