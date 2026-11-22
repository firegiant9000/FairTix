package com.fairtix.notifications.infrastructure;

import com.fairtix.notifications.application.EmailService;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

public class SmtpEmailService implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailService.class);

    private final JavaMailSender mailSender;
    private final String fromAddress;

    public SmtpEmailService(JavaMailSender mailSender, String fromAddress) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
    }

    @Override
    public void sendEmail(String to, String subject, String htmlBody) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            String requestId = MDC.get("requestId");
            if (requestId != null && !requestId.isBlank()) {
                message.setHeader("X-Request-Id", requestId);
            }
            mailSender.send(message);
            // No recipient and no subject in logs: a support-ticket subject is the user's own
            // text. The requestId in the log pattern ties the line to the request that sent it.
            log.info("Email sent");
        } catch (MessagingException | MailException e) {
            // The exception message can quote the address, so log its type only.
            log.error("Failed to send email error={}", e.getClass().getSimpleName());
            throw new RuntimeException("Email delivery failed", e);
        }
    }
}
