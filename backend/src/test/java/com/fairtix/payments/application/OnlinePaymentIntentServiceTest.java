package com.fairtix.payments.application;

import com.fairtix.audit.application.AuditService;
import com.fairtix.payments.application.StripePaymentService.ConnectContext;
import com.fairtix.payments.application.StripePaymentService.StripeIntent;
import com.fairtix.payments.domain.IntentInvalidationReason;
import com.fairtix.payments.domain.OnlinePaymentIntent;
import com.fairtix.payments.domain.OnlinePaymentIntentStatus;
import com.fairtix.payments.infrastructure.OnlinePaymentIntentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OnlinePaymentIntentServiceTest {

  private static final UUID USER = UUID.randomUUID();
  private static final UUID HOLD_A = UUID.randomUUID();
  private static final List<UUID> HOLDS = List.of(HOLD_A);
  private static final long AMOUNT = 5000L;
  private static final Instant NOW = Instant.parse("2026-11-18T12:00:00Z");

  private StripePaymentService stripe;
  private OnlinePaymentIntentRepository repo;
  private AuditService audit;
  private OnlinePaymentIntentService service;

  @BeforeEach
  void setUp() {
    stripe = mock(StripePaymentService.class);
    repo = mock(OnlinePaymentIntentRepository.class);
    audit = mock(AuditService.class);
    service = new OnlinePaymentIntentService(stripe, repo, audit);
    service.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private OnlinePaymentIntent row(long amount, ConnectContext connect, String intentId) {
    OnlinePaymentIntent row = mock(OnlinePaymentIntent.class);
    when(row.getId()).thenReturn(UUID.randomUUID());
    when(row.getAmountCents()).thenReturn(amount);
    when(row.getRoutingHash()).thenReturn(StripeIdempotencyKeys.routingHash(connect));
    when(row.getStripePaymentIntentId()).thenReturn(intentId);
    when(repo.findByUserIdAndHoldSetHashAndStatus(USER, StripeIdempotencyKeys.holdSetHash(HOLDS),
        OnlinePaymentIntentStatus.ACTIVE)).thenReturn(Optional.of(row));
    return row;
  }

  private void noRow() {
    when(repo.findByUserIdAndHoldSetHashAndStatus(any(), anyString(), any()))
        .thenReturn(Optional.empty());
  }

  private static StripeIntent intent(String id, String status, String secret) {
    return new StripeIntent(id, status, secret);
  }

  private void stubCreate(StripeIntent created) {
    when(stripe.createPaymentIntent(anyLong(), anyString(), any(), any(), anyString()))
        .thenReturn(created);
  }

  @Test
  void noRow_createsWithOriginalKey_andInserts() throws Exception {
    noRow();
    stubCreate(intent("pi_new", "requires_payment_method", "secret_new"));

    String secret = service.clientSecretFor(USER, HOLDS, AMOUNT, null);

    assertEquals("secret_new", secret);
    verify(stripe).createPaymentIntent(AMOUNT, "usd", null, USER,
        StripeIdempotencyKeys.onlineIntent(USER, HOLDS, AMOUNT, null));
    ArgumentCaptor<OnlinePaymentIntent> saved = ArgumentCaptor.forClass(OnlinePaymentIntent.class);
    verify(repo).saveAndFlush(saved.capture());
    assertEquals(USER, saved.getValue().getUserId());
    assertEquals(StripeIdempotencyKeys.holdSetHash(HOLDS), saved.getValue().getHoldSetHash());
    assertEquals(StripeIdempotencyKeys.routingHash(null), saved.getValue().getRoutingHash());
    assertEquals(AMOUNT, saved.getValue().getAmountCents());
    assertEquals("pi_new", saved.getValue().getStripePaymentIntentId());
  }

  @Test
  void activeRow_unpaidSameAmount_isReusedWithoutCreate() throws Exception {
    row(AMOUNT, null, "pi_old");
    when(stripe.retrievePaymentIntent("pi_old"))
        .thenReturn(intent("pi_old", "requires_payment_method", "secret_old"));

    assertEquals("secret_old", service.clientSecretFor(USER, HOLDS, AMOUNT, null));

    verify(stripe, never()).createPaymentIntent(anyLong(), anyString(), any(), any(), anyString());
    verify(stripe, never()).cancelPaymentIntent(anyString());
  }

  @Test
  void activeRow_succeededWithChangedAmount_isReturned() throws Exception {
    row(4000L, null, "pi_old");
    when(stripe.retrievePaymentIntent("pi_old")).thenReturn(intent("pi_old", "succeeded", "secret_old"));

    assertEquals("secret_old", service.clientSecretFor(USER, HOLDS, AMOUNT, null));

    verify(stripe, never()).cancelPaymentIntent(anyString());
    verify(stripe, never()).createPaymentIntent(anyLong(), anyString(), any(), any(), anyString());
    verify(repo, never()).invalidate(any(), any(), any());
  }

  @Test
  void processingWithChangedAmount_isReturned() throws Exception {
    row(4000L, null, "pi_old");
    when(stripe.retrievePaymentIntent("pi_old")).thenReturn(intent("pi_old", "processing", "secret_old"));

    assertEquals("secret_old", service.clientSecretFor(USER, HOLDS, AMOUNT, null));

    verify(stripe, never()).cancelPaymentIntent(anyString());
    verify(stripe, never()).createPaymentIntent(anyLong(), anyString(), any(), any(), anyString());
    verify(repo, never()).invalidate(any(), any(), any());
  }

  @Test
  void activeRow_canceled_invalidatesAndCreatesReplacement() throws Exception {
    OnlinePaymentIntent row = row(AMOUNT, null, "pi_old");
    when(stripe.retrievePaymentIntent("pi_old")).thenReturn(intent("pi_old", "canceled", "secret_old"));
    stubCreate(intent("pi_new", "requires_payment_method", "secret_new"));

    assertEquals("secret_new", service.clientSecretFor(USER, HOLDS, AMOUNT, null));

    verify(repo).invalidate(row.getId(), IntentInvalidationReason.CANCELED, NOW);
    verify(stripe, never()).cancelPaymentIntent(anyString());
    verify(stripe).createPaymentIntent(AMOUNT, "usd", null, USER,
        StripeIdempotencyKeys.onlineIntent(USER, HOLDS, AMOUNT, null, "pi_old"));
  }

  @Test
  void amountChanged_createsWithReplacementKey() throws Exception {
    OnlinePaymentIntent row = row(4000L, null, "pi_old");
    when(stripe.retrievePaymentIntent("pi_old"))
        .thenReturn(intent("pi_old", "requires_payment_method", "secret_old"));
    when(stripe.cancelPaymentIntent("pi_old")).thenReturn(intent("pi_old", "canceled", "secret_old"));
    stubCreate(intent("pi_new", "requires_payment_method", "secret_new"));

    assertEquals("secret_new", service.clientSecretFor(USER, HOLDS, AMOUNT, null));

    InOrder order = inOrder(stripe, repo);
    order.verify(stripe).cancelPaymentIntent("pi_old");
    order.verify(repo).invalidate(row.getId(), IntentInvalidationReason.AMOUNT_CHANGED, NOW);
    order.verify(stripe).createPaymentIntent(AMOUNT, "usd", null, USER,
        StripeIdempotencyKeys.onlineIntent(USER, HOLDS, AMOUNT, null, "pi_old"));
    verify(audit).log(USER, "PAYMENT_INTENT_REPLACED", "PAYMENT", null,
        "stripe_intent=pi_old, reason=AMOUNT_CHANGED");
  }

  @Test
  void routingChanged_cancelsAndInvalidatesAsRoutingChanged() throws Exception {
    OnlinePaymentIntent row = row(AMOUNT, null, "pi_old");
    ConnectContext connect = new ConnectContext("org-1", "acct_1", 100L, "SFX");
    when(stripe.retrievePaymentIntent("pi_old"))
        .thenReturn(intent("pi_old", "requires_payment_method", "secret_old"));
    when(stripe.cancelPaymentIntent("pi_old")).thenReturn(intent("pi_old", "canceled", "secret_old"));
    stubCreate(intent("pi_new", "requires_payment_method", "secret_new"));

    assertEquals("secret_new", service.clientSecretFor(USER, HOLDS, AMOUNT, connect));

    verify(repo).invalidate(row.getId(), IntentInvalidationReason.ROUTING_CHANGED, NOW);
    verify(audit).log(USER, "PAYMENT_INTENT_REPLACED", "PAYMENT", null,
        "stripe_intent=pi_old, reason=ROUTING_CHANGED");
  }

  @Test
  void requiresCaptureWithChangedAmount_isReturned() {
    row(4000L, null, "pi_old");
    when(stripe.retrievePaymentIntent("pi_old"))
        .thenReturn(intent("pi_old", "requires_capture", "secret_old"));

    assertEquals("secret_old", service.clientSecretFor(USER, HOLDS, AMOUNT, null));

    verify(stripe, never()).cancelPaymentIntent(anyString());
    verify(stripe, never()).createPaymentIntent(anyLong(), anyString(), any(), any(), anyString());
    verify(repo, never()).invalidate(any(), any(), any());
  }

  @Test
  void cancelReturnsUnexpectedStatus_throwsRetryConflict() {
    row(4000L, null, "pi_old");
    when(stripe.retrievePaymentIntent("pi_old"))
        .thenReturn(intent("pi_old", "requires_payment_method", "secret_old"));
    when(stripe.cancelPaymentIntent("pi_old"))
        .thenReturn(intent("pi_old", "requires_payment_method", "secret_old"));

    assertThrows(PaymentRetryConflictException.class,
        () -> service.clientSecretFor(USER, HOLDS, AMOUNT, null));

    verify(repo, never()).invalidate(any(), any(), any());
    verify(stripe, never()).createPaymentIntent(anyLong(), anyString(), any(), any(), anyString());
  }

  @Test
  void insertRace_noWinnerFound_throwsRetryConflict() {
    noRow();
    stubCreate(intent("pi_new", "requires_payment_method", "secret_new"));
    when(repo.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("dup"));

    assertThrows(PaymentRetryConflictException.class,
        () -> service.clientSecretFor(USER, HOLDS, AMOUNT, null));
  }

  @Test
  void invalidateReturnsZero_stillCreatesReplacement() {
    OnlinePaymentIntent row = row(4000L, null, "pi_old");
    when(stripe.retrievePaymentIntent("pi_old"))
        .thenReturn(intent("pi_old", "requires_payment_method", "secret_old"));
    when(stripe.cancelPaymentIntent("pi_old")).thenReturn(intent("pi_old", "canceled", "secret_old"));
    when(repo.invalidate(any(), any(), any())).thenReturn(0);
    stubCreate(intent("pi_new", "requires_payment_method", "secret_new"));

    assertEquals("secret_new", service.clientSecretFor(USER, HOLDS, AMOUNT, null));

    verify(repo).invalidate(row.getId(), IntentInvalidationReason.AMOUNT_CHANGED, NOW);
    verify(stripe).createPaymentIntent(AMOUNT, "usd", null, USER,
        StripeIdempotencyKeys.onlineIntent(USER, HOLDS, AMOUNT, null, "pi_old"));
  }

  @Test
  void createReplaysCanceledIntent_createsReplacementKeyedOnIt() {
    noRow();
    String originalKey = StripeIdempotencyKeys.onlineIntent(USER, HOLDS, AMOUNT, null);
    String replacementKey = StripeIdempotencyKeys.onlineIntent(USER, HOLDS, AMOUNT, null, "pi_dead");
    when(stripe.createPaymentIntent(AMOUNT, "usd", null, USER, originalKey))
        .thenReturn(intent("pi_dead", "canceled", "secret_dead"));
    when(stripe.createPaymentIntent(AMOUNT, "usd", null, USER, replacementKey))
        .thenReturn(intent("pi_new", "requires_payment_method", "secret_new"));

    assertEquals("secret_new", service.clientSecretFor(USER, HOLDS, AMOUNT, null));

    ArgumentCaptor<OnlinePaymentIntent> saved = ArgumentCaptor.forClass(OnlinePaymentIntent.class);
    verify(repo).saveAndFlush(saved.capture());
    assertEquals("pi_new", saved.getValue().getStripePaymentIntentId());
  }

  @Test
  void createReplaysCanceledTwice_throwsRetryConflict() {
    noRow();
    stubCreate(intent("pi_dead", "canceled", "secret_dead"));

    assertThrows(PaymentRetryConflictException.class,
        () -> service.clientSecretFor(USER, HOLDS, AMOUNT, null));

    verify(repo, never()).saveAndFlush(any());
  }

  @Test
  void cancelRefusedBecausePaid_returnsPaidIntent() throws Exception {
    row(4000L, null, "pi_old");
    when(stripe.retrievePaymentIntent("pi_old"))
        .thenReturn(intent("pi_old", "requires_payment_method", "secret_old"));
    when(stripe.cancelPaymentIntent("pi_old")).thenReturn(intent("pi_old", "succeeded", "secret_paid"));

    assertEquals("secret_paid", service.clientSecretFor(USER, HOLDS, AMOUNT, null));

    verify(repo, never()).invalidate(any(), any(), any());
    verify(stripe, never()).createPaymentIntent(anyLong(), anyString(), any(), any(), anyString());
    verifyNoInteractions(audit);
  }

  @Test
  void insertRace_sameIntent_returnsSecret() throws Exception {
    OnlinePaymentIntent winner = mock(OnlinePaymentIntent.class);
    when(winner.getStripePaymentIntentId()).thenReturn("pi_new");
    when(repo.findByUserIdAndHoldSetHashAndStatus(any(), anyString(), any()))
        .thenReturn(Optional.empty(), Optional.of(winner));
    stubCreate(intent("pi_new", "requires_payment_method", "secret_new"));
    when(repo.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("dup"));

    assertEquals("secret_new", service.clientSecretFor(USER, HOLDS, AMOUNT, null));
  }

  @Test
  void insertRace_differentIntent_throwsRetryConflict() throws Exception {
    OnlinePaymentIntent winner = mock(OnlinePaymentIntent.class);
    when(winner.getStripePaymentIntentId()).thenReturn("pi_other");
    when(repo.findByUserIdAndHoldSetHashAndStatus(any(), anyString(), any()))
        .thenReturn(Optional.empty(), Optional.of(winner));
    stubCreate(intent("pi_new", "requires_payment_method", "secret_new"));
    when(repo.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("dup"));

    assertThrows(PaymentRetryConflictException.class,
        () -> service.clientSecretFor(USER, HOLDS, AMOUNT, null));
  }
}
