package com.fairtix.audit.api;

import com.fairtix.audit.domain.AuditLog;
import com.fairtix.audit.infrastructure.AuditLogRepository;
import com.fairtix.audit.infrastructure.AuditLogSpecifications;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/audit")
@PreAuthorize("hasRole('ADMIN')")
public class AdminAuditController {

    private final AuditLogRepository auditLogRepository;

    public AdminAuditController(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @GetMapping
    public Page<AuditLogResponse> queryAuditLogs(
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Instant from,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        PageRequest pageable = PageRequest.of(page, Math.min(size, 100),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return auditLogRepository.findAll(AuditLogSpecifications.withFilters(userId, action, from), pageable)
                .map(AuditLogResponse::from);
    }

    public record AuditLogResponse(
            UUID id,
            UUID userId,
            String action,
            String resourceType,
            UUID resourceId,
            String details,
            Instant createdAt
    ) {
        static AuditLogResponse from(AuditLog log) {
            return new AuditLogResponse(
                    log.getId(), log.getUserId(), log.getAction(),
                    log.getResourceType(), log.getResourceId(),
                    log.getDetails(), log.getCreatedAt());
        }
    }
}
