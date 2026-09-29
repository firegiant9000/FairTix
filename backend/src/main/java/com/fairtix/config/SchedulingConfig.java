package com.fairtix.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on {@code @Scheduled} jobs (hold expiry, queue admission, fraud sweeps, ...).
 *
 * <p>Enabled unless {@code fairtix.scheduling.enabled=false}. Integration tests that
 * invoke a scheduler method directly set that property so a background tick cannot
 * run the same job concurrently and double its side effects.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "fairtix.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
