package com.fairtix.support;

import com.fairtix.config.TestRedisConfig;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.test.context.transaction.TestTransaction;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for the container suite ({@code *IT}, run by failsafe in Maven
 * {@code verify}). Flyway applies the full migration history to an empty PostgreSQL
 * database and Hibernate runs with {@code ddl-auto=validate}; see
 * {@code application-it.properties}. Redis is a real Redis 7 container, and the
 * {@code @Scheduled} jobs are off.
 *
 * <p>Both containers are singletons started once per JVM, not JUnit
 * {@code @Container} fields: the JUnit extension would stop and restart them for
 * each test class, while Spring's context cache keeps the first container's port.
 * Testcontainers' Ryuk sidecar removes them when the JVM exits.
 *
 * <p>Redis: {@code fairtix.test.redis=container} (the {@code it} profile) switches
 * off {@link TestRedisConfig}'s mock, so the application's own {@code RedisConfig}
 * builds the Redisson client from {@code spring.data.redis.host}/{@code port}.
 *
 * <p>Isolation: every IT starts with the migrated schema, no rows and an empty Redis.
 * Seed rows from the migrations are removed right after Flyway runs
 * ({@link com.fairtix.config.TestFlywayConfig}), and after each
 * test every table is truncated and Redis is flushed. A {@code @Transactional} test's
 * rollback is not enough on its own, because {@code REQUIRES_NEW} writes (for example
 * {@code AuditService.log}) commit independently, and Redis writes are not
 * transactional at all.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("it")
public abstract class PostgresIT {

  /** Pinned, and the Debian (glibc) variant like docker-compose, so collation matches. */
  protected static final String POSTGRES_IMAGE = "postgres:16.10";

  /** Pinned patch release of docker-compose's {@code redis:7-alpine}. */
  protected static final String REDIS_IMAGE = "redis:7.4.6-alpine";

  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE);

  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE)).withExposedPorts(6379);

  static {
    POSTGRES.start();
    REDIS.start();
  }

  @DynamicPropertySource
  static void redisProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
  }

  @Autowired private DataSource dataSource;
  @Autowired private RedissonClient redissonClient;

  /** Non-transactional tests: their writes are committed, so remove them now. */
  @AfterEach
  void truncateIfNoTestTransaction() {
    if (!TestTransaction.isActive()) {
      TestDatabase.truncateAllTables(dataSource);
      redissonClient.getKeys().flushall();
    }
  }

  /** Transactional tests: runs after the rollback, so the TRUNCATE neither blocks nor rolls back. */
  @AfterTransaction
  void truncateAfterRollback() {
    TestDatabase.truncateAllTables(dataSource);
    redissonClient.getKeys().flushall();
  }

}
