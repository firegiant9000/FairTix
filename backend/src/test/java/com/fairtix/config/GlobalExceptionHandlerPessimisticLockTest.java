package com.fairtix.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A PostgreSQL deadlock ({@code 40P01}) or lock failure reaches clients as a retryable
 * 409, not 500 (#317). Spring translates a deadlock to a
 * {@link PessimisticLockingFailureException}, typically {@link CannotAcquireLockException};
 * PostgreSQL has already rolled the transaction back, so nothing was changed.
 *
 * <p>Red before the handler: the catch-all answered 500 {@code INTERNAL_ERROR}.
 */
class GlobalExceptionHandlerPessimisticLockTest {

  private final MockMvc mockMvc = MockMvcBuilders
      .standaloneSetup(new LockingController())
      .setControllerAdvice(new GlobalExceptionHandler())
      .build();

  @Test
  void deadlock_mapsToRetryable409() throws Exception {
    mockMvc.perform(post("/test/deadlock"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"))
        .andExpect(jsonPath("$.message").value(not(containsString("deadlock"))))
        .andExpect(jsonPath("$.message").value(not(containsString("com.fairtix"))));
  }

  @Test
  void otherPessimisticLockFailure_mapsToRetryable409() throws Exception {
    mockMvc.perform(post("/test/pessimistic"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
  }

  /**
   * A lock taken with the {@code EntityManager} directly in a service (a locking
   * refresh, say) is not translated by Spring: it reaches the handler as the raw JPA
   * exception. Review finding on #317.
   */
  @Test
  void rawJpaPessimisticLockException_mapsToRetryable409() throws Exception {
    mockMvc.perform(post("/test/jpa-pessimistic"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
  }

  @Test
  void rawJpaLockTimeoutException_mapsToRetryable409() throws Exception {
    mockMvc.perform(post("/test/jpa-lock-timeout"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
  }

  @Test
  void deadlock_logsTheSqlStateAtWarn() throws Exception {
    assertThat(warnLineFor("/test/deadlock")).contains("sqlState=40P01");
  }

  /** Hibernate's usual shape: JPA exception, then a JDBC wrapper, then the SQLException. */
  @Test
  void sqlStateTwoCausesDown_isLogged() throws Exception {
    assertThat(warnLineFor("/test/jpa-wrapped")).contains("sqlState=40P01");
  }

  @Test
  void noSqlException_logsNullSqlState() throws Exception {
    assertThat(warnLineFor("/test/pessimistic")).contains("sqlState=null");
  }

  private String warnLineFor(String path) throws Exception {
    Logger handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    ListAppender<ILoggingEvent> logs = new ListAppender<>();
    logs.start();
    handlerLogger.addAppender(logs);
    try {
      mockMvc.perform(post(path)).andExpect(status().isConflict());
    } finally {
      handlerLogger.detachAppender(logs);
    }
    assertThat(logs.list).singleElement().satisfies(event -> {
      assertThat(event.getLevel()).isEqualTo(Level.WARN);
      assertThat(event.getThrowableProxy()).isNull();
    });
    return logs.list.get(0).getFormattedMessage();
  }

  @RestController
  static class LockingController {
    @PostMapping("/test/jpa-pessimistic")
    void jpaPessimistic() {
      throw new jakarta.persistence.PessimisticLockException("could not obtain lock",
          new SQLException("ERROR: deadlock detected", "40P01"));
    }

    @PostMapping("/test/jpa-wrapped")
    void jpaWrapped() {
      throw new jakarta.persistence.PessimisticLockException("could not obtain lock",
          new RuntimeException("JDBC wrapper",
              new SQLException("ERROR: deadlock detected", "40P01")));
    }

    @PostMapping("/test/jpa-lock-timeout")
    void jpaLockTimeout() {
      throw new jakarta.persistence.LockTimeoutException("lock timeout");
    }

    @PostMapping("/test/deadlock")
    void deadlock() {
      throw new CannotAcquireLockException("could not execute statement",
          new SQLException("ERROR: deadlock detected", "40P01"));
    }

    @PostMapping("/test/pessimistic")
    void pessimistic() {
      throw new PessimisticLockingFailureException("lock failure");
    }
  }
}
