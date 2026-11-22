package com.fairtix.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.jdbc.datasource.DataSourceUtils;
import javax.sql.DataSource;
import java.sql.Connection;

/**
 * V56: audit rows written by SupportTicketService.createTicket with details='subject=<free text>'
 * are rewritten to 'category=<CATEGORY>' (or 'subject=[redacted]' when the ticket is gone). The
 * shared IT database already has V56 applied (empty), so the test seeds rows and runs the
 * migration file again; the predicate makes that safe.
 */
class SupportTicketSubjectRedactionMigrationIT extends PostgresIT {

  private static final String MIGRATION = "db/migration/V56__redact_support_ticket_subjects_in_audit.sql";

  @Autowired private JdbcTemplate jdbc;
  @Autowired private DataSource dataSource;

  private UUID user;

  @BeforeEach
  void seedUser() {
    user = UUID.randomUUID();
    jdbc.update("INSERT INTO users (id, email, password, role) VALUES (?, ?, 'x', 'USER')",
        user, "u-" + user + "@example.test");
  }

  @Test
  void rewritesMatchingRowsAndLeavesEverythingElseAlone() {
    UUID ticket = UUID.randomUUID();
    jdbc.update("INSERT INTO support_tickets (id, user_id, subject, category) VALUES (?, ?, ?, 'BILLING')",
        ticket, user, "my secret subject");
    UUID withTicket = audit("CREATE", "SUPPORT_TICKET", ticket, "subject=my secret subject");
    UUID noTicket = audit("CREATE", "SUPPORT_TICKET", UUID.randomUUID(), "subject=orphan subject");
    UUID nullResource = audit("CREATE", "SUPPORT_TICKET", null, "subject=null resource");
    UUID otherAction = audit("UPDATE", "SUPPORT_TICKET", ticket, "subject=other action");
    UUID otherType = audit("CREATE", "ORDER", ticket, "subject=other type");
    UUID otherDetails = audit("CREATE", "SUPPORT_TICKET", ticket, "category=BILLING");
    UUID nullDetails = audit("CREATE", "SUPPORT_TICKET", ticket, null);
    UUID midDetails = audit("CREATE", "SUPPORT_TICKET", ticket, "x subject=mid");
    Map<String, Object> before = row(withTicket);

    runMigration();

    assertThat(details(withTicket)).isEqualTo("category=BILLING");
    assertThat(details(noTicket)).isEqualTo("subject=[redacted]");
    assertThat(details(nullResource)).isEqualTo("subject=[redacted]");
    assertThat(details(otherAction)).isEqualTo("subject=other action");
    assertThat(details(otherType)).isEqualTo("subject=other type");
    assertThat(details(otherDetails)).isEqualTo("category=BILLING");
    assertThat(details(nullDetails)).isNull();
    assertThat(details(midDetails)).isEqualTo("x subject=mid");

    Map<String, Object> after = row(withTicket);
    before.remove("details");
    after.remove("details");
    assertThat(after).isEqualTo(before);
  }

  @Test
  void secondRunChangesNothing() {
    UUID ticket = UUID.randomUUID();
    jdbc.update("INSERT INTO support_tickets (id, user_id, subject, category) VALUES (?, ?, 's', 'OTHER')",
        ticket, user);
    UUID a = audit("CREATE", "SUPPORT_TICKET", ticket, "subject=s");
    UUID b = audit("CREATE", "SUPPORT_TICKET", UUID.randomUUID(), "subject=gone");
    runMigration();
    Map<String, Object> afterFirstA = row(a);
    Map<String, Object> afterFirstB = row(b);

    runMigration();

    assertThat(row(a)).isEqualTo(afterFirstA);
    assertThat(row(b)).isEqualTo(afterFirstB);
  }

  private void runMigration() {
    Connection c = DataSourceUtils.getConnection(dataSource);
    try {
      ScriptUtils.executeSqlScript(c, new ClassPathResource(MIGRATION));
    } finally {
      DataSourceUtils.releaseConnection(c, dataSource);
    }
  }

  private UUID audit(String action, String type, UUID resourceId, String details) {
    UUID id = UUID.randomUUID();
    jdbc.update("INSERT INTO audit_logs (id, user_id, action, resource_type, resource_id, details,"
            + " created_at, request_id) VALUES (?, ?, ?, ?, ?, ?, '2026-01-02T03:04:05Z'::timestamptz, ?)",
        id, user, action, type, resourceId, details, "req-" + id);
    return id;
  }

  private String details(UUID id) {
    return jdbc.queryForObject("SELECT details FROM audit_logs WHERE id = ?", String.class, id);
  }

  private Map<String, Object> row(UUID id) {
    return jdbc.queryForMap("SELECT * FROM audit_logs WHERE id = ?", id);
  }
}
