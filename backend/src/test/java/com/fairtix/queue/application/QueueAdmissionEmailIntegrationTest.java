package com.fairtix.queue.application;

import com.fairtix.events.domain.Event;
import com.fairtix.events.infrastructure.EventRepository;
import com.fairtix.inventory.domain.Seat;
import com.fairtix.inventory.infrastructure.SeatRepository;
import com.fairtix.notifications.application.EmailService;
import com.fairtix.queue.domain.QueueEntry;
import com.fairtix.queue.domain.QueueStatus;
import com.fairtix.queue.infrastructure.QueueRepository;
import com.fairtix.queue.scheduler.QueueAdmissionScheduler;
import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Cross-module integration test: queue admission scheduler admits a waiting user
 * and sends an admission notification email (#145 wiring).
 *
 * Not @Transactional so the scheduler's transaction commits before email assertions run.
 * Each test uses a UUID-based email to avoid unique-constraint conflicts.
 *
 * Spring's auto-task-scheduler is disabled here because the test invokes
 * {@code queueAdmissionScheduler.admitWaitingUsers()} explicitly. Without this
 * override, Spring Boot 4.0.6's @Scheduled initial-delay defaults can fire the
 * background tick during the test window, producing a duplicate admission email
 * that fails the {@code verify(...).sendEmail(...)} expectation.
 */
@SpringBootTest(properties = "spring.task.scheduling.enabled=false")
class QueueAdmissionEmailIntegrationTest {

    @Autowired private QueueAdmissionScheduler queueAdmissionScheduler;
    @Autowired private EventRepository eventRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private QueueRepository queueRepository;
    @Autowired private UserRepository userRepository;

    @MockitoSpyBean
    private EmailService emailService;

    private User testUser;
    private Event testEvent;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setEmail(UUID.randomUUID() + "@queueemail.test");
        testUser.setPassword("$2a$10$dummyhashfortest");
        testUser.setEmailVerified(true);
        testUser = userRepository.save(testUser);

        testEvent = eventRepository.save(
                new Event("Queue Email Test Concert", null, Instant.now().plusSeconds(86400), (UUID) null));
        testEvent.updateQueueSettings(true, null);
        testEvent = eventRepository.save(testEvent);

        seatRepository.save(new Seat(testEvent, "GA", "1", "1", new BigDecimal("50.00")));
    }

    @Test
    void admitWaitingUsers_sendsAdmissionEmailToUser() {
        queueRepository.save(
                new QueueEntry(testEvent.getId(), testUser.getId(),
                        UUID.randomUUID().toString(), 1));

        queueAdmissionScheduler.admitWaitingUsers();

        QueueEntry admitted = queueRepository.findByEventIdAndUserId(
                testEvent.getId(), testUser.getId()).orElseThrow();
        assertThat(admitted.getStatus()).isEqualTo(QueueStatus.ADMITTED);
        assertThat(admitted.getExpiresAt()).isAfter(Instant.now());

        verify(emailService).sendEmail(
                eq(testUser.getEmail()),
                contains("admitted"),
                anyString());
    }

    @Test
    void admitWaitingUsers_noWaitingEntries_sendsNoEmail() {
        // No QueueEntry created — scheduler should be a no-op
        queueAdmissionScheduler.admitWaitingUsers();

        verifyNoInteractions(emailService);
    }
}
