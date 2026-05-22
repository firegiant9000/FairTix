package com.fairtix.fraud.application;

import com.fairtix.fraud.domain.RiskScore;
import com.fairtix.fraud.domain.RiskTier;
import com.fairtix.fraud.domain.SuspiciousFlag;
import com.fairtix.fraud.domain.SuspiciousFlagSeverity;
import com.fairtix.fraud.infrastructure.RiskScoreRepository;
import com.fairtix.fraud.infrastructure.SuspiciousFlagRepository;
import com.fairtix.payments.domain.PaymentStatus;
import com.fairtix.payments.infrastructure.PaymentRecordRepository;
import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class RiskScoringService {

    private final RiskScoreRepository riskScoreRepository;
    private final SuspiciousFlagRepository flagRepository;
    private final PaymentRecordRepository paymentRecordRepository;
    private final UserRepository userRepository;

    @Value("${fraud.score.low-severity-points:5}")
    private int lowSeverityPoints;

    @Value("${fraud.score.medium-severity-points:15}")
    private int mediumSeverityPoints;

    @Value("${fraud.score.high-severity-points:30}")
    private int highSeverityPoints;

    @Value("${fraud.score.payment-failure-points:20}")
    private int paymentFailurePoints;

    @Value("${fraud.score.new-account-points:10}")
    private int newAccountPoints;

    @Value("${fraud.score.payment-failure-threshold:3}")
    private int paymentFailureThreshold;

    @Value("${fraud.score.new-account-hours:24}")
    private int newAccountHours;

    public RiskScoringService(RiskScoreRepository riskScoreRepository,
                              SuspiciousFlagRepository flagRepository,
                              PaymentRecordRepository paymentRecordRepository,
                              UserRepository userRepository) {
        this.riskScoreRepository = riskScoreRepository;
        this.flagRepository = flagRepository;
        this.paymentRecordRepository = paymentRecordRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public RiskScore recalculate(UUID userId) {
        List<SuspiciousFlag> activeFlags = flagRepository.findByUserIdAndResolvedAtIsNull(userId);

        int score = 0;
        StringBuilder notes = new StringBuilder();

        for (SuspiciousFlag flag : activeFlags) {
            int points = pointsForFlag(flag);
            score += points;
            notes.append(flag.getFlagType().name()).append("(+").append(points).append(") ");
        }

        long failureCount = paymentRecordRepository.countByUserIdAndStatus(userId, PaymentStatus.FAILURE);
        if (failureCount >= paymentFailureThreshold) {
            score += paymentFailurePoints;
            notes.append("PAYMENT_FAILURES(+").append(paymentFailurePoints).append(") ");
        }

        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isPresent() && userOpt.get().getCreatedAt() != null) {
            Instant accountAgeCutoff = Instant.now().minus(newAccountHours, ChronoUnit.HOURS);
            if (userOpt.get().getCreatedAt().isAfter(accountAgeCutoff)) {
                score += newAccountPoints;
                notes.append("NEW_ACCOUNT(+").append(newAccountPoints).append(") ");
            }
        }

        score = Math.min(score, 100);
        RiskTier tier = RiskTier.fromScore(score);

        Optional<RiskScore> existing = riskScoreRepository.findById(userId);
        RiskScore riskScore;
        if (existing.isPresent()) {
            riskScore = existing.get();
            riskScore.update(score, tier, activeFlags.size(), notes.toString().trim());
        } else {
            riskScore = new RiskScore(userId, score, tier, activeFlags.size(), notes.toString().trim());
        }
        return riskScoreRepository.save(riskScore);
    }

    public Optional<RiskScore> getScore(UUID userId) {
        return riskScoreRepository.findById(userId);
    }

    public RiskTier getTier(UUID userId) {
        return riskScoreRepository.findById(userId)
                .map(RiskScore::getTier)
                .orElse(RiskTier.LOW);
    }

    // Nightly decay job — re-runs scoring for all users with an active score so
    // time-decay is applied even without new flag activity.
    @Scheduled(cron = "${fraud.score.decay-cron:0 0 2 * * *}")
    @Transactional
    public void runDecaySweep() {
        MDC.put("requestId", "sched-runDecaySweep-" + UUID.randomUUID());
        try {
            riskScoreRepository.findAll().forEach(rs -> recalculate(rs.getUserId()));
        } finally {
            MDC.remove("requestId");
        }
    }

    private int pointsForFlag(SuspiciousFlag flag) {
        int base = switch (flag.getSeverity()) {
            case LOW -> lowSeverityPoints;
            case MEDIUM -> mediumSeverityPoints;
            case HIGH -> highSeverityPoints;
        };
        // Decay: halve points after severity-specific age threshold
        long ageHours = ChronoUnit.HOURS.between(flag.getCreatedAt(), Instant.now());
        int decayThresholdHours = switch (flag.getSeverity()) {
            case LOW -> 7 * 24;
            case MEDIUM -> 3 * 24;
            case HIGH -> 24;
        };
        if (ageHours >= decayThresholdHours) {
            base = base / 2;
        }
        return base;
    }
}
