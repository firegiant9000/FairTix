package com.fairtix.tickets.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fairtix.support.PostgresIT;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The transfer-velocity script against a real Redis 7 container: the default limit
 * is 5 per 24 hours.
 */
class TransferVelocityLimiterIT extends PostgresIT {

    private static final int LIMIT = 5;

    @Autowired private TransferVelocityLimiter limiter;
    @Autowired private RedissonClient redissonClient;

    /**
     * Requests that all arrive at once: a separate count and add let every one of
     * them see room. The script takes exactly the limit.
     */
    @Test
    void concurrentAcquires_neverOvershootTheLimit() throws Exception {
        UUID user = UUID.randomUUID();
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<Boolean> attempt = () -> {
                    start.await();
                    return limiter.tryAcquire(user);
                };
                results.add(pool.submit(attempt));
            }
            start.countDown();
            int taken = 0;
            for (Future<Boolean> result : results) {
                if (result.get(10, TimeUnit.SECONDS)) {
                    taken++;
                }
            }
            assertThat(taken).isEqualTo(LIMIT);
        } finally {
            pool.shutdownNow();
        }
        assertThat(redissonClient.getScoredSortedSet(TransferVelocityLimiter.KEY_PREFIX + user).size())
                .isEqualTo(LIMIT);
    }

    @Test
    void fullWindow_throwsAndKeyExpires() {
        UUID user = UUID.randomUUID();
        for (int i = 0; i < LIMIT; i++) {
            limiter.acquire(user);
        }

        assertThatThrownBy(() -> limiter.acquire(user))
                .isInstanceOf(TransferVelocityExceededException.class);
        assertThat(redissonClient.getKeys().remainTimeToLive(TransferVelocityLimiter.KEY_PREFIX + user))
                .isPositive();
    }

    @Test
    void limitIsPerSender() {
        UUID first = UUID.randomUUID();
        for (int i = 0; i < LIMIT; i++) {
            limiter.acquire(first);
        }

        assertThat(limiter.tryAcquire(UUID.randomUUID())).isTrue();
    }
}
