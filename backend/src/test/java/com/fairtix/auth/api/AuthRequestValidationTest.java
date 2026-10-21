package com.fairtix.auth.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Bean Validation on the auth request bodies (RM:318): malformed credentials are
 * a 400 {@code VALIDATION_ERROR} before they reach the lockout counter or BCrypt.
 *
 * <p>Red on commit {@code RED_COMMIT}: with no constraints and no {@code @Valid},
 * a null login email threw an NPE in {@code LoginAttemptService} (500), an invalid
 * email registered, and over-long passwords reached BCrypt.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Transactional
class AuthRequestValidationTest {

  private static final String LONG_PASSWORD = "Aa1!" + "x".repeat(69); // 73 chars

  @Autowired private WebApplicationContext context;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context)
        .apply(springSecurity())
        .build();
  }

  @Test
  void login_nullEmail_returns400() throws Exception {
    mockMvc.perform(post("/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{ \"password\": \"whatever1!\" }"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void login_blankPassword_returns400() throws Exception {
    mockMvc.perform(post("/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{ \"email\": \"someone@test.com\", \"password\": \"\" }"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void login_overlongPassword_returns400WithoutEchoingIt() throws Exception {
    mockMvc.perform(post("/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{ \"email\": \"someone@test.com\", \"password\": \"%s\" }"
                .formatted(LONG_PASSWORD)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.message").value(not(containsString(LONG_PASSWORD))));
  }

  @Test
  void register_invalidEmail_returns400() throws Exception {
    mockMvc.perform(post("/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{ \"email\": \"not-an-email\", \"password\": \"StrongP4ss!\" }"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void register_overlongEmail_returns400() throws Exception {
    String email = "a".repeat(250) + "@t.co"; // 255 chars
    mockMvc.perform(post("/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{ \"email\": \"%s\", \"password\": \"StrongP4ss!\" }".formatted(email)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void register_73CharPassword_returns400() throws Exception {
    mockMvc.perform(post("/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{ \"email\": \"long@test.com\", \"password\": \"%s\" }"
                .formatted(LONG_PASSWORD)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.message").value(not(containsString(LONG_PASSWORD))));
  }

  @Test
  void register_72CharPassword_succeeds() throws Exception {
    String password = "Aa1!" + "x".repeat(68);
    mockMvc.perform(post("/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{ \"email\": \"max@test.com\", \"password\": \"%s\" }".formatted(password)))
        .andExpect(status().isOk());
  }

  @Test
  void forgotPassword_invalidEmail_returns400() throws Exception {
    mockMvc.perform(post("/auth/forgot-password")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{ \"email\": \"not-an-email\" }"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void resetPassword_missingToken_returns400() throws Exception {
    mockMvc.perform(post("/auth/reset-password")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{ \"newPassword\": \"StrongP4ss!\" }"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void resetPassword_73CharPassword_returns400() throws Exception {
    mockMvc.perform(post("/auth/reset-password")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{ \"token\": \"%s\", \"newPassword\": \"%s\" }"
                .formatted(java.util.UUID.randomUUID(), LONG_PASSWORD)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  /** Review Focus 5: a malformed JSON body is a 400, not a 500. */
  @Test
  void login_malformedJson_returns400MalformedRequest() throws Exception {
    mockMvc.perform(post("/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{ \"email\": "))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
  }
}
