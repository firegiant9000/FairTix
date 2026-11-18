package com.fairtix.payments.scheduler;

import com.fairtix.payments.infrastructure.OnlinePaymentIntentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

@Component
public class OnlinePaymentIntentRetentionScheduler {

  private static final Logger log =
      LoggerFactory.getLogger(OnlinePaymentIntentRetentionScheduler.class);

  static final Duration RETENTION = Duration.ofDays(30);

  private final OnlinePaymentIntentRepository repository;
  private Clock clock = Clock.systemUTC();

  public OnlinePaymentIntentRetentionScheduler(OnlinePaymentIntentRepository repository) {
    this.repository = repository;
  }

  void setClock(Clock clock) {
    this.clock = clock;
  }

  @Scheduled(cron = "${payments.online-intent.retention-cron:0 30 3 * * *}")
  public void deleteOldRows() {
    MDC.put("requestId", "sched-deleteOldOnlineIntents-" + UUID.randomUUID());
    try {
      int deleted = repository.deleteCreatedBefore(clock.instant().minus(RETENTION));
      log.info("Deleted {} online payment intent rows older than {} days", deleted,
          RETENTION.toDays());
    } finally {
      MDC.remove("requestId");
    }
  }
}
