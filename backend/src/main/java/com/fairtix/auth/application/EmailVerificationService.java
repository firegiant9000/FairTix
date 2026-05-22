package com.fairtix.auth.application;

import com.fairtix.auth.domain.EmailVerificationToken;
import com.fairtix.auth.infrastructure.EmailVerificationTokenRepository;
import com.fairtix.notifications.application.EmailTemplateService;
import com.fairtix.notifications.application.NotificationGate;
import com.fairtix.notifications.domain.NotificationCategory;
import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
public class EmailVerificationService {

    private static final int TOKEN_EXPIRY_HOURS = 24;
    private static final int MAX_ACTIVE_TOKENS = 3;

    private final EmailVerificationTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final NotificationGate notificationGate;
    private final EmailTemplateService emailTemplateService;
    private final String baseUrl;

    public EmailVerificationService(
            EmailVerificationTokenRepository tokenRepository,
            UserRepository userRepository,
            NotificationGate notificationGate,
            EmailTemplateService emailTemplateService,
            @Value("${app.base-url}") String baseUrl) {
        this.tokenRepository = tokenRepository;
        this.userRepository = userRepository;
        this.notificationGate = notificationGate;
        this.emailTemplateService = emailTemplateService;
        this.baseUrl = baseUrl;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendVerificationEmail(User user) {
        // Enforce max active tokens per user — delete oldest if at cap
        List<EmailVerificationToken> existing = tokenRepository.findByUserIdOrderByCreatedAtAsc(user.getId());
        while (existing.size() >= MAX_ACTIVE_TOKENS) {
            tokenRepository.delete(existing.remove(0));
        }

        EmailVerificationToken token = new EmailVerificationToken();
        token.setUserId(user.getId());
        token.setToken(UUID.randomUUID().toString());
        token.setExpiresAt(Instant.now().plus(TOKEN_EXPIRY_HOURS, ChronoUnit.HOURS));
        tokenRepository.save(token);

        String link = baseUrl + "/verify?token=" + token.getToken();
        String html = emailTemplateService.buildVerificationEmail(user.getEmail(), link);
        notificationGate.sendEmail(user.getId(), NotificationCategory.ACCOUNT_VERIFICATION,
                user.getEmail(), "Verify your FairTix account", html);
    }

    @Transactional
    public void verifyToken(String tokenValue) {
        EmailVerificationToken token = tokenRepository.findByToken(tokenValue)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid verification token"));

        if (token.isUsed()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Verification token already used");
        }
        if (token.isExpired()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Verification token has expired");
        }

        User user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        user.setEmailVerified(true);
        userRepository.save(user);

        token.setUsedAt(Instant.now());
        tokenRepository.save(token);
    }
}
