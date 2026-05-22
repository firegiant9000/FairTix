package com.fairtix.holds.scheduler;

import com.fairtix.holds.application.EventHoldService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Releases event holds whose {@code auto_release_at} has passed.
 *
 * <p>Mirrors the existing seat-hold cleanup scheduler pattern. Tick interval
 * is configurable via {@code holds.event.auto-release-interval-ms} and shares
 * the {@code @EnableScheduling} declared on the main application class.
 */
@Component
public class HoldReleaseScheduler {

  private static final Logger log = LoggerFactory.getLogger(HoldReleaseScheduler.class);

  // Synthetic system user id for audit attribution of automatic releases.
  // Real audit rows with a NULL user_id are forbidden by the schema; a fixed
  // sentinel keeps reconciliation queries unambiguous.
  private static final UUID SYSTEM_USER = UUID.fromString("00000000-0000-0000-0000-000000000000");

  private final EventHoldService holds;

  public HoldReleaseScheduler(EventHoldService holds) {
    this.holds = holds;
  }

  @Scheduled(fixedDelayString = "${holds.event.auto-release-interval-ms:60000}")
  public void run() {
    try {
      int released = holds.releaseDueHolds(Instant.now(), SYSTEM_USER);
      if (released > 0) {
        log.info("Auto-released {} event hold(s)", released);
      }
    } catch (RuntimeException e) {
      log.warn("Auto-release of event holds failed: {}", e.getMessage());
    }
  }
}
