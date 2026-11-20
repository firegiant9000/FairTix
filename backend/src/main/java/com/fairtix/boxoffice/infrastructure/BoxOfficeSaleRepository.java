package com.fairtix.boxoffice.infrastructure;

import com.fairtix.boxoffice.domain.BoxOfficeSale;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BoxOfficeSaleRepository extends JpaRepository<BoxOfficeSale, UUID> {

  List<BoxOfficeSale> findAllBySessionIdOrderByCreatedAtAsc(UUID sessionId);

  Optional<BoxOfficeSale> findByStripePaymentIntentId(String paymentIntentId);

  /** A query, not an entity read: under open-in-view a managed copy of the sale may be stale. */
  boolean existsByStripePaymentIntentIdAndOrderIdIsNotNull(String paymentIntentId);

  /**
   * Flags the session's card sales that have no order as abandoned, and returns how many.
   * One guarded UPDATE, not an entity write: one that waits on a confirm holding the sale row
   * re-checks {@code order_id IS NULL} after the wait (READ COMMITTED), so a sale completed
   * meanwhile is skipped and its order id is never written back.
   */
  @Modifying
  @Query("UPDATE BoxOfficeSale s SET s.abandonedAt = :now"
      + " WHERE s.sessionId = :sessionId AND s.method = com.fairtix.boxoffice.domain.SaleMethod.CARD"
      + " AND s.orderId IS NULL AND s.abandonedAt IS NULL")
  int markPendingCardSalesAbandoned(@Param("sessionId") UUID sessionId, @Param("now") Instant now);

  /** Which of {@code holdIds} belong to a card sale (V53 links). */
  @Query(value = "SELECT hold_id FROM box_office_sale_holds WHERE hold_id IN (:holdIds)",
      nativeQuery = true)
  List<UUID> findLinkedHoldIds(@Param("holdIds") Collection<UUID> holdIds);
}
