package com.fairtix.auth.scheduler;

import com.fairtix.auth.infrastructure.EmailVerificationTokenRepository;
import com.fairtix.auth.infrastructure.PasswordResetTokenRepository;
import com.fairtix.auth.infrastructure.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Component
public class VerificationTokenCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(VerificationTokenCleanupScheduler.class);

    private final EmailVerificationTokenRepository verificationTokenRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final RefreshTokenRepository refreshTokenRepository;

    public VerificationTokenCleanupScheduler(
            EmailVerificationTokenRepository verificationTokenRepository,
            PasswordResetTokenRepository passwordResetTokenRepository,
            RefreshTokenRepository refreshTokenRepository) {
        this.verificationTokenRepository = verificationTokenRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.refreshTokenRepository = refreshTokenRepository;
    }

    @Scheduled(fixedDelayString = "${verification.token.cleanup.interval-ms:3600000}")
    @Transactional
    public void cleanUpExpiredTokens() {
        MDC.put("requestId", "sched-cleanUpExpiredTokens-" + UUID.randomUUID());
        try {
            Instant cutoff = Instant.now().minus(1, ChronoUnit.HOURS);
            verificationTokenRepository.deleteExpiredBefore(cutoff);
            passwordResetTokenRepository.deleteExpiredBefore(cutoff);
            refreshTokenRepository.deleteExpiredBefore(cutoff);
            log.debug("Cleaned up expired verification, password reset, and refresh tokens older than 1h past expiry");
        } finally {
            MDC.remove("requestId");
        }
    }
}
