package com.fairtix.payments.scheduler;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
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
  private static final int BATCH = OnlinePaymentIntentRetentionScheduler.BATCH_SIZE;
  private static final int BUDGET = OnlinePaymentIntentRetentionScheduler.MAX_ROWS_PER_RUN;
  private static final int MAX_FAILURES =
      OnlinePaymentIntentRetentionScheduler.MAX_CONSECUTIVE_FAILURES;

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

  /**
   * Answers the keyset query from {@code rows}, taken as already in id order: the rows after the
   * given one, up to the limit. Rows stay in the list whatever the service does, like rows the
   * job leaves ACTIVE.
   */
  private void keysetOver(List<OnlinePaymentIntent> rows) {
    List<UUID> ids = rows.stream().map(OnlinePaymentIntent::getId).toList();
    when(repository.findActiveCreatedBefore(eq(CUTOFF), any(), any())).thenAnswer(inv -> {
      UUID after = inv.getArgument(1);
      Limit limit = inv.getArgument(2);
      if (!START.equals(after) && !ids.contains(after)) {
        throw new AssertionError("unknown cursor " + after);
      }
      int from = START.equals(after) ? 0 : ids.indexOf(after) + 1;
      return rows.subList(from, Math.min(from + limit.max(), rows.size()));
    });
  }

  @Test
  void deleteOldRows_deletesRowsInvalidatedBeforeThirtyDayCutoff() {
    scheduler.deleteOldRows();

    verify(repository).deleteInvalidatedBefore(CUTOFF);
  }

  @Test
  void deleteOldRows_endsEveryOldActiveRowAcrossPages_thenNextRunStartsOver() {
    List<OnlinePaymentIntent> all = rows(BATCH + 1);
    keysetOver(all);
    when(intents.endIfAbandoned(any())).thenReturn(AbandonedIntentOutcome.LEFT_PAID);
    UUID lastOfFirstPage = all.get(BATCH - 1).getId();

    scheduler.deleteOldRows();

    verify(intents, times(BATCH + 1)).endIfAbandoned(any());
    verify(repository).findActiveCreatedBefore(eq(CUTOFF), eq(lastOfFirstPage), any());

    clearInvocations(intents, repository);
    scheduler.deleteOldRows();

    verify(intents).endIfAbandoned(all.get(0));
    verify(repository).findActiveCreatedBefore(eq(CUTOFF), eq(START), any());
  }

  @Test
  void deleteOldRows_aFailingRowDoesNotStopTheOthers() {
    List<OnlinePaymentIntent> page = rows(3);
    keysetOver(page);
    when(intents.endIfAbandoned(page.get(0))).thenThrow(new RuntimeException("stripe down"));
    when(intents.endIfAbandoned(page.get(1))).thenReturn(AbandonedIntentOutcome.LEFT_PAID);
    when(intents.endIfAbandoned(page.get(2))).thenReturn(AbandonedIntentOutcome.CANCELED);

    scheduler.deleteOldRows();

    verify(intents).endIfAbandoned(page.get(2));
    verify(repository).deleteInvalidatedBefore(CUTOFF);
  }

  @Test
  void deleteOldRows_stopsAfterTooManyFailuresInARow_thenResumesAfterThem_thenStartsOver() {
    List<OnlinePaymentIntent> all = rows(MAX_FAILURES + 2);
    keysetOver(all);
    for (int i = 0; i < MAX_FAILURES; i++) {
      when(intents.endIfAbandoned(all.get(i))).thenThrow(new RuntimeException("stripe down"));
    }
    when(intents.endIfAbandoned(all.get(MAX_FAILURES)))
        .thenReturn(AbandonedIntentOutcome.CANCELED);
    when(intents.endIfAbandoned(all.get(MAX_FAILURES + 1)))
        .thenReturn(AbandonedIntentOutcome.CANCELED);

    scheduler.deleteOldRows();

    verify(intents, times(MAX_FAILURES)).endIfAbandoned(any());
    verify(intents, never()).endIfAbandoned(all.get(MAX_FAILURES));
    verify(repository).deleteInvalidatedBefore(CUTOFF);
    UUID lastFailed = all.get(MAX_FAILURES - 1).getId();

    clearInvocations(intents, repository);
    scheduler.deleteOldRows();

    verify(repository).findActiveCreatedBefore(eq(CUTOFF), eq(lastFailed), any());
    verify(intents, never()).endIfAbandoned(all.get(0));
    verify(intents, never()).endIfAbandoned(all.get(MAX_FAILURES - 1));
    verify(intents).endIfAbandoned(all.get(MAX_FAILURES));
    verify(intents).endIfAbandoned(all.get(MAX_FAILURES + 1));

    clearInvocations(intents);
    scheduler.deleteOldRows();

    verify(intents).endIfAbandoned(all.get(0));
  }

  @Test
  void deleteOldRows_failuresSeparatedBySuccesses_doNotStopThePass() {
    int n = 2 * MAX_FAILURES;
    List<OnlinePaymentIntent> all = rows(n);
    keysetOver(all);
    when(intents.endIfAbandoned(any())).thenReturn(AbandonedIntentOutcome.CANCELED);
    for (int i = 0; i < n; i += 2) {
      when(intents.endIfAbandoned(all.get(i))).thenThrow(new RuntimeException("stripe down"));
    }

    scheduler.deleteOldRows();

    verify(intents, times(n)).endIfAbandoned(any());
  }

  @Test
  void deleteOldRows_stopsAtTheRowBudgetMidPage_andResumesThereNextRun() {
    // The budget is not a multiple of the page size, so the stop falls inside a page.
    assertNotEquals(0, BUDGET % BATCH);
    List<OnlinePaymentIntent> all = rows(BUDGET + BATCH);
    keysetOver(all);
    when(intents.endIfAbandoned(any())).thenReturn(AbandonedIntentOutcome.LEFT_PAID);

    scheduler.deleteOldRows();

    verify(intents, times(BUDGET)).endIfAbandoned(any());
    verify(intents, never()).endIfAbandoned(all.get(BUDGET));

    clearInvocations(intents);
    scheduler.deleteOldRows();

    verify(intents, never()).endIfAbandoned(all.get(BUDGET - 1));
    verify(intents).endIfAbandoned(all.get(BUDGET));
    verify(intents).endIfAbandoned(all.get(BUDGET + BATCH - 1));
  }

  @Test
  void deleteOldRows_stopsWhenTheTimeBudgetIsUsed_andResumesNextRun() {
    List<OnlinePaymentIntent> all = rows(3);
    keysetOver(all);
    MutableClock clock = new MutableClock(NOW);
    scheduler.setClock(clock);
    when(intents.endIfAbandoned(any())).thenReturn(AbandonedIntentOutcome.CANCELED);
    when(intents.endIfAbandoned(all.get(0))).thenAnswer(inv -> {
      clock.advance(OnlinePaymentIntentRetentionScheduler.MAX_RUN_TIME);
      return AbandonedIntentOutcome.CANCELED;
    });
    when(intents.endIfAbandoned(all.get(1))).thenReturn(AbandonedIntentOutcome.CANCELED);

    scheduler.deleteOldRows();

    verify(intents).endIfAbandoned(all.get(0));
    verify(intents, never()).endIfAbandoned(all.get(1));

    clock.set(NOW);
    clearInvocations(intents);
    scheduler.deleteOldRows();

    verify(intents, never()).endIfAbandoned(all.get(0));
    verify(intents).endIfAbandoned(all.get(1));
  }

  private static final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    void advance(Duration by) {
      now = now.plus(by);
    }

    void set(Instant to) {
      now = to;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
