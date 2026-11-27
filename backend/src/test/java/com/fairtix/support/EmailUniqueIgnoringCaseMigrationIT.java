package com.fairtix.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * V57: users.email becomes unique ignoring case. On a database that already holds case
 * variants of one address the migration fails, naming the count but not the addresses, and
 * changes nothing; on a clean one it builds the index. Runs on a scratch database migrated to
 * V56, because the shared IT database is already at the latest version.
 */
class EmailUniqueIgnoringCaseMigrationIT extends PostgresIT {

  @Autowired private JdbcTemplate jdbcTemplate;

  private final String database = "v57_check_" + UUID.randomUUID().toString().replace("-", "");
  private JdbcTemplate scratchJdbc;
  private DriverManagerDataSource scratch;

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
  void caseVariantsAlreadyPresent_failTheMigrationWithoutNamingThem() {
    migrateTo("56");
    insertUser("dup@v57.test");
    insertUser("Dup@V57.test");
    insertUser("other@v57.test");

    assertThatThrownBy(() -> migrateTo("57"))
        .isInstanceOf(FlywayException.class)
        .hasMessageContaining("1 email address(es) belong to more than one account")
        .satisfies(e -> assertThat(e.getMessage()).doesNotContainIgnoringCase("dup@v57.test"));

    assertThat(scratchJdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(3);
    assertThat(indexExists("uk_users_email_upper")).isFalse();
    assertThat(indexExists("idx_users_email_upper")).as("V54's index left in place").isTrue();
  }

  @Test
  void noCaseVariants_buildsTheUniqueIndexAndDropsV54s() {
    migrateTo("56");
    insertUser("alice@v57.test");
    insertUser("bob@v57.test");

    migrateTo("57");

    assertThat(indexExists("uk_users_email_upper")).isTrue();
    assertThat(indexExists("idx_users_email_upper")).isFalse();
    assertThatThrownBy(() -> insertUser("ALICE@v57.test"))
        .hasMessageContaining("uk_users_email_upper");
  }

  private void migrateTo(String version) {
    Flyway.configure()
        .dataSource(scratch)
        .locations("classpath:db/migration")
        .target(version)
        .load()
        .migrate();
  }

  private void insertUser(String email) {
    scratchJdbc.update("INSERT INTO users (id, email, password, role) VALUES (?, ?, 'x', 'USER')",
        UUID.randomUUID(), email);
  }

  private boolean indexExists(String name) {
    return Boolean.TRUE.equals(scratchJdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM pg_indexes WHERE tablename = 'users' AND indexname = ?)",
        Boolean.class, name));
  }
}
