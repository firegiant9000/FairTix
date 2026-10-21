package com.fairtix.support;

import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Test database helpers for the PostgreSQL schema every Spring context test runs on.
 * Kept out of {@link PostgresIT}, because touching that class starts its containers.
 */
public final class TestDatabase {

  private TestDatabase() {
  }

  /**
   * Empties every application table (not Flyway's history) in one statement, so
   * foreign keys need no ordering. Call it outside any open transaction: TRUNCATE
   * takes ACCESS EXCLUSIVE locks until commit, and a {@code REQUIRES_NEW} write on
   * another connection would wait on them.
   */
  public static void truncateAllTables(DataSource dataSource) {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    List<String> tables = jdbc.queryForList(
        "SELECT format('%I.%I', schemaname, tablename) FROM pg_tables"
            + " WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'",
        String.class);
    if (!tables.isEmpty()) {
      jdbc.execute("TRUNCATE TABLE " + String.join(", ", tables) + " RESTART IDENTITY CASCADE");
    }
  }
}
