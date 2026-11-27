package com.fairtix.holds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fairtix.events.domain.Event;
import com.fairtix.events.infrastructure.EventRepository;
import com.fairtix.holds.application.CompLimitExceededException;
import com.fairtix.holds.application.CompService;
import com.fairtix.holds.application.EventHoldService;
import com.fairtix.holds.domain.HoldCategory;
import com.fairtix.holds.dto.CreateEventHoldRequest;
import com.fairtix.holds.dto.IssueCompRequest;
import com.fairtix.inventory.application.SeatHoldService;
import com.fairtix.inventory.domain.Seat;
import com.fairtix.inventory.domain.SeatHold;
import com.fairtix.inventory.infrastructure.SeatRepository;
import com.fairtix.orders.application.OrderService;
import com.fairtix.orders.application.PurchaseCapExceededException;
import com.fairtix.payments.domain.PaymentStatus;
import com.fairtix.support.PostgresIT;
import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Comps obey the event's comp limit under concurrency, and count toward the
 * recipient's purchase cap ({@code events.max_tickets_per_user}) like tickets
 * they bought. Box office stays uncapped.
 *
 * <p>Red on {@code 625dcbdc}: the comp-limit count ran before any lock, so two
 * overlapping comps both counted the same number and both issued; and comps
 * never checked the recipient's cap.
 *
 * <p>The race is forced, not left to timing: a third connection holds the row
 * locks on both seats, each comp is started and observed waiting on a lock in
 * {@code pg_stat_activity}, and only then are the seats released. Not
 * {@code @Transactional}: every write commits, and {@link PostgresIT} truncates
 * the tables after each test.
 */
class CompIssueIT extends PostgresIT {

  @Autowired private UserRepository userRepository;
  @Autowired private EventRepository eventRepository;
  @Autowired private SeatRepository seatRepository;
  @Autowired private SeatHoldService seatHoldService;
  @Autowired private OrderService orderService;
  @Autowired private CompService compService;
  @Autowired private EventHoldService eventHoldService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private DataSource dataSource;

  private Event event;
  private Seat seatA;
  private Seat seatB;
  private User staff;
  private User guest;

  @BeforeEach
  void setUp() {
    event = eventRepository.save(
        new Event("Comp Concert", null, Instant.now().plusSeconds(86400), (UUID) null));
    seatA = seatRepository.save(new Seat(event, "A", "1", "1", new BigDecimal("50.00")));
    seatB = seatRepository.save(new Seat(event, "A", "1", "2", new BigDecimal("50.00")));
    staff = newUser("staff@test.com");
    guest = newUser("guest@test.com");
  }

  @Test
  @Timeout(60)
  void overlappingComps_stayWithinTheCompLimit() throws Exception {
    jdbcTemplate.update(
        "UPDATE events SET comp_limit = 1, version = version + 1 WHERE id = ?", event.getId());

    List<Throwable> failures = raceUnderSeatLocks(
        () -> compService.issueComp(comp(seatA, null), staff.getId()),
        () -> compService.issueComp(comp(seatB, null), staff.getId()));

    assertThat(compTickets()).as("comps issued under a limit of 1").isEqualTo(1);
    assertThat(failures).hasSize(1);
    assertThat(failures.get(0)).isInstanceOf(CompLimitExceededException.class);
  }

  @Test
  void compOverTheRecipientsCap_isRefused() {
    setCap(1);
    SeatHold hold =
        seatHoldService.createHold(event.getId(), List.of(seatA.getId()), guest.getId(), 10).get(0);
    seatHoldService.confirmHold(hold.getId(), guest.getId());
    orderService.createOrderWithPayment(guest.getId(), List.of(hold.getId()), PaymentStatus.SUCCESS);

    assertThatThrownBy(() -> compService.issueComp(comp(seatB, guest.getEmail()), staff.getId()))
        .isInstanceOf(PurchaseCapExceededException.class);

    assertThat(compTickets()).isZero();
    assertThat(seatStatus(seatB)).isEqualTo("AVAILABLE");
  }

  @Test
  void compWithinTheRecipientsCap_countsTowardIt() {
    setCap(1);

    compService.issueComp(comp(seatA, guest.getEmail()), staff.getId());

    assertThat(compTickets()).isEqualTo(1);
    assertThatThrownBy(() ->
        seatHoldService.createHold(event.getId(), List.of(seatB.getId()), guest.getId(), 10))
        .hasMessageContaining("Purchase cap");
  }

  /**
   * Registration keeps the email as typed. A comp addressed in another case reaches the
   * same account, so it counts toward her cap instead of a new stub user's.
   */
  @Test
  void compToARegisteredEmailInAnotherCase_isRefusedOverHerCap() {
    setCap(1);
    User alice = newUser("Alice@Example.com");
    SeatHold hold =
        seatHoldService.createHold(event.getId(), List.of(seatA.getId()), alice.getId(), 10).get(0);
    seatHoldService.confirmHold(hold.getId(), alice.getId());
    orderService.createOrderWithPayment(alice.getId(), List.of(hold.getId()), PaymentStatus.SUCCESS);

    assertThatThrownBy(() -> compService.issueComp(comp(seatB, "alice@example.com"), staff.getId()))
        .isInstanceOf(PurchaseCapExceededException.class);

    assertThat(compTickets()).isZero();
    assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM users WHERE lower(email) = 'alice@example.com'", Integer.class))
        .as("no stub user beside her account").isEqualTo(1);
  }

  /** A name-only comp has no recipient account and does not use up the issuer's cap. */
  @Test
  void nameOnlyComps_onACappedEvent_areNotCountedAgainstTheIssuer() {
    setCap(1);

    compService.issueComp(comp(seatA, null), staff.getId());
    compService.issueComp(comp(seatB, null), staff.getId());

    assertThat(compTickets()).isEqualTo(2);
  }

  /** Converting a house hold takes the comp's advisory locks before its seat lock. */
  @Test
  void convertingAHoldToAComp_onACappedEvent_issuesTheComp() {
    setCap(1);
    jdbcTemplate.update(
        "UPDATE events SET comp_limit = 5, version = version + 1 WHERE id = ?", event.getId());
    UUID holdId = eventHoldService.create(new CreateEventHoldRequest(
        event.getId(), List.of(seatA.getId()), HoldCategory.HOUSE, null, null), staff.getId())
        .get(0).getId();

    eventHoldService.convertToComp(holdId, "Guest", guest.getEmail(), "press", false, staff.getId());

    assertThat(compTickets()).isEqualTo(1);
    assertThat(seatStatus(seatA)).isEqualTo("SOLD");
  }

  private IssueCompRequest comp(Seat seat, String recipientEmail) {
    return new IssueCompRequest(event.getId(), List.of(seat.getId()), null, recipientEmail, "test", false);
  }

  private List<Throwable> raceUnderSeatLocks(Callable<Object> first, Callable<Object> second)
      throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    List<Throwable> failures = new ArrayList<>();
    try (Connection lock = dataSource.getConnection()) {
      lock.setAutoCommit(false);
      try (PreparedStatement ps =
          lock.prepareStatement("SELECT id FROM seats WHERE id IN (?, ?) ORDER BY id FOR UPDATE")) {
        ps.setObject(1, seatA.getId());
        ps.setObject(2, seatB.getId());
        ps.executeQuery().close();
      }
      List<Future<Object>> futures = new ArrayList<>();
      futures.add(pool.submit(first));
      boolean firstBlocked = waitForLockWaiters(1, Duration.ofSeconds(10));
      futures.add(pool.submit(second));
      boolean bothBlocked = waitForLockWaiters(2, Duration.ofSeconds(10));
      lock.commit();
      assertThat(firstBlocked).as("the first transaction waited on a lock").isTrue();
      assertThat(bothBlocked).as("both transactions waited on a lock").isTrue();
      for (Future<Object> future : futures) {
        try {
          future.get(30, TimeUnit.SECONDS);
        } catch (ExecutionException ex) {
          failures.add(ex.getCause());
        }
      }
    } finally {
      pool.shutdownNow();
    }
    return failures;
  }

  private boolean waitForLockWaiters(int waiters, Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      Integer count = jdbcTemplate.queryForObject(
          "SELECT COUNT(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
              + " AND datname = current_database()", Integer.class);
      if (count != null && count >= waiters) {
        return true;
      }
      Thread.sleep(50);
    }
    return false;
  }

  private void setCap(int cap) {
    jdbcTemplate.update(
        "UPDATE events SET max_tickets_per_user = ?, version = version + 1 WHERE id = ?",
        cap, event.getId());
  }

  private int compTickets() {
    Integer count = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM tickets WHERE event_id = ? AND kind = 'COMP'", Integer.class, event.getId());
    return count == null ? 0 : count;
  }

  private String seatStatus(Seat seat) {
    return jdbcTemplate.queryForObject("SELECT status FROM seats WHERE id = ?", String.class, seat.getId());
  }

  private User newUser(String email) {
    User user = new User();
    user.setEmail(email);
    user.setPassword("$2a$10$dummyhashfortest");
    user.setEmailVerified(true);
    return userRepository.save(user);
  }
}
