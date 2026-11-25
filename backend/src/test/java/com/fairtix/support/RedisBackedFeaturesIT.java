package com.fairtix.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fairtix.auth.application.LoginAttemptService;
import com.fairtix.events.domain.Event;
import com.fairtix.events.infrastructure.EventRepository;
import com.fairtix.inventory.domain.Seat;
import com.fairtix.inventory.infrastructure.SeatRepository;
import com.fairtix.queue.application.QueueService;
import com.fairtix.ratelimit.RateLimitService;
import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Redis-backed features against a real Redis 7 container (RM:139): the
 * {@code TestRedisConfig} mock always allows rate-limited calls, so these paths had
 * never run in a test.
 *
 * <p>{@link PostgresIT} flushes Redis after each test, so counters and sets do not
 * leak between tests.
 */
class RedisBackedFeaturesIT extends PostgresIT {

  @Autowired private RedissonClient redissonClient;
  @Autowired private RateLimitService rateLimitService;
  @Autowired private LoginAttemptService loginAttemptService;
  @Autowired private QueueService queueService;
  @Autowired private EventRepository eventRepository;
  @Autowired private SeatRepository seatRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlatformTransactionManager transactionManager;

  @Test
  void contextUsesRealRedisson() {
    assertThat(Mockito.mockingDetails(redissonClient).isMock()).as("RedissonClient is a mock").isFalse();
    redissonClient.<String>getBucket("probe").set("x");
    assertThat(redissonClient.<String>getBucket("probe").get()).isEqualTo("x");
  }

  /**
   * Redisson's defaults (3 s timeout, 3 retries 1.5 s apart) stall a request for
   * seconds before RateLimitFilter can fail open or closed. Bound them.
   * Intended-red commit: {@code bc554c0}.
   */
  @Test
  void redissonCallsAreBounded() {
    var server = redissonClient.getConfig().useSingleServer();
    assertThat(server.getTimeout()).as("timeout ms").isEqualTo(1000);
    assertThat(server.getRetryAttempts()).as("retry attempts").isEqualTo(1);
    assertThat(server.getRetryDelay().calcDelay(1).toMillis()).as("retry interval ms").isEqualTo(500);
    assertThat(server.getConnectTimeout()).as("connect timeout ms").isEqualTo(2000);
  }

  /**
   * The key includes the request path, which the caller chooses, so limiter keys must
   * expire or Redis grows without bound (review round 1 on #263). Checks every key the
   * Redisson limiter creates, not just the main one.
   */
  @Test
  void rateLimiterKeys_expire() {
    rateLimitService.isAllowed("ip:203.0.113.9", "/api/anything/random-1");

    var keys = new java.util.ArrayList<String>();
    redissonClient.getKeys().getKeysByPattern("*rate_limit*").forEach(keys::add);
    assertThat(keys).isNotEmpty();
    for (String key : keys) {
      assertThat(redissonClient.getBucket(key).remainTimeToLive()).as("TTL of %s", key).isPositive();
    }
  }

  /** Refresh is on the default tier, not the auth tier's 10/min (review round 3 on #263). */
  @Test
  void rateLimiter_allowsElevenRefreshesFromOneIp() {
    for (int i = 0; i < 11; i++) {
      assertThat(rateLimitService.isAllowed("ip:203.0.113.10", "/auth/refresh")).as("refresh %d", i + 1).isTrue();
    }
  }

  @Test
  void rateLimiter_rejectsAfterTheAuthLimit() {
    // ratelimit.auth defaults to 10 per minute.
    for (int i = 0; i < 10; i++) {
      assertThat(rateLimitService.isAllowed("ip:203.0.113.7", "/api/auth/login")).as("call %d", i + 1).isTrue();
    }
    assertThat(rateLimitService.isAllowed("ip:203.0.113.7", "/api/auth/login")).as("call 11").isFalse();
    assertThat(rateLimitService.isAllowed("ip:203.0.113.8", "/api/auth/login")).as("another client").isTrue();
  }

  @Test
  void loginLockout_locksAtMaxAttempts_withTtl_andResets() {
    String email = "Lockout@Test.com";
    for (int i = 0; i < loginAttemptService.getMaxAttempts() - 1; i++) {
      loginAttemptService.recordFailure(email);
    }
    assertThat(loginAttemptService.isLocked(email)).isFalse();

    loginAttemptService.recordFailure(email.toLowerCase());

    assertThat(loginAttemptService.isLocked(email)).isTrue();
    // auth.lockout-duration-minutes defaults to 15.
    assertThat(loginAttemptService.getRemainingLockoutSeconds(email)).isBetween(1L, 900L);
    loginAttemptService.resetAttempts(email);
    assertThat(loginAttemptService.isLocked(email)).isFalse();
    assertThat(loginAttemptService.getRemainingLockoutSeconds(email)).isZero();
  }

  @Test
  void queueAdmission_databaseTracksAdmitLeaveAndExpire() {
    Event event = queueEvent();
    UUID leaver = newUser("leaver@test.com");
    UUID expirer = newUser("expirer@test.com");
    queueService.joinQueue(event.getId(), leaver);
    queueService.joinQueue(event.getId(), expirer);

    assertThat(queueService.admitNextBatch(event.getId())).hasSize(2);

    assertThat(queueService.isAdmitted(event.getId(), leaver)).isTrue();

    queueService.leaveQueue(event.getId(), leaver);
    assertThat(queueService.isAdmitted(event.getId(), leaver)).isFalse();

    jdbcTemplate.update("UPDATE queue_entries SET expires_at = now() - interval '1 minute'"
        + " WHERE event_id = ? AND user_id = ?", event.getId(), expirer);
    queueService.expireAdmissions(event.getId());
    assertThat(queueService.isAdmitted(event.getId(), expirer)).isFalse();
  }

  /**
   * A rolled-back {@code admitNextBatch} leaves the user {@code WAITING} and not
   * admitted: admission is read from the database only.
   */
  @Test
  void queueAdmission_rollback_leavesUserNotAdmitted() {
    Event event = queueEvent();
    UUID user = newUser("rollback@test.com");
    queueService.joinQueue(event.getId(), user);

    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
      assertThat(queueService.admitNextBatch(event.getId())).hasSize(1);
      status.setRollbackOnly();
    });

    assertThat(jdbcTemplate.queryForObject(
        "SELECT status FROM queue_entries WHERE event_id = ? AND user_id = ?",
        String.class, event.getId(), user)).isEqualTo("WAITING");
    assertThat(queueService.isAdmitted(event.getId(), user)).isFalse();
  }

  /**
   * The admitted set outlives the admission window (queue.admission-window-minutes,
   * 15 by default) by one minute, then Redis drops it.
   */
  @Test
  void queueAdmission_admittedSetExpiresJustAfterTheWindow() {
    Event event = queueEvent();
    queueService.joinQueue(event.getId(), newUser("ttl@test.com"));

    assertThat(queueService.admitNextBatch(event.getId())).hasSize(1);

    assertThat(admittedSetTtlMillis(event)).isBetween(15 * 60_000L, 16 * 60_000L);
  }

  /**
   * A released hold puts the user back in the admitted set. If the set's key was gone
   * (Redis deletes an empty set, e.g. once the user's hold emptied it), that add creates
   * a new key and it must still expire, not live forever.
   */
  @Test
  void queueAdmission_reinstatedUserLandsInASetThatExpires() {
    Event event = queueEvent();
    UUID user = newUser("reinstate@test.com");
    queueService.joinQueue(event.getId(), user);
    assertThat(queueService.admitNextBatch(event.getId())).hasSize(1);
    queueService.completeQueueEntry(event.getId(), user);
    assertThat(redissonClient.getSet("queue:admitted:" + event.getId()).isExists())
        .as("the set is empty, so Redis removed it").isFalse();

    queueService.reinstateAfterHoldRelease(event.getId(), user);

    assertThat(admittedInRedis(event, user)).isTrue();
    assertThat(admittedSetTtlMillis(event)).as("TTL ms (-1 means no expiry)")
        .isBetween(1L, 16 * 60_000L);
  }

  /** A reinstate into a set that still exists keeps the set's TTL; it doesn't extend it. */
  @Test
  void queueAdmission_reinstateDoesNotExtendALiveSet() {
    Event event = queueEvent();
    UUID reinstated = newUser("keep-ttl@test.com");
    UUID other = newUser("keeps-set-alive@test.com");
    queueService.joinQueue(event.getId(), reinstated);
    queueService.joinQueue(event.getId(), other);
    assertThat(queueService.admitNextBatch(event.getId())).hasSize(2);
    queueService.completeQueueEntry(event.getId(), reinstated);
    redissonClient.getSet("queue:admitted:" + event.getId()).expire(java.time.Duration.ofSeconds(60));

    queueService.reinstateAfterHoldRelease(event.getId(), reinstated);

    assertThat(admittedInRedis(event, reinstated)).isTrue();
    assertThat(admittedSetTtlMillis(event)).isBetween(1L, 60_000L);
  }

  private long admittedSetTtlMillis(Event event) {
    return redissonClient.getSet("queue:admitted:" + event.getId()).remainTimeToLive();
  }

  private Event queueEvent() {
    Event event = new Event("Queue Concert", null, Instant.now().plusSeconds(86400), (UUID) null);
    event.updateQueueSettings(true, null);
    event = eventRepository.save(event);
    seatRepository.save(new Seat(event, "A", "1", "1", new BigDecimal("40.00")));
    seatRepository.save(new Seat(event, "A", "1", "2", new BigDecimal("40.00")));
    return event;
  }

  private UUID newUser(String email) {
    User user = new User();
    user.setEmail(email);
    user.setPassword("$2a$10$dummyhashfortest");
    user.setEmailVerified(true);
    return userRepository.save(user).getId();
  }
}
