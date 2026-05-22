package com.fairtix.audit.application;

import com.fairtix.audit.domain.AuditLog;
import com.fairtix.audit.infrastructure.AuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AuditService {

  private static final Logger log = LoggerFactory.getLogger(AuditService.class);

  private final AuditLogRepository repository;

  public AuditService(AuditLogRepository repository) {
    this.repository = repository;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void log(UUID userId, String action, String resourceType, UUID resourceId, String details) {
    String requestId = MDC.get("requestId");
    log.info("AUDIT: user={} action={} type={} resource={} details={}",
        userId, action, resourceType, resourceId, details);
    repository.save(new AuditLog(userId, action, resourceType, resourceId, details, requestId));
  }
}
