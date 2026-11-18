package com.fairtix.payments.infrastructure;

import com.fairtix.payments.domain.IntentInvalidationReason;
import com.fairtix.payments.domain.OnlinePaymentIntent;
import com.fairtix.payments.domain.OnlinePaymentIntentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface OnlinePaymentIntentRepository extends JpaRepository<OnlinePaymentIntent, UUID> {

  Optional<OnlinePaymentIntent> findByUserIdAndHoldSetHashAndStatus(
      UUID userId, String holdSetHash, OnlinePaymentIntentStatus status);

  boolean existsByStripePaymentIntentId(String stripePaymentIntentId);

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

  @Transactional
  @Modifying
  @Query("DELETE FROM OnlinePaymentIntent i WHERE i.createdAt < :cutoff")
  int deleteCreatedBefore(@Param("cutoff") Instant cutoff);
}
