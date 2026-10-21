package com.fairtix.fraud.api;

import com.fairtix.audit.domain.AuditLog;
import com.fairtix.audit.infrastructure.AuditLogRepository;
import com.fairtix.auth.WithMockPrincipal;
import com.fairtix.fraud.application.RiskScoringService;
import com.fairtix.fraud.application.StepUpGateService;
import com.fairtix.fraud.domain.RiskTier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Step-up fails closed while reCAPTCHA is off (D4, RM:319). The test profile runs
 * with {@code auth.recaptcha.enabled=false}, the shipped default.
 *
 * <p>Red on commit {@code RED_COMMIT}: {@code RecaptchaService.assertValidToken}
 * returned early when disabled, so any signed-in user could {@code POST
 * /auth/step-up/verify {}} and be marked verified for 15 minutes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class StepUpFailClosedTest {

  @Autowired private WebApplicationContext context;
  @Autowired private StepUpGateService stepUpGateService;
  @Autowired private AuditLogRepository auditLogRepository;

  @MockitoBean private RiskScoringService riskScoringService;

  private MockMvc mockMvc;
  private UUID userId;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context)
        .apply(springSecurity())
        .build();
    userId = UUID.randomUUID();
  }

  @Test
  void verify_withRecaptchaOff_returns503AndNeverMarksVerified() throws Exception {
    mockMvc.perform(post("/auth/step-up/verify")
            .with(WithMockPrincipal.user(userId, "stepup@test.com"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("STEP_UP_UNAVAILABLE"));

    assertThat(stepUpGateService.isVerified(userId)).isFalse();
    assertThat(auditLogRepository.findByUserIdAndCreatedAtAfter(userId, Instant.EPOCH))
        .extracting(AuditLog::getAction)
        .contains("STEP_UP_VERIFY_UNAVAILABLE")
        .doesNotContain("STEP_UP_VERIFIED", "STEP_UP_VERIFY_SUCCESS");
  }

  @Test
  void verify_withAnyTokenAndRecaptchaOff_stillReturns503() throws Exception {
    mockMvc.perform(post("/auth/step-up/verify")
            .with(WithMockPrincipal.user(userId, "stepup@test.com"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{ \"captchaToken\": \"anything\" }"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("STEP_UP_UNAVAILABLE"));

    assertThat(stepUpGateService.isVerified(userId)).isFalse();
  }

  @Test
  void verify_overlongToken_returns400() throws Exception {
    mockMvc.perform(post("/auth/step-up/verify")
            .with(WithMockPrincipal.user(userId, "stepup@test.com"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{ \"captchaToken\": \"%s\" }".formatted("x".repeat(4097))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void gatedUnverifiedUser_gets428OnCheckout() throws Exception {
    when(riskScoringService.getTier(any())).thenReturn(RiskTier.CRITICAL);

    mockMvc.perform(post("/api/payments/checkout")
            .with(WithMockPrincipal.user(userId, "stepup@test.com"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().is(428))
        .andExpect(jsonPath("$.code").value("STEP_UP_REQUIRED"))
        .andExpect(jsonPath("$.action").value("CHECKOUT"));
  }

  @Test
  void gatedVerifiedUser_passesTheFilter() throws Exception {
    when(riskScoringService.getTier(any())).thenReturn(RiskTier.CRITICAL);
    stepUpGateService.markVerified(userId);

    // An empty body fails PaymentRequest validation, which proves the request got
    // past StepUpFilter to the controller.
    mockMvc.perform(post("/api/payments/checkout")
            .with(WithMockPrincipal.user(userId, "stepup@test.com"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }
}
