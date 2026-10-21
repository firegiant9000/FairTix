package com.fairtix.fraud.application;

import com.fairtix.fraud.domain.RiskScore;
import com.fairtix.fraud.domain.RiskTier;
import com.fairtix.fraud.domain.SuspiciousFlag;
import com.fairtix.fraud.domain.SuspiciousFlagSeverity;
import com.fairtix.fraud.domain.SuspiciousFlagType;
import com.fairtix.fraud.infrastructure.RiskScoreRepository;
import com.fairtix.fraud.infrastructure.SuspiciousFlagRepository;
import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class RiskScoringServiceTest {

    @Autowired
    private RiskScoringService riskScoringService;

    @Autowired
    private SuspiciousFlagRepository flagRepository;

    @Autowired
    private RiskScoreRepository riskScoreRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    private UUID userId;

    @BeforeEach
    void setUp() {
        // suspicious_flags.user_id and user_risk_scores.user_id reference users(id).
        User user = new User();
        user.setEmail("risky@test.com");
        user.setPassword("$2a$10$dummyhashfortest");
        userId = userRepository.saveAndFlush(user).getId();
        // Older than fraud.score.new-account-hours (24), so the expected scores come from
        // flags only, without the NEW_ACCOUNT points. Clear so the service reloads it.
        jdbcTemplate.update("UPDATE users SET created_at = now() - interval '30 days' WHERE id = ?", userId);
        entityManager.clear();
    }

    @Test
    void noFlagsProducesZeroScoreAndLowTier() {
        RiskScore score = riskScoringService.recalculate(userId);

        assertThat(score.getScore()).isEqualTo(0);
        assertThat(score.getTier()).isEqualTo(RiskTier.LOW);
    }

    @Test
    void scoreAccumulatesCorrectlyAcrossSeverities() {
        // HIGH = 30 pts, MEDIUM = 15 pts → total = 45
        flagRepository.save(new SuspiciousFlag(userId,
                SuspiciousFlagType.RAPID_HOLD_CYCLING, SuspiciousFlagSeverity.HIGH, "test"));
        flagRepository.save(new SuspiciousFlag(userId,
                SuspiciousFlagType.VOLUME_ANOMALY, SuspiciousFlagSeverity.MEDIUM, "test"));

        RiskScore score = riskScoringService.recalculate(userId);

        assertThat(score.getScore()).isEqualTo(45);
        assertThat(score.getTier()).isEqualTo(RiskTier.MEDIUM);
    }

    @Test
    void tierBoundaryLowToMediumAt25() {
        // Single HIGH flag = 30 pts → MEDIUM tier (25–49)
        flagRepository.save(new SuspiciousFlag(userId,
                SuspiciousFlagType.RAPID_HOLD_CYCLING, SuspiciousFlagSeverity.HIGH, "test"));

        RiskScore score = riskScoringService.recalculate(userId);

        assertThat(score.getScore()).isEqualTo(30);
        assertThat(score.getTier()).isEqualTo(RiskTier.MEDIUM);
    }

    @Test
    void tierBoundaryHighAt50() {
        // Two HIGH flags = 60 pts → HIGH tier (50–74)
        flagRepository.save(new SuspiciousFlag(userId,
                SuspiciousFlagType.RAPID_HOLD_CYCLING, SuspiciousFlagSeverity.HIGH, "test"));
        flagRepository.save(new SuspiciousFlag(userId,
                SuspiciousFlagType.REPEATED_FAILED_PAYMENTS, SuspiciousFlagSeverity.HIGH, "test"));

        RiskScore score = riskScoringService.recalculate(userId);

        assertThat(score.getScore()).isEqualTo(60);
        assertThat(score.getTier()).isEqualTo(RiskTier.HIGH);
    }

    @Test
    void tierBoundaryCriticalAt75() {
        // Three HIGH flags = 90 pts → CRITICAL tier (75+)
        flagRepository.save(new SuspiciousFlag(userId,
                SuspiciousFlagType.RAPID_HOLD_CYCLING, SuspiciousFlagSeverity.HIGH, "test"));
        flagRepository.save(new SuspiciousFlag(userId,
                SuspiciousFlagType.REPEATED_FAILED_PAYMENTS, SuspiciousFlagSeverity.HIGH, "test"));
        flagRepository.save(new SuspiciousFlag(userId,
                SuspiciousFlagType.HIGH_RELEASE_RATE, SuspiciousFlagSeverity.HIGH, "test"));

        RiskScore score = riskScoringService.recalculate(userId);

        assertThat(score.getScore()).isEqualTo(90);
        assertThat(score.getTier()).isEqualTo(RiskTier.CRITICAL);
    }

    @Test
    void scoreIsCappedAt100() {
        // Four HIGH flags (30 pts each) = 120 pts, capped at 100
        flagRepository.save(new SuspiciousFlag(userId,
                SuspiciousFlagType.RAPID_HOLD_CYCLING, SuspiciousFlagSeverity.HIGH, "test"));
        flagRepository.save(new SuspiciousFlag(userId,
                SuspiciousFlagType.REPEATED_FAILED_PAYMENTS, SuspiciousFlagSeverity.HIGH, "test"));
        flagRepository.save(new SuspiciousFlag(userId,
                SuspiciousFlagType.HIGH_RELEASE_RATE, SuspiciousFlagSeverity.HIGH, "test"));
        flagRepository.save(new SuspiciousFlag(userId,
                SuspiciousFlagType.VOLUME_ANOMALY, SuspiciousFlagSeverity.HIGH, "test"));

        RiskScore score = riskScoringService.recalculate(userId);

        assertThat(score.getScore()).isEqualTo(100);
        assertThat(score.getTier()).isEqualTo(RiskTier.CRITICAL);
    }

    @Test
    void recalculateUpdatesExistingRecord() {
        riskScoringService.recalculate(userId);
        flagRepository.save(new SuspiciousFlag(userId,
                SuspiciousFlagType.RAPID_HOLD_CYCLING, SuspiciousFlagSeverity.HIGH, "test"));
        riskScoringService.recalculate(userId);

        assertThat(riskScoreRepository.findAll()).filteredOn(rs -> rs.getUserId().equals(userId)).hasSize(1);
        assertThat(riskScoreRepository.findById(userId)).isPresent();
        assertThat(riskScoreRepository.findById(userId).get().getScore()).isEqualTo(30);
    }
}
