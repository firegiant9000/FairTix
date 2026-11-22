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
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link AuditService#logInCurrentTransaction} writes on the caller's connection
 * and commits or rolls back with the audited change. This is what keeps the hold
 * path at one pooled connection per call (see {@code SeatHoldContentionIT}).
 *
 * <p>Not {@code @Transactional}: every write commits, and {@link PostgresIT}
 * truncates the tables after each test.
 */
@ExtendWith(OutputCaptureExtension.class)
class AuditInCurrentTransactionIT extends PostgresIT {

  @Autowired private PlatformTransactionManager transactionManager;
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

  @Test
  void rolledBackTransaction_leavesNoAuditRowAndNoLogLine(CapturedOutput output) {
    String action = "LOGLINE_ROLLBACK_PROBE";
    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
      auditService.logInCurrentTransaction(UUID.randomUUID(), action, "TEST", UUID.randomUUID(), null);
      status.setRollbackOnly();
    });

    assertThat(count("SELECT COUNT(*) FROM audit_logs WHERE action = '" + action + "'")).isZero();
    assertThat(output.getAll()).doesNotContain(action);
  }

  @Test
  void committedTransaction_leavesAuditRowAndLogLine(CapturedOutput output) {
    String action = "LOGLINE_COMMIT_PROBE";
    new TransactionTemplate(transactionManager).executeWithoutResult(status ->
        auditService.logInCurrentTransaction(UUID.randomUUID(), action, "TEST", UUID.randomUUID(), null));

    assertThat(count("SELECT COUNT(*) FROM audit_logs WHERE action = '" + action + "'")).isEqualTo(1);
    assertThat(output.getAll()).contains("AUDIT: user=").contains("action=" + action);
  }

  @Test
  void requiresNewLog_survivesOuterRollback_andLogsLine(CapturedOutput output) {
    String action = "LOGLINE_REQUIRES_NEW_PROBE";
    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
      auditService.log(UUID.randomUUID(), action, "TEST", UUID.randomUUID(), null);
      // Logged at the inner (REQUIRES_NEW) commit, before the outer transaction ends.
      assertThat(output.getAll()).contains("action=" + action);
      status.setRollbackOnly();
    });

    assertThat(count("SELECT COUNT(*) FROM audit_logs WHERE action = '" + action + "'")).isEqualTo(1);
  }

  /**
   * The REQUIRES_NEW insert itself fails: no row and no AUDIT: line. (The database
   * error Hibernate logs can quote the row's values, so match the line's own
   * {@code action=} field rather than the bare probe string.)
   */
  @Test
  void requiresNewLog_failedInsert_leavesNoRowAndNoLogLine(CapturedOutput output) {
    String action = "LOGLINE_FAILED_INSERT_PROBE";
    jdbcTemplate.execute(
        "ALTER TABLE audit_logs ADD CONSTRAINT it_reject_audit CHECK (false) NOT VALID");
    try {
      assertThatThrownBy(() -> auditService.log(UUID.randomUUID(), action, "TEST", UUID.randomUUID(), null))
          .isInstanceOf(RuntimeException.class);
    } finally {
      jdbcTemplate.execute("ALTER TABLE audit_logs DROP CONSTRAINT it_reject_audit");
    }

    assertThat(count("SELECT COUNT(*) FROM audit_logs WHERE action = '" + action + "'")).isZero();
    assertThat(output.getAll()).doesNotContain("action=" + action);
  }

  private int count(String sql) {
    Integer n = jdbcTemplate.queryForObject(sql, Integer.class);
    return n == null ? 0 : n;
  }
}
