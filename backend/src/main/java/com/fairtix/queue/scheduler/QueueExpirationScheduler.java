package com.fairtix.queue.scheduler;

import com.fairtix.queue.application.QueueService;
import com.fairtix.queue.application.QueueSseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class QueueExpirationScheduler {

    private static final Logger log = LoggerFactory.getLogger(QueueExpirationScheduler.class);

    private final QueueService queueService;
    private final QueueSseService queueSseService;

    public QueueExpirationScheduler(QueueService queueService, QueueSseService queueSseService) {
        this.queueService = queueService;
        this.queueSseService = queueSseService;
    }

    @Scheduled(fixedDelayString = "${queue.expiration.interval-ms:30000}")
    public void expireAdmissions() {
        MDC.put("requestId", "sched-expireAdmissions-" + UUID.randomUUID());
        try {
            List<UUID> eventIds = queueService.findEventIdsWithExpiredAdmissions();
            for (UUID eventId : eventIds) {
                try {
                    queueService.expireAdmissions(eventId);
                    queueSseService.broadcast(eventId);
                } catch (Exception e) {
                    log.error("Failed to expire admissions for event {}: {}", eventId, e.getMessage());
                }
            }
        } finally {
            MDC.remove("requestId");
        }
    }
}
