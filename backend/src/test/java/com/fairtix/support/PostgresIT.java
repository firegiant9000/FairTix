package com.fairtix.support;

import com.fairtix.config.TestRedisConfig;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.test.context.transaction.TestTransaction;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for the PostgreSQL container suite ({@code *IT}, run by failsafe in
 * Maven {@code verify}). Flyway applies the full migration history to an empty
 * database and Hibernate runs with {@code ddl-auto=validate}; see
 * {@code application-it.properties}. Redis is still the {@link TestRedisConfig} mock,
 * and the {@code @Scheduled} jobs are off.
 *
 * <p>The container is a singleton started once per JVM, not a JUnit
 * {@code @Container} field: the JUnit extension would stop and restart it for each
 * test class, while Spring's context cache keeps the first container's port.
 * Testcontainers' Ryuk sidecar removes it when the JVM exits.
 *
 * <p>Isolation: every IT starts with the migrated schema and no rows. Seed rows
 * from the migrations are removed right after Flyway runs, and every table is
 * truncated after each test. A {@code @Transactional} test's rollback is not
 * enough on its own, because {@code REQUIRES_NEW} writes (for example
 * {@code AuditService.log}) commit independently.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("it")
@Import({TestRedisConfig.class, PostgresIT.EmptyDatabaseAfterMigration.class})
public abstract class PostgresIT {

  /** Pinned, and the Debian (glibc) variant like docker-compose, so collation matches. */
  static final String POSTGRES_IMAGE = "postgres:16.10";

  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE);

  static {
    POSTGRES.start();
  }

  @Autowired private DataSource dataSource;

  /** Non-transactional tests: their writes are committed, so remove them now. */
  @AfterEach
  void truncateIfNoTestTransaction() {
    if (!TestTransaction.isActive()) {
      truncateAllTables(dataSource);
    }
  }

  /** Transactional tests: runs after the rollback, so the TRUNCATE neither blocks nor rolls back. */
  @AfterTransaction
  void truncateAfterRollback() {
    truncateAllTables(dataSource);
  }

  static void truncateAllTables(DataSource dataSource) {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    List<String> tables = jdbc.queryForList(
        "SELECT format('%I.%I', schemaname, tablename) FROM pg_tables"
            + " WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'",
        String.class);
    if (!tables.isEmpty()) {
      jdbc.execute("TRUNCATE TABLE " + String.join(", ", tables) + " RESTART IDENTITY CASCADE");
    }
  }

  /** Migrates as usual, then removes the migrations' seed rows. */
  @TestConfiguration(proxyBeanMethods = false)
  static class EmptyDatabaseAfterMigration {

    @Bean
    FlywayMigrationStrategy migrateThenTruncate() {
      return flyway -> {
        flyway.migrate();
        truncateAllTables(flyway.getConfiguration().getDataSource());
      };
    }
  }
}
