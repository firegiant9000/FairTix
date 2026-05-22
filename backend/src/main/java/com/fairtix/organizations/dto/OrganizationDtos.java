package com.fairtix.organizations.dto;

import java.time.Instant;
import java.util.UUID;

import com.fairtix.organizations.domain.OrgRole;
import com.fairtix.organizations.domain.Organization;
import com.fairtix.organizations.domain.OrganizationMember;
import com.fairtix.organizations.domain.OrganizationStatus;

public final class OrganizationDtos {
  private OrganizationDtos() {}

  public record CreateOrganizationRequest(String name, String contactEmail) {}

  public record InviteMemberRequest(String email, OrgRole role) {}

  public record UpdateMemberRoleRequest(OrgRole role) {}

  public record AcceptInviteRequest(String token) {}

  public record OrganizationResponse(UUID id, String name, String slug, String contactEmail,
                                     OrganizationStatus status, Instant createdAt) {
    public static OrganizationResponse from(Organization o) {
      return new OrganizationResponse(o.getId(), o.getName(), o.getSlug(),
          o.getContactEmail(), o.getStatus(), o.getCreatedAt());
    }
  }

  public record MemberResponse(UUID userId, OrgRole role, Instant createdAt) {
    public static MemberResponse from(OrganizationMember m) {
      return new MemberResponse(m.getUserId(), m.getRole(), m.getCreatedAt());
    }
  }
}
