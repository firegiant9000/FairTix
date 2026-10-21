package com.fairtix.auth.api;

import com.fairtix.audit.domain.AuditLog;
import com.fairtix.audit.infrastructure.AuditLogRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests for login attempt lockout and password strength validation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Transactional
class LoginAttemptTest {

  @Autowired private WebApplicationContext context;
  @Autowired private AuditLogRepository auditLogRepository;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context)
        .apply(springSecurity())
        .build();
  }

  // -------------------------------------------------------------------------
  // Password Strength Validation
  // -------------------------------------------------------------------------

  @ParameterizedTest(name = "{2}")
  @CsvSource({
      "weak1@test.com, Ab1!,       too short",
      "weak2@test.com, abcdefg1!,  no uppercase",
      "weak3@test.com, ABCDEFG1!,  no lowercase",
      "weak4@test.com, Abcdefgh!,  no digit",
      "weak5@test.com, Abcdefg1,   no special char",
  })
  void register_weakPassword_returns400(String email, String password, String description) throws Exception {
    String body = """
        { "email": "%s", "password": "%s" }
        """.formatted(email, password);
    mockMvc.perform(post("/auth/register")
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("WEAK_PASSWORD"));
  }

  @Test
  void register_strongPassword_succeeds() throws Exception {
    String body = """
        { "email": "strong@test.com", "password": "StrongP4ss!" }
        """;
    mockMvc.perform(post("/auth/register")
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value("strong@test.com"))
        .andExpect(jsonPath("$.role").value("USER"));
  }

  // -------------------------------------------------------------------------
  // Login Attempt Lockout
  // -------------------------------------------------------------------------

  @Test
  void login_lockoutAfterMaxAttempts() throws Exception {
    // Register a user first
    String regBody = """
        { "email": "lockout@test.com", "password": "StrongP4ss!" }
        """;
    mockMvc.perform(post("/auth/register")
            .contentType(MediaType.APPLICATION_JSON).content(regBody))
        .andExpect(status().isOk());

    // Fail 5 times with wrong password
    String badLogin = """
        { "email": "lockout@test.com", "password": "wrongpassword" }
        """;
    for (int i = 0; i < 5; i++) {
      mockMvc.perform(post("/auth/login")
              .contentType(MediaType.APPLICATION_JSON).content(badLogin))
          .andExpect(status().isUnauthorized());
    }

    // 6th attempt should be locked out (429)
    mockMvc.perform(post("/auth/login")
            .contentType(MediaType.APPLICATION_JSON).content(badLogin))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"))
        .andExpect(jsonPath("$.remainingSeconds").isNumber());
  }

  @Test
  void login_successfulLoginResetsAttempts() throws Exception {
    // Register
    String regBody = """
        { "email": "reset@test.com", "password": "StrongP4ss!" }
        """;
    mockMvc.perform(post("/auth/register")
            .contentType(MediaType.APPLICATION_JSON).content(regBody))
        .andExpect(status().isOk());

    // Fail 3 times
    String badLogin = """
        { "email": "reset@test.com", "password": "wrongpassword" }
        """;
    for (int i = 0; i < 3; i++) {
      mockMvc.perform(post("/auth/login")
              .contentType(MediaType.APPLICATION_JSON).content(badLogin))
          .andExpect(status().isUnauthorized());
    }

    // Successful login resets
    String goodLogin = """
        { "email": "reset@test.com", "password": "StrongP4ss!" }
        """;
    mockMvc.perform(post("/auth/login")
            .contentType(MediaType.APPLICATION_JSON).content(goodLogin))
        .andExpect(status().isOk());

    // Can fail again without being locked
    mockMvc.perform(post("/auth/login")
            .contentType(MediaType.APPLICATION_JSON).content(badLogin))
        .andExpect(status().isUnauthorized());
  }

  // -------------------------------------------------------------------------
  // Audit events (red on commit RED_COMMIT: no LOGIN_FAILED or ACCOUNT_LOCKED
  // event existed). Audit writes are REQUIRES_NEW, so they commit even though
  // this test class rolls back; each test filters by its own user or start time.
  // -------------------------------------------------------------------------

  @Test
  void login_wrongPassword_auditsLoginFailedWithUserIdAndNoCredentials() throws Exception {
    UUID userId = register("audit-fail@test.com");

    mockMvc.perform(post("/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{ \"email\": \"audit-fail@test.com\", \"password\": \"Wrong-P4ss!\" }"))
        .andExpect(status().isUnauthorized());

    List<AuditLog> failures = auditFor(userId, "LOGIN_FAILED");
    assertThat(failures).hasSize(1);
    assertThat(failures.get(0).getResourceId()).isEqualTo(userId);
    assertNoCredentials(failures, "audit-fail@test.com", "Wrong-P4ss!");
  }

  @Test
  void login_unknownEmail_auditsLoginFailedWithNullUser() throws Exception {
    Instant start = Instant.now();

    mockMvc.perform(post("/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{ \"email\": \"nobody-audit@test.com\", \"password\": \"Wrong-P4ss!\" }"))
        .andExpect(status().isUnauthorized());

    List<AuditLog> failures = auditLogRepository.findAll().stream()
        .filter(a -> "LOGIN_FAILED".equals(a.getAction()))
        .filter(a -> a.getUserId() == null)
        .filter(a -> !a.getCreatedAt().isBefore(start))
        .toList();
    assertThat(failures).isNotEmpty();
    assertNoCredentials(failures, "nobody-audit@test.com", "Wrong-P4ss!");
  }

  @Test
  void login_lockout_auditsAccountLockedOnce() throws Exception {
    UUID userId = register("audit-lock@test.com");
    String badLogin = """
        { "email": "audit-lock@test.com", "password": "Wrong-P4ss!" }
        """;
    for (int i = 0; i < 5; i++) {
      mockMvc.perform(post("/auth/login")
              .contentType(MediaType.APPLICATION_JSON).content(badLogin))
          .andExpect(status().isUnauthorized());
    }
    // Rejected while locked: no further LOGIN_FAILED or ACCOUNT_LOCKED.
    mockMvc.perform(post("/auth/login")
            .contentType(MediaType.APPLICATION_JSON).content(badLogin))
        .andExpect(status().isTooManyRequests());

    assertThat(auditFor(userId, "LOGIN_FAILED")).hasSize(5);
    List<AuditLog> locked = auditFor(userId, "ACCOUNT_LOCKED");
    assertThat(locked).hasSize(1);
    assertNoCredentials(locked, "audit-lock@test.com", "Wrong-P4ss!");
  }

  private UUID register(String email) throws Exception {
    MvcResult result = mockMvc.perform(post("/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{ \"email\": \"%s\", \"password\": \"StrongP4ss!\" }".formatted(email)))
        .andExpect(status().isOk())
        .andReturn();
    return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.userId"));
  }

  private List<AuditLog> auditFor(UUID userId, String action) {
    return auditLogRepository.findByUserIdAndCreatedAtAfter(userId, Instant.EPOCH).stream()
        .filter(a -> action.equals(a.getAction()))
        .toList();
  }

  private static void assertNoCredentials(List<AuditLog> rows, String email, String password) {
    assertThat(rows).allSatisfy(a -> {
      String details = a.getDetails() == null ? "" : a.getDetails();
      assertThat(details).doesNotContainIgnoringCase(email).doesNotContain(password);
    });
  }
}
