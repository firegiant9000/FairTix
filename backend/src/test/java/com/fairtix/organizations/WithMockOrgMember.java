package com.fairtix.organizations;

import java.util.UUID;

import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.fairtix.auth.WithMockPrincipal;
import com.fairtix.organizations.domain.OrgRole;
import com.fairtix.organizations.domain.OrganizationMember;
import com.fairtix.organizations.infrastructure.OrganizationMemberRepository;

/**
 * Test helper for org-scoped controller tests.
 *
 * Why: most existing MockMvc tests authenticated a user without binding them
 * to any organization. Once {@code @OrgScoped} is enforced, those tests would
 * either start failing or — worse — pass for the wrong reason because a
 * platform ADMIN bypass swallowed the missing membership. This helper makes
 * the membership explicit per test.
 *
 * Usage: call {@link #seed(OrganizationMemberRepository, UUID, UUID, OrgRole)}
 * during setup, then attach {@link #asPrincipal(UUID, String)} to the MockMvc
 * request.
 */
public final class WithMockOrgMember {

  private WithMockOrgMember() {}

  public static OrganizationMember seed(OrganizationMemberRepository repo,
                                        UUID orgId, UUID userId, OrgRole role) {
    return repo.save(new OrganizationMember(orgId, userId, role));
  }

  public static RequestPostProcessor asPrincipal(UUID userId, String email) {
    return WithMockPrincipal.user(userId, email);
  }
}
