package com.fairtix.support;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

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
   *
   * <p>Runs in its own transaction with {@code lock_timeout}, so a connection left
   * holding a lock (for example a worker abandoned by a timed-out test) fails the
   * caller after 10 s instead of hanging the build.
   */
  public static void truncateAllTables(DataSource dataSource) {
    try (Connection connection = dataSource.getConnection()) {
      boolean autoCommit = connection.getAutoCommit();
      connection.setAutoCommit(false);
      try (Statement statement = connection.createStatement()) {
        statement.execute("SET LOCAL lock_timeout = '10s'");
        List<String> tables = new ArrayList<>();
        try (ResultSet rs = statement.executeQuery(
            "SELECT format('%I.%I', schemaname, tablename) FROM pg_tables"
                + " WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'")) {
          while (rs.next()) {
            tables.add(rs.getString(1));
          }
        }
        if (!tables.isEmpty()) {
          statement.execute("TRUNCATE TABLE " + String.join(", ", tables) + " RESTART IDENTITY CASCADE");
        }
        connection.commit();
      } catch (SQLException ex) {
        connection.rollback();
        throw ex;
      } finally {
        connection.setAutoCommit(autoCommit);
      }
    } catch (SQLException ex) {
      throw new IllegalStateException("could not empty the test database", ex);
    }
  }
}
