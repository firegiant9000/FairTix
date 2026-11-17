package com.fairtix.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fairtix.events.domain.Event;
import com.fairtix.events.infrastructure.EventRepository;
import com.fairtix.inventory.application.SeatHoldService;
import com.fairtix.inventory.domain.Seat;
import com.fairtix.inventory.infrastructure.SeatRepository;
import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The read-only checks in {@code docs/runbooks/cap-queue-checks.sql} run against
 * the migrated schema, so a schema change that breaks one fails the build
 * instead of the rollout. The two invariant checks are also shown to find what
 * they are meant to find.
 */
class RolloutChecksIT extends PostgresIT {

  private static final Path CHECKS = Path.of("../docs/runbooks/cap-queue-checks.sql");

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private EventRepository eventRepository;
  @Autowired private SeatRepository seatRepository;
  @Autowired private SeatHoldService seatHoldService;
  @Autowired private UserRepository userRepository;

  @Test
  void everyCheckRuns() throws Exception {
    Map<String, String> checks = checks();
    assertThat(checks).containsKeys(
        "postgres_version", "users_over_cap", "queues_over_capacity",
        "duplicate_queue_positions", "lock_waiters", "connections_by_state", "advisory_locks_held",
        "stripe_charges_without_order");
    checks.forEach((name, sql) -> jdbcTemplate.queryForList(sql));
  }

  @Test
  void stripeChargesWithoutOrder_leavesOutIntentsThatGotAnOrderLater() throws Exception {
    UUID user = newUser("paid@test.com");
    // A busy attempt then a failed one for an intent whose order was never created.
    failedStripeOrder(user, "pi_orphan", "STRIPE_ORDER_BUSY");
    failedStripeOrder(user, "pi_orphan", "STRIPE_ORDER_FAILED");
    // Only busy attempts so far, still no order, and its hold is still confirmed.
    Event event = eventRepository.save(
        new Event("Paid Concert", null, Instant.now().plusSeconds(86400), (UUID) null));
    Seat seat = seatRepository.save(new Seat(event, "A", "1", "1", new BigDecimal("10.00")));
    UUID holdId = seatHoldService.createHold(event.getId(), List.of(seat.getId()), user, 10).get(0).getId();
    seatHoldService.confirmHold(holdId, user);
    failedStripeOrder(user, "pi_busy", "STRIPE_ORDER_BUSY", holdId);
    // A busy attempt for an intent that a later attempt turned into an order.
    failedStripeOrder(user, "pi_fulfilled", "STRIPE_ORDER_BUSY");
    UUID order = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO orders (id, user_id, status, total_amount, currency, created_at)"
            + " VALUES (?, ?, 'COMPLETED', 10.00, 'USD', now())",
        order, user);
    jdbcTemplate.update(
        "INSERT INTO payment_records (id, order_id, user_id, amount, currency, status, transaction_id, created_at)"
            + " VALUES (?, ?, ?, 10.00, 'USD', 'SUCCESS', 'pi_fulfilled', now())",
        UUID.randomUUID(), order, user);

    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(checks().get("stripe_charges_without_order"));
    assertThat(rows).extracting(row -> row.get("payment_intent"))
        .containsExactlyInAnyOrder("pi_orphan", "pi_busy");
    Map<String, Object> orphan = rows.stream()
        .filter(row -> "pi_orphan".equals(row.get("payment_intent"))).findFirst().orElseThrow();
    assertThat(((Number) orphan.get("failed_attempts")).intValue()).isEqualTo(2);
    assertThat(orphan).containsEntry("busy_only", false);
    assertThat(((Number) orphan.get("holds_still_confirmed")).intValue()).isZero();
    Map<String, Object> busy = rows.stream()
        .filter(row -> "pi_busy".equals(row.get("payment_intent"))).findFirst().orElseThrow();
    assertThat(busy).containsEntry("busy_only", true);
    // Not safe to refund yet: checkout with this intent could still create the order.
    assertThat(((Number) busy.get("holds_still_confirmed")).intValue()).isEqualTo(1);
  }

  @Test
  void usersOverCap_findsAUserAboveTheCap() throws Exception {
    Event event = new Event("Checked Concert", null, Instant.now().plusSeconds(86400), (UUID) null);
    event = eventRepository.save(event);
    Seat a = seatRepository.save(new Seat(event, "A", "1", "1", new BigDecimal("10.00")));
    Seat b = seatRepository.save(new Seat(event, "A", "1", "2", new BigDecimal("10.00")));
    UUID user = newUser("over@test.com");
    seatHoldService.createHold(event.getId(), List.of(a.getId(), b.getId()), user, 10);

    assertThat(jdbcTemplate.queryForList(checks().get("users_over_cap"))).isEmpty();

    // The organizer lowers the cap below what the user already holds.
    jdbcTemplate.update("UPDATE events SET max_tickets_per_user = 1 WHERE id = ?", event.getId());

    List<Map<String, Object>> rows = jdbcTemplate.queryForList(checks().get("users_over_cap"));
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0)).containsEntry("user_id", user);
    assertThat(((Number) rows.get(0).get("live_holds")).intValue()).isEqualTo(2);
  }

  @Test
  void queuesOverCapacity_findsAnOverfullQueue() throws Exception {
    Event event = new Event("Queued Concert", null, Instant.now().plusSeconds(86400), (UUID) null);
    event.updateQueueSettings(true, 1);
    event = eventRepository.save(event);
    for (int i = 0; i < 2; i++) {
      jdbcTemplate.update(
          "INSERT INTO queue_entries (id, event_id, user_id, token, position, status, created_at)"
              + " VALUES (?, ?, ?, ?, ?, 'WAITING', now())",
          UUID.randomUUID(), event.getId(), newUser("q" + i + "@test.com"),
          UUID.randomUUID().toString(), i + 1);
    }

    List<Map<String, Object>> rows = jdbcTemplate.queryForList(checks().get("queues_over_capacity"));
    assertThat(rows).hasSize(1);
    assertThat(((Number) rows.get(0).get("live_entries")).intValue()).isEqualTo(2);
  }

  /** The file split on its {@code -- name:} lines, in order. */
  private static Map<String, String> checks() throws Exception {
    Map<String, String> checks = new LinkedHashMap<>();
    String name = null;
    StringBuilder sql = new StringBuilder();
    for (String line : Files.readAllLines(CHECKS)) {
      if (line.startsWith("-- name:")) {
        if (name != null) {
          put(checks, name, sql);
        }
        name = line.substring("-- name:".length()).trim();
        sql.setLength(0);
      } else if (name != null && !line.startsWith("--")) {
        sql.append(line).append('\n');
      }
    }
    if (name != null) {
      put(checks, name, sql);
    }
    return checks;
  }

  private static void put(Map<String, String> checks, String name, StringBuilder sql) {
    if (checks.put(name, strip(sql)) != null) {
      throw new IllegalStateException("Duplicate check name in " + CHECKS + ": " + name);
    }
  }

  private void failedStripeOrder(UUID user, String paymentIntentId, String action) {
    failedStripeOrder(user, paymentIntentId, action, UUID.randomUUID());
  }

  private void failedStripeOrder(UUID user, String paymentIntentId, String action, UUID holdId) {
    // Same details format as PaymentController writes (List.toString of the hold ids).
    jdbcTemplate.update(
        "INSERT INTO audit_logs (id, user_id, action, resource_type, details, created_at)"
            + " VALUES (?, ?, ?, 'PAYMENT', ?, now())",
        UUID.randomUUID(), user, action,
        "Charge succeeded but order creation failed: stripe_intent=" + paymentIntentId
            + ", holdIds=" + List.of(holdId) + ", error=RuntimeException");
  }

  private static String strip(StringBuilder sql) {
    String text = sql.toString().trim();
    return text.endsWith(";") ? text.substring(0, text.length() - 1) : text;
  }

  private UUID newUser(String email) {
    User user = new User();
    user.setEmail(email);
    user.setPassword("$2a$10$dummyhashfortest");
    user.setEmailVerified(true);
    return userRepository.save(user).getId();
  }
}
