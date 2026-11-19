package com.fairtix.inventory.infrastructure;

import com.fairtix.inventory.domain.HoldStatus;
import com.fairtix.inventory.domain.SeatHold;
import com.fairtix.inventory.domain.SeatStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SeatHoldRepository extends JpaRepository<SeatHold, UUID> {

  @Query("SELECT sh.status, COUNT(sh) FROM SeatHold sh GROUP BY sh.status")
  List<Object[]> countByStatusGrouped();

  @Query(value = "SELECT CAST(sh.created_at AS DATE) AS hold_date, COUNT(*) FROM seat_holds sh WHERE sh.created_at >= :since GROUP BY CAST(sh.created_at AS DATE) ORDER BY hold_date", nativeQuery = true)
  List<Object[]> countHoldsPerDay(@Param("since") Instant since);

  Optional<SeatHold> findByIdAndOwnerId(UUID id, UUID ownerId);

  Optional<SeatHold> findBySeat_IdAndStatus(UUID seatId, HoldStatus status);

  /**
   * Used by the scheduler to fetch one bounded page of expired holds.
   * Always query page 0: expired items are mutated to EXPIRED each run,
   * so they naturally disappear from subsequent queries.
   */
  Page<SeatHold> findAllByStatusAndExpiresAtBefore(HoldStatus status, Instant now, Pageable pageable);

  /** CONFIRMED-hold sweep: does another live hold own this seat? */
  boolean existsBySeat_IdAndStatusInAndIdNot(UUID seatId, Collection<HoldStatus> statuses, UUID id);

  /**
   * Payment-intent extension: the caller's holds as plain values. Loading the
   * entities would put each {@code Seat} in the persistence context, and a
   * concurrent extension's version bump would then fail the seat lock query
   * with an optimistic-lock conflict (#298).
   */
  @Query("SELECT new com.fairtix.inventory.infrastructure.HoldSeatRef(h.id, h.status, h.seat.id,"
      + " h.seat.event.id)"
      + " FROM SeatHold h WHERE h.id IN :ids AND h.ownerId = :ownerId")
  List<HoldSeatRef> findRefsByIdInAndOwnerId(@Param("ids") Collection<UUID> ids,
      @Param("ownerId") UUID ownerId);

  /**
   * Payment-intent extension: sets {@code expiresAt} only, and only on holds that
   * are still {@code status} in the database and expire before {@code extendTo}.
   * A bulk update, so a hold the sweep just expired is never written back from a
   * stale managed copy ({@code SeatHold} has no {@code @Version}).
   */
  @Modifying
  @Query("UPDATE SeatHold h SET h.expiresAt = :extendTo"
      + " WHERE h.id IN :ids AND h.status = :status AND h.expiresAt < :extendTo")
  int extendExpiry(@Param("ids") Collection<UUID> ids, @Param("status") HoldStatus status,
      @Param("extendTo") Instant extendTo);

  /** Payment-intent extension: re-reads the database after the seat locks are taken. */
  @Query("SELECT COUNT(h) FROM SeatHold h WHERE h.id IN :ids AND h.status = :status"
      + " AND h.seat.status = :seatStatus")
  long countByIdInAndStatusAndSeatStatus(@Param("ids") Collection<UUID> ids,
      @Param("status") HoldStatus status, @Param("seatStatus") SeatStatus seatStatus);

  /** Soft-limit: count how many active holds an owner currently has. */
  long countByOwnerIdAndStatus(UUID ownerId, HoldStatus status);

  /** List-holds endpoint: all holds for an owner filtered by status. */
  List<SeatHold> findAllByOwnerIdAndStatus(UUID ownerId, HoldStatus status);

  /** Cancel cascade: the event's holds in any of these statuses. */
  List<SeatHold> findAllBySeat_Event_IdAndStatusIn(UUID eventId, Collection<HoldStatus> statuses);

  /** Purchase cap: use it through {@code PurchaseCapGuard}, which owns the statuses. */
  long countByOwnerIdAndSeat_Event_IdAndStatusIn(UUID ownerId, UUID eventId,
      Collection<HoldStatus> statuses);
}
