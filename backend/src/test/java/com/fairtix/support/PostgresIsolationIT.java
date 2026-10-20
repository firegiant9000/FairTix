package com.fairtix.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fairtix.audit.application.AuditService;
import java.util.UUID;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Self-contained guard for {@link PostgresIT}'s isolation, independent of the order
 * failsafe runs the other ITs in: a {@code REQUIRES_NEW} write made inside a
 * rolled-back {@code @Transactional} test commits on its own, and must still be
 * gone before the next test starts.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PostgresIsolationIT extends PostgresIT {

  @Autowired private AuditService auditService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  @Order(1)
  @Transactional
  void requiresNewWriteCommitsDespiteTheTestRollback() {
    auditService.log(UUID.randomUUID(), "ISOLATION_GUARD", "TEST", UUID.randomUUID(), "leak probe");

    // Already committed by REQUIRES_NEW; the test transaction's rollback won't undo it.
    assertThat(auditRows()).isEqualTo(1);
  }

  @Test
  @Order(2)
  void nextTestStartsWithoutIt() {
    assertThat(auditRows()).isZero();
  }

  private int auditRows() {
    Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_logs", Integer.class);
    return count == null ? 0 : count;
  }
}
