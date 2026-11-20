package com.fairtix.payments.infrastructure;

import com.fairtix.payments.domain.IntentInvalidationReason;
import com.fairtix.payments.domain.OnlinePaymentIntent;
import com.fairtix.payments.domain.OnlinePaymentIntentStatus;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OnlinePaymentIntentRepository extends JpaRepository<OnlinePaymentIntent, UUID> {

  Optional<OnlinePaymentIntent> findByUserIdAndHoldSetHashAndStatus(
      UUID userId, String holdSetHash, OnlinePaymentIntentStatus status);

  boolean existsByStripePaymentIntentId(String stripePaymentIntentId);

  /** The intent id is unique, so this is the one row for the intent, in any status. */
  Optional<OnlinePaymentIntent> findByStripePaymentIntentId(String stripePaymentIntentId);

  /**
   * One-shot: only an ACTIVE row is invalidated. Returns the number of rows changed (0 or 1).
   * Clears the persistence context so later reads see the new status.
   */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("UPDATE OnlinePaymentIntent i SET i.status = com.fairtix.payments.domain.OnlinePaymentIntentStatus.INVALIDATED,"
      + " i.invalidatedReason = :reason, i.invalidatedAt = :at"
      + " WHERE i.id = :id AND i.status = com.fairtix.payments.domain.OnlinePaymentIntentStatus.ACTIVE")
  int invalidate(@Param("id") UUID id, @Param("reason") IntentInvalidationReason reason,
      @Param("at") Instant at);

  /**
   * One-shot by intent id: only an ACTIVE row is invalidated. The intent id is unique, so this
   * changes 0 or 1 rows.
   */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("UPDATE OnlinePaymentIntent i SET i.status = com.fairtix.payments.domain.OnlinePaymentIntentStatus.INVALIDATED,"
      + " i.invalidatedReason = :reason, i.invalidatedAt = :at"
      + " WHERE i.stripePaymentIntentId = :intentId"
      + " AND i.status = com.fairtix.payments.domain.OnlinePaymentIntentStatus.ACTIVE")
  int invalidateActiveByStripePaymentIntentId(@Param("intentId") String stripePaymentIntentId,
      @Param("reason") IntentInvalidationReason reason, @Param("at") Instant at);

  /**
   * One page of ACTIVE rows created before the cutoff, in id order after {@code after}: a keyset,
   * so rows the caller leaves ACTIVE (paid, or a Stripe error) don't fill every page. Start with
   * the nil UUID, which sorts first.
   */
  @Query("SELECT i FROM OnlinePaymentIntent i"
      + " WHERE i.status = com.fairtix.payments.domain.OnlinePaymentIntentStatus.ACTIVE"
      + " AND i.createdAt < :cutoff AND i.id > :after ORDER BY i.id")
  List<OnlinePaymentIntent> findActiveCreatedBefore(@Param("cutoff") Instant cutoff,
      @Param("after") UUID after, Limit limit);

  /**
   * Deletes only INVALIDATED rows, by invalidation time. ACTIVE rows are never deleted: confirmed
   * holds can be extended indefinitely, and losing the row would let the next /intent create a
   * second payable intent for the same hold set. Old ACTIVE rows are ended first, by canceling
   * their unpaid intents in Stripe ({@code OnlinePaymentIntentService.endIfAbandoned}).
   */
  @Transactional
  @Modifying
  @Query("DELETE FROM OnlinePaymentIntent i"
      + " WHERE i.status = com.fairtix.payments.domain.OnlinePaymentIntentStatus.INVALIDATED"
      + " AND i.invalidatedAt < :cutoff")
  int deleteInvalidatedBefore(@Param("cutoff") Instant cutoff);
}
