package com.fairtix.payments.application;

import com.fairtix.audit.application.AuditService;
import com.fairtix.payments.application.StripePaymentService.ConnectContext;
import com.fairtix.payments.application.StripePaymentService.StripeIntent;
import com.fairtix.payments.domain.IntentInvalidationReason;
import com.fairtix.payments.domain.OnlinePaymentIntent;
import com.fairtix.payments.domain.OnlinePaymentIntentStatus;
import com.fairtix.payments.infrastructure.OnlinePaymentIntentRepository;
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

  private final StripePaymentService stripe;
  private final OnlinePaymentIntentRepository repository;
  private final AuditService auditService;
  private Clock clock = Clock.systemUTC();

  public OnlinePaymentIntentService(StripePaymentService stripe,
      OnlinePaymentIntentRepository repository, AuditService auditService) {
    this.stripe = stripe;
    this.repository = repository;
    this.auditService = auditService;
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
    // we already invalidated (rows are invalidated only after Stripe confirmed canceled).
    // Create once more keyed on it.
    StripeIntent replacement = stripe.createPaymentIntent(amountCents, "usd", connect, userId,
        StripeIdempotencyKeys.onlineIntent(userId, holdIds, amountCents, connect, created.id()));
    secret = insert(userId, holdHash, amountCents, routing, replacement);
    if (secret != null) {
      return secret;
    }
    throw new PaymentRetryConflictException(
        "Replacement payment intent " + replacement.id() + " was already used; retry", null);
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
