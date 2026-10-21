package com.fairtix.config;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fairtix.payments.application.PaymentRetryConflictException;
import com.fairtix.refunds.application.RefundProviderException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Client-facing status codes for the Stripe idempotency failure modes (exposure gate (b)). */
class GlobalExceptionHandlerPaymentsTest {

  private final MockMvc mockMvc = MockMvcBuilders
      .standaloneSetup(new FailingController())
      .setControllerAdvice(new GlobalExceptionHandler())
      .build();

  @Test
  void retryConflict_mapsTo409() throws Exception {
    mockMvc.perform(post("/test/retry-conflict"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PAYMENT_RETRY_CONFLICT"))
        .andExpect(jsonPath("$.message").value(not(containsString("idempotent"))));
  }

  @Test
  void refundProviderError_mapsTo502() throws Exception {
    mockMvc.perform(post("/test/refund-provider"))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.code").value("REFUND_PROVIDER_ERROR"));
  }

  @RestController
  static class FailingController {
    @PostMapping("/test/refund-provider")
    void provider() {
      throw new RefundProviderException("The payment provider could not process this refund.",
          new RuntimeException("network"));
    }

    @PostMapping("/test/retry-conflict")
    void conflict() {
      throw new PaymentRetryConflictException("Stripe rejected a retry: Keys for idempotent requests",
          null);
    }
  }
}
