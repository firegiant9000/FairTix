package com.fairtix.support;

import com.fairtix.config.TestRedisConfig;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for the PostgreSQL container suite ({@code *IT}, run by failsafe in
 * Maven {@code verify}). Flyway applies the full migration history to an empty
 * database and Hibernate runs with {@code ddl-auto=validate}; see
 * {@code application-it.properties}. Redis is still the {@link TestRedisConfig} mock.
 *
 * <p>The container is a singleton started once per JVM, not a JUnit
 * {@code @Container} field: the JUnit extension would stop and restart it for each
 * test class, while Spring's context cache keeps the first container's port.
 * Testcontainers' Ryuk sidecar removes it when the JVM exits.
 *
 * <p>Every IT shares this database and usually the cached context, so subclasses
 * must not leave rows behind: use {@code @Transactional} rollback, or delete what
 * the test created when it commits (for example multi-threaded tests).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("it")
@Import(TestRedisConfig.class)
public abstract class PostgresIT {

  /** Pinned so CI and local runs use the same server build. */
  static final String POSTGRES_IMAGE = "postgres:16.10-alpine";

  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE);

  static {
    POSTGRES.start();
  }
}
