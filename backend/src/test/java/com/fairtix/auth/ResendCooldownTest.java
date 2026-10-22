package com.fairtix.auth;

import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /auth/resend-verification} sends at most one email per account per
 * 60 s; the per-IP limit alone allowed 10 a minute to the same inbox.
 *
 * <p>Red on commit {@code <red>}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Transactional
class ResendCooldownTest {

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void resend_withinCooldown_sendsNothing() throws Exception {
    MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    User u = new User();
    u.setEmail("resend-" + UUID.randomUUID() + "@test.com");
    u.setPassword("x");
    u = userRepository.saveAndFlush(u);

    for (int i = 0; i < 3; i++) {
      mockMvc.perform(post("/auth/resend-verification")
              .with(WithMockPrincipal.user(u.getId(), u.getEmail())))
          .andExpect(status().isNoContent());
    }

    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM email_verification_tokens WHERE user_id = ?", Integer.class, u.getId()))
        .isEqualTo(1);
  }
}
