package com.fairtix.config;

import org.mockito.Mockito;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Replaces the real RedissonClient with a mock for tests,
 * so tests don't need a running Redis instance.
 *
 * Supports both rate limiter mocking and atomic long mocking
 * (used by LoginAttemptService).
 *
 * <p>Component-scanned into every Spring context test. The container suite
 * ({@code PostgresIT}, profile {@code it}) sets {@code fairtix.test.redis=container}
 * to switch it off and use a real Redis instead.
 */
@Configuration
@ConditionalOnProperty(name = "fairtix.test.redis", havingValue = "mock", matchIfMissing = true)
public class TestRedisConfig {

  private final ConcurrentHashMap<String, AtomicLong> atomicLongs = new ConcurrentHashMap<>();

  @Bean
  @Primary
  public RedissonClient redissonClient() {
    RedissonClient mock = Mockito.mock(RedissonClient.class);

    // Rate limiter mock
    RRateLimiter limiter = Mockito.mock(RRateLimiter.class);
    when(mock.getRateLimiter(anyString())).thenReturn(limiter);
    when(limiter.tryAcquire(1)).thenReturn(true);
    when(limiter.isExists()).thenReturn(false);
    when(limiter.trySetRate(Mockito.any(), Mockito.anyLong(), Mockito.anyLong(), Mockito.any()))
        .thenReturn(true);

    // Atomic long mock for login attempt tracking
    when(mock.getAtomicLong(anyString())).thenAnswer(invocation -> {
      String key = invocation.getArgument(0);
      AtomicLong backing = atomicLongs.computeIfAbsent(key, k -> new AtomicLong(0));

      RAtomicLong atomicMock = Mockito.mock(RAtomicLong.class);
      when(atomicMock.get()).thenAnswer(i -> backing.get());
      when(atomicMock.incrementAndGet()).thenAnswer(i -> backing.incrementAndGet());
      when(atomicMock.delete()).thenAnswer(i -> {
        backing.set(0);
        return true;
      });
      when(atomicMock.remainTimeToLive()).thenReturn(900_000L); // 15 min in ms
      when(atomicMock.expire(Mockito.any(java.time.Duration.class))).thenReturn(true);
      return atomicMock;
    });

    // RSet mock for QueueService (admitted set per event)
    when(mock.getSet(anyString())).thenAnswer(invocation -> {
      @SuppressWarnings("unchecked")
      RSet<String> setMock = Mockito.mock(RSet.class);
      when(setMock.contains(Mockito.any())).thenReturn(false);
      when(setMock.add(Mockito.any())).thenReturn(true);
      when(setMock.remove(Mockito.any())).thenReturn(true);
      when(setMock.expire(Mockito.any(java.time.Duration.class))).thenReturn(true);
      return setMock;
    });

    return mock;
  }
}
