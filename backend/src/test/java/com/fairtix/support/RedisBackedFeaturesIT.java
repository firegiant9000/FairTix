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
 * {@code TestRedisConfig} mock always allows rate-limited calls and never finds a
 * user in the admitted set, so these paths had never run in a test.
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
    assertThat(server.getRetryInterval()).as("retry interval ms").isEqualTo(500);
    assertThat(server.getConnectTimeout()).as("connect timeout ms").isEqualTo(2000);
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
  void queueAdmission_redisSetTracksAdmitLeaveAndExpire() {
    Event event = queueEvent();
    UUID leaver = newUser("leaver@test.com");
    UUID expirer = newUser("expirer@test.com");
    queueService.joinQueue(event.getId(), leaver);
    queueService.joinQueue(event.getId(), expirer);

    assertThat(queueService.admitNextBatch(event.getId())).hasSize(2);

    assertThat(admittedInRedis(event, leaver)).isTrue();
    assertThat(queueService.isAdmitted(event.getId(), leaver)).isTrue();

    queueService.leaveQueue(event.getId(), leaver);
    assertThat(admittedInRedis(event, leaver)).isFalse();
    assertThat(queueService.isAdmitted(event.getId(), leaver)).isFalse();

    jdbcTemplate.update("UPDATE queue_entries SET expires_at = now() - interval '1 minute'"
        + " WHERE event_id = ? AND user_id = ?", event.getId(), expirer);
    queueService.expireAdmissions(event.getId());
    assertThat(admittedInRedis(event, expirer)).isFalse();
    assertThat(queueService.isAdmitted(event.getId(), expirer)).isFalse();
  }

  /**
   * Pins current behaviour, finding F-4.1 (not fixed in this slice):
   * {@code admitNextBatch} adds to the Redis set before its transaction commits, so a
   * rolled-back admission leaves the user admitted in Redis while the database still
   * says {@code WAITING}, and {@code isAdmitted} trusts Redis first.
   */
  @Test
  void queueAdmission_rollback_leavesUserAdmittedInRedis() {
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
    assertThat(queueService.isAdmitted(event.getId(), user)).as("F-4.1: Redis outlives the rollback").isTrue();
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

  private boolean admittedInRedis(Event event, UUID userId) {
    return redissonClient.getSet("queue:admitted:" + event.getId()).contains(userId.toString());
  }
}
