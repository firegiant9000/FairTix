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
        "stripe_orders_failed_by_day");
    checks.forEach((name, sql) -> jdbcTemplate.queryForList(sql));
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
          checks.put(name, strip(sql));
        }
        name = line.substring("-- name:".length()).trim();
        sql.setLength(0);
      } else if (name != null && !line.startsWith("--")) {
        sql.append(line).append('\n');
      }
    }
    if (name != null) {
      checks.put(name, strip(sql));
    }
    return checks;
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
