package com.fairtix.fraud.scheduler;

import com.fairtix.audit.infrastructure.AuditLogRepository;
import com.fairtix.fraud.application.BehaviorAnalysisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Component
public class BehaviorAnalysisSweepScheduler {

    private static final Logger log = LoggerFactory.getLogger(BehaviorAnalysisSweepScheduler.class);

    private final BehaviorAnalysisService behaviorAnalysisService;
    private final AuditLogRepository auditLogRepository;

    @Value("${fraud.analysis.interval-ms:300000}")
    private long intervalMs;

    public BehaviorAnalysisSweepScheduler(BehaviorAnalysisService behaviorAnalysisService,
                                          AuditLogRepository auditLogRepository) {
        this.behaviorAnalysisService = behaviorAnalysisService;
        this.auditLogRepository = auditLogRepository;
    }

    @Scheduled(fixedDelayString = "${fraud.analysis.interval-ms:300000}")
    public void sweep() {
        MDC.put("requestId", "sched-sweep-" + UUID.randomUUID());
        try {
            // Analyze users active in the last two sweep intervals to catch recent patterns
            Instant since = Instant.now().minus(intervalMs * 2, ChronoUnit.MILLIS);
            List<UUID> activeUserIds = auditLogRepository.findDistinctUserIdsByCreatedAtAfter(since);

            if (activeUserIds.isEmpty()) {
                return;
            }

            log.debug("Fraud sweep: analyzing {} active users", activeUserIds.size());
            int processed = 0;
            for (UUID userId : activeUserIds) {
                try {
                    behaviorAnalysisService.analyzeUser(userId);
                    processed++;
                } catch (Exception ex) {
                    log.warn("Fraud sweep error for user {}: {}", userId, ex.getMessage());
                }
            }
            log.debug("Fraud sweep complete: processed {} users", processed);
        } finally {
            MDC.remove("requestId");
        }
    }
}
