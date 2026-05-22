package com.fairtix.organizations.application;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import com.fairtix.organizations.domain.OrganizationStatus;
import com.fairtix.organizations.dto.SignupDtos.SubmitForReviewRequest;
import com.fairtix.organizations.domain.Organization;
import com.fairtix.organizations.infrastructure.OrganizationRepository;
import com.fairtix.users.domain.Role;
import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class OrgSignupServiceTest {

  @Autowired private OrganizationService organizationService;
  @Autowired private OrgSignupService signupService;
  @Autowired private OrganizationRepository organizations;
  @Autowired private UserRepository users;
  @Autowired private EinCipher einCipher;

  private User owner;
  private User stranger;
  private User admin;

  @BeforeEach
  void setUp() {
    owner = newUser("owner-" + uniq() + "@fairtix.test", Role.USER);
    stranger = newUser("stranger-" + uniq() + "@fairtix.test", Role.USER);
    admin = newUser("admin-" + uniq() + "@fairtix.test", Role.ADMIN);
  }

  @Test
  void submitForReviewMovesOrgToPendingReviewAndEncryptsEin() {
    Organization org = organizationService.createOrganization("Velvet Room", "v@x.test", owner.getId());

    Organization after = signupService.submitForReview(org.getId(), owner.getId(), validRequest());

    assertThat(after.getStatus()).isEqualTo(OrganizationStatus.PENDING_REVIEW);
    assertThat(after.getSubmittedForReviewAt()).isNotNull();
    assertThat(after.getEinEncrypted()).isNotNull().isNotEqualTo("12-3456789");
    assertThat(einCipher.decrypt(after.getEinEncrypted())).isEqualTo("12-3456789");
  }

  @Test
  void nonOwnerCannotSubmit() {
    Organization org = organizationService.createOrganization("Velvet Room", "v@x.test", owner.getId());
    assertThatThrownBy(() ->
        signupService.submitForReview(org.getId(), stranger.getId(), validRequest()))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void nonUsCountryRejected() {
    Organization org = organizationService.createOrganization("Velvet Room", "v@x.test", owner.getId());
    SubmitForReviewRequest req = new SubmitForReviewRequest(
        "Legal", null, "1 Way", null, "City", "ON", "M5V", "CA",
        "Owner", null, null, null);
    assertThatThrownBy(() -> signupService.submitForReview(org.getId(), owner.getId(), req))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("US");
  }

  @Test
  void approveTransitionsToActive() {
    Organization org = submitted();

    Organization approved = signupService.approve(org.getId(), admin.getId());

    assertThat(approved.getStatus()).isEqualTo(OrganizationStatus.ACTIVE);
    assertThat(approved.getReviewedAt()).isNotNull();
    assertThat(approved.getReviewedByUserId()).isEqualTo(admin.getId());
  }

  @Test
  void rejectRequiresReasonAndStoresIt() {
    Organization org = submitted();

    Organization rejected = signupService.reject(org.getId(), admin.getId(), "Suspicious activity");

    assertThat(rejected.getStatus()).isEqualTo(OrganizationStatus.REJECTED);
    assertThat(rejected.getRejectionReason()).isEqualTo("Suspicious activity");
  }

  @Test
  void cannotApproveOrgThatWasNeverSubmitted() {
    Organization org = organizationService.createOrganization("Velvet Room", "v@x.test", owner.getId());
    assertThatThrownBy(() -> signupService.approve(org.getId(), admin.getId()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void overrideCapRequiresFutureDate() {
    Organization org = organizationService.createOrganization("Velvet Room", "v@x.test", owner.getId());
    assertThatThrownBy(() -> signupService.overrideSalesCap(
        org.getId(), admin.getId(), 500_000L, Instant.now().minus(1, ChronoUnit.HOURS)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void listForReviewReturnsPendingReviewOnly() {
    Organization submitted = submitted();
    Organization other = organizationService.createOrganization("Other", "o@x.test", stranger.getId());

    var queue = signupService.listForReview();

    assertThat(queue).extracting(Organization::getId).contains(submitted.getId()).doesNotContain(other.getId());
  }

  // helpers

  private Organization submitted() {
    Organization org = organizationService.createOrganization("Velvet Room", "v@x.test", owner.getId());
    return signupService.submitForReview(org.getId(), owner.getId(), validRequest());
  }

  private static SubmitForReviewRequest validRequest() {
    return new SubmitForReviewRequest(
        "Velvet Room LLC", "Velvet Room",
        "1 Music Row", null, "Nashville", "TN", "37203", "US",
        "Jane Owner", "+1-555-555-5555",
        "12-3456789", "blue-note-nyc");
  }

  private User newUser(String email, Role role) {
    User u = new User();
    u.setEmail(email);
    u.setPassword("bcrypt-placeholder");
    u.setRole(role);
    u.setEmailVerified(true);
    return users.save(u);
  }

  private static String uniq() {
    return UUID.randomUUID().toString().substring(0, 8);
  }
}
