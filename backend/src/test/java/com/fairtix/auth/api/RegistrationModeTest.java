package com.fairtix.auth.api;

import com.fairtix.audit.infrastructure.AuditLogRepository;
import com.fairtix.users.infrastructure.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /auth/register} under each registration mode (RM:323). {@code open} is
 * the test file's setting and is covered by {@link AuthControllerTest}.
 *
 * <p>Red on commit {@code 4bf8642}: every mode let anyone register.
 */
class RegistrationModeTest {

  abstract static class Base {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired AuditLogRepository auditLogRepository;
    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
      mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    ResultActions register(String email) throws Exception {
      return mockMvc.perform(post("/auth/register")
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"email\":\"%s\",\"password\":\"Password123!\"}".formatted(email)));
    }
  }

  @Nested
  @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
  @TestPropertySource(properties = "fairtix.registration.mode=disabled")
  class Disabled extends Base {

    @Test
    void disabled_returns403_noUserRow_auditsBlocked() throws Exception {
      Instant start = Instant.now();
      String email = "closed-" + UUID.randomUUID() + "@test.com";

      register(email)
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("REGISTRATION_CLOSED"));

      assertThat(userRepository.findByEmail(email)).isEmpty();
      assertThat(auditLogRepository.findAll().stream()
          .filter(a -> "REGISTRATION_BLOCKED".equals(a.getAction()))
          .filter(a -> !a.getCreatedAt().isBefore(start)))
          .singleElement()
          .satisfies(a -> {
            assertThat(a.getUserId()).isNull();
            assertThat(a.getDetails()).isEqualTo("mode=DISABLED").doesNotContain(email);
          });
    }
  }

  @Nested
  @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
  @TestPropertySource(properties = {
      "fairtix.registration.mode=allowlist",
      "fairtix.registration.allowlist=Vetted@FairTix.demo"})
  @Transactional
  class Allowlist extends Base {

    @Test
    void allowlist_match_createsVerifiedUser() throws Exception {
      register("vetted@fairtix.demo").andExpect(status().isOk())
          .andExpect(jsonPath("$.emailVerified").value(true));

      assertThat(userRepository.findByEmail("vetted@fairtix.demo"))
          .hasValueSatisfying(u -> assertThat(u.isEmailVerified()).isTrue());
    }

    @Test
    void allowlist_miss_403() throws Exception {
      register("stranger@fairtix.demo")
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.code").value("REGISTRATION_CLOSED"));

      assertThat(userRepository.findByEmail("stranger@fairtix.demo")).isEmpty();
    }
  }
}
