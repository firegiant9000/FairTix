package com.fairtix.fraud.application;

import com.fairtix.audit.domain.AuditLog;
import com.fairtix.audit.infrastructure.AuditLogRepository;
import com.fairtix.fraud.domain.SuspiciousFlagType;
import com.fairtix.fraud.infrastructure.SuspiciousFlagRepository;
import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class BehaviorAnalysisServiceTest {

    @Autowired
    private BehaviorAnalysisService behaviorAnalysisService;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private SuspiciousFlagRepository flagRepository;

    @Autowired
    private UserRepository userRepository;

    private UUID userId;

    @BeforeEach
    void setUp() {
        // suspicious_flags.user_id references users(id).
        User user = new User();
        user.setEmail("behaviour@test.com");
        user.setPassword("$2a$10$dummyhashfortest");
        userId = userRepository.save(user).getId();
    }

    private void createHoldAuditLogs(UUID uid, int count) {
        for (int i = 0; i < count; i++) {
            auditLogRepository.save(new AuditLog(uid, "CREATE", "HOLD", UUID.randomUUID(), "hold " + i));
        }
    }

    @Test
    void rapidHoldCyclingFlagsWhenThresholdExceeded() {
        // Default threshold is >5 in 2 minutes; create 6
        createHoldAuditLogs(userId, 6);

        behaviorAnalysisService.analyzeUser(userId);

        assertThat(flagRepository.findByUserIdAndResolvedAtIsNull(userId))
                .anyMatch(f -> f.getFlagType() == SuspiciousFlagType.RAPID_HOLD_CYCLING);
    }

    @Test
    void rapidHoldCyclingDoesNotFlagBelowThreshold() {
        // 5 holds = at threshold (not above), should not flag
        createHoldAuditLogs(userId, 5);

        behaviorAnalysisService.analyzeUser(userId);

        assertThat(flagRepository.findByUserIdAndResolvedAtIsNull(userId))
                .noneMatch(f -> f.getFlagType() == SuspiciousFlagType.RAPID_HOLD_CYCLING);
    }

    @Test
    void volumeAnomalyFlagsWhenThresholdExceeded() {
        // Default threshold is >10 in 10 minutes; create 11
        createHoldAuditLogs(userId, 11);

        behaviorAnalysisService.analyzeUser(userId);

        assertThat(flagRepository.findByUserIdAndResolvedAtIsNull(userId))
                .anyMatch(f -> f.getFlagType() == SuspiciousFlagType.VOLUME_ANOMALY);
    }

    @Test
    void repeatedFailedPaymentsFlagsWhenThresholdExceeded() {
        // Default threshold is >3 in 24 hours; create 4
        for (int i = 0; i < 4; i++) {
            auditLogRepository.save(new AuditLog(userId, "PAYMENT_FAILED", "PAYMENT", UUID.randomUUID(), "fail " + i));
        }

        behaviorAnalysisService.analyzeUser(userId);

        assertThat(flagRepository.findByUserIdAndResolvedAtIsNull(userId))
                .anyMatch(f -> f.getFlagType() == SuspiciousFlagType.REPEATED_FAILED_PAYMENTS);
    }

    @Test
    void analyzeUserDoesNotFlagWhenNoActivity() {
        behaviorAnalysisService.analyzeUser(userId);

        assertThat(flagRepository.findByUserIdAndResolvedAtIsNull(userId)).isEmpty();
    }

    @Test
    void dedupSuppressesDuplicateRapidHoldCyclingFlag() {
        createHoldAuditLogs(userId, 6);

        // First analysis creates the flag; second is suppressed by dedup window
        behaviorAnalysisService.analyzeUser(userId);
        behaviorAnalysisService.analyzeUser(userId);

        long rapidHoldFlags = flagRepository.findByUserIdAndResolvedAtIsNull(userId).stream()
                .filter(f -> f.getFlagType() == SuspiciousFlagType.RAPID_HOLD_CYCLING)
                .count();
        assertThat(rapidHoldFlags).isEqualTo(1);
    }
}
