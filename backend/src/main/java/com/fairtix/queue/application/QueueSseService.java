package com.fairtix.queue.application;

import com.fairtix.queue.api.QueueStatusResponse;
import com.fairtix.queue.domain.QueueEntry;
import com.fairtix.queue.domain.QueueStatus;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.api.listener.MessageListener;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pushes queue status to browsers over SSE.
 *
 * <p>Each instance keeps the emitters of the browsers connected to it. A status change can
 * happen on any instance (the admission job runs on whichever instance holds its lock), so
 * {@link #publishUpdate} announces it on the Redis topic {@value #TOPIC} and every instance,
 * the sender included, runs {@link #broadcast} for its own emitters. The message is only the
 * event id: each instance re-reads each user's status from the database, which stays the
 * source of truth, and nothing about a user goes through Redis.
 *
 * <p>If Redis is unreachable, no instance is subscribed, or this instance's own subscription
 * failed at startup (retried in the background at most every 30 s), the sender broadcasts locally; browsers on
 * other instances then see the change on their next update or reconnect (the first message
 * on a new stream is the current status). Redis pub/sub delivers at most once: an instance
 * whose subscription is reconnecting misses that message, and if it was the event's last
 * admission its browsers wait for the SSE reconnect ({@value #SSE_TIMEOUT_MS} ms). Redisson
 * resubscribes the listener after a reconnect. Broadcasts run on one worker thread, and
 * repeats for an event that is already queued are dropped.
 */
@Service
public class QueueSseService {

    private static final Logger log = LoggerFactory.getLogger(QueueSseService.class);
    private static final long SSE_TIMEOUT_MS = 180_000L;
    static final String TOPIC = "fairtix:queue:updates";

    private record EmitterEntry(UUID userId, SseEmitter emitter) {}

    private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<EmitterEntry>> registry = new ConcurrentHashMap<>();
    private final QueueService queueService;
    private final RTopic topic;
    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();
    private final ExecutorService fanOut = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "queue-sse-fanout");
        thread.setDaemon(true);
        return thread;
    });
    private final MessageListener<String> listener = (channel, message) -> onMessage(message);
    private final AtomicBoolean subscribing = new AtomicBoolean();
    private volatile Integer listenerId;
    private volatile long lastSubscribeAttemptNanos;
    private volatile boolean closed;

    /** Minimum time between subscription retries. Package-private for tests. */
    long resubscribeCooldownNanos = TimeUnit.SECONDS.toNanos(30);

    public QueueSseService(QueueService queueService, RedissonClient redissonClient) {
        this.queueService = queueService;
        this.topic = redissonClient.getTopic(TOPIC, StringCodec.INSTANCE);
    }

    /**
     * Subscribes this instance to the topic at startup (blocking; Redisson has just
     * connected). A failure is retried by {@link #publishUpdate}, asynchronously and at most
     * once per {@link #resubscribeCooldownNanos}; until then this instance's own changes are
     * broadcast locally.
     */
    @PostConstruct
    void subscribe() {
        lastSubscribeAttemptNanos = System.nanoTime();
        try {
            listenerId = topic.addListener(String.class, listener);
        } catch (Exception e) {
            log.warn("Queue update subscription failed; retrying later: {}", e.getClass().getSimpleName());
        }
    }

    /** Never blocks the caller: a scheduler tick or an admin request. */
    private void retrySubscribe() {
        if (closed || listenerId != null
                || System.nanoTime() - lastSubscribeAttemptNanos < resubscribeCooldownNanos
                || !subscribing.compareAndSet(false, true)) {
            return;
        }
        lastSubscribeAttemptNanos = System.nanoTime();
        try {
            topic.addListenerAsync(String.class, listener).whenComplete((id, error) -> {
                subscribing.set(false);
                if (error != null) {
                    log.warn("Queue update subscription retry failed: {}", error.getClass().getSimpleName());
                } else if (closed || listenerId != null) {
                    topic.removeListenerAsync(id);
                } else {
                    listenerId = id;
                }
            });
        } catch (Exception e) {
            subscribing.set(false);
            log.warn("Queue update subscription retry failed: {}", e.getClass().getSimpleName());
        }
    }

    @PreDestroy
    void unsubscribe() {
        closed = true;
        Integer id = listenerId;
        if (id != null) {
            try {
                topic.removeListener(id);
            } catch (Exception e) {
                log.debug("Queue update unsubscribe failed: {}", e.getClass().getSimpleName());
            }
        }
        fanOut.shutdownNow();
    }

    public SseEmitter register(UUID eventId, UUID userId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        EmitterEntry entry = new EmitterEntry(userId, emitter);
        registry.computeIfAbsent(eventId, k -> new CopyOnWriteArrayList<>()).add(entry);

        Runnable remove = () -> removeEntry(eventId, entry);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());

        try {
            QueueEntry queueEntry = queueService.getStatus(eventId, userId);
            long totalAhead = queueEntry.getStatus() == QueueStatus.WAITING
                    ? Math.max(0, queueEntry.getPosition() - 1)
                    : 0;
            emitter.send(SseEmitter.event()
                    .data(QueueStatusResponse.from(queueEntry, totalAhead), MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            log.warn("Initial SSE send failed for event {} user {}: {}", eventId, userId, e.getMessage());
            emitter.completeWithError(e);
            removeEntry(eventId, entry);
        }

        return emitter;
    }

    /**
     * Tells every instance that the queue for {@code eventId} changed, so each pushes fresh
     * statuses to its own browsers. Call after the change has committed.
     *
     * <p>Asynchronous: the caller (a scheduler tick looping over events) does not wait on
     * Redis, so an outage cannot stretch the tick past its lock. If this instance is not
     * subscribed, or the publish fails or reaches no subscriber, this instance's browsers
     * are updated locally.
     */
    public void publishUpdate(UUID eventId) {
        if (listenerId == null) {
            retrySubscribe();
            broadcastLocallyLater(eventId);
        }
        try {
            topic.publishAsync(eventId.toString()).whenComplete((receivers, error) -> {
                if (error != null) {
                    log.warn("Queue update publish failed for event {}; broadcasting locally: {}",
                            eventId, error.getClass().getSimpleName());
                    broadcastLocallyLater(eventId);
                } else if (receivers == null || receivers == 0) {
                    broadcastLocallyLater(eventId);
                }
            });
        } catch (Exception e) {
            log.warn("Queue update publish failed for event {}; broadcasting locally: {}",
                    eventId, e.getClass().getSimpleName());
            broadcastLocallyLater(eventId);
        }
    }

    /** Sends the current status to the browsers connected to this instance. */
    public void broadcast(UUID eventId) {
        CopyOnWriteArrayList<EmitterEntry> entries = registry.get(eventId);
        if (entries == null || entries.isEmpty()) {
            return;
        }

        for (EmitterEntry entry : entries) {
            try {
                QueueEntry queueEntry = queueService.getStatus(eventId, entry.userId());
                long totalAhead = queueEntry.getStatus() == QueueStatus.WAITING
                        ? Math.max(0, queueEntry.getPosition() - 1)
                        : 0;
                entry.emitter().send(SseEmitter.event()
                        .data(QueueStatusResponse.from(queueEntry, totalAhead), MediaType.APPLICATION_JSON));
            } catch (Exception e) {
                log.warn("SSE broadcast failed for event {} user {}: {}", eventId, entry.userId(), e.getMessage());
                entry.emitter().completeWithError(e);
                removeEntry(eventId, entry);
            }
        }
    }

    void onMessage(String message) {
        UUID eventId;
        try {
            eventId = UUID.fromString(message);
        } catch (IllegalArgumentException e) {
            log.warn("Ignoring malformed queue update message");
            return;
        }
        broadcastLocallyLater(eventId);
    }

    /**
     * Queues a local broadcast on the fan-out thread. A repeat for an event that is already
     * queued is dropped: the queued broadcast has not read the database yet, so it sends the
     * latest status anyway.
     */
    private void broadcastLocallyLater(UUID eventId) {
        if (!registry.containsKey(eventId) || !pending.add(eventId)) {
            return;
        }
        try {
            fanOut.execute(() -> {
                pending.remove(eventId);
                broadcast(eventId);
            });
        } catch (RejectedExecutionException e) {
            pending.remove(eventId); // shutting down
        }
    }

    private void removeEntry(UUID eventId, EmitterEntry entry) {
        CopyOnWriteArrayList<EmitterEntry> entries = registry.get(eventId);
        if (entries != null) {
            entries.remove(entry);
        }
    }
}
