package com.fairtix.notifications.application;

import com.fairtix.notifications.domain.NotificationCategory;
import com.fairtix.notifications.domain.NotificationPreference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class NotificationGate {

    private static final Logger log = LoggerFactory.getLogger(NotificationGate.class);

    private final NotificationPreferenceService preferenceService;
    private final EmailService emailService;

    public NotificationGate(NotificationPreferenceService preferenceService, EmailService emailService) {
        this.preferenceService = preferenceService;
        this.emailService = emailService;
    }

    public boolean shouldSend(UUID userId, NotificationCategory category) {
        if (category.isTransactional()) {
            return true;
        }
        if (userId == null) {
            logSuppressed(null, category, "missing-userId");
            return false;
        }
        NotificationPreference prefs = preferenceService.getPreferences(userId);
        boolean enabled = category.isEmailEnabledFor(prefs);
        if (!enabled) {
            logSuppressed(userId, category, "user-opt-out");
        }
        return enabled;
    }

    public void sendEmail(UUID userId, NotificationCategory category, String to, String subject, String body) {
        if (!shouldSend(userId, category)) {
            return;
        }
        emailService.sendEmail(to, subject, body);
    }

    private void logSuppressed(UUID userId, NotificationCategory category, String reason) {
        log.info("Notification suppressed requestId={} userId={} category={} suppressed=true reason={}",
                MDC.get("requestId"), userId, category, reason);
    }
}
