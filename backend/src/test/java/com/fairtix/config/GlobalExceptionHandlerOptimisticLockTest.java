package com.fairtix.config;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fairtix.inventory.domain.Seat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A {@code Seat.@Version} conflict (for example two checkouts, or a checkout and
 * the hold sweep, updating the same seat) reaches clients as 409, not 500.
 */
class GlobalExceptionHandlerOptimisticLockTest {

  @Test
  void optimisticLockConflict_mapsTo409() throws Exception {
    MockMvc mockMvc = MockMvcBuilders
        .standaloneSetup(new ConflictingController())
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();

    mockMvc.perform(post("/test/optimistic-lock"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"))
        .andExpect(jsonPath("$.message").value(not(containsString("com.fairtix"))));
  }

  @RestController
  static class ConflictingController {
    @PostMapping("/test/optimistic-lock")
    void update() {
      throw new ObjectOptimisticLockingFailureException(Seat.class, UUID.randomUUID());
    }
  }
}
