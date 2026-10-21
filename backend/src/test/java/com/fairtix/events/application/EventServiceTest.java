package com.fairtix.events.application;

import com.fairtix.events.domain.Event;
import com.fairtix.events.dto.UpdateEventRequest;
import com.fairtix.events.infrastructure.EventRepository;
import com.fairtix.performers.domain.Performer;
import com.fairtix.performers.infrastructure.PerformerRepository;
import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class EventServiceTest {

  @Autowired private EventService eventService;
  @Autowired private EventRepository eventRepository;
  @Autowired private PerformerRepository performerRepository;
  @Autowired private UserRepository userRepository;

  private UUID organizerId;
  private Event event;
  private Performer performer1;
  private Performer performer2;

  @BeforeEach
  void setUp() {
    // events.organizer_id references users(id).
    User organizer = new User();
    organizer.setEmail("organizer@test.com");
    organizer.setPassword("$2a$10$dummyhashfortest");
    organizerId = userRepository.save(organizer).getId();
    event = eventRepository.save(
        new Event("Test Event", null, Instant.now().plusSeconds(86400), organizerId));
    performer1 = performerRepository.save(new Performer("Artist One", "Rock", null, null));
    performer2 = performerRepository.save(new Performer("Artist Two", "Jazz", null, null));
  }

  @Test
  void updatingEventWithPerformerIds_linksPerformers() {
    UpdateEventRequest request = new UpdateEventRequest(
        event.getTitle(), event.getStartTime(), null, null, null,
        List.of(performer1.getId(), performer2.getId()));

    Event updated = eventService.update(event.getId(), request, organizerId);

    assertThat(updated.getPerformers()).hasSize(2);
    assertThat(updated.getPerformers().stream().map(Performer::getId))
        .containsExactlyInAnyOrder(performer1.getId(), performer2.getId());
  }

  @Test
  void updatingEventWithEmptyPerformerIds_removesAllPerformers() {
    UpdateEventRequest addRequest = new UpdateEventRequest(
        event.getTitle(), event.getStartTime(), null, null, null,
        List.of(performer1.getId()));
    eventService.update(event.getId(), addRequest, organizerId);

    UpdateEventRequest clearRequest = new UpdateEventRequest(
        event.getTitle(), event.getStartTime(), null, null, null,
        List.of());
    Event updated = eventService.update(event.getId(), clearRequest, organizerId);

    assertThat(updated.getPerformers()).isEmpty();
  }

  @Test
  void updatingEventWithNullPerformerIds_doesNotChangePerformers() {
    UpdateEventRequest addRequest = new UpdateEventRequest(
        event.getTitle(), event.getStartTime(), null, null, null,
        List.of(performer1.getId()));
    eventService.update(event.getId(), addRequest, organizerId);

    UpdateEventRequest noChangeRequest = new UpdateEventRequest(
        event.getTitle(), event.getStartTime(), null, null, null,
        null);
    Event updated = eventService.update(event.getId(), noChangeRequest, organizerId);

    assertThat(updated.getPerformers()).hasSize(1);
    assertThat(updated.getPerformers().get(0).getId()).isEqualTo(performer1.getId());
  }
}
