package com.fairtix.auth.application;

import com.fairtix.users.application.UserEmailLookup;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class LoginAttemptService {

  private final RedissonClient redissonClient;
  private final int maxAttempts;
  private final Duration lockoutDuration;

  private static final String KEY_PREFIX = "login_attempts:";

  public LoginAttemptService(RedissonClient redissonClient,
      @Value("${auth.max-login-attempts:5}") int maxAttempts,
      @Value("${auth.lockout-duration-minutes:15}") int lockoutMinutes) {
    this.redissonClient = redissonClient;
    this.maxAttempts = maxAttempts;
    this.lockoutDuration = Duration.ofMinutes(lockoutMinutes);
  }

  public boolean isLocked(String email) {
    RAtomicLong counter = redissonClient.getAtomicLong(key(email));
    return counter.get() >= maxAttempts;
  }

  public long getAttemptCount(String email) {
    RAtomicLong counter = redissonClient.getAtomicLong(key(email));
    return counter.get();
  }

  /** Returns the failure count including this one. */
  public long recordFailure(String email) {
    RAtomicLong counter = redissonClient.getAtomicLong(key(email));
    long current = counter.incrementAndGet();
    if (current == 1 || current >= maxAttempts) {
      // Set TTL on first failure; refresh it when lockout threshold is reached
      // so the lockout duration is a full window from the moment of lockout
      counter.expire(lockoutDuration);
    }
    return current;
  }

  public void resetAttempts(String email) {
    redissonClient.getAtomicLong(key(email)).delete();
  }

  public long getRemainingLockoutSeconds(String email) {
    RAtomicLong counter = redissonClient.getAtomicLong(key(email));
    long ttl = counter.remainTimeToLive();
    return ttl > 0 ? ttl / 1000 : 0;
  }

  public int getMaxAttempts() {
    return maxAttempts;
  }

  /**
   * Keyed on the folding login's account lookup honours (UserEmailLookup.normalize), so
   * every spelling that can reach an account shares that account's count.
   */
  private static String key(String email) {
    return KEY_PREFIX + UserEmailLookup.normalize(email);
  }
}
