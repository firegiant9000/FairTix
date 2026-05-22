package com.fairtix.audit.infrastructure;

import com.fairtix.audit.domain.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    List<AuditLog> findByUserIdAndCreatedAtAfter(UUID userId, Instant since);

    long countByUserIdAndActionAndCreatedAtAfter(UUID userId, String action, Instant since);

    long countByUserIdAndActionAndResourceTypeAndCreatedAtAfter(UUID userId, String action, String resourceType, Instant since);

    long countByActionAndResourceIdAndCreatedAtAfter(String action, UUID resourceId, Instant since);

    @Query("SELECT DISTINCT a.userId FROM AuditLog a WHERE a.createdAt > :since")
    List<UUID> findDistinctUserIdsByCreatedAtAfter(@Param("since") Instant since);

    @Query("SELECT a FROM AuditLog a WHERE (:userId IS NULL OR a.userId = :userId) AND (:action IS NULL OR a.action LIKE CONCAT(:action, '%')) AND (:from IS NULL OR a.createdAt >= :from)")
    Page<AuditLog> findWithFilters(
            @Param("userId") UUID userId,
            @Param("action") String action,
            @Param("from") Instant from,
            Pageable pageable);
}
