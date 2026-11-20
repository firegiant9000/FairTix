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

  @Test
  void deleteOldRows_afterAFailureStop_nextRunResumesAfterTheLastFailedRow() {
    int limit = OnlinePaymentIntentRetentionScheduler.MAX_CONSECUTIVE_FAILURES;
    List<OnlinePaymentIntent> page = rows(limit + 2);
    UUID lastFailed = page.get(limit - 1).getId();
    List<OnlinePaymentIntent> rest = page.subList(limit, page.size());
    when(repository.findActiveCreatedBefore(eq(CUTOFF), eq(START), any())).thenReturn(page);
    when(repository.findActiveCreatedBefore(eq(CUTOFF), eq(lastFailed), any())).thenReturn(rest);
    when(intents.endIfAbandoned(any())).thenThrow(new RuntimeException("stripe down"));

    scheduler.deleteOldRows();
    scheduler.deleteOldRows();

    verify(repository).findActiveCreatedBefore(eq(CUTOFF), eq(lastFailed), any());
  }

  @Test
  void deleteOldRows_stopsAtTheRowBudget_andResumesThereNextRun() {
    int budget = OnlinePaymentIntentRetentionScheduler.MAX_ROWS_PER_RUN;
    int batch = OnlinePaymentIntentRetentionScheduler.BATCH_SIZE;
    List<OnlinePaymentIntent> all = rows(budget + 1);
    for (int from = 0; from < all.size(); from += batch) {
      UUID after = from == 0 ? START : all.get(from - 1).getId();
      List<OnlinePaymentIntent> page = all.subList(from, Math.min(from + batch, all.size()));
      when(repository.findActiveCreatedBefore(eq(CUTOFF), eq(after), any())).thenReturn(page);
    }
    when(intents.endIfAbandoned(any())).thenReturn(AbandonedIntentOutcome.LEFT_PAID);

    scheduler.deleteOldRows();

    verify(intents, times(budget)).endIfAbandoned(any());
    verify(intents, never()).endIfAbandoned(all.get(budget));

    scheduler.deleteOldRows();

    verify(intents).endIfAbandoned(all.get(budget));
  }

  @Test
  void deleteOldRows_stopsWhenTheTimeBudgetIsUsed() {
    List<OnlinePaymentIntent> page = rows(3);
    when(repository.findActiveCreatedBefore(eq(CUTOFF), eq(START), any())).thenReturn(page);
    MutableClock clock = new MutableClock(NOW);
    scheduler.setClock(clock);
    when(intents.endIfAbandoned(page.get(0))).thenAnswer(inv -> {
      clock.advance(OnlinePaymentIntentRetentionScheduler.MAX_RUN_TIME);
      return AbandonedIntentOutcome.CANCELED;
    });

    scheduler.deleteOldRows();

    verify(intents).endIfAbandoned(page.get(0));
    verify(intents, never()).endIfAbandoned(page.get(1));
  }

  private static final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    void advance(java.time.Duration by) {
      now = now.plus(by);
    }

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
