package com.fairtix.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * The Redis admitted set is a copy of the database's queue state, and Redis
 * failing must not undo database work or grant access the database does not.
 *
 * <p>Red on {@code 625dcbdc}: removing a user from the admitted set ran inside
 * the caller's transaction with no error handling, so a Redis error rolled back
 * a paid Stripe order (after the charge), a queue exit and the admission-expiry
 * sweep; and {@code isAdmitted} trusted a Redis hit, so a set entry left behind
 * by a failed removal still let the user hold seats and check out.
 *
 * <p>Redis failures are injected on a spy of the real client: for this test's
 * event, the admitted set reports every user as present and throws on removal.
 * Not {@code @Transactional}: every write commits, and {@link PostgresIT}
 * truncates the tables after each test.
 */
// Its own context (spies on RedissonClient): close it after the class so its pool
// doesn't count against the shared container's max_connections for the rest of the suite.
@DirtiesContext
class QueueRedisFailureIT extends PostgresIT {

  @Autowired private UserRepository userRepository;
  @Autowired private EventRepository eventRepository;
  @Autowired private SeatRepository seatRepository;
  @Autowired private SeatHoldService seatHoldService;
  @Autowired private OrderService orderService;
  @Autowired private QueueService queueService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @MockitoSpyBean private RedissonClient redissonClient;

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
  void paidOrder_commitsWhenTheAdmittedSetRemovalFails() {
    admit(buyer);
    SeatHold hold =
        seatHoldService.createHold(event.getId(), List.of(seat.getId()), buyer, 10).get(0);
    seatHoldService.confirmHold(hold.getId(), buyer);
    // Back to ADMITTED, as after releasing another hold (reinstateAfterHoldRelease):
    // checkout then completes the entry and removes the user from the set.
    setEntry("ADMITTED", "now() + interval '10 minutes'");
    redisAdmittedSetIsBroken();

    orderService.createOrderWithStripePayment(buyer, List.of(hold.getId()), "pi_test_redis_down");

    assertThat(count("SELECT COUNT(*) FROM orders WHERE status = 'COMPLETED'")).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM tickets WHERE status = 'VALID'")).isEqualTo(1);
    assertThat(entryStatus()).isEqualTo("COMPLETED");
  }

  /** Creating a hold completes the admission, which removes the user from the set. */
  @Test
  void hold_commitsWhenTheAdmittedSetRemovalFails() {
    admit(buyer);
    redisAdmittedSetIsBroken();

    seatHoldService.createHold(event.getId(), List.of(seat.getId()), buyer, 10);

    assertThat(count("SELECT COUNT(*) FROM seat_holds WHERE status = 'ACTIVE'")).isEqualTo(1);
    assertThat(entryStatus()).isEqualTo("COMPLETED");
  }

  @Test
  void leavingTheQueue_commitsWhenTheAdmittedSetRemovalFails() {
    admit(buyer);
    redisAdmittedSetIsBroken();

    queueService.leaveQueue(event.getId(), buyer);

    assertThat(entryStatus()).isEqualTo("EXPIRED");
    assertThat(count("SELECT COUNT(*) FROM audit_logs WHERE action = 'QUEUE_LEFT'")).isEqualTo(1);
  }

  @Test
  void expiredAdmission_withAStaleRedisEntry_grantsNoClearance() {
    admit(buyer);
    redisAdmittedSetIsBroken();
    setEntry("ADMITTED", "now() - interval '1 minute'");

    queueService.expireAdmissions(event.getId());

    assertThat(entryStatus()).isEqualTo("EXPIRED");
    assertNoClearance();
  }

  /** The set says admitted, the database says the user is still waiting. */
  @Test
  void staleRedisEntry_aloneGrantsNoClearance() {
    queueService.joinQueue(event.getId(), buyer);
    redisAdmittedSetIsBroken();

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

  /**
   * From here on, this event's admitted set holds every user (a removal that
   * failed earlier left them there) and every removal fails.
   */
  @SuppressWarnings("unchecked")
  private void redisAdmittedSetIsBroken() {
    RSet<Object> set = mock(RSet.class);
    when(set.contains(any())).thenReturn(true);
    when(set.remove(any())).thenThrow(new RedisException("simulated Redis failure"));
    when(set.removeAll(any())).thenThrow(new RedisException("simulated Redis failure"));
    doReturn(set).when(redissonClient).getSet("queue:admitted:" + event.getId());
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
