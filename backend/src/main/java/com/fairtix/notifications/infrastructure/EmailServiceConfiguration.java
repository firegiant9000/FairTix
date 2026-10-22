package com.fairtix.notifications.infrastructure;

import com.fairtix.notifications.application.EmailService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * SMTP only when {@code fairtix.mail.enabled=true}; otherwise mail is dropped
 * ({@link NoOpEmailService}). Main always sets {@code spring.mail.host}, so it can't be
 * the switch. The lab runs with mail off and no SMTP credentials (RM:323).
 */
@Configuration
public class EmailServiceConfiguration {

    @Bean
    @ConditionalOnProperty(name = "fairtix.mail.enabled", havingValue = "true")
    public EmailService smtpEmailService(JavaMailSender mailSender,
                                         @Value("${app.mail.from}") String fromAddress) {
        return new SmtpEmailService(mailSender, fromAddress);
    }

    @Bean
    @ConditionalOnMissingBean(EmailService.class)
    public EmailService noOpEmailService() {
        return new NoOpEmailService();
    }
}
