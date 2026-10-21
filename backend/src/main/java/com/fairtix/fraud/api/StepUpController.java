package com.fairtix.fraud.api;

import com.fairtix.audit.application.AuditService;
import com.fairtix.auth.application.RecaptchaService;
import com.fairtix.auth.domain.CustomUserPrincipal;
import com.fairtix.fraud.application.StepUpGateService;
import com.fairtix.fraud.application.StepUpUnavailableException;
import jakarta.validation.Valid;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/auth/step-up")
public class StepUpController {

    private static final String ATTEMPTS_PREFIX = "step_up_attempts:";
    private static final int MAX_ATTEMPTS = 5;
    private static final Duration WINDOW = Duration.ofMinutes(10);

    private final StepUpGateService stepUpGateService;
    private final RecaptchaService recaptchaService;
    private final AuditService auditService;
    private final RedissonClient redissonClient;

    public StepUpController(StepUpGateService stepUpGateService,
                            RecaptchaService recaptchaService,
                            AuditService auditService,
                            RedissonClient redissonClient) {
        this.stepUpGateService = stepUpGateService;
        this.recaptchaService = recaptchaService;
        this.auditService = auditService;
        this.redissonClient = redissonClient;
    }

    @PreAuthorize("isAuthenticated()")
    @PostMapping("/verify")
    public ResponseEntity<Map<String, String>> verify(
            @AuthenticationPrincipal CustomUserPrincipal principal,
            @Valid @RequestBody StepUpVerifyRequest body) {

        UUID userId = principal.getUserId();
        RAtomicLong counter = redissonClient.getAtomicLong(ATTEMPTS_PREFIX + userId);
        long attempts = counter.incrementAndGet();
        if (attempts == 1) {
            counter.expire(WINDOW);
        }
        if (attempts > MAX_ATTEMPTS) {
            auditService.log(userId, "STEP_UP_VERIFY_RATE_LIMITED", "USER", userId,
                    "attempt " + attempts + " exceeds max " + MAX_ATTEMPTS + " per " + WINDOW.toMinutes() + " min");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "Too many verification attempts. Please wait 10 minutes before trying again."));
        }

        // Fail closed (D4): with reCAPTCHA off, assertValidToken accepts anything,
        // so there is nothing to verify against.
        if (!recaptchaService.isEnabled()) {
            auditService.log(userId, "STEP_UP_VERIFY_UNAVAILABLE", "USER", userId,
                    "reCAPTCHA is not configured");
            throw new StepUpUnavailableException();
        }

        String captchaToken = body.captchaToken();
        try {
            recaptchaService.assertValidToken(captchaToken);
            stepUpGateService.markVerified(userId);
            counter.delete();
            auditService.log(userId, "STEP_UP_VERIFY_SUCCESS", "USER", userId, null);
            return ResponseEntity.ok(Map.of("status", "verified"));
        } catch (Exception e) {
            auditService.log(userId, "STEP_UP_VERIFY_FAILURE", "USER", userId, e.getMessage());
            throw e;
        }
    }
}
