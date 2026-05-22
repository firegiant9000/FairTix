package com.fairtix.events.application;

import com.fairtix.events.domain.Event;
import com.fairtix.events.dto.UpdateEventRequest;
import com.fairtix.events.infrastructure.EventRepository;
import com.fairtix.organizations.application.OrganizationService;
import com.fairtix.organizations.domain.OrgRole;
import com.fairtix.organizations.domain.Organization;
import com.fairtix.organizations.domain.OrganizationInvite;
import com.fairtix.users.domain.Role;
import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;
import com.fairtix.venues.domain.Venue;
import com.fairtix.venues.infrastructure.VenueRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Locks down the EventService.verifyOwnership refactor: organization membership
 * is the new authority for write access, with platform-ADMIN bypass and a
 * legacy organizer_id fallback for orphan events created before V33 backfilled.
 */
@SpringBootTest
@Transactional
class EventServiceOrgAccessTest {

  @Autowired private EventService eventService;
  @Autowired private EventRepository eventRepository;
  @Autowired private VenueRepository venueRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationService organizationService;

  private Venue venue;

  @BeforeEach
  void setUp() {
    venue = venueRepository.save(new Venue("V-" + uniq(), null, null, null, null, null, null));
  }

  @Test
  void orgManagerCanUpdateEventBelongingToTheirOrg() {
    User ownerUser = newUser(Role.USER);
    User managerUser = newUser(Role.USER);
    Organization org = organizationService.createOrganization("Org-" + uniq(), "c@x.test", ownerUser.getId());
    OrganizationInvite invite = organizationService.invite(org.getId(), managerUser.getEmail(),
        OrgRole.MANAGER, ownerUser.getId());
    organizationService.acceptInvite(invite.getToken(), managerUser.getId());

    Event event = saveEvent(org.getId(), null);

    Event updated = eventService.update(event.getId(),
        new UpdateEventRequest("Renamed", Instant.now().plusSeconds(7200), null, null, null, null),
        managerUser.getId());

    assertThat(updated.getTitle()).isEqualTo("Renamed");
  }

  @Test
  void doorRoleCannotUpdateEventEvenInOwnOrg() {
    User ownerUser = newUser(Role.USER);
    User doorUser = newUser(Role.USER);
    Organization org = organizationService.createOrganization("Org-" + uniq(), "c@x.test", ownerUser.getId());
    OrganizationInvite invite = organizationService.invite(org.getId(), doorUser.getEmail(),
        OrgRole.DOOR, ownerUser.getId());
    organizationService.acceptInvite(invite.getToken(), doorUser.getId());

    Event event = saveEvent(org.getId(), null);

    assertThatThrownBy(() -> eventService.update(event.getId(),
        new UpdateEventRequest("Renamed", Instant.now().plusSeconds(7200), null, null, null, null),
        doorUser.getId()))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("DOOR");
  }

  @Test
  void nonMemberCannotUpdateEvent() {
    User ownerUser = newUser(Role.USER);
    User outsider = newUser(Role.USER);
    Organization org = organizationService.createOrganization("Org-" + uniq(), "c@x.test", ownerUser.getId());
    Event event = saveEvent(org.getId(), null);

    assertThatThrownBy(() -> eventService.update(event.getId(),
        new UpdateEventRequest("X", Instant.now().plusSeconds(7200), null, null, null, null),
        outsider.getId()))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("not a member");
  }

  @Test
  void platformAdminCanUpdateAnyOrgsEvent() {
    User ownerUser = newUser(Role.USER);
    User admin = newUser(Role.ADMIN);
    Organization org = organizationService.createOrganization("Org-" + uniq(), "c@x.test", ownerUser.getId());
    Event event = saveEvent(org.getId(), null);

    Event updated = eventService.update(event.getId(),
        new UpdateEventRequest("Admin renamed", Instant.now().plusSeconds(7200), null, null, null, null),
        admin.getId());

    assertThat(updated.getTitle()).isEqualTo("Admin renamed");
  }

  @Test
  void orphanEventWithMatchingOrganizerIdStillUpdates() {
    // Mirrors pre-V33 events: no org, organizer_id set the old way.
    User organizer = newUser(Role.USER);
    Event event = saveEvent(null, organizer.getId());

    Event updated = eventService.update(event.getId(),
        new UpdateEventRequest("Legacy update", Instant.now().plusSeconds(7200), null, null, null, null),
        organizer.getId());

    assertThat(updated.getTitle()).isEqualTo("Legacy update");
  }

  @Test
  void orphanEventWithMismatchedOrganizerIdIsRejected() {
    User organizer = newUser(Role.USER);
    User outsider = newUser(Role.USER);
    Event event = saveEvent(null, organizer.getId());

    assertThatThrownBy(() -> eventService.update(event.getId(),
        new UpdateEventRequest("X", Instant.now().plusSeconds(7200), null, null, null, null),
        outsider.getId()))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("do not own");
  }

  @Test
  void orgEventRejectsNullCallerId() {
    User ownerUser = newUser(Role.USER);
    Organization org = organizationService.createOrganization("Org-" + uniq(), "c@x.test", ownerUser.getId());
    Event event = saveEvent(org.getId(), null);

    assertThatThrownBy(() -> eventService.update(event.getId(),
        new UpdateEventRequest("X", Instant.now().plusSeconds(7200), null, null, null, null),
        null))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void orgMemberInDifferentOrgCannotUpdate() {
    User ownerA = newUser(Role.USER);
    User ownerB = newUser(Role.USER);
    Organization orgA = organizationService.createOrganization("A-" + uniq(), "a@x.test", ownerA.getId());
    organizationService.createOrganization("B-" + uniq(), "b@x.test", ownerB.getId());
    Event event = saveEvent(orgA.getId(), null);

    assertThatThrownBy(() -> eventService.update(event.getId(),
        new UpdateEventRequest("X", Instant.now().plusSeconds(7200), null, null, null, null),
        ownerB.getId()))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("not a member");
  }

  // -- helpers --

  private User newUser(Role role) {
    User u = new User();
    u.setEmail(uniq() + "@x.test");
    u.setPassword("bcrypt-placeholder");
    u.setRole(role);
    u.setEmailVerified(true);
    return userRepository.save(u);
  }

  private Event saveEvent(UUID organizationId, UUID organizerId) {
    Event event = new Event("E-" + uniq(), venue, Instant.now().plusSeconds(3600), organizerId);
    if (organizationId != null) event.setOrganizationId(organizationId);
    return eventRepository.save(event);
  }

  private static String uniq() {
    return UUID.randomUUID().toString().substring(0, 8);
  }
}
