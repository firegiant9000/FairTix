package com.fairtix.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fairtix.audit.application.AuditService;
import com.fairtix.events.domain.Event;
import com.fairtix.events.infrastructure.EventRepository;
import com.fairtix.inventory.application.SeatHoldService;
import com.fairtix.inventory.domain.Seat;
import com.fairtix.inventory.infrastructure.SeatRepository;
import com.fairtix.support.PostgresIT;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;

/**
 * {@link AuditService#logInCurrentTransaction} writes on the caller's connection
 * and commits or rolls back with the audited change. This is what keeps the hold
 * path at one pooled connection per call (see {@code SeatHoldContentionIT}).
 *
 * <p>Not {@code @Transactional}: every write commits, and {@link PostgresIT}
 * truncates the tables after each test.
 */
class AuditInCurrentTransactionIT extends PostgresIT {

  @Autowired private AuditService auditService;
  @Autowired private SeatHoldService seatHoldService;
  @Autowired private EventRepository eventRepository;
  @Autowired private SeatRepository seatRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private Event event;
  private Seat seat;

  @BeforeEach
  void setUp() {
    event = eventRepository.save(
        new Event("Audit Concert", null, Instant.now().plusSeconds(86400), (UUID) null));
    seat = seatRepository.save(new Seat(event, "A", "1", "1", new BigDecimal("50.00")));
  }

  @Test
  void withoutTransaction_throws() {
    assertThatThrownBy(() -> auditService.logInCurrentTransaction(
        UUID.randomUUID(), "PROBE", "TEST", UUID.randomUUID(), null))
        .isInstanceOf(IllegalTransactionStateException.class);
    assertThat(count("SELECT COUNT(*) FROM audit_logs")).isZero();
  }

  @Test
  void holdAndAuditCommitTogether() {
    UUID owner = UUID.randomUUID();
    MDC.put("requestId", "it-audit-req");
    try {
      seatHoldService.createHold(event.getId(), List.of(seat.getId()), owner, 10);
    } finally {
      MDC.remove("requestId");
    }

    List<Map<String, Object>> rows = jdbcTemplate.queryForList(
        "SELECT user_id, action, resource_type, request_id FROM audit_logs");
    assertThat(rows).singleElement().satisfies(row -> {
      assertThat(row.get("user_id")).isEqualTo(owner);
      assertThat(row.get("action")).isEqualTo("CREATE");
      assertThat(row.get("resource_type")).isEqualTo("HOLD");
      assertThat(row.get("request_id")).isEqualTo("it-audit-req");
    });
  }

  /**
   * The database rejects every audit insert (a {@code NOT VALID} check applies to
   * new rows only), so the hold must roll back with it: no unaudited hold.
   */
  @Test
  void auditFailure_rollsBackHold() {
    jdbcTemplate.execute(
        "ALTER TABLE audit_logs ADD CONSTRAINT it_reject_audit CHECK (false) NOT VALID");
    try {
      assertThatThrownBy(() -> seatHoldService.createHold(
          event.getId(), List.of(seat.getId()), UUID.randomUUID(), 10))
          .isInstanceOf(RuntimeException.class);
    } finally {
      jdbcTemplate.execute("ALTER TABLE audit_logs DROP CONSTRAINT it_reject_audit");
    }

    assertThat(count("SELECT COUNT(*) FROM seat_holds")).isZero();
    assertThat(jdbcTemplate.queryForObject(
        "SELECT status FROM seats WHERE id = ?", String.class, seat.getId())).isEqualTo("AVAILABLE");
  }

  private int count(String sql) {
    Integer n = jdbcTemplate.queryForObject(sql, Integer.class);
    return n == null ? 0 : n;
  }
}
