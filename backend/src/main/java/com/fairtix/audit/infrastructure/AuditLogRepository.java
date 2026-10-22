package com.fairtix.audit.infrastructure;

import com.fairtix.audit.domain.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID>,
        JpaSpecificationExecutor<AuditLog> {

    List<AuditLog> findByUserIdAndCreatedAtAfter(UUID userId, Instant since);

    long countByUserIdAndActionAndCreatedAtAfter(UUID userId, String action, Instant since);

    long countByUserIdAndActionAndResourceTypeAndCreatedAtAfter(UUID userId, String action, String resourceType, Instant since);

    long countByActionAndResourceIdAndCreatedAtAfter(String action, UUID resourceId, Instant since);

    @Query("SELECT DISTINCT a.userId FROM AuditLog a WHERE a.createdAt > :since")
    List<UUID> findDistinctUserIdsByCreatedAtAfter(@Param("since") Instant since);
}
