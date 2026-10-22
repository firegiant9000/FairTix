package com.fairtix.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.CoreMigrationType;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

/**
 * Proves the full Flyway history applies to an empty PostgreSQL 16 database and
 * that Hibernate's {@code validate} accepts the resulting schema. The context only
 * starts if {@code validate} passes, so a green context load is the schema check.
 */
class FlywayPostgresIT extends PostgresIT {

  private static final int LAST_HISTORICAL_VERSION = 45;

  @Autowired private Flyway flyway;
  @Autowired private DataSource dataSource;
  @Autowired private Environment environment;
  @Autowired private ApplicationContext applicationContext;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void runsAgainstPostgres16_notH2() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      DatabaseMetaData meta = connection.getMetaData();
      assertThat(meta.getDatabaseProductName()).isEqualTo("PostgreSQL");
      assertThat(meta.getDatabaseMajorVersion()).isEqualTo(16);
    }
  }

  @Test
  void hibernateValidatesTheMigratedSchema() {
    assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
    assertThat(environment.getProperty("spring.flyway.enabled")).isEqualTo("true");
  }

  @Test
  void backgroundSchedulersAreOff() {
    assertThat(applicationContext.getBeanNamesForType(ScheduledAnnotationBeanPostProcessor.class))
        .isEmpty();
  }

  /** Seed rows and anything an earlier IT committed (e.g. REQUIRES_NEW audit rows) are gone. */
  @Test
  void startsWithNoRowsInAnyTable() {
    List<String> nonEmpty = jdbcTemplate.queryForList(
            "SELECT tablename FROM pg_tables WHERE schemaname = 'public'"
                + " AND tablename <> 'flyway_schema_history'", String.class)
        .stream()
        .filter(table -> Boolean.TRUE.equals(jdbcTemplate.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM public.\"" + table + "\")", Boolean.class)))
        .toList();
    assertThat(nonEmpty).isEmpty();
  }

  @Test
  void everyMigrationAppliesFromAnEmptyDatabase() {
    assertThat(flyway.getConfiguration().isBaselineOnMigrate()).isFalse();

    MigrationInfo[] applied = flyway.info().applied();
    List<String> versions = Arrays.stream(applied)
        .map(info -> info.getVersion().getVersion())
        .toList();

    // V1 is a real SQL migration, not a BASELINE marker, so nothing was skipped.
    assertThat(applied[0].getType()).isEqualTo(CoreMigrationType.SQL);
    assertThat(applied).noneMatch(info -> info.getType().isBaseline());
    assertThat(applied).allMatch(info -> info.getState() == MigrationState.SUCCESS);
    assertThat(flyway.info().pending()).isEmpty();
    assertThat(versions).containsAll(IntStream.rangeClosed(1, LAST_HISTORICAL_VERSION)
        .mapToObj(String::valueOf)
        .toList());
  }

  /** V47: an audit row may have no user (a failed login for an unregistered email). */
  @Test
  void v47MakesAuditLogUserIdNullable() {
    String nullable = jdbcTemplate.queryForObject(
        "SELECT is_nullable FROM information_schema.columns"
            + " WHERE table_schema = 'public' AND table_name = 'audit_logs' AND column_name = 'user_id'",
        String.class);
    assertThat(nullable).isEqualTo("YES");
  }

  /** V46: one active ticket per seat, as a partial unique index. */
  @Test
  void v46AddsThePartialUniqueIndexOnTicketSeat() {
    assertThat(Arrays.stream(flyway.info().applied()).map(info -> info.getVersion().getVersion()))
        .contains("46");

    String indexDef = jdbcTemplate.queryForObject(
        "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = 'uk_tickets_seat_active'",
        String.class);
    assertThat(indexDef)
        .startsWith("CREATE UNIQUE INDEX uk_tickets_seat_active ON public.tickets USING btree (seat_id)")
        .contains("WHERE")
        .contains("'CANCELLED'")
        .contains("'REFUNDED'");
  }
}
