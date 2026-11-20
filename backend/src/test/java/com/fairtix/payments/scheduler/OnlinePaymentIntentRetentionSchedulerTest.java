package com.fairtix.payments.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fairtix.payments.application.OnlinePaymentIntentService;
import com.fairtix.payments.application.OnlinePaymentIntentService.AbandonedIntentOutcome;
import com.fairtix.payments.domain.OnlinePaymentIntent;
import com.fairtix.payments.infrastructure.OnlinePaymentIntentRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;

class OnlinePaymentIntentRetentionSchedulerTest {

  private static final Instant NOW = Instant.parse("2026-11-18T03:30:00Z");
  private static final Instant CUTOFF = Instant.parse("2026-10-19T03:30:00Z");
  private static final UUID START = new UUID(0L, 0L);

  private OnlinePaymentIntentRepository repository;
  private OnlinePaymentIntentService intents;
  private OnlinePaymentIntentRetentionScheduler scheduler;

  @BeforeEach
  void setUp() {
    repository = mock(OnlinePaymentIntentRepository.class);
    intents = mock(OnlinePaymentIntentService.class);
    scheduler = new OnlinePaymentIntentRetentionScheduler(repository, intents);
    scheduler.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
    when(repository.findActiveCreatedBefore(any(), any(), any())).thenReturn(List.of());
  }

  private static List<OnlinePaymentIntent> rows(int n) {
    List<OnlinePaymentIntent> rows = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      OnlinePaymentIntent row = mock(OnlinePaymentIntent.class);
      when(row.getId()).thenReturn(UUID.randomUUID());
      when(row.getStripePaymentIntentId()).thenReturn("pi_" + i);
      rows.add(row);
    }
    return rows;
  }

  @Test
  void deleteOldRows_deletesRowsInvalidatedBeforeThirtyDayCutoff() {
    scheduler.deleteOldRows();

    verify(repository).deleteInvalidatedBefore(CUTOFF);
  }

  @Test
  void deleteOldRows_endsEveryActiveRowOlderThanTheCutoff_pageByPage() {
    int batch = OnlinePaymentIntentRetentionScheduler.BATCH_SIZE;
    List<OnlinePaymentIntent> first = rows(batch);
    List<OnlinePaymentIntent> second = rows(1);
    UUID lastOfFirst = first.get(batch - 1).getId();
    UUID lastOfSecond = second.get(0).getId();
    when(repository.findActiveCreatedBefore(CUTOFF, START, Limit.of(batch))).thenReturn(first);
    when(repository.findActiveCreatedBefore(CUTOFF, lastOfFirst, Limit.of(batch)))
        .thenReturn(second);
    when(intents.endIfAbandoned(any())).thenReturn(AbandonedIntentOutcome.CANCELED);

    scheduler.deleteOldRows();

    verify(intents, times(batch + 1)).endIfAbandoned(any());
    verify(intents).endIfAbandoned(second.get(0));
    verify(repository, never())
        .findActiveCreatedBefore(eq(CUTOFF), eq(lastOfSecond), any());
  }

  @Test
  void deleteOldRows_aFailingRowDoesNotStopTheOthers() {
    List<OnlinePaymentIntent> page = rows(3);
    when(repository.findActiveCreatedBefore(eq(CUTOFF), eq(START), any())).thenReturn(page);
    when(intents.endIfAbandoned(page.get(0))).thenThrow(new RuntimeException("stripe down"));
    when(intents.endIfAbandoned(page.get(1))).thenReturn(AbandonedIntentOutcome.LEFT_PAID);
    when(intents.endIfAbandoned(page.get(2))).thenReturn(AbandonedIntentOutcome.CANCELED);

    scheduler.deleteOldRows();

    verify(intents).endIfAbandoned(page.get(2));
    verify(repository).deleteInvalidatedBefore(CUTOFF);
  }

  @Test
  void deleteOldRows_stopsAfterTooManyFailuresInARow() {
    int limit = OnlinePaymentIntentRetentionScheduler.MAX_CONSECUTIVE_FAILURES;
    List<OnlinePaymentIntent> page = rows(limit + 5);
    when(repository.findActiveCreatedBefore(eq(CUTOFF), eq(START), any())).thenReturn(page);
    when(intents.endIfAbandoned(any())).thenThrow(new RuntimeException("stripe down"));

    scheduler.deleteOldRows();

    verify(intents, times(limit)).endIfAbandoned(any());
    verify(repository).deleteInvalidatedBefore(CUTOFF);
  }
}
