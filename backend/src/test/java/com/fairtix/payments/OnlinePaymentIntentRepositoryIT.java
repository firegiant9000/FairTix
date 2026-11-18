package com.fairtix.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fairtix.payments.domain.IntentInvalidationReason;
import com.fairtix.payments.domain.OnlinePaymentIntent;
import com.fairtix.payments.domain.OnlinePaymentIntentStatus;
import com.fairtix.payments.infrastructure.OnlinePaymentIntentRepository;
import com.fairtix.support.PostgresIT;
import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class OnlinePaymentIntentRepositoryIT extends PostgresIT {

  private static final String HASH = "a".repeat(64);
  private static final String ROUTING = "b".repeat(64);

  @Autowired private OnlinePaymentIntentRepository repository;
  @Autowired private UserRepository userRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private User user;

  @BeforeEach
  void setUp() {
    User u = new User();
    u.setEmail("intentrepo@test.com");
    u.setPassword("$2a$10$dummyhashfortest");
    u.setEmailVerified(true);
    user = userRepository.save(u);
  }

  private OnlinePaymentIntent intent(String piId) {
    return new OnlinePaymentIntent(user.getId(), HASH, 4000L, ROUTING, piId);
  }

  @Test
  void secondActiveRowForSameHoldSet_isRejected() {
    repository.saveAndFlush(intent("pi_1"));
    assertThatThrownBy(() -> repository.saveAndFlush(intent("pi_2")))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void invalidatedRow_doesNotBlockNewActiveRow() {
    OnlinePaymentIntent a = repository.saveAndFlush(intent("pi_a"));
    int changed = repository.invalidate(a.getId(), IntentInvalidationReason.AMOUNT_CHANGED, Instant.now());
    assertThat(changed).isEqualTo(1);

    OnlinePaymentIntent b = repository.saveAndFlush(intent("pi_b"));

    assertThat(repository.findByUserIdAndHoldSetHashAndStatus(
            user.getId(), HASH, OnlinePaymentIntentStatus.ACTIVE))
        .get()
        .extracting(OnlinePaymentIntent::getId)
        .isEqualTo(b.getId());
  }

  @Test
  void invalidate_isOneShot() {
    OnlinePaymentIntent a = repository.saveAndFlush(intent("pi_once"));
    assertThat(repository.invalidate(a.getId(), IntentInvalidationReason.CANCELED, Instant.now()))
        .isEqualTo(1);
    assertThat(repository.invalidate(a.getId(), IntentInvalidationReason.ROUTING_CHANGED, Instant.now()))
        .isEqualTo(0);

    OnlinePaymentIntent reloaded = repository.findById(a.getId()).orElseThrow();
    assertThat(reloaded.getStatus()).isEqualTo(OnlinePaymentIntentStatus.INVALIDATED);
    assertThat(reloaded.getInvalidatedReason()).isEqualTo(IntentInvalidationReason.CANCELED);
    assertThat(reloaded.getInvalidatedAt()).isNotNull();
  }

  @Test
  void deleteCreatedBefore_removesOnlyOldRows() {
    jdbcTemplate.update(
        "INSERT INTO online_payment_intents (id, user_id, hold_set_hash, amount_cents, routing_hash,"
            + " stripe_payment_intent_id, status, created_at)"
            + " VALUES (?, ?, ?, 100, ?, 'pi_old', 'INVALIDATED', NOW() - INTERVAL '31 days')",
        UUID.randomUUID(), user.getId(), "c".repeat(64), ROUTING);
    OnlinePaymentIntent fresh = repository.saveAndFlush(intent("pi_fresh"));

    int deleted = repository.deleteCreatedBefore(Instant.now().minus(Duration.ofDays(30)));

    assertThat(deleted).isEqualTo(1);
    assertThat(repository.findAll()).extracting(OnlinePaymentIntent::getId).containsExactly(fresh.getId());
  }
}
