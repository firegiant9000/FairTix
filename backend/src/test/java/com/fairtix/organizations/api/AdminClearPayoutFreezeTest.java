package com.fairtix.organizations.api;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import com.fairtix.audit.infrastructure.AuditLogRepository;
import com.fairtix.auth.WithMockPrincipal;
import com.fairtix.organizations.application.OrganizationService;
import com.fairtix.organizations.domain.Organization;
import com.fairtix.organizations.infrastructure.OrganizationRepository;
import com.fairtix.users.domain.Role;
import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;

import jakarta.persistence.EntityManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Admin action that clears the advisory dispute payout-freeze flag (#364, option 3).
 * ADMIN only; audited as STRIPE_PAYOUT_FREEZE_CLEARED with the acting admin.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Transactional
class AdminClearPayoutFreezeTest {

  private static final String PATH = "/api/admin/organizations/{id}/payout-freeze";

  @Autowired private WebApplicationContext context;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationService organizationService;
  @Autowired private OrganizationRepository organizations;
  @Autowired private AuditLogRepository auditLogs;
  @Autowired private EntityManager entityManager;

  private MockMvc mvc() {
    return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  private User user(Role role) {
    User u = new User();
    u.setEmail("freeze-" + UUID.randomUUID() + "@test.com");
    u.setPassword("x");
    u.setRole(role);
    return userRepository.saveAndFlush(u);
  }

  private Organization frozenOrg(User owner) {
    Organization org = organizationService.createOrganization(
        "Freeze " + UUID.randomUUID().toString().substring(0, 6), "f@x.test", owner.getId());
    organizations.freezePayouts(org.getId(), Instant.now());
    entityManager.flush();
    entityManager.clear();
    return org;
  }

  private boolean frozen(Organization org) {
    entityManager.clear();
    return organizations.findById(org.getId()).orElseThrow().isStripePayoutsFrozen();
  }

  private long clearedAudits(Organization org, User admin) {
    return auditLogs.findAll().stream()
        .filter(a -> "STRIPE_PAYOUT_FREEZE_CLEARED".equals(a.getAction()))
        .filter(a -> org.getId().equals(a.getResourceId()))
        .filter(a -> admin == null || admin.getId().equals(a.getUserId()))
        .count();
  }

  @Test
  void admin_clearsFlagAndAuditsActingAdmin() throws Exception {
    User admin = user(Role.ADMIN);
    Organization org = frozenOrg(user(Role.USER));

    mvc().perform(delete(PATH, org.getId())
            .with(WithMockPrincipal.admin(admin.getId(), admin.getEmail())))
        .andExpect(status().isNoContent());

    assertThat(frozen(org)).isFalse();
    assertThat(clearedAudits(org, admin)).isEqualTo(1);
  }

  @Test
  void admin_clearingUnfrozenOrgIsIdempotentWithoutAudit() throws Exception {
    User admin = user(Role.ADMIN);
    Organization org = frozenOrg(user(Role.USER));
    organizations.clearPayoutsFreeze(org.getId(), Instant.now());

    mvc().perform(delete(PATH, org.getId())
            .with(WithMockPrincipal.admin(admin.getId(), admin.getEmail())))
        .andExpect(status().isNoContent());

    assertThat(clearedAudits(org, null)).isZero();
  }

  @Test
  void admin_unknownOrgIs404() throws Exception {
    User admin = user(Role.ADMIN);

    mvc().perform(delete(PATH, UUID.randomUUID())
            .with(WithMockPrincipal.admin(admin.getId(), admin.getEmail())))
        .andExpect(status().isNotFound());
  }

  @Test
  void nonAdmin_isForbiddenAndFlagStays() throws Exception {
    User regular = user(Role.USER);
    Organization org = frozenOrg(regular);

    mvc().perform(delete(PATH, org.getId())
            .with(WithMockPrincipal.user(regular.getId(), regular.getEmail())))
        .andExpect(status().isForbidden());

    assertThat(frozen(org)).isTrue();
  }

  @Test
  void unauthenticated_isRejected() throws Exception {
    mvc().perform(delete(PATH, UUID.randomUUID()))
        .andExpect(status().isUnauthorized());
  }
}
