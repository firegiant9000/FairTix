package com.fairtix.config;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
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

  @RestController
  static class LockingController {
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
