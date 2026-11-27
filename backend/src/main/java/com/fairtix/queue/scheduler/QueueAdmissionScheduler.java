package com.fairtix.queue.scheduler;

import com.fairtix.events.infrastructure.EventRepository;
import com.fairtix.notifications.application.EmailTemplateService;
import com.fairtix.notifications.application.NotificationGate;
import com.fairtix.notifications.domain.NotificationCategory;
import com.fairtix.queue.application.QueueService;
import com.fairtix.queue.application.QueueSseService;
import com.fairtix.queue.domain.QueueEntry;
import com.fairtix.users.infrastructure.UserRepository;
import jakarta.annotation.PostConstruct;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
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

    @Value("${queue.admission.interval-ms:30000}")
    private long intervalMs;

    @Value("${queue.admission.lock-at-least-for:PT28S}")
    private Duration lockAtLeastFor;

    /** Fails startup if the lock would make a single instance skip its own next run. */
    @PostConstruct
    void checkLockFitsInterval() {
        if (lockAtLeastFor.toMillis() >= intervalMs) {
            throw new IllegalStateException("queue.admission.lock-at-least-for (" + lockAtLeastFor
                    + ") must be below queue.admission.interval-ms (" + intervalMs + " ms)");
        }
    }

    /**
     * Admits the next batch for every event with a waiting queue, then tells every instance
     * to push the new statuses to its connected browsers.
     *
     * <p>The lock is held for most of an interval, not just for the run: each run admits up
     * to {@code queue.max-admission-batch} per event, so two instances running in the same
     * interval would admit at twice the configured rate. With the defaults (28 s against a
     * 30 s delay) the cluster admits at most about 7% faster than one instance. The lock must
     * stay below {@code queue.admission.interval-ms}, or a single instance skips its own next
     * run; {@link #checkLockFitsInterval} enforces that at startup.
     */
    @SchedulerLock(name = "queueAdmission", lockAtMostFor = "PT2M",
            lockAtLeastFor = "${queue.admission.lock-at-least-for:PT28S}")
    @Scheduled(fixedDelayString = "${queue.admission.interval-ms:30000}")
    public void admitWaitingUsers() {
        MDC.put("requestId", "sched-admitWaitingUsers-" + UUID.randomUUID());
        try {
            List<UUID> eventIds = queueService.findEventIdsWithWaitingEntries();
            for (UUID eventId : eventIds) {
                try {
                    List<QueueEntry> admitted = queueService.admitNextBatch(eventId);
                    queueSseService.publishUpdate(eventId);
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
                // Exception type only: a mail failure's message can quote the address.
                log.warn("Failed to send queue admission email for user {}: {}", entry.getUserId(),
                        ex.getClass().getSimpleName());
            }
        }
    }
}
