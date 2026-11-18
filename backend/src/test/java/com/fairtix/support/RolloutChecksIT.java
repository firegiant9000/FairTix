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
        "stripe_charges_without_order", "refunds_on_live_orders");
    checks.forEach((name, sql) -> jdbcTemplate.queryForList(sql));
  }

  /** Webhook redeliveries of one refund are one row, with the order's status. */
  @Test
  void refundsOnLiveOrders_groupsRedeliveriesPerOrder() throws Exception {
    UUID user = newUser("refunded@test.com");
    UUID order = paidOrder(user, "pi_live", "COMPLETED");
    for (int i = 0; i < 2; i++) {
      // Same details format as StripeWebhookController writes.
      jdbcTemplate.update(
          "INSERT INTO audit_logs (id, user_id, action, resource_type, resource_id, details, created_at)"
              + " VALUES (?, ?, 'REFUND_ON_LIVE_ORDER', 'ORDER', ?, ?, now())",
          UUID.randomUUID(), new UUID(0L, 0L), order,
          "Stripe refund with no refund request: stripe_intent=pi_live, charge=ch_live,"
              + " amount_refunded_cents=5000, accounted_cents=0");
    }

    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(checks().get("refunds_on_live_orders"));
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0))
        .containsEntry("order_id", order)
        .containsEntry("order_status", "COMPLETED")
        .containsEntry("payment_intent", "pi_live");
    assertThat(((Number) rows.get(0).get("amount_refunded_cents")).longValue()).isEqualTo(5000L);
    assertThat(((Number) rows.get(0).get("events")).intValue()).isEqualTo(2);
    assertThat(rows.get(0)).containsEntry("refunded_before_order", false);
  }

  /**
   * A refund that arrived before its order committed is reported once the order
   * exists. A refund of a charge that never got an order is not.
   */
  @Test
  void refundsOnLiveOrders_matchesRefundsThatArrivedBeforeTheOrder() throws Exception {
    UUID user = newUser("early-refund@test.com");
    UUID order = paidOrder(user, "pi_early", "COMPLETED");
    unmatchedRefund("pi_early");
    unmatchedRefund("pi_never_ordered");
    // Already handled: the order was refunded through FairTix afterwards.
    paidOrder(user, "pi_handled", "REFUNDED");
    unmatchedRefund("pi_handled");

    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(checks().get("refunds_on_live_orders"));
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0))
        .containsEntry("order_id", order)
        .containsEntry("payment_intent", "pi_early")
        .containsEntry("refunded_before_order", true);
  }

  private UUID paidOrder(UUID user, String paymentIntentId, String status) {
    UUID order = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO orders (id, user_id, status, total_amount, currency, created_at)"
            + " VALUES (?, ?, ?, 50.00, 'USD', now())",
        order, user, status);
    jdbcTemplate.update(
        "INSERT INTO payment_records (id, order_id, user_id, amount, currency, status, transaction_id, created_at)"
            + " VALUES (?, ?, ?, 50.00, 'USD', 'SUCCESS', ?, now())",
        UUID.randomUUID(), order, user, paymentIntentId);
    return order;
  }

  private void unmatchedRefund(String paymentIntentId) {
    // Same details format as StripeWebhookController writes.
    jdbcTemplate.update(
        "INSERT INTO audit_logs (id, user_id, action, resource_type, details, created_at)"
            + " VALUES (?, ?, 'STRIPE_REFUND_UNMATCHED', 'PAYMENT', ?, now())",
        UUID.randomUUID(), new UUID(0L, 0L),
        "Stripe refund with no payment record yet: stripe_intent=" + paymentIntentId
            + ", charge=ch_x, amount_refunded_cents=5000");
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
    // Details with no hold ids to read: reported, but never as safe to refund.
    jdbcTemplate.update(
        "INSERT INTO audit_logs (id, user_id, action, resource_type, details, created_at)"
            + " VALUES (?, ?, 'STRIPE_ORDER_FAILED', 'PAYMENT', ?, now())",
        UUID.randomUUID(), user, "Charge succeeded but order creation failed: stripe_intent=pi_legacy");
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
        .containsExactlyInAnyOrder("pi_orphan", "pi_busy", "pi_legacy");
    Map<String, Object> legacy = rows.stream()
        .filter(row -> "pi_legacy".equals(row.get("payment_intent"))).findFirst().orElseThrow();
    assertThat(legacy.get("holds_still_confirmed")).isNull();
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
    assertThat(busy).containsEntry("not_verified", false);
  }

  /** A 402 on verification is listed, marked so it is checked in Stripe first. */
  @Test
  void stripeChargesWithoutOrder_marksPaymentNotVerifiedRows() throws Exception {
    UUID user = newUser("mismatch@test.com");
    UUID holdId = UUID.randomUUID();
    // Same details format as PaymentController writes for PaymentNotVerified.
    jdbcTemplate.update(
        "INSERT INTO audit_logs (id, user_id, action, resource_type, details, created_at)"
            + " VALUES (?, ?, 'STRIPE_ORDER_FAILED', 'PAYMENT', ?, now())",
        UUID.randomUUID(), user,
        "Payment not verified (amount_received differs from the holds' total, or the intent"
            + " has not succeeded); order not created: stripe_intent=pi_mismatch, holdIds="
            + List.of(holdId) + ", error=PaymentNotVerified");

    Map<String, Object> row = jdbcTemplate.queryForList(checks().get("stripe_charges_without_order"))
        .stream().filter(r -> "pi_mismatch".equals(r.get("payment_intent"))).findFirst().orElseThrow();
    assertThat(row).containsEntry("not_verified", true);
    assertThat(((Number) row.get("holds_still_confirmed")).intValue()).isZero();
  }

  /**
   * One attempt with readable hold ids (none confirmed) and one without: MAX alone
   * would ignore the NULL and show 0, listing the charge as safe to refund.
   */
  @Test
  void stripeChargesWithoutOrder_mixedUnreadableAttempts_areNeverRefundable() throws Exception {
    UUID user = newUser("mixed@test.com");
    failedStripeOrder(user, "pi_mixed", "STRIPE_ORDER_FAILED");
    jdbcTemplate.update(
        "INSERT INTO audit_logs (id, user_id, action, resource_type, details, created_at)"
            + " VALUES (?, ?, 'STRIPE_ORDER_FAILED', 'PAYMENT', ?, now())",
        UUID.randomUUID(), user, "Charge succeeded but order creation failed: stripe_intent=pi_mixed");

    Map<String, Object> mixed = chargeWithoutOrder("pi_mixed");
    assertThat(((Number) mixed.get("failed_attempts")).intValue()).isEqualTo(2);
    assertThat(mixed.get("holds_still_confirmed")).isNull();
  }

  /**
   * Holds that stay confirmed keep a charge unrefundable until it leaves the
   * 14-day window, so after 7 days the row is flagged for escalation. A charge
   * that is already refundable is not.
   */
  @Test
  void stripeChargesWithoutOrder_flagsOldUnrefundableChargesForEscalation() throws Exception {
    UUID user = newUser("stuck@test.com");
    Event event = eventRepository.save(
        new Event("Stuck Concert", null, Instant.now().plusSeconds(86400), (UUID) null));
    Seat seat = seatRepository.save(new Seat(event, "A", "1", "1", new BigDecimal("10.00")));
    UUID holdId = seatHoldService.createHold(event.getId(), List.of(seat.getId()), user, 10).get(0).getId();
    seatHoldService.confirmHold(holdId, user);
    failedStripeOrder(user, "pi_stuck", "STRIPE_ORDER_FAILED", holdId, "8 days");
    failedStripeOrder(user, "pi_recent", "STRIPE_ORDER_FAILED", holdId, "1 day");
    failedStripeOrder(user, "pi_refundable", "STRIPE_ORDER_FAILED", UUID.randomUUID(), "8 days");
    jdbcTemplate.update(
        "INSERT INTO audit_logs (id, user_id, action, resource_type, details, created_at)"
            + " VALUES (?, ?, 'STRIPE_ORDER_FAILED', 'PAYMENT', ?, now() - interval '8 days')",
        UUID.randomUUID(), user, "Charge succeeded but order creation failed: stripe_intent=pi_unreadable");

    assertThat(chargeWithoutOrder("pi_stuck")).containsEntry("escalate", true);
    assertThat(chargeWithoutOrder("pi_unreadable")).containsEntry("escalate", true);
    assertThat(chargeWithoutOrder("pi_recent")).containsEntry("escalate", false);
    assertThat(chargeWithoutOrder("pi_refundable")).containsEntry("escalate", false);
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
    failedStripeOrder(user, paymentIntentId, action, holdId, "0 seconds");
  }

  private void failedStripeOrder(UUID user, String paymentIntentId, String action, UUID holdId,
      String age) {
    // Same details format as PaymentController writes (List.toString of the hold ids).
    jdbcTemplate.update(
        "INSERT INTO audit_logs (id, user_id, action, resource_type, details, created_at)"
            + " VALUES (?, ?, ?, 'PAYMENT', ?, now() - CAST(? AS interval))",
        UUID.randomUUID(), user, action,
        "Charge succeeded but order creation failed: stripe_intent=" + paymentIntentId
            + ", holdIds=" + List.of(holdId) + ", error=RuntimeException", age);
  }

  private Map<String, Object> chargeWithoutOrder(String paymentIntentId) throws Exception {
    return jdbcTemplate.queryForList(checks().get("stripe_charges_without_order")).stream()
        .filter(row -> paymentIntentId.equals(row.get("payment_intent")))
        .findFirst().orElseThrow();
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
