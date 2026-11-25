package com.fairtix.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fairtix.notifications.infrastructure.NoOpEmailService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class NoOpEmailServiceTest {

  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
  private Logger logger;

  @BeforeEach
  void attach() {
    logger = (Logger) LoggerFactory.getLogger(NoOpEmailService.class);
    logger.setLevel(Level.TRACE); // capture every level, so the assertion checks which one
    logs.start();
    logger.addAppender(logs);
  }

  @AfterEach
  void detach() {
    logger.detachAppender(logs);
    logger.setLevel(null);
  }

  @Test
  void suppressedMail_isVisibleAtInfo_withoutAddressOrSubject() {
    new NoOpEmailService().sendEmail("alice@example.com", "Your order is confirmed", "<p>Thanks</p>");

    assertThat(logs.list).hasSize(1);
    ILoggingEvent line = logs.list.get(0);
    assertThat(line.getLevel()).isEqualTo(Level.INFO);
    assertThat(line.getFormattedMessage())
        .contains("Email suppressed")
        .doesNotContain("alice@example.com")
        .doesNotContain("Your order is confirmed");
  }
}
