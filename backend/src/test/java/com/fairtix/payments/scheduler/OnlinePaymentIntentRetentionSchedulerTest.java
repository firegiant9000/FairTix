package com.fairtix.payments.scheduler;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fairtix.payments.infrastructure.OnlinePaymentIntentRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class OnlinePaymentIntentRetentionSchedulerTest {

  @Test
  void deleteOldRows_usesThirtyDayCutoffFromClock() {
    OnlinePaymentIntentRepository repository = mock(OnlinePaymentIntentRepository.class);
    OnlinePaymentIntentRetentionScheduler scheduler =
        new OnlinePaymentIntentRetentionScheduler(repository);
    scheduler.setClock(Clock.fixed(Instant.parse("2026-11-18T03:30:00Z"), ZoneOffset.UTC));

    scheduler.deleteOldRows();

    verify(repository).deleteCreatedBefore(Instant.parse("2026-10-19T03:30:00Z"));
  }
}
