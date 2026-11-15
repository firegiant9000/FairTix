package com.fairtix.queue.application;

import com.fairtix.audit.application.AuditService;
import com.fairtix.common.ResourceNotFoundException;
import com.fairtix.fraud.application.RiskScoringService;
import com.fairtix.fraud.application.UserFlaggedForAbuseException;
import com.fairtix.fraud.domain.RiskTier;
import com.fairtix.events.domain.Event;
import com.fairtix.events.infrastructure.EventRepository;
import com.fairtix.inventory.domain.SeatStatus;
import com.fairtix.inventory.infrastructure.SeatRepository;
import com.fairtix.queue.domain.QueueEntry;
import com.fairtix.queue.domain.QueueStatus;
import com.fairtix.queue.infrastructure.QueueRepository;
import com.fairtix.tickets.application.PurchaseCapGuard;
import jakarta.transaction.Transactional;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class QueueService {

    private static final String POSITION_KEY = "queue:position:";
    private static final String ADMITTED_KEY = "queue:admitted:";
    private static final UUID SYSTEM = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private final QueueRepository queueRepository;
    private final EventRepository eventRepository;
    private final SeatRepository seatRepository;
    private final PurchaseCapGuard purchaseCapGuard;
    private final RedissonClient redissonClient;
    private final AuditService auditService;
    private final RiskScoringService riskScoringService;

    @Value("${queue.admission-window-minutes:15}")
    private int admissionWindowMinutes;

    @Value("${queue.max-admission-batch:50}")
    private int maxAdmissionBatch;

    public QueueService(QueueRepository queueRepository,
                        EventRepository eventRepository,
                        SeatRepository seatRepository,
                        PurchaseCapGuard purchaseCapGuard,
                        RedissonClient redissonClient,
                        AuditService auditService,
                        RiskScoringService riskScoringService) {
        this.queueRepository = queueRepository;
        this.eventRepository = eventRepository;
        this.seatRepository = seatRepository;
        this.purchaseCapGuard = purchaseCapGuard;
        this.redissonClient = redissonClient;
        this.auditService = auditService;
        this.riskScoringService = riskScoringService;
    }

    @Transactional
    public QueueEntry joinQueue(UUID eventId, UUID userId) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event not found: " + eventId));

        if (!event.isQueueRequired()) {
            throw new IllegalArgumentException("This event does not require a queue");
        }

        if (riskScoringService.getTier(userId) == RiskTier.CRITICAL) {
            throw new UserFlaggedForAbuseException();
        }

        if (event.getMaxTicketsPerUser() != null) {
            long owned = purchaseCapGuard.activeTickets(userId, eventId);
            if (owned >= event.getMaxTicketsPerUser()) {
                throw new QueueConflictException(
                        "You have already purchased the maximum number of tickets for this event");
            }
        }

        Optional<QueueEntry> existing = queueRepository.findByEventIdAndUserId(eventId, userId);
        if (existing.isPresent()) {
            QueueStatus existingStatus = existing.get().getStatus();
            if (existingStatus == QueueStatus.WAITING || existingStatus == QueueStatus.ADMITTED) {
                throw new QueueConflictException("You are already in the queue for this event");
            }
            // EXPIRED or COMPLETED (hold was released) — delete old entry so the user can rejoin
            queueRepository.delete(existing.get());
        }

        if (event.getQueueCapacity() != null) {
            long totalEntries = queueRepository.countByEventIdAndStatus(eventId, QueueStatus.WAITING)
                    + queueRepository.countByEventIdAndStatus(eventId, QueueStatus.ADMITTED);
            if (totalEntries >= event.getQueueCapacity()) {
                throw new QueueConflictException("Queue is at capacity for this event");
            }
        }

        RAtomicLong counter = redissonClient.getAtomicLong(POSITION_KEY + eventId);
        long position = counter.incrementAndGet();

        QueueEntry entry = new QueueEntry(eventId, userId, UUID.randomUUID().toString(), (int) position);
        QueueEntry saved = queueRepository.save(entry);
        auditService.log(userId, "QUEUE_JOINED", "QUEUE", eventId, null);
        return saved;
    }

    @Transactional
    public QueueEntry getStatus(UUID eventId, UUID userId) {
        return queueRepository.findByEventIdAndUserId(eventId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("You are not in the queue for this event"));
    }

    @Transactional
    public void leaveQueue(UUID eventId, UUID userId) {
        QueueEntry entry = queueRepository.findByEventIdAndUserId(eventId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("You are not in the queue for this event"));

        if (entry.getStatus() == QueueStatus.WAITING || entry.getStatus() == QueueStatus.ADMITTED) {
            if (entry.getStatus() == QueueStatus.ADMITTED) {
                removeFromAdmittedSet(eventId, userId);
            }
            entry.expire();
            queueRepository.save(entry);
            auditService.log(userId, "QUEUE_LEFT", "QUEUE", eventId, null);
        }
    }

    public boolean isAdmitted(UUID eventId, UUID userId) {
        RSet<String> admittedSet = redissonClient.getSet(ADMITTED_KEY + eventId);
        if (admittedSet.contains(userId.toString())) {
            return true;
        }
        // Redis miss — fall back to DB
        Optional<QueueEntry> entry = queueRepository.findByEventIdAndUserId(eventId, userId);
        return entry.isPresent()
                && entry.get().getStatus() == QueueStatus.ADMITTED
                && entry.get().getExpiresAt() != null
                && entry.get().getExpiresAt().isAfter(Instant.now());
    }

    /**
     * Called when a user releases a hold on a queue-required event.
     * If the queue entry is COMPLETED, reinstates it:
     *   - back to ADMITTED (+ Redis set) if the admission window is still open
     *   - to EXPIRED otherwise, so the user can rejoin the queue
     */
    @Transactional
    public void reinstateAfterHoldRelease(UUID eventId, UUID userId) {
        queueRepository.findByEventIdAndUserId(eventId, userId).ifPresent(entry -> {
            if (entry.getStatus() != QueueStatus.COMPLETED) {
                return;
            }
            if (entry.getExpiresAt() != null && entry.getExpiresAt().isAfter(Instant.now())) {
                entry.admit(entry.getExpiresAt()); // restore ADMITTED, keep original window
                RSet<String> admittedSet = redissonClient.getSet(ADMITTED_KEY + eventId);
                admittedSet.add(userId.toString());
            } else {
                entry.expire(); // window gone — user must rejoin
            }
            queueRepository.save(entry);
        });
    }

    /**
     * Returns true if the user has queue clearance to proceed to checkout.
     * Accepts both ADMITTED (active admission window) and COMPLETED (hold already
     * created from this admission slot) — both states represent a user who has
     * legitimately passed through the queue.
     */
    public boolean hasCheckoutClearance(UUID eventId, UUID userId) {
        if (isAdmitted(eventId, userId)) {
            return true;
        }
        Optional<QueueEntry> entry = queueRepository.findByEventIdAndUserId(eventId, userId);
        return entry.isPresent() && entry.get().getStatus() == QueueStatus.COMPLETED;
    }

    @Transactional
    public void completeQueueEntry(UUID eventId, UUID userId) {
        queueRepository.findByEventIdAndUserId(eventId, userId).ifPresent(entry -> {
            if (entry.getStatus() == QueueStatus.ADMITTED) {
                entry.complete();
                removeFromAdmittedSet(eventId, userId);
                queueRepository.save(entry);
            }
        });
    }

    @Transactional
    public List<QueueEntry> admitNextBatch(UUID eventId) {
        long availableSeats = seatRepository.findByEvent_IdAndStatus(eventId, SeatStatus.AVAILABLE).size();
        long currentlyAdmitted = queueRepository.countByEventIdAndStatus(eventId, QueueStatus.ADMITTED);
        long toAdmit = Math.min(availableSeats - currentlyAdmitted, maxAdmissionBatch);

        if (toAdmit <= 0) {
            return List.of();
        }

        List<QueueEntry> waiting = queueRepository.findByEventIdAndStatusOrderByPositionAsc(
                eventId, QueueStatus.WAITING, PageRequest.of(0, (int) toAdmit));

        Instant expiresAt = Instant.now().plusSeconds(admissionWindowMinutes * 60L);
        RSet<String> admittedSet = redissonClient.getSet(ADMITTED_KEY + eventId);

        for (QueueEntry entry : waiting) {
            entry.admit(expiresAt);
            admittedSet.add(entry.getUserId().toString());
        }
        // Set TTL on admitted set slightly longer than admission window
        admittedSet.expire(admissionWindowMinutes + 1, TimeUnit.MINUTES);

        queueRepository.saveAll(waiting);
        if (!waiting.isEmpty()) {
            auditService.log(SYSTEM, "QUEUE_ADMITTED", "QUEUE", eventId, "count=" + waiting.size());
        }
        return List.copyOf(waiting);
    }

    @Transactional
    public void expireAdmissions(UUID eventId) {
        List<QueueEntry> expiredForEvent = queueRepository
                .findByStatusAndExpiresAtBefore(QueueStatus.ADMITTED, Instant.now())
                .stream()
                .filter(e -> e.getEventId().equals(eventId))
                .toList();

        for (QueueEntry entry : expiredForEvent) {
            entry.expire();
            removeFromAdmittedSet(eventId, entry.getUserId());
        }
        queueRepository.saveAll(expiredForEvent);
        if (!expiredForEvent.isEmpty()) {
            auditService.log(SYSTEM, "QUEUE_ADMISSION_EXPIRED", "QUEUE", eventId,
                "count=" + expiredForEvent.size());
        }
    }

    public long countWaiting(UUID eventId) {
        return queueRepository.countByEventIdAndStatus(eventId, QueueStatus.WAITING);
    }

    public List<QueueEntry> getQueueForEvent(UUID eventId) {
        return queueRepository.findByEventIdAndStatusOrderByPositionAsc(
                eventId, QueueStatus.WAITING, PageRequest.of(0, Integer.MAX_VALUE));
    }

    public List<UUID> findEventIdsWithWaitingEntries() {
        return queueRepository.findDistinctEventIdsByStatus(QueueStatus.WAITING);
    }

    public List<UUID> findEventIdsWithExpiredAdmissions() {
        return queueRepository.findDistinctEventIdsWithExpiredByStatus(QueueStatus.ADMITTED, Instant.now());
    }

    private void removeFromAdmittedSet(UUID eventId, UUID userId) {
        redissonClient.getSet(ADMITTED_KEY + eventId).remove(userId.toString());
    }
}
