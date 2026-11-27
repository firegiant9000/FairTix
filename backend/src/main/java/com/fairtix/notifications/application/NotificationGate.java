package com.fairtix.notifications.application;

import com.fairtix.notifications.domain.NotificationCategory;
import com.fairtix.notifications.domain.NotificationPreference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class NotificationGate {

    private static final Logger log = LoggerFactory.getLogger(NotificationGate.class);

    private final NotificationPreferenceService preferenceService;
    private final EmailService emailService;
    private final TaskExecutor mailExecutor;
    private final TaskExecutor bulkMailExecutor;

    @Autowired
    public NotificationGate(NotificationPreferenceService preferenceService, EmailService emailService,
            @Qualifier("mailExecutor") TaskExecutor mailExecutor,
            @Qualifier("bulkMailExecutor") TaskExecutor bulkMailExecutor) {
        this.preferenceService = preferenceService;
        this.emailService = emailService;
        this.mailExecutor = mailExecutor;
        this.bulkMailExecutor = bulkMailExecutor;
    }

    /** Sends on the calling thread; for unit tests. */
    NotificationGate(NotificationPreferenceService preferenceService, EmailService emailService) {
        this(preferenceService, emailService, new SyncTaskExecutor(), new SyncTaskExecutor());
    }

    /**
     * Event cancellation mails every ticket holder at once; it goes to its own pool so a
     * large cancellation can't delay one-recipient mail such as a password reset.
     */
    static boolean isBulk(NotificationCategory category) {
        return category == NotificationCategory.EVENT_CANCELLED;
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

    /**
     * Checks the preference on the calling thread, then hands the send to the mail executor
     * (or the bulk one, see {@link #isBulk}; {@code fairtix.mail.async}, on by default), so
     * the SMTP round trip doesn't hold the
     * caller's request thread or its pooled database connection. A failed send is logged here
     * with the exception type only (its message can quote the address) and not rethrown: by
     * then the caller may have returned. A full queue runs the send on the calling thread.
     */
    public void sendEmail(UUID userId, NotificationCategory category, String to, String subject, String body) {
        if (!shouldSend(userId, category)) {
            return;
        }
        (isBulk(category) ? bulkMailExecutor : mailExecutor).execute(() -> {
            try {
                emailService.sendEmail(to, subject, body);
            } catch (RuntimeException e) {
                log.warn("Failed to send notification email requestId={} userId={} category={} error={}",
                        MDC.get("requestId"), userId, category, e.getClass().getSimpleName());
                return;
            }
            // "Handed to", not "sent": with mail off the EmailService drops it. The EmailService
            // logs what actually happened ("Email sent" or "Email suppressed").
            log.info("Notification handed to mail service requestId={} userId={} category={}",
                    MDC.get("requestId"), userId, category);
        });
    }

    private void logSuppressed(UUID userId, NotificationCategory category, String reason) {
        log.info("Notification suppressed requestId={} userId={} category={} suppressed=true reason={}",
                MDC.get("requestId"), userId, category, reason);
    }
}
