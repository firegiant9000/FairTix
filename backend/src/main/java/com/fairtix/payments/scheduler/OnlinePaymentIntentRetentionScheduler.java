package com.fairtix.payments.scheduler;

import com.fairtix.payments.application.OnlinePaymentIntentService;
import com.fairtix.payments.application.OnlinePaymentIntentService.AbandonedIntentOutcome;
import com.fairtix.payments.domain.OnlinePaymentIntent;
import com.fairtix.payments.infrastructure.OnlinePaymentIntentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class OnlinePaymentIntentRetentionScheduler {

  private static final Logger log =
      LoggerFactory.getLogger(OnlinePaymentIntentRetentionScheduler.class);

  static final Duration RETENTION = Duration.ofDays(30);

  /** Rows read per page when ending abandoned ACTIVE rows. */
  static final int BATCH_SIZE = 100;

  /** Stops the abandoned-row pass when Stripe keeps failing, rather than calling it for every row. */
  static final int MAX_CONSECUTIVE_FAILURES = 10;

  private final OnlinePaymentIntentRepository repository;
  private final OnlinePaymentIntentService intents;
  private Clock clock = Clock.systemUTC();

  public OnlinePaymentIntentRetentionScheduler(OnlinePaymentIntentRepository repository,
      OnlinePaymentIntentService intents) {
    this.repository = repository;
    this.intents = intents;
  }

  void setClock(Clock clock) {
    this.clock = clock;
  }

  @Scheduled(cron = "${payments.online-intent.retention-cron:0 30 3 * * *}")
  public void deleteOldRows() {
    MDC.put("requestId", "sched-deleteOldOnlineIntents-" + UUID.randomUUID());
    try {
      Instant cutoff = clock.instant().minus(RETENTION);
      int deleted = repository.deleteInvalidatedBefore(cutoff);
      log.info("Deleted {} online payment intent rows invalidated more than {} days ago", deleted,
          RETENTION.toDays());
      endAbandonedRows(cutoff);
    } finally {
      MDC.remove("requestId");
    }
  }

  /**
   * Ends ACTIVE rows created before the cutoff whose checkout never completed: unpaid intents are
   * canceled in Stripe, paid ones are left for the runbook. Ended rows are deleted by a later run,
   * 30 days after they were ended.
   */
  private void endAbandonedRows(Instant cutoff) {
    Map<AbandonedIntentOutcome, Integer> counts = new EnumMap<>(AbandonedIntentOutcome.class);
    int failures = 0;
    int consecutiveFailures = 0;
    UUID after = new UUID(0L, 0L);
    pages:
    while (true) {
      List<OnlinePaymentIntent> page =
          repository.findActiveCreatedBefore(cutoff, after, Limit.of(BATCH_SIZE));
      for (OnlinePaymentIntent row : page) {
        try {
          counts.merge(intents.endIfAbandoned(row), 1, Integer::sum);
          consecutiveFailures = 0;
        } catch (RuntimeException e) {
          failures++;
          consecutiveFailures++;
          log.warn("Could not end old online intent row {}", row.getStripePaymentIntentId(), e);
          if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            log.error("Stopped ending old online intent rows after {} failures in a row",
                consecutiveFailures);
            break pages;
          }
        }
      }
      if (page.size() < BATCH_SIZE) {
        break;
      }
      after = page.get(page.size() - 1).getId();
    }
    log.info("Old ACTIVE online intent rows: {}, failed {}", counts, failures);
  }
}
