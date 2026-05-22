package com.fairtix.holds.infrastructure;

import com.fairtix.holds.domain.EventHold;
import com.fairtix.holds.domain.HoldCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EventHoldRepository extends JpaRepository<EventHold, UUID> {

  @Query("SELECT h FROM EventHold h WHERE h.event.id = :eventId "
       + "AND h.releasedAt IS NULL AND h.convertedTicketId IS NULL "
       + "ORDER BY h.category, h.createdAt")
  List<EventHold> findActiveByEvent(@Param("eventId") UUID eventId);

  @Query("SELECT h FROM EventHold h WHERE h.event.id = :eventId AND h.category = :category "
       + "AND h.releasedAt IS NULL AND h.convertedTicketId IS NULL "
       + "ORDER BY h.createdAt")
  List<EventHold> findActiveByEventAndCategory(@Param("eventId") UUID eventId,
                                               @Param("category") HoldCategory category);

  @Query("SELECT h FROM EventHold h WHERE h.seat.id = :seatId "
       + "AND h.releasedAt IS NULL AND h.convertedTicketId IS NULL")
  Optional<EventHold> findActiveBySeat(@Param("seatId") UUID seatId);

  @Query("SELECT COUNT(h) FROM EventHold h WHERE h.event.id = :eventId "
       + "AND h.releasedAt IS NULL AND h.convertedTicketId IS NULL")
  long countActiveByEvent(@Param("eventId") UUID eventId);

  @Query("SELECT h FROM EventHold h WHERE h.autoReleaseAt IS NOT NULL "
       + "AND h.autoReleaseAt < :now "
       + "AND h.releasedAt IS NULL AND h.convertedTicketId IS NULL")
  List<EventHold> findDueForAutoRelease(@Param("now") Instant now);
}
