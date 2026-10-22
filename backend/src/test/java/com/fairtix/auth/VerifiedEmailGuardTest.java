package com.fairtix.auth;

import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A verified email is required to create a payment intent, start a ticket transfer
 * or create an organization (RM:323). Checkout already required one.
 *
 * <p>Red on commit {@code <red>}: only checkout checked, so an unverified account
 * could extend holds through the intent, mail transfer offers and open organizations.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Transactional
class VerifiedEmailGuardTest {

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  private User user(boolean verified) {
    User u = new User();
    u.setEmail("guard-" + UUID.randomUUID() + "@test.com");
    u.setPassword("x");
    u.setEmailVerified(verified);
    return userRepository.saveAndFlush(u);
  }

  private ResultActions intent(User u) throws Exception {
    return mockMvc.perform(post("/api/payments/intent")
        .with(WithMockPrincipal.user(u.getId(), u.getEmail()))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"holdIds\":[\"%s\"]}".formatted(UUID.randomUUID())));
  }

  private ResultActions transfer(User u) throws Exception {
    return mockMvc.perform(post("/api/tickets/{id}/transfer", UUID.randomUUID())
        .with(WithMockPrincipal.user(u.getId(), u.getEmail()))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"toEmail\":\"friend@test.com\"}"));
  }

  private ResultActions createOrg(User u) throws Exception {
    return mockMvc.perform(post("/api/organizations")
        .with(WithMockPrincipal.user(u.getId(), u.getEmail()))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"name\":\"Org %s\",\"contactEmail\":\"org@test.com\"}"
            .formatted(UUID.randomUUID())));
  }

  @Test
  void unverified_intent_403() throws Exception {
    // Stripe is off in tests, so a verified user would get 501: the guard runs first.
    intent(user(false))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value(
            "Email address not verified. Please verify your email before purchasing tickets."));
  }

  @Test
  void unverified_transfer_403() throws Exception {
    transfer(user(false))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value(
            "Email address not verified. Please verify your email before transferring tickets."));
  }

  @Test
  void unverified_createOrg_403() throws Exception {
    createOrg(user(false))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value(
            "Email address not verified. Please verify your email before creating an organization."));
  }

  @Test
  void verified_paths_unchanged() throws Exception {
    User u = user(true);
    intent(u).andExpect(status().isNotImplemented());
    assertThat(transfer(u).andReturn().getResponse().getStatus()).isNotEqualTo(403);
    createOrg(u).andExpect(status().isOk());
  }

  @Test
  void unknownUser_403() throws Exception {
    mockMvc.perform(post("/api/organizations")
            .with(WithMockPrincipal.user(UUID.randomUUID(), "ghost@test.com"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Ghost org\",\"contactEmail\":\"g@test.com\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void guard_readsDbNotToken() throws Exception {
    User u = user(false);
    createOrg(u).andExpect(status().isForbidden());

    u.setEmailVerified(true);
    userRepository.saveAndFlush(u);

    // Same principal, no new token: the guard reads the current row.
    createOrg(u).andExpect(status().isOk());
  }
}
