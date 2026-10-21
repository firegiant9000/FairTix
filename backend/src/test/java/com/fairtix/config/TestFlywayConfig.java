package com.fairtix.config;

import com.fairtix.support.TestDatabase;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Every Spring context test starts from the full Flyway schema with no rows: migrate
 * as usual, then remove the migrations' seed rows (demo venues, events and the
 * seeded admin). This replaces H2's {@code ddl-auto=create-drop}, which gave each new
 * context an empty schema.
 *
 * <p>Component-scanned into every {@code @SpringBootTest}, like
 * {@link TestRedisConfig}. Contexts that share the Testcontainers JDBC database each
 * truncate when they start; a cached context reused later sees whatever earlier
 * classes committed, as it did on H2.
 */
@Configuration(proxyBeanMethods = false)
public class TestFlywayConfig {

  @Bean
  FlywayMigrationStrategy migrateThenTruncate() {
    return flyway -> {
      flyway.migrate();
      TestDatabase.truncateAllTables(flyway.getConfiguration().getDataSource());
    };
  }
}
