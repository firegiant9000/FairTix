package com.fairtix.auth.application;

import com.fairtix.audit.application.AuditService;
import com.fairtix.auth.domain.PasswordResetToken;
import com.fairtix.auth.infrastructure.PasswordResetTokenRepository;
import com.fairtix.notifications.application.EmailTemplateService;
import com.fairtix.notifications.application.NotificationGate;
import com.fairtix.notifications.domain.NotificationCategory;
import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    private static final int TOKEN_EXPIRY_HOURS = 1;
    private static final int RATE_LIMIT_MAX = 3;
    private static final Duration RATE_LIMIT_WINDOW = Duration.ofMinutes(15);
    private static final String RATE_KEY_PREFIX = "pwd_reset_rate:";

    private static final Pattern UPPERCASE = Pattern.compile("[A-Z]");
    private static final Pattern LOWERCASE = Pattern.compile("[a-z]");
    private static final Pattern DIGIT = Pattern.compile("[0-9]");
    private static final Pattern SPECIAL = Pattern.compile("[^a-zA-Z0-9]");
    private static final int MIN_PASSWORD_LENGTH = 8;

    private final PasswordResetTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final NotificationGate notificationGate;
    private final EmailTemplateService emailTemplateService;
    private final LoginAttemptService loginAttemptService;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final RedissonClient redissonClient;
    private final String baseUrl;

    public PasswordResetService(
            PasswordResetTokenRepository tokenRepository,
            UserRepository userRepository,
            NotificationGate notificationGate,
            EmailTemplateService emailTemplateService,
            LoginAttemptService loginAttemptService,
            PasswordEncoder passwordEncoder,
            AuditService auditService,
            RedissonClient redissonClient,
            @Value("${app.base-url}") String baseUrl) {
        this.tokenRepository = tokenRepository;
        this.userRepository = userRepository;
        this.notificationGate = notificationGate;
        this.emailTemplateService = emailTemplateService;
        this.loginAttemptService = loginAttemptService;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.redissonClient = redissonClient;
        this.baseUrl = baseUrl;
    }

    /**
     * Initiates a password reset for the given email.
     * Always returns without error — never reveals whether the email is registered.
     */
    @Transactional
    public void requestReset(String email) {
        checkRateLimit(email);

        User user = userRepository.findByEmail(email).orElse(null);

        // Return silently if user doesn't exist or is deleted — don't leak email existence
        if (user == null || user.isDeleted()) {
            log.debug("Password reset requested for unknown or deleted email={}", email);
            return;
        }

        // Invalidate any existing tokens for this user — one active token at a time
        tokenRepository.deleteByUserId(user.getId());

        PasswordResetToken token = new PasswordResetToken();
        token.setUserId(user.getId());
        token.setToken(UUID.randomUUID().toString());
        token.setExpiresAt(Instant.now().plus(TOKEN_EXPIRY_HOURS, ChronoUnit.HOURS));
        tokenRepository.save(token);

        String link = baseUrl + "/reset-password?token=" + token.getToken();
        String html = emailTemplateService.buildPasswordResetEmail(user.getEmail(), link);

        try {
            notificationGate.sendEmail(user.getId(), NotificationCategory.PASSWORD_RESET,
                    user.getEmail(), "Reset your FairTix password", html);
        } catch (Exception e) {
            log.error("Failed to send password reset email to={} error={}", user.getEmail(), e.getMessage());
            // Don't fail the request — token is saved, user can retry
        }

        try {
            auditService.log(user.getId(), "PASSWORD_RESET_REQUESTED", "USER", user.getId(), null);
        } catch (Exception e) {
            log.warn("Failed to write audit log for password reset request userId={}", user.getId());
        }
    }

    /**
     * Resets the user's password using a valid reset token.
     */
    @Transactional
    public void resetPassword(String tokenValue, String newPassword) {
        PasswordResetToken token = tokenRepository.findByToken(tokenValue)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired reset token"));

        if (token.isUsed()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Reset token has already been used");
        }
        if (token.isExpired()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Reset token has expired");
        }

        validatePasswordStrength(newPassword);

        User user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        token.setUsedAt(Instant.now());
        tokenRepository.save(token);

        // Clear any active login lockout so the user can log in immediately
        loginAttemptService.resetAttempts(user.getEmail());

        try {
            auditService.log(user.getId(), "PASSWORD_RESET_COMPLETED", "USER", user.getId(), null);
        } catch (Exception e) {
            log.warn("Failed to write audit log for password reset completion userId={}", user.getId());
        }
    }

    private void checkRateLimit(String email) {
        String key = RATE_KEY_PREFIX + email.toLowerCase();
        RAtomicLong counter = redissonClient.getAtomicLong(key);
        long count = counter.incrementAndGet();
        if (count == 1) {
            counter.expire(RATE_LIMIT_WINDOW);
        }
        if (count > RATE_LIMIT_MAX) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many password reset requests. Please try again later.");
        }
    }

    private void validatePasswordStrength(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new WeakPasswordException("Password must be at least " + MIN_PASSWORD_LENGTH + " characters long");
        }
        if (!UPPERCASE.matcher(password).find()) {
            throw new WeakPasswordException("Password must contain at least one uppercase letter");
        }
        if (!LOWERCASE.matcher(password).find()) {
            throw new WeakPasswordException("Password must contain at least one lowercase letter");
        }
        if (!DIGIT.matcher(password).find()) {
            throw new WeakPasswordException("Password must contain at least one digit");
        }
        if (!SPECIAL.matcher(password).find()) {
            throw new WeakPasswordException("Password must contain at least one special character");
        }
    }
}
