package com.fairtix.users.api;

import com.fairtix.audit.infrastructure.AuditLogRepository;
import com.fairtix.auth.WithMockPrincipal;
import com.fairtix.users.domain.Role;
import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Promoting a user to ADMIN is audited with the acting admin and the target (RM:324:
 * who holds admin must be traceable). Red on commit {@code 220a1ef}: no audit row.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Transactional
class AdminPromoteAuditTest {

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private AuditLogRepository auditLogRepository;

  private User user(Role role) {
    User u = new User();
    u.setEmail("promo-" + UUID.randomUUID() + "@test.com");
    u.setPassword("x");
    u.setRole(role);
    return userRepository.saveAndFlush(u);
  }

  @Test
  void promote_writesUserPromotedAudit() throws Exception {
    User admin = user(Role.ADMIN);
    User target = user(Role.USER);

    MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build()
        .perform(patch("/api/admin/users/{id}/promote", target.getId())
            .with(WithMockPrincipal.admin(admin.getId(), admin.getEmail())))
        .andExpect(status().isOk());

    assertThat(auditLogRepository.findAll().stream()
        .filter(a -> "USER_PROMOTED".equals(a.getAction()))
        .filter(a -> target.getId().equals(a.getResourceId())))
        .singleElement()
        .satisfies(a -> {
          assertThat(a.getUserId()).isEqualTo(admin.getId());
          assertThat(a.getResourceType()).isEqualTo("USER");
          assertThat(a.getDetails()).isEqualTo("role=ADMIN");
        });
  }
}
