package com.fairtix.notifications;

import com.fairtix.notifications.application.EmailService;
import com.fairtix.notifications.infrastructure.EmailServiceConfiguration;
import com.fairtix.notifications.infrastructure.NoOpEmailService;
import com.fairtix.notifications.infrastructure.SmtpEmailService;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Outbound mail is off unless {@code fairtix.mail.enabled=true}, SMTP calls are
 * bounded, and logs carry no recipient address (RM:323).
 *
 * <p>Red on commit {@code <red>}: {@code SmtpEmailService} was selected whenever
 * {@code spring.mail.host} had any value, and main defaults it to {@code localhost},
 * so every deploy tried to send. JavaMail's default timeouts are infinite, and every
 * send logged the recipient.
 */
@ExtendWith(OutputCaptureExtension.class)
class MailConfigTest {

  private static final Path RESOURCES = Path.of("src/main/resources");

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withUserConfiguration(EmailServiceConfiguration.class)
      .withBean(JavaMailSender.class, () -> mock(JavaMailSender.class))
      .withPropertyValues("spring.mail.host=localhost", "app.mail.from=noreply@test.com");

  @Test
  void noFlag_selectsNoOp() {
    runner.run(ctx -> assertThat(ctx.getBean(EmailService.class)).isInstanceOf(NoOpEmailService.class));
  }

  @Test
  void flagFalse_selectsNoOp() {
    runner.withPropertyValues("fairtix.mail.enabled=false")
        .run(ctx -> assertThat(ctx.getBean(EmailService.class)).isInstanceOf(NoOpEmailService.class));
  }

  @Test
  void flagTrue_selectsSmtp() {
    runner.withPropertyValues("fairtix.mail.enabled=true")
        .run(ctx -> assertThat(ctx.getBean(EmailService.class)).isInstanceOf(SmtpEmailService.class));
  }

  @Test
  void smtpTimeoutsSetInMainFile() throws IOException {
    Properties main = load("application.properties");
    for (String key : new String[] {"connectiontimeout", "timeout", "writetimeout"}) {
      String value = main.getProperty("spring.mail.properties.mail.smtp." + key);
      assertThat(value).as(key).isNotNull();
      assertThat(Integer.parseInt(value)).as(key).isBetween(1, 10_000);
    }
  }

  @Test
  void mainAndProd_mailDefaultOff_devAndStagingOn() throws IOException {
    assertThat(load("application.properties").getProperty("fairtix.mail.enabled"))
        .isEqualTo("${MAIL_ENABLED:false}");
    assertThat(load("application-prod.properties").getProperty("fairtix.mail.enabled")).isNull();
    assertThat(load("application-dev.properties").getProperty("fairtix.mail.enabled"))
        .isEqualTo("true");
    assertThat(load("application-staging.properties").getProperty("fairtix.mail.enabled"))
        .isEqualTo("${MAIL_ENABLED:true}");
  }

  @Test
  void sendLog_omitsRecipient(CapturedOutput output) {
    JavaMailSender sender = mock(JavaMailSender.class);
    when(sender.createMimeMessage()).thenAnswer(inv -> new MimeMessage((Session) null));
    SmtpEmailService service = new SmtpEmailService(sender, "noreply@test.com");

    service.sendEmail("someone.private@example.com", "Hello", "<p>hi</p>");
    doThrow(new MailSendException("refused")).when(sender).send(any(MimeMessage.class));
    assertThatThrownBy(() -> service.sendEmail("other.private@example.com", "Hello", "<p>hi</p>"))
        .isInstanceOf(RuntimeException.class);

    assertThat(output.getAll())
        .contains("Email sent")
        .contains("Failed to send email")
        .doesNotContain("someone.private@example.com")
        .doesNotContain("other.private@example.com");
  }

  private static Properties load(String file) throws IOException {
    Properties props = new Properties();
    try (Reader reader = Files.newBufferedReader(RESOURCES.resolve(file))) {
      props.load(reader);
    }
    return props;
  }
}
