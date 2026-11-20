package com.fairtix.events.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fairtix.common.TransactionLock;
import com.fairtix.events.domain.EventNotOnSaleException;
import com.fairtix.events.infrastructure.EventRepository;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

/**
 * Which wait each kind of caller gets on the event's sale lock. The ITs run with longer waits
 * (application-it.properties), so the production defaults are pinned here.
 */
class EventSaleGuardTest {

  private static final long PURCHASE_WAIT = 500;
  private static final long STATUS_CHANGE_WAIT = 20_000;

  private EventRepository events;
  private TransactionLock transactionLock;
  private EventSaleGuard guard;
  private final UUID eventId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    events = mock(EventRepository.class);
    transactionLock = mock(TransactionLock.class);
    guard = new EventSaleGuard(events, transactionLock, PURCHASE_WAIT, STATUS_CHANGE_WAIT);
    when(events.findStatusById(eventId)).thenReturn(Optional.of("ACTIVE"));
  }

  @Test
  void productionDefaults_areAShortPurchaseWaitAndALongStatusChangeWait() {
    Constructor<?> constructor = EventSaleGuard.class.getConstructors()[0];
    List<String> values = Arrays.stream(constructor.getParameters())
        .map(p -> p.getAnnotation(Value.class))
        .filter(v -> v != null)
        .map(Value::value)
        .toList();
    assertThat(values).containsExactly(
        "${fairtix.event-sale-lock.purchase-wait-ms:500}",
        "${fairtix.event-sale-lock.status-change-wait-ms:20000}");
  }

  @Test
  void aWaitOfZeroOrLess_isRejected() {
    // lock_timeout = 0 would mean an unbounded wait.
    assertThatThrownBy(() -> new EventSaleGuard(events, transactionLock, 0, STATUS_CHANGE_WAIT))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new EventSaleGuard(events, transactionLock, PURCHASE_WAIT, -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void purchaseBeforeACharge_waitsThePurchaseWait() {
    guard.lockOnSale(eventId);

    verify(transactionLock).acquireShared("event-sale:" + eventId, PURCHASE_WAIT);
    verify(transactionLock, never()).acquireShared(anyString());
  }

  @Test
  void purchaseAfterACharge_waitsTheGeneralTransactionLockWait() {
    guard.lockOnSaleAfterCharge(List.of(eventId));

    verify(transactionLock).acquireShared("event-sale:" + eventId);
    verify(transactionLock, never()).acquireShared(anyString(), anyLong());
  }

  @Test
  void statusChange_waitsTheStatusChangeWait_exclusively() {
    guard.lockForStatusChange(eventId);

    verify(transactionLock).acquire("event-sale:" + eventId, STATUS_CHANGE_WAIT);
    verify(transactionLock, never()).acquireShared(anyString(), anyLong());
  }

  @Test
  void purchaseAfterACharge_onAnEndedEvent_isRefused() {
    when(events.findStatusById(eventId)).thenReturn(Optional.of("CANCELLED"));

    assertThatThrownBy(() -> guard.lockOnSaleAfterCharge(eventId))
        .isInstanceOf(EventNotOnSaleException.class);
  }
}
