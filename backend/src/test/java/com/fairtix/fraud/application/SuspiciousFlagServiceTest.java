package com.fairtix.fraud.application;

import com.fairtix.fraud.domain.SuspiciousFlagSeverity;
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
class SuspiciousFlagServiceTest {

    @Autowired
    private SuspiciousFlagService suspiciousFlagService;

    @Autowired
    private SuspiciousFlagRepository flagRepository;

    @Autowired
    private UserRepository userRepository;

    private UUID userId;

    @BeforeEach
    void setUp() {
        // suspicious_flags.user_id references users(id).
        User user = new User();
        user.setEmail("flagged@test.com");
        user.setPassword("$2a$10$dummyhashfortest");
        userId = userRepository.save(user).getId();
    }

    @Test
    void flagCreatedSuccessfully() {
        suspiciousFlagService.flag(userId, SuspiciousFlagType.RAPID_HOLD_CYCLING,
                SuspiciousFlagSeverity.HIGH, "test");

        var flags = flagRepository.findByUserIdAndResolvedAtIsNull(userId);
        assertThat(flags).hasSize(1);
        assertThat(flags.get(0).getFlagType()).isEqualTo(SuspiciousFlagType.RAPID_HOLD_CYCLING);
        assertThat(flags.get(0).getSeverity()).isEqualTo(SuspiciousFlagSeverity.HIGH);
    }

    @Test
    void dedupWindowSuppressesDuplicateFlagOfSameType() {
        suspiciousFlagService.flag(userId, SuspiciousFlagType.RAPID_HOLD_CYCLING,
                SuspiciousFlagSeverity.HIGH, "first");
        suspiciousFlagService.flag(userId, SuspiciousFlagType.RAPID_HOLD_CYCLING,
                SuspiciousFlagSeverity.HIGH, "second");

        var flags = flagRepository.findByUserIdAndResolvedAtIsNull(userId);
        assertThat(flags).hasSize(1);
    }

    @Test
    void differentFlagTypesAreNotDeduped() {
        suspiciousFlagService.flag(userId, SuspiciousFlagType.RAPID_HOLD_CYCLING,
                SuspiciousFlagSeverity.HIGH, "cycling");
        suspiciousFlagService.flag(userId, SuspiciousFlagType.REPEATED_FAILED_PAYMENTS,
                SuspiciousFlagSeverity.HIGH, "payments");

        var flags = flagRepository.findByUserIdAndResolvedAtIsNull(userId);
        assertThat(flags).hasSize(2);
    }

    @Test
    void hasActiveCriticalFlagReturnsTrueForUnresolvedHighFlag() {
        suspiciousFlagService.flag(userId, SuspiciousFlagType.RAPID_HOLD_CYCLING,
                SuspiciousFlagSeverity.HIGH, "test");

        assertThat(suspiciousFlagService.hasActiveCriticalFlag(userId)).isTrue();
    }

    @Test
    void hasActiveCriticalFlagReturnsFalseWhenFlagIsResolved() {
        suspiciousFlagService.flag(userId, SuspiciousFlagType.RAPID_HOLD_CYCLING,
                SuspiciousFlagSeverity.HIGH, "test");

        UUID flagId = flagRepository.findByUserIdAndResolvedAtIsNull(userId).get(0).getId();
        suspiciousFlagService.resolve(flagId, UUID.randomUUID());

        assertThat(suspiciousFlagService.hasActiveCriticalFlag(userId)).isFalse();
    }

    @Test
    void hasActiveCriticalFlagReturnsFalseForMediumSeverity() {
        suspiciousFlagService.flag(userId, SuspiciousFlagType.VOLUME_ANOMALY,
                SuspiciousFlagSeverity.MEDIUM, "test");

        assertThat(suspiciousFlagService.hasActiveCriticalFlag(userId)).isFalse();
    }

    @Test
    void hasActiveCriticalFlagReturnsFalseForNoFlags() {
        assertThat(suspiciousFlagService.hasActiveCriticalFlag(userId)).isFalse();
    }
}
