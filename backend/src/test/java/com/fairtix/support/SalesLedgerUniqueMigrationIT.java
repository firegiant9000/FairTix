package com.fairtix.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * #291, V50: the migration keeps the earliest ledger row per (channel, source_id), deletes the
 * other copies, and then adds the unique constraint. Runs on a scratch database migrated to V49,
 * because the shared IT database is already at the latest version.
 */
class SalesLedgerUniqueMigrationIT extends PostgresIT {

  @Autowired private JdbcTemplate jdbcTemplate;

  private final String database = "v50_check_" + UUID.randomUUID().toString().replace("-", "");
  private DriverManagerDataSource scratch;
  private JdbcTemplate scratchJdbc;

  @BeforeEach
  void createScratchDatabase() {
    jdbcTemplate.execute("CREATE DATABASE " + database);
    scratch = new DriverManagerDataSource(
        "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + database,
        POSTGRES.getUsername(), POSTGRES.getPassword());
    scratchJdbc = new JdbcTemplate(scratch);
  }

  @AfterEach
  void dropScratchDatabase() {
    jdbcTemplate.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
  }

  @Test
  void v50KeepsTheEarliestCopyAndAddsTheConstraint() {
    migrateTo("49");
    UUID org = UUID.randomUUID();
    scratchJdbc.update("INSERT INTO organizations (id, name, slug) VALUES (?, 'Org', ?)",
        org, "org-" + org);
    UUID kept = insert(org, 5_000, "ONLINE", "pi_dup", "2026-11-01T10:00:00Z");
    insert(org, 5_000, "ONLINE", "pi_dup", "2026-11-01T10:05:00Z");
    insert(org, 5_000, "ONLINE", "pi_dup", "2026-11-01T10:10:00Z");
    UUID otherChannel = insert(org, 2_000, "BOX_OFFICE", "pi_dup", "2026-11-01T11:00:00Z");
    UUID single = insert(org, 3_000, "ONLINE", "pi_single", "2026-11-01T12:00:00Z");
    UUID noSourceA = insert(org, 1_000, "BOX_OFFICE", null, "2026-11-01T13:00:00Z");
    UUID noSourceB = insert(org, 1_000, "BOX_OFFICE", null, "2026-11-01T13:00:00Z");

    migrateTo("50");

    List<UUID> remaining = scratchJdbc.queryForList("SELECT id FROM org_sales_ledger", UUID.class);
    assertThat(remaining).containsExactlyInAnyOrder(kept, otherChannel, single, noSourceA, noSourceB);
    assertThatThrownBy(() -> insert(org, 5_000, "ONLINE", "pi_single", "2026-11-02T00:00:00Z"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private void migrateTo(String version) {
    Flyway.configure()
        .dataSource(scratch)
        .locations("classpath:db/migration")
        .target(version)
        .load()
        .migrate();
  }

  private UUID insert(UUID org, long amountCents, String channel, String sourceId, String createdAt) {
    UUID id = UUID.randomUUID();
    scratchJdbc.update("INSERT INTO org_sales_ledger (id, organization_id, amount_cents, channel,"
            + " source_id, created_at) VALUES (?, ?, ?, ?, ?, ?::timestamptz)",
        id, org, amountCents, channel, sourceId, createdAt);
    return id;
  }
}
