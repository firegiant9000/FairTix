package com.fairtix.organizations.application;

import com.fairtix.common.ResourceNotFoundException;
import com.fairtix.organizations.domain.OrgPermission;
import com.fairtix.organizations.domain.OrgRole;
import com.fairtix.organizations.domain.Organization;
import com.fairtix.organizations.domain.OrganizationInvite;
import com.fairtix.organizations.domain.OrganizationMember;
import com.fairtix.organizations.infrastructure.OrganizationInviteRepository;
import com.fairtix.organizations.infrastructure.OrganizationMemberRepository;
import com.fairtix.users.domain.Role;
import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the org ACL boundary end-to-end against the real schema. Covers the
 * five gaps senior engineer review flagged: invite lifecycle, last-OWNER
 * invariant, role-based permission checks, platform-admin bypass, and
 * cross-org isolation.
 */
@SpringBootTest
@Transactional
class OrganizationServiceTest {

  @Autowired private OrganizationService service;
  @Autowired private OrganizationMemberRepository members;
  @Autowired private OrganizationInviteRepository invites;
  @Autowired private UserRepository userRepository;

  private User owner;
  private User invitee;
  private User stranger;
  private User platformAdmin;

  @BeforeEach
  void setUp() {
    owner = newUser("owner-" + uniq() + "@fairtix.test", Role.USER);
    invitee = newUser("invitee-" + uniq() + "@fairtix.test", Role.USER);
    stranger = newUser("stranger-" + uniq() + "@fairtix.test", Role.USER);
    platformAdmin = newUser("admin-" + uniq() + "@fairtix.test", Role.ADMIN);
  }

  // -------- Organization creation --------

  @Test
  void createOrganizationAssignsSlugAndPromotesOwner() {
    Organization org = service.createOrganization("Blue Note", "blue@note.test", owner.getId());

    assertThat(org.getId()).isNotNull();
    assertThat(org.getSlug()).isEqualTo("blue-note");
    OrganizationMember member = members.findByOrganizationIdAndUserId(org.getId(), owner.getId())
        .orElseThrow();
    assertThat(member.getRole()).isEqualTo(OrgRole.OWNER);
  }

  @Test
  void slugCollisionSuffixesNumerically() {
    service.createOrganization("Blue Note", "a@x.test", owner.getId());
    Organization second = service.createOrganization("Blue Note", "b@x.test", invitee.getId());
    assertThat(second.getSlug()).isEqualTo("blue-note-2");
  }

  // -------- Invite lifecycle --------

  @Test
  void inviteThenAcceptCreatesMember() {
    Organization org = service.createOrganization("Org", "c@x.test", owner.getId());
    OrganizationInvite invite = service.invite(org.getId(), invitee.getEmail(), OrgRole.MANAGER, owner.getId());

    OrganizationMember newMember = service.acceptInvite(invite.getToken(), invitee.getId());

    assertThat(newMember.getRole()).isEqualTo(OrgRole.MANAGER);
    assertThat(invites.findByToken(invite.getToken()).orElseThrow().isAccepted()).isTrue();
  }

  @Test
  void inviteAcceptIsRejectedForDifferentEmail() {
    Organization org = service.createOrganization("Org", "c@x.test", owner.getId());
    OrganizationInvite invite = service.invite(org.getId(), invitee.getEmail(), OrgRole.MANAGER, owner.getId());

    assertThatThrownBy(() -> service.acceptInvite(invite.getToken(), stranger.getId()))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void inviteCannotBeAcceptedTwice() {
    Organization org = service.createOrganization("Org", "c@x.test", owner.getId());
    OrganizationInvite invite = service.invite(org.getId(), invitee.getEmail(), OrgRole.MANAGER, owner.getId());
    service.acceptInvite(invite.getToken(), invitee.getId());

    assertThatThrownBy(() -> service.acceptInvite(invite.getToken(), invitee.getId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("already accepted");
  }

  @Test
  void expiredInviteIsRejected() throws Exception {
    Organization org = service.createOrganization("Org", "c@x.test", owner.getId());
    OrganizationInvite invite = service.invite(org.getId(), invitee.getEmail(), OrgRole.MANAGER, owner.getId());

    forceExpiry(invite);

    assertThatThrownBy(() -> service.acceptInvite(invite.getToken(), invitee.getId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("expired");
  }

  @Test
  void nonMemberCannotInvite() {
    Organization org = service.createOrganization("Org", "c@x.test", owner.getId());
    assertThatThrownBy(() ->
        service.invite(org.getId(), invitee.getEmail(), OrgRole.MANAGER, stranger.getId()))
        .isInstanceOf(AccessDeniedException.class);
  }

  // -------- Last-OWNER invariant --------

  @Test
  void lastOwnerCannotBeRemoved() {
    Organization org = service.createOrganization("Org", "c@x.test", owner.getId());
    assertThatThrownBy(() -> service.removeMember(org.getId(), owner.getId(), owner.getId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("last OWNER");
  }

  @Test
  void lastOwnerCannotBeDemoted() {
    Organization org = service.createOrganization("Org", "c@x.test", owner.getId());
    assertThatThrownBy(() ->
        service.updateMemberRole(org.getId(), owner.getId(), OrgRole.MANAGER, owner.getId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("last OWNER");
  }

  @Test
  void secondOwnerCanBeDemotedWhenAnotherOwnerExists() {
    Organization org = service.createOrganization("Org", "c@x.test", owner.getId());
    OrganizationInvite invite = service.invite(org.getId(), invitee.getEmail(), OrgRole.OWNER, owner.getId());
    service.acceptInvite(invite.getToken(), invitee.getId());

    OrganizationMember demoted = service.updateMemberRole(
        org.getId(), invitee.getId(), OrgRole.MANAGER, owner.getId());

    assertThat(demoted.getRole()).isEqualTo(OrgRole.MANAGER);
  }

  // -------- Permission resolution --------

  @Test
  void hasPermissionPassesForMatchingRole() {
    Organization org = service.createOrganization("Org", "c@x.test", owner.getId());
    OrganizationInvite invite = service.invite(org.getId(), invitee.getEmail(), OrgRole.MANAGER, owner.getId());
    service.acceptInvite(invite.getToken(), invitee.getId());

    assertThat(service.hasPermission(invitee.getId(), org.getId(), OrgPermission.EVENTS_WRITE)).isTrue();
    assertThat(service.hasPermission(invitee.getId(), org.getId(), OrgPermission.SCANNER_USE)).isFalse();
  }

  @Test
  void hasPermissionDeniesNonMember() {
    Organization org = service.createOrganization("Org", "c@x.test", owner.getId());
    assertThat(service.hasPermission(stranger.getId(), org.getId(), OrgPermission.EVENTS_READ)).isFalse();
  }

  @Test
  void platformAdminBypassesOrgMembership() {
    Organization org = service.createOrganization("Org", "c@x.test", owner.getId());
    assertThat(service.hasPermission(platformAdmin.getId(), org.getId(), OrgPermission.EVENTS_WRITE)).isTrue();
    assertThat(service.hasPermission(platformAdmin.getId(), org.getId(), OrgPermission.PAYOUTS_READ)).isTrue();
  }

  @Test
  void requirePermissionThrowsWhenMissing() {
    Organization org = service.createOrganization("Org", "c@x.test", owner.getId());
    assertThatThrownBy(() ->
        service.requirePermission(stranger.getId(), org.getId(), OrgPermission.EVENTS_WRITE))
        .isInstanceOf(AccessDeniedException.class);
  }

  // -------- Cross-org isolation --------

  @Test
  void memberOfOrgACannotAccessOrgB() {
    Organization orgA = service.createOrganization("A", "a@x.test", owner.getId());
    Organization orgB = service.createOrganization("B", "b@x.test", invitee.getId());

    assertThat(service.hasPermission(owner.getId(), orgA.getId(), OrgPermission.EVENTS_WRITE)).isTrue();
    assertThat(service.hasPermission(owner.getId(), orgB.getId(), OrgPermission.EVENTS_WRITE)).isFalse();
    assertThat(service.hasPermission(invitee.getId(), orgA.getId(), OrgPermission.EVENTS_READ)).isFalse();
  }

  @Test
  void listMembershipsForUserReturnsAllOrgs() {
    Organization orgA = service.createOrganization("A", "a@x.test", owner.getId());
    Organization orgB = service.createOrganization("B", "b@x.test", owner.getId());

    assertThat(service.listMembershipsForUser(owner.getId()))
        .extracting(OrganizationMember::getOrganizationId)
        .containsExactlyInAnyOrder(orgA.getId(), orgB.getId());
  }

  @Test
  void getOnMissingOrgThrowsResourceNotFound() {
    assertThatThrownBy(() -> service.get(UUID.randomUUID()))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  // -------- helpers --------

  private User newUser(String email, Role role) {
    User u = new User();
    u.setEmail(email);
    u.setPassword("bcrypt-placeholder");
    u.setRole(role);
    u.setEmailVerified(true);
    return userRepository.save(u);
  }

  private static String uniq() {
    return UUID.randomUUID().toString().substring(0, 8);
  }

  /** Forces an invite past its expiry without waiting 7 days. */
  private static void forceExpiry(OrganizationInvite invite) throws Exception {
    Field f = OrganizationInvite.class.getDeclaredField("expiresAt");
    f.setAccessible(true);
    f.set(invite, Instant.now().minus(1, ChronoUnit.HOURS));
  }
}
