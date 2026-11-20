package com.fairtix.payments.application;

import com.fairtix.audit.application.AuditService;
import com.fairtix.payments.application.StripePaymentService.ConnectContext;
import com.fairtix.payments.application.StripePaymentService.StripeIntent;
import com.fairtix.payments.domain.IntentInvalidationReason;
import com.fairtix.payments.domain.OnlinePaymentIntent;
import com.fairtix.payments.domain.OnlinePaymentIntentStatus;
import com.fairtix.payments.infrastructure.OnlinePaymentIntentRepository;
import com.fairtix.payments.infrastructure.PaymentRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Returns the client secret of the one online PaymentIntent for a user and hold set,
 * persisting it so a customer returning after Stripe's idempotency window gets the same
 * intent back instead of being able to pay twice.
 *
 * <p>Deliberately not {@code @Transactional}: Stripe calls must stay outside DB transactions.
 */
@Service
public class OnlinePaymentIntentService {

  private static final Logger log = LoggerFactory.getLogger(OnlinePaymentIntentService.class);

  private static final Set<String> PAID_OR_IN_FLIGHT = Set.of("succeeded", "processing", "requires_capture");

  /**
   * How many replacements replay recovery creates before giving up with a retryable 409. A chain
   * only grows when a hold set's intent is canceled and replaced, so a real one stays short; the
   * bound stops a pathological chain from making unbounded Stripe calls in one request.
   */
  static final int MAX_REPLAYED_REPLACEMENTS = 5;

  private final StripePaymentService stripe;
  private final OnlinePaymentIntentRepository repository;
  private final AuditService auditService;
  private final PaymentRecordRepository paymentRecordRepository;
  private Clock clock = Clock.systemUTC();

  public OnlinePaymentIntentService(StripePaymentService stripe,
      OnlinePaymentIntentRepository repository, AuditService auditService,
      PaymentRecordRepository paymentRecordRepository) {
    this.stripe = stripe;
    this.repository = repository;
    this.auditService = auditService;
    this.paymentRecordRepository = paymentRecordRepository;
  }

  void setClock(Clock clock) {
    this.clock = clock;
  }

  public String clientSecretFor(UUID userId, List<UUID> holdIds, long amountCents,
      ConnectContext connect) {
    String holdHash = StripeIdempotencyKeys.holdSetHash(holdIds);
    String routing = StripeIdempotencyKeys.routingHash(connect);
    String replaces = null;

    Optional<OnlinePaymentIntent> existing = repository.findByUserIdAndHoldSetHashAndStatus(
        userId, holdHash, OnlinePaymentIntentStatus.ACTIVE);
    if (existing.isPresent()) {
      OnlinePaymentIntent row = existing.get();
      String intentId = row.getStripePaymentIntentId();
      StripeIntent intent = stripe.retrievePaymentIntent(intentId);
      if (PAID_OR_IN_FLIGHT.contains(intent.status())) {
        return intent.clientSecret();
      }
      if ("canceled".equals(intent.status())) {
        repository.invalidate(row.getId(), IntentInvalidationReason.CANCELED, clock.instant());
      } else if (row.getAmountCents() != amountCents || !routing.equals(row.getRoutingHash())) {
        IntentInvalidationReason reason = row.getAmountCents() != amountCents
            ? IntentInvalidationReason.AMOUNT_CHANGED
            : IntentInvalidationReason.ROUTING_CHANGED;
        StripeIntent after = stripe.cancelPaymentIntent(intentId);
        if (PAID_OR_IN_FLIGHT.contains(after.status())) {
          return after.clientSecret(); // paid in another tab
        }
        if (!"canceled".equals(after.status())) {
          throw new PaymentRetryConflictException(
              "Existing payment intent " + intentId + " could not be canceled (status "
                  + after.status() + ")", null);
        }
        auditService.log(userId, "PAYMENT_INTENT_REPLACED", "PAYMENT", null,
            "stripe_intent=" + intentId + ", reason=" + reason);
        repository.invalidate(row.getId(), reason, clock.instant());
      } else {
        return intent.clientSecret();
      }
      replaces = intentId;
    }

    StripeIntent created = stripe.createPaymentIntent(amountCents, "usd", connect, userId,
        StripeIdempotencyKeys.onlineIntent(userId, holdIds, amountCents, connect, replaces));
    if ("canceled".equals(created.status())) {
      // Defensive only: an idempotent replay carries the creation-time status, not this one.
      String deadId = created.id();
      created = stripe.createPaymentIntent(amountCents, "usd", connect, userId,
          StripeIdempotencyKeys.onlineIntent(userId, holdIds, amountCents, connect, deadId));
      if ("canceled".equals(created.status())) {
        throw new PaymentRetryConflictException(
            "Replacement payment intent " + created.id() + " was already canceled; retry", null);
      }
    }
    String secret = insert(userId, holdHash, amountCents, routing, created);
    if (secret != null) {
      return secret;
    }
    // Insert failed, no active winner, and the id already has a row: Stripe replayed an intent
    // whose row was ended. The replacement keyed on it may itself be a replay of an ended intent
    // (the hold set was replaced more than once, and the process stopped after Stripe created the
    // newest intent but before its row was inserted), so walk the chain, bounded.
    StripeIntent replayed = created;
    for (int replacements = 0; ; replacements++) {
      Optional<String> paid = paidOrInFlightSecret(replayed.id());
      if (paid.isPresent()) {
        return paid.get();
      }
      if (replacements == MAX_REPLAYED_REPLACEMENTS) {
        throw new PaymentRetryConflictException(
            "Replacement payment intent " + replayed.id() + " was already used; retry", null);
      }
      replayed = stripe.createPaymentIntent(amountCents, "usd", connect, userId,
          StripeIdempotencyKeys.onlineIntent(userId, holdIds, amountCents, connect, replayed.id()));
      secret = insert(userId, holdHash, amountCents, routing, replayed);
      if (secret != null) {
        return secret;
      }
    }
  }

  /**
   * For an intent Stripe replayed whose row was already ended: the secret if the intent is paid
   * or in flight, else empty. The row was ended either because the intent was canceled, or
   * (reason COMPLETED) because a concurrent checkout committed its order after the lookup. The
   * replay carries the creation-time status, so this asks Stripe: a paid or in-flight intent is
   * returned as is (checkout then answers with the existing order), never replaced with a second
   * payable one.
   */
  private Optional<String> paidOrInFlightSecret(String intentId) {
    StripeIntent current = stripe.retrievePaymentIntent(intentId);
    return PAID_OR_IN_FLIGHT.contains(current.status())
        ? Optional.of(current.clientSecret())
        : Optional.empty();
  }

  /**
   * Whether the intent was created by {@link #clientSecretFor} for this user, this hold set and
   * this payout routing. Stripe's verification only proves the charge is the caller's and covers
   * the amount; without this check, an intent paid for one hold set could buy another of the same
   * total, and the funds would go to the first organizer's account.
   *
   * <p>The row's status is not checked: a completed intent's resubmit is answered from its order
   * before this runs, and an invalidated one was canceled at Stripe, so it never verifies. False
   * when there is no row: a legacy intent created before rows were stored, or an invalidated row
   * the retention job has deleted.
   */
  public boolean isBoundTo(String stripePaymentIntentId, UUID userId, List<UUID> holdIds,
      ConnectContext connect) {
    return repository.findByStripePaymentIntentId(stripePaymentIntentId)
        .filter(row -> userId.equals(row.getUserId()))
        .filter(row -> StripeIdempotencyKeys.holdSetHash(holdIds).equals(row.getHoldSetHash()))
        .filter(row -> StripeIdempotencyKeys.routingHash(connect).equals(row.getRoutingHash()))
        .isPresent();
  }

  /**
   * Ends the ACTIVE row for an intent whose order has been created, so retention can delete it.
   * Returns the number of rows changed: 0 when there is no ACTIVE row (already ended, or an
   * intent created before rows were stored).
   */
  public int markCompleted(String stripePaymentIntentId) {
    return repository.invalidateActiveByStripePaymentIntentId(
        stripePaymentIntentId, IntentInvalidationReason.COMPLETED, clock.instant());
  }

  /** What {@link #endIfAbandoned} did with an old ACTIVE row. */
  public enum AbandonedIntentOutcome {
    /** The order exists; the row was ended as COMPLETED. */
    COMPLETED,
    /** The intent is canceled in Stripe; the row was ended as CANCELED. */
    CANCELED,
    /** Paid or in flight without an order: left ACTIVE for the runbook. */
    LEFT_PAID,
    /** Stripe did not cancel it; left ACTIVE for the next run. */
    NOT_CANCELED
  }

  /**
   * Ends an old ACTIVE row so retention can delete it, for a checkout that never completed.
   * An age cutoff alone is unsafe (confirmed holds can be extended indefinitely, and deleting the
   * row would let the next /intent create a second payable intent), so the intent is canceled in
   * Stripe first. A paid or in-flight intent is never canceled. If the customer does come back, the
   * lookup in {@link #clientSecretFor} sees the canceled intent and replaces it. The cutoff is the
   * row's age, not when it was last handed out, so a customer still on the checkout page when the
   * job runs gets one failed payment attempt and a fresh intent on reload; no money moves.
   *
   * <p>Not transactional: the Stripe calls stay outside DB transactions.
   */
  public AbandonedIntentOutcome endIfAbandoned(OnlinePaymentIntent row) {
    String intentId = row.getStripePaymentIntentId();
    if (paymentRecordRepository.findByTransactionId(intentId).isPresent()) {
      markCompleted(intentId);
      return AbandonedIntentOutcome.COMPLETED;
    }
    StripeIntent current = stripe.retrievePaymentIntent(intentId);
    if (PAID_OR_IN_FLIGHT.contains(current.status())) {
      log.warn("Old online intent is paid or in flight without an order, left ACTIVE: {}",
          intentId);
      return AbandonedIntentOutcome.LEFT_PAID;
    }
    if ("canceled".equals(current.status())) {
      // Canceled elsewhere, or by an earlier run whose audit write failed: still put it on record.
      auditService.log(row.getUserId(), "PAYMENT_INTENT_ABANDONED", "PAYMENT", null,
          "stripe_intent=" + intentId + ", already_canceled=true");
    } else {
      StripeIntent after = stripe.cancelPaymentIntent(intentId);
      if (PAID_OR_IN_FLIGHT.contains(after.status())) {
        log.warn("Old online intent was paid while being canceled, left ACTIVE: {}", intentId);
        return AbandonedIntentOutcome.LEFT_PAID;
      }
      if (!"canceled".equals(after.status())) {
        log.warn("Old online intent {} could not be canceled (status {})", intentId,
            after.status());
        return AbandonedIntentOutcome.NOT_CANCELED;
      }
      auditService.log(row.getUserId(), "PAYMENT_INTENT_ABANDONED", "PAYMENT", null,
          "stripe_intent=" + intentId);
    }
    repository.invalidate(row.getId(), IntentInvalidationReason.CANCELED, clock.instant());
    return AbandonedIntentOutcome.CANCELED;
  }

  /**
   * Inserts the row. Returns the secret on success or when a concurrent winner stored the same
   * intent; returns null when there is no active winner and the intent id is already stored (a
   * replay of an invalidated intent); throws on any other conflict.
   */
  private String insert(UUID userId, String holdHash, long amountCents, String routing,
      StripeIntent created) {
    try {
      repository.saveAndFlush(
          new OnlinePaymentIntent(userId, holdHash, amountCents, routing, created.id()));
      return created.clientSecret();
    } catch (DataIntegrityViolationException e) {
      Optional<OnlinePaymentIntent> found = repository
          .findByUserIdAndHoldSetHashAndStatus(userId, holdHash, OnlinePaymentIntentStatus.ACTIVE);
      if (found.isEmpty()) {
        if (repository.existsByStripePaymentIntentId(created.id())) {
          log.warn("Stripe replayed an already-invalidated intent: {}", created.id());
          return null;
        }
        log.warn("Online intent insert failed and no active row found: created={}",
            created.id(), e);
        throw new PaymentRetryConflictException(
            "Concurrent payment intent creation; retry", e);
      }
      OnlinePaymentIntent winner = found.get();
      if (created.id().equals(winner.getStripePaymentIntentId())) {
        return created.clientSecret();
      }
      log.warn("Concurrent online intent creation: created={} stored={}", created.id(),
          winner.getStripePaymentIntentId(), e);
      throw new PaymentRetryConflictException(
          "Concurrent payment intent creation; retry", e);
    }
  }
}
