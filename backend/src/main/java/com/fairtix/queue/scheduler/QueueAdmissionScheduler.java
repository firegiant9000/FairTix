package com.fairtix.queue.scheduler;

import com.fairtix.events.infrastructure.EventRepository;
import com.fairtix.notifications.application.EmailTemplateService;
import com.fairtix.notifications.application.NotificationGate;
import com.fairtix.notifications.domain.NotificationCategory;
import com.fairtix.queue.application.QueueService;
import com.fairtix.queue.application.QueueSseService;
import com.fairtix.queue.domain.QueueEntry;
import com.fairtix.users.infrastructure.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class QueueAdmissionScheduler {

    private static final Logger log = LoggerFactory.getLogger(QueueAdmissionScheduler.class);

    private final QueueService queueService;
    private final QueueSseService queueSseService;
    private final NotificationGate notificationGate;
    private final EmailTemplateService emailTemplateService;
    private final UserRepository userRepository;
    private final EventRepository eventRepository;

    public QueueAdmissionScheduler(QueueService queueService,
                                   QueueSseService queueSseService,
                                   NotificationGate notificationGate,
                                   EmailTemplateService emailTemplateService,
                                   UserRepository userRepository,
                                   EventRepository eventRepository) {
        this.queueService = queueService;
        this.queueSseService = queueSseService;
        this.notificationGate = notificationGate;
        this.emailTemplateService = emailTemplateService;
        this.userRepository = userRepository;
        this.eventRepository = eventRepository;
    }

    @Scheduled(fixedDelayString = "${queue.admission.interval-ms:30000}")
    public void admitWaitingUsers() {
        MDC.put("requestId", "sched-admitWaitingUsers-" + UUID.randomUUID());
        try {
            List<UUID> eventIds = queueService.findEventIdsWithWaitingEntries();
            for (UUID eventId : eventIds) {
                try {
                    List<QueueEntry> admitted = queueService.admitNextBatch(eventId);
                    queueSseService.broadcast(eventId);
                    sendAdmissionEmails(admitted, eventId);
                } catch (Exception e) {
                    log.error("Failed to admit batch for event {}: {}", eventId, e.getMessage());
                }
            }
        } finally {
            MDC.remove("requestId");
        }
    }

    private void sendAdmissionEmails(List<QueueEntry> admitted, UUID eventId) {
        if (admitted.isEmpty()) return;
        String eventTitle = eventRepository.findById(eventId)
                .map(e -> e.getTitle())
                .orElse("the event");
        for (QueueEntry entry : admitted) {
            try {
                userRepository.findById(entry.getUserId()).ifPresent(user -> {
                    String expiresAt = entry.getExpiresAt() != null ? entry.getExpiresAt().toString() : "soon";
                    String body = emailTemplateService.buildQueueAdmittedEmail(
                            user.getEmail(), eventTitle, expiresAt);
                    notificationGate.sendEmail(user.getId(), NotificationCategory.QUEUE_ADMISSION,
                            user.getEmail(), "You're admitted — " + eventTitle, body);
                });
            } catch (Exception ex) {
                log.warn("Failed to send queue admission email for user {}: {}", entry.getUserId(), ex.getMessage());
            }
        }
    }
}
