package com.fairtix.audit.application;

import com.fairtix.audit.domain.AuditLog;
import com.fairtix.audit.infrastructure.AuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

@Service
public class AuditService {

  private static final Logger log = LoggerFactory.getLogger(AuditService.class);

  private final AuditLogRepository repository;

  public AuditService(AuditLogRepository repository) {
    this.repository = repository;
  }

  /**
   * Writes the audit row in its own transaction, so it commits even if the
   * caller rolls back. Called from inside a transaction, this needs a second
   * pooled connection while the caller's is still held; on hot paths prefer
   * {@link #logInCurrentTransaction}. The {@code AUDIT:} log line is written
   * only after the audit row commits.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void log(UUID userId, String action, String resourceType, UUID resourceId, String details) {
    save(userId, action, resourceType, resourceId, details);
  }

  /**
   * Writes the audit row in the caller's transaction, on the caller's
   * connection: it commits or rolls back with the audited change, and an audit
   * failure rolls the change back. Throws if there is no transaction. The
   * {@code AUDIT:} log line is written only after the audit row commits.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void logInCurrentTransaction(UUID userId, String action, String resourceType,
      UUID resourceId, String details) {
    save(userId, action, resourceType, resourceId, details);
  }

  private void save(UUID userId, String action, String resourceType, UUID resourceId, String details) {
    String requestId = MDC.get("requestId");
    repository.save(new AuditLog(userId, action, resourceType, resourceId, details, requestId));
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          logLine(userId, action, resourceType, resourceId, details);
        }
      });
    } else {
      logLine(userId, action, resourceType, resourceId, details);
    }
  }

  private void logLine(UUID userId, String action, String resourceType, UUID resourceId, String details) {
    log.info("AUDIT: user={} action={} type={} resource={} details={}",
        userId, action, resourceType, resourceId, details);
  }
}
