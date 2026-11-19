package com.fairtix.refunds.infrastructure;

import com.fairtix.refunds.domain.RefundRequest;
import com.fairtix.refunds.domain.RefundStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefundRepository extends JpaRepository<RefundRequest, UUID> {

  List<RefundRequest> findAllByUserIdOrderByCreatedAtDesc(UUID userId);

  Optional<RefundRequest> findByOrderIdAndStatusIn(UUID orderId, List<RefundStatus> statuses);

  /** Exists, not find: an order can carry more than one {@code COMPLETED} refund row. */
  boolean existsByOrderIdAndStatusIn(UUID orderId, List<RefundStatus> statuses);

  Page<RefundRequest> findAllByOrderByCreatedAtDesc(Pageable pageable);

  Page<RefundRequest> findAllByStatusOrderByCreatedAtDesc(RefundStatus status, Pageable pageable);

  List<RefundRequest> findAllByOrderId(UUID orderId);

  /**
   * Marks an active refund {@code COMPLETED} without rewriting the rest of the row.
   * A full-entity save from the webhook would overwrite {@code stripe_refund_id}
   * written concurrently by {@code RefundService.finalise}. Returns 0 if the refund
   * was no longer active once its row lock was granted.
   */
  @Modifying
  @Query("UPDATE RefundRequest r SET r.status = com.fairtix.refunds.domain.RefundStatus.COMPLETED, "
      + "r.completedAt = :now, r.updatedAt = :now "
      + "WHERE r.id = :id AND r.status IN (com.fairtix.refunds.domain.RefundStatus.APPROVED, "
      + "com.fairtix.refunds.domain.RefundStatus.REQUESTED, "
      + "com.fairtix.refunds.domain.RefundStatus.PENDING_MANUAL)")
  int completeIfActive(@Param("id") UUID id, @Param("now") Instant now);

  /** Approved refunds on this event's orders whose money hasn't moved yet. */
  @Query("SELECT DISTINCT r.id FROM RefundRequest r, Ticket t "
      + "WHERE t.order.id = r.orderId AND t.event.id = :eventId "
      + "AND r.status = com.fairtix.refunds.domain.RefundStatus.APPROVED "
      + "AND r.stripeRefundId IS NULL")
  List<UUID> findExecutableIdsForEvent(@Param("eventId") UUID eventId);

  /**
   * Locks the open refund requests (REQUESTED, PENDING_MANUAL, APPROVED) on orders with a
   * VALID ticket for the event, and returns their ids. Event cancellation takes these before
   * any ticket row, as {@code RefundService.finalise} takes its refund row before the tickets.
   * Only the cancellation locks several refund rows, and two cancellations of one event are
   * already serialised on the event's sale lock, so their order among themselves doesn't matter.
   */
  @Query(value = "SELECT id FROM refund_requests WHERE status IN ('REQUESTED', 'PENDING_MANUAL',"
      + " 'APPROVED') AND order_id IN (SELECT order_id FROM tickets WHERE event_id = :eventId"
      + " AND status = 'VALID') ORDER BY id FOR UPDATE", nativeQuery = true)
  List<UUID> lockOpenRefundIdsForEvent(@Param("eventId") UUID eventId);

  @Query(value = "SELECT CAST(created_at AS DATE), COUNT(*) FROM refund_requests WHERE created_at >= :since GROUP BY CAST(created_at AS DATE) ORDER BY 1", nativeQuery = true)
  List<Object[]> requestedPerDay(@Param("since") Instant since);
}
