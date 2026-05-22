package com.fairtix.organizations.application;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import com.fairtix.audit.application.AuditService;
import com.fairtix.common.ResourceNotFoundException;
import com.fairtix.organizations.domain.OrgPermission;
import com.fairtix.organizations.domain.OrgRole;
import com.fairtix.organizations.domain.Organization;
import com.fairtix.organizations.domain.OrganizationInvite;
import com.fairtix.organizations.domain.OrganizationMember;
import com.fairtix.organizations.domain.OrganizationStatus;
import com.fairtix.organizations.infrastructure.OrganizationInviteRepository;
import com.fairtix.organizations.infrastructure.OrganizationMemberRepository;
import com.fairtix.organizations.infrastructure.OrganizationRepository;
import com.fairtix.users.domain.Role;
import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;

import jakarta.transaction.Transactional;

@Service
@Transactional
public class OrganizationService {

  private static final SecureRandom RANDOM = new SecureRandom();

  private final OrganizationRepository organizations;
  private final OrganizationMemberRepository members;
  private final OrganizationInviteRepository invites;
  private final UserRepository users;
  private final AuditService auditService;

  public OrganizationService(OrganizationRepository organizations,
                             OrganizationMemberRepository members,
                             OrganizationInviteRepository invites,
                             UserRepository users,
                             AuditService auditService) {
    this.organizations = organizations;
    this.members = members;
    this.invites = invites;
    this.users = users;
    this.auditService = auditService;
  }

  public Organization createOrganization(String name, String contactEmail, UUID ownerUserId) {
    String slug = generateUniqueSlug(name);
    Organization org = new Organization(name, slug, contactEmail);
    org.setStatus(OrganizationStatus.ACTIVE);
    organizations.save(org);
    members.save(new OrganizationMember(org.getId(), ownerUserId, OrgRole.OWNER));
    return org;
  }

  public Organization get(UUID orgId) {
    return organizations.findById(orgId)
        .orElseThrow(() -> new ResourceNotFoundException("Organization not found: " + orgId));
  }

  public List<OrganizationMember> listMembers(UUID orgId) {
    return members.findAllByOrganizationId(orgId);
  }

  public List<OrganizationMember> listMembershipsForUser(UUID userId) {
    return members.findAllByUserId(userId);
  }

  public OrganizationInvite invite(UUID orgId, String email, OrgRole role, UUID actorUserId) {
    requirePermission(actorUserId, orgId, OrgPermission.TEAM_WRITE);
    String token = randomToken();
    OrganizationInvite invite = new OrganizationInvite(orgId, email.toLowerCase(), role, token,
        Instant.now().plus(7, ChronoUnit.DAYS), actorUserId);
    return invites.save(invite);
  }

  public OrganizationMember acceptInvite(String token, UUID acceptingUserId) {
    OrganizationInvite invite = invites.findByToken(token)
        .orElseThrow(() -> new ResourceNotFoundException("Invite not found"));
    if (invite.isAccepted()) throw new IllegalStateException("Invite already accepted");
    if (invite.isExpired())  throw new IllegalStateException("Invite has expired");

    User user = users.findById(acceptingUserId)
        .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    if (!user.getEmail().equalsIgnoreCase(invite.getEmail())) {
      throw new AccessDeniedException("Invite is for a different email");
    }
    if (members.findByOrganizationIdAndUserId(invite.getOrganizationId(), acceptingUserId).isPresent()) {
      throw new IllegalStateException("User already a member");
    }
    invite.accept();
    return members.save(new OrganizationMember(invite.getOrganizationId(), acceptingUserId, invite.getRole()));
  }

  public OrganizationMember updateMemberRole(UUID orgId, UUID memberUserId, OrgRole newRole, UUID actorUserId) {
    requirePermission(actorUserId, orgId, OrgPermission.TEAM_WRITE);
    OrganizationMember member = members.findByOrganizationIdAndUserId(orgId, memberUserId)
        .orElseThrow(() -> new ResourceNotFoundException("Member not found"));
    OrgRole previousRole = member.getRole();
    if (previousRole == OrgRole.OWNER && newRole != OrgRole.OWNER) {
      long owners = members.countByOrganizationIdAndRole(orgId, OrgRole.OWNER);
      if (owners <= 1) throw new IllegalStateException("Cannot demote the last OWNER");
    }
    member.setRole(newRole);
    if (previousRole != newRole) {
      auditService.log(actorUserId, "ORG_MEMBER_ROLE_CHANGED", "ORGANIZATION_MEMBER", member.getId(),
          String.format("orgId=%s targetUserId=%s from=%s to=%s",
              orgId, memberUserId, previousRole, newRole));
    }
    return member;
  }

  public void removeMember(UUID orgId, UUID memberUserId, UUID actorUserId) {
    requirePermission(actorUserId, orgId, OrgPermission.TEAM_WRITE);
    OrganizationMember member = members.findByOrganizationIdAndUserId(orgId, memberUserId)
        .orElseThrow(() -> new ResourceNotFoundException("Member not found"));
    if (member.getRole() == OrgRole.OWNER) {
      long owners = members.countByOrganizationIdAndRole(orgId, OrgRole.OWNER);
      if (owners <= 1) throw new IllegalStateException("Cannot remove the last OWNER");
    }
    UUID memberId = member.getId();
    OrgRole removedRole = member.getRole();
    members.delete(member);
    auditService.log(actorUserId, "ORG_MEMBER_REMOVED", "ORGANIZATION_MEMBER", memberId,
        String.format("orgId=%s targetUserId=%s role=%s", orgId, memberUserId, removedRole));
  }

  // --- ACL helpers ---

  public boolean isPlatformAdmin(UUID userId) {
    return users.findById(userId).map(u -> u.getRole() == Role.ADMIN).orElse(false);
  }

  public Optional<OrganizationMember> findMembership(UUID userId, UUID orgId) {
    return members.findByOrganizationIdAndUserId(orgId, userId);
  }

  /**
   * Returns true if the user has the given permission within the org.
   * Platform ADMIN always passes. Owners and roles with ALL pass.
   */
  public boolean hasPermission(UUID userId, UUID orgId, OrgPermission permission) {
    if (isPlatformAdmin(userId)) return true;
    return members.findByOrganizationIdAndUserId(orgId, userId)
        .map(m -> m.getRole().has(permission))
        .orElse(false);
  }

  public void requirePermission(UUID userId, UUID orgId, OrgPermission permission) {
    if (!hasPermission(userId, orgId, permission)) {
      throw new AccessDeniedException(
          "Missing permission " + permission + " on organization " + orgId);
    }
  }

  // --- internals ---

  private String randomToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private String generateUniqueSlug(String name) {
    String base = name == null ? "org" : name.toLowerCase().replaceAll("[^a-z0-9]+", "-")
        .replaceAll("^-+|-+$", "");
    if (base.isEmpty()) base = "org";
    if (base.length() > 80) base = base.substring(0, 80);
    String candidate = base;
    int suffix = 2;
    while (organizations.existsBySlug(candidate)) {
      candidate = base + "-" + suffix;
      suffix++;
    }
    return candidate;
  }
}
