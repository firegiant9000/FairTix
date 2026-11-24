package com.fairtix.notifications.infrastructure;

import com.fairtix.notifications.application.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NoOpEmailService implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(NoOpEmailService.class);

    @Override
    public void sendEmail(String to, String subject, String htmlBody) {
        // No subject: a support-ticket subject is the user's own text (see SmtpEmailService).
        // INFO, like SmtpEmailService's "Email sent", so the default log shows mail is off.
        log.info("Email suppressed (fairtix.mail.enabled is off)");
    }
}
