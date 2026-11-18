package com.fairtix.tickets.application;

import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * The per-sender transfer-request limit: at most {@code max-per-window} slots in a
 * sliding window of {@code window-hours}, kept in a Redis sorted set scored by
 * time.
 *
 * <p>Pruning, counting and taking a slot run as one Lua script, so concurrent
 * requests cannot all see room and overshoot the limit. A slot is not given back
 * if the request fails afterwards: callers take it at the point from which an
 * attempt should count (see {@link TransferService#createTransferRequest}).
 */
@Component
public class TransferVelocityLimiter {

    static final String KEY_PREFIX = "transfer:velocity:";

    /**
     * KEYS[1] the sender's set. ARGV: window start (ms; entries up to and including
     * it are pruned), now (ms), a unique member, the limit, the key's TTL (ms).
     * Returns 1 when a slot was taken, 0 when the window is full. The member is
     * unique per call, so a resend of the same call (Redisson retries after a
     * response timeout) finds its own slot and answers 1 again instead of 0.
     */
    static final String ACQUIRE_SCRIPT = """
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
            if redis.call('ZSCORE', KEYS[1], ARGV[3]) then
              return 1
            end
            if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[4]) then
              return 0
            end
            redis.call('ZADD', KEYS[1], ARGV[2], ARGV[3])
            redis.call('PEXPIRE', KEYS[1], ARGV[5])
            return 1
            """;

    private final RedissonClient redissonClient;

    @Value("${fairtix.transfer.velocity.max-per-window:5}")
    private int maxPerWindow;

    @Value("${fairtix.transfer.velocity.window-hours:24}")
    private int windowHours;

    public TransferVelocityLimiter(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /** Takes a slot for {@code userId}, or throws if the window is already full. */
    public void acquire(UUID userId) {
        if (!tryAcquire(userId)) {
            throw new TransferVelocityExceededException(maxPerWindow, windowHours);
        }
    }

    boolean tryAcquire(UUID userId) {
        Instant now = Instant.now();
        long windowStart = now.minus(windowHours, ChronoUnit.HOURS).toEpochMilli();
        long ttlMillis = (windowHours + 1L) * 3_600_000L;
        Long taken = redissonClient.getScript(StringCodec.INSTANCE).eval(
                RScript.Mode.READ_WRITE,
                ACQUIRE_SCRIPT,
                RScript.ReturnType.INTEGER,
                List.<Object>of(KEY_PREFIX + userId),
                String.valueOf(windowStart),
                String.valueOf(now.toEpochMilli()),
                UUID.randomUUID().toString(),
                String.valueOf(maxPerWindow),
                String.valueOf(ttlMillis));
        return taken != null && taken == 1L;
    }
}
