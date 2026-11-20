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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
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
  void invalidateActiveByStripePaymentIntentId_isOneShotAndSkipsInvalidatedRows() {
    OnlinePaymentIntent a = repository.saveAndFlush(intent("pi_completed"));

    assertThat(repository.invalidateActiveByStripePaymentIntentId(
        "pi_completed", IntentInvalidationReason.COMPLETED, Instant.now())).isEqualTo(1);
    assertThat(repository.invalidateActiveByStripePaymentIntentId(
        "pi_completed", IntentInvalidationReason.COMPLETED, Instant.now())).isEqualTo(0);
    assertThat(repository.invalidateActiveByStripePaymentIntentId(
        "pi_unknown", IntentInvalidationReason.COMPLETED, Instant.now())).isEqualTo(0);

    OnlinePaymentIntent reloaded = repository.findById(a.getId()).orElseThrow();
    assertThat(reloaded.getStatus()).isEqualTo(OnlinePaymentIntentStatus.INVALIDATED);
    assertThat(reloaded.getInvalidatedReason()).isEqualTo(IntentInvalidationReason.COMPLETED);
  }

  @Test
  void completedRow_isDeletedByRetentionAfter30Days() {
    OnlinePaymentIntent old = repository.saveAndFlush(intent("pi_completed_old"));
    repository.invalidateActiveByStripePaymentIntentId(
        "pi_completed_old", IntentInvalidationReason.COMPLETED,
        Instant.now().minus(Duration.ofDays(31)));
    OnlinePaymentIntent recent = repository.saveAndFlush(
        new OnlinePaymentIntent(user.getId(), "f".repeat(64), 4000L, ROUTING, "pi_completed_new"));
    repository.invalidateActiveByStripePaymentIntentId(
        "pi_completed_new", IntentInvalidationReason.COMPLETED,
        Instant.now().minus(Duration.ofDays(1)));

    int deleted = repository.deleteInvalidatedBefore(Instant.now().minus(Duration.ofDays(30)));

    assertThat(deleted).isEqualTo(1);
    assertThat(repository.findAll())
        .extracting(OnlinePaymentIntent::getId)
        .containsExactly(recent.getId())
        .doesNotContain(old.getId());
  }

  private UUID insertRow(String piId, String holdHash, String status, String createdAgo,
      String invalidatedAgo) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO online_payment_intents (id, user_id, hold_set_hash, amount_cents, routing_hash,"
            + " stripe_payment_intent_id, status, invalidated_reason, created_at, invalidated_at)"
            + " VALUES (?, ?, ?, 100, ?, ?, ?, ?, NOW() - CAST(? AS INTERVAL),"
            + " NOW() - CAST(? AS INTERVAL))",
        id, user.getId(), holdHash, ROUTING, piId, status,
        invalidatedAgo == null ? null : "CANCELED", createdAgo, invalidatedAgo);
    return id;
  }

  @Test
  void deleteInvalidatedBefore_removesOnlyRowsInvalidatedBeforeCutoff() {
    insertRow("pi_old_invalidated", "c".repeat(64), "INVALIDATED", "40 days", "31 days");
    UUID oldActive = insertRow("pi_old_active", "d".repeat(64), "ACTIVE", "45 days", null);
    UUID recentlyInvalidated =
        insertRow("pi_recent_invalidated", "e".repeat(64), "INVALIDATED", "45 days", "1 day");
    OnlinePaymentIntent fresh = repository.saveAndFlush(intent("pi_fresh"));

    int deleted = repository.deleteInvalidatedBefore(Instant.now().minus(Duration.ofDays(30)));

    assertThat(deleted).isEqualTo(1);
    assertThat(repository.findAll())
        .extracting(OnlinePaymentIntent::getId)
        .containsExactlyInAnyOrder(oldActive, recentlyInvalidated, fresh.getId());
  }

  @Test
  void findActiveCreatedBefore_pagesOldActiveRowsByIdAfterTheGivenId() {
    UUID a = insertRow("pi_abandoned_a", "1".repeat(64), "ACTIVE", "31 days", null);
    UUID b = insertRow("pi_abandoned_b", "2".repeat(64), "ACTIVE", "40 days", null);
    UUID c = insertRow("pi_abandoned_c", "3".repeat(64), "ACTIVE", "45 days", null);
    insertRow("pi_old_invalidated", "4".repeat(64), "INVALIDATED", "45 days", "40 days");
    repository.saveAndFlush(intent("pi_recent_active"));
    Instant cutoff = Instant.now().minus(Duration.ofDays(30));
    // Order by Postgres's UUID ordering, which the query's keyset uses.
    List<UUID> ordered = jdbcTemplate.queryForList(
        "SELECT id FROM online_payment_intents WHERE id IN (?, ?, ?) ORDER BY id", UUID.class,
        a, b, c);

    List<OnlinePaymentIntent> firstPage =
        repository.findActiveCreatedBefore(cutoff, new UUID(0L, 0L), Limit.of(2));
    List<OnlinePaymentIntent> secondPage =
        repository.findActiveCreatedBefore(cutoff, firstPage.get(1).getId(), Limit.of(2));

    assertThat(firstPage).extracting(OnlinePaymentIntent::getId)
        .containsExactly(ordered.get(0), ordered.get(1));
    assertThat(secondPage).extracting(OnlinePaymentIntent::getId)
        .containsExactly(ordered.get(2));
  }
}
