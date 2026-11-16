package com.fairtix.queue.infrastructure;

import com.fairtix.queue.domain.QueueEntry;
import com.fairtix.queue.domain.QueueStatus;
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

public interface QueueRepository extends JpaRepository<QueueEntry, UUID> {

    Optional<QueueEntry> findByEventIdAndUserId(UUID eventId, UUID userId);

    Optional<QueueEntry> findByToken(String token);

    long countByEventIdAndStatus(UUID eventId, QueueStatus status);

    List<QueueEntry> findByEventIdAndStatusOrderByPositionAsc(UUID eventId, QueueStatus status, Pageable pageable);

    @Query("SELECT DISTINCT q.eventId FROM QueueEntry q WHERE q.status = :status")
    List<UUID> findDistinctEventIdsByStatus(@Param("status") QueueStatus status);

    @Query("SELECT DISTINCT q.eventId FROM QueueEntry q WHERE q.status = :status AND q.expiresAt < :now")
    List<UUID> findDistinctEventIdsWithExpiredByStatus(
            @Param("status") QueueStatus status,
            @Param("now") Instant now);

    List<QueueEntry> findByStatusAndExpiresAtBefore(QueueStatus status, Instant now);

    /** The highest position ever given out for the event that is still on record; 0 if none. */
    @Query("SELECT COALESCE(MAX(q.position), 0) FROM QueueEntry q WHERE q.eventId = :eventId")
    long findMaxPositionByEventId(@Param("eventId") UUID eventId);

    /**
     * Deletes the entry only if it still has one of {@code statuses}. The status is
     * checked by the database when the row is locked, so an entry that another
     * transaction has just moved to a different status is left alone.
     *
     * @return 1 if the entry was deleted, 0 if it was gone or had another status
     */
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM QueueEntry q WHERE q.id = :id AND q.status IN :statuses")
    int deleteByIdAndStatusIn(@Param("id") UUID id, @Param("statuses") Collection<QueueStatus> statuses);
}
