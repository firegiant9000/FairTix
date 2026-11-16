package com.fairtix.queue.application;

import com.fairtix.audit.application.AuditService;
import com.fairtix.common.ResourceNotFoundException;
import com.fairtix.common.TransactionLock;
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
import com.fairtix.tickets.domain.TicketStatus;
import com.fairtix.tickets.infrastructure.TicketRepository;
import jakarta.transaction.Transactional;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
    private static final Logger log = LoggerFactory.getLogger(QueueService.class);
    private static final List<QueueStatus> REJOINABLE = List.of(QueueStatus.EXPIRED, QueueStatus.COMPLETED);

    private final QueueRepository queueRepository;
    private final EventRepository eventRepository;
    private final SeatRepository seatRepository;
    private final TicketRepository ticketRepository;
    private final RedissonClient redissonClient;
    private final AuditService auditService;
    private final RiskScoringService riskScoringService;
    private final TransactionLock transactionLock;

    @Value("${queue.admission-window-minutes:15}")
    private int admissionWindowMinutes;

    @Value("${queue.max-admission-batch:50}")
    private int maxAdmissionBatch;

    /**
     * How long a join waits for the capacity lock. Shorter than the general
     * transaction-lock wait: every join to a capped event queues on one lock, each
     * holding a pooled connection, so a burst must give connections back quickly.
     */
    @Value("${queue.join-lock-wait-ms:500}")
    private long joinLockWaitMs;

    public QueueService(QueueRepository queueRepository,
                        EventRepository eventRepository,
                        SeatRepository seatRepository,
                        TicketRepository ticketRepository,
                        RedissonClient redissonClient,
                        AuditService auditService,
                        RiskScoringService riskScoringService,
                        TransactionLock transactionLock) {
        this.transactionLock = transactionLock;
        this.queueRepository = queueRepository;
        this.eventRepository = eventRepository;
        this.seatRepository = seatRepository;
        this.ticketRepository = ticketRepository;
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
            long owned = ticketRepository.countByUser_IdAndEvent_IdAndStatusNot(
                    userId, eventId, TicketStatus.CANCELLED);
            if (owned >= event.getMaxTicketsPerUser()) {
                throw new QueueConflictException(
                        "You have already purchased the maximum number of tickets for this event");
            }
        }

        // Taken before the capacity lock, so that the lock covers database work only:
        // a slow Redis must not hold up every other join to this event. A join that
        // is rejected below leaves a gap; positions only order the queue.
        long position = nextPosition(eventId);

        // Capacity is count-then-insert, so joins to a capped queue run one at a time
        // per event: this lock is held until the transaction ends, and the next join
        // then counts this one's entry. Taken before any row is written.
        if (event.getQueueCapacity() != null) {
            transactionLock.acquire("queue-capacity:" + eventId, joinLockWaitMs);
        }

        Optional<QueueEntry> existing = queueRepository.findByEventIdAndUserId(eventId, userId);
        if (existing.isPresent()) {
            QueueStatus existingStatus = existing.get().getStatus();
            if (existingStatus == QueueStatus.WAITING || existingStatus == QueueStatus.ADMITTED) {
                throw new QueueConflictException("You are already in the queue for this event");
            }
            // EXPIRED or COMPLETED (hold was released) — delete old entry so the user can rejoin.
            // The delete runs now (Hibernate would otherwise write the new entry's insert
            // first and hit uq_queue_event_user), and only if the entry still has the
            // status read above: a hold release can put a COMPLETED entry back to
            // ADMITTED in between, and that admission must not be deleted.
            if (queueRepository.deleteByIdAndStatusIn(existing.get().getId(), REJOINABLE) == 0) {
                throw new QueueConflictException("You are already in the queue for this event");
            }
        }

        if (event.getQueueCapacity() != null) {
            long totalEntries = queueRepository.countByEventIdAndStatus(eventId, QueueStatus.WAITING)
                    + queueRepository.countByEventIdAndStatus(eventId, QueueStatus.ADMITTED);
            if (totalEntries >= event.getQueueCapacity()) {
                throw new QueueConflictException("Queue is at capacity for this event");
            }
        }

        QueueEntry entry = new QueueEntry(eventId, userId, UUID.randomUUID().toString(), (int) position);
        QueueEntry saved;
        try {
            // Flushed here so that a second join by the same user that got past the
            // lookup above fails on uq_queue_event_user now, with a clear message.
            saved = queueRepository.saveAndFlush(entry);
        } catch (DataIntegrityViolationException ex) {
            throw new QueueConflictException("You are already in the queue for this event");
        }
        // Same connection as the join: a REQUIRES_NEW audit would need a second
        // pooled connection while joins waiting on the capacity lock hold the others.
        auditService.logInCurrentTransaction(userId, "QUEUE_JOINED", "QUEUE", eventId, null);
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
            auditService.logInCurrentTransaction(userId, "QUEUE_LEFT", "QUEUE", eventId, null);
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
                addToAdmittedSetAfterCommit(eventId, List.of(userId.toString()), false);
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
        // Admission is count-then-write: without this, the scheduler on two instances,
        // or the scheduler and the admin endpoint, read the same counts and admit the
        // same entries or more than the seats allow. A batch that is already being
        // admitted elsewhere is skipped, not queued; the next run picks up the rest.
        if (!transactionLock.tryAcquire("queue-admit:" + eventId)) {
            return List.of();
        }

        long availableSeats = seatRepository.findByEvent_IdAndStatus(eventId, SeatStatus.AVAILABLE).size();
        long currentlyAdmitted = queueRepository.countByEventIdAndStatus(eventId, QueueStatus.ADMITTED);
        long toAdmit = Math.min(availableSeats - currentlyAdmitted, maxAdmissionBatch);

        if (toAdmit <= 0) {
            return List.of();
        }

        List<QueueEntry> waiting = queueRepository.findByEventIdAndStatusOrderByPositionAsc(
                eventId, QueueStatus.WAITING, PageRequest.of(0, (int) toAdmit));

        Instant expiresAt = Instant.now().plusSeconds(admissionWindowMinutes * 60L);
        for (QueueEntry entry : waiting) {
            entry.admit(expiresAt);
        }
        queueRepository.saveAll(waiting);
        if (!waiting.isEmpty()) {
            addToAdmittedSetAfterCommit(eventId,
                    waiting.stream().map(entry -> entry.getUserId().toString()).toList(), true);
            auditService.logInCurrentTransaction(
                    SYSTEM, "QUEUE_ADMITTED", "QUEUE", eventId, "count=" + waiting.size());
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
            auditService.logInCurrentTransaction(SYSTEM, "QUEUE_ADMISSION_EXPIRED", "QUEUE", eventId,
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

    /**
     * The next queue position for the event. The counter lives in Redis only, so
     * after Redis loses it the count restarts at 1 while entries on record have
     * higher positions; admission is by position, so new joiners would be admitted
     * first. A value that is not above the highest position on record is therefore
     * moved past it. Concurrent joins each add their own shortfall, so every result
     * is still unique and above that highest position; the gaps do not matter.
     */
    private long nextPosition(UUID eventId) {
        RAtomicLong counter = redissonClient.getAtomicLong(POSITION_KEY + eventId);
        long position = counter.incrementAndGet();
        long highestOnRecord = queueRepository.findMaxPositionByEventId(eventId);
        if (position <= highestOnRecord) {
            position = counter.addAndGet(highestOnRecord - position + 1);
        }
        return position;
    }

    /**
     * Adds users to the Redis admitted set once the transaction that admitted them
     * has committed. Added earlier, a rolled-back admission would leave Redis saying
     * "admitted" until the set expired. If the add fails, {@link #isAdmitted} still
     * finds the admission in the database.
     */
    private void addToAdmittedSetAfterCommit(UUID eventId, List<String> userIds, boolean refreshTtl) {
        Runnable add = () -> {
            try {
                RSet<String> admittedSet = redissonClient.getSet(ADMITTED_KEY + eventId);
                admittedSet.addAll(userIds);
                if (refreshTtl) {
                    // TTL on the admitted set slightly longer than the admission window
                    admittedSet.expire(admissionWindowMinutes + 1, TimeUnit.MINUTES);
                }
            } catch (RuntimeException ex) {
                log.warn("Could not add {} user(s) to the admitted set of event {}: {}",
                        userIds.size(), eventId, ex.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    add.run();
                }
            });
        } else {
            add.run();
        }
    }

    private void removeFromAdmittedSet(UUID eventId, UUID userId) {
        redissonClient.getSet(ADMITTED_KEY + eventId).remove(userId.toString());
    }
}
