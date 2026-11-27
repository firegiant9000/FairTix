package com.fairtix.config;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Cluster-wide locks for the {@code @Scheduled} jobs (ShedLock, JDBC provider, table
 * {@code shedlock} from V58). With two or more instances every instance's scheduler still
 * fires; each job method carries a {@code @SchedulerLock}, and an instance that finds the
 * job's row locked skips that tick instead of running it a second time.
 *
 * <p>Lock times come from the database clock ({@code usingDbTime}), so skew between the
 * instances' clocks does not move them. {@code lockAtMostFor} is how long a lock outlives an
 * instance that dies mid-run; {@code lockAtLeastFor} keeps a fast job from running again on
 * another instance in the same interval (see each job's annotation).
 *
 * <p>The lock advice is ordered first ({@link Ordered#HIGHEST_PRECEDENCE}), so it wraps the
 * job's {@code @Transactional} advice: the lock row is committed before the job's transaction
 * starts and released after it ends. Inside the transaction another instance would not see
 * the lock until the job had already finished.
 *
 * <p>Independent of {@code fairtix.scheduling.enabled}: the advice also guards a direct call.
 * The test suites switch it off ({@code fairtix.scheduling.lock.enabled=false}) because they
 * call job methods directly, often several times in a row; {@code MultiInstanceIT} turns it on.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M", order = Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = "fairtix.scheduling.lock.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulerLockConfig {

  @Bean
  public LockProvider lockProvider(DataSource dataSource) {
    return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
        .withJdbcTemplate(new JdbcTemplate(dataSource))
        .usingDbTime()
        .build());
  }
}
