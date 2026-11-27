package com.fairtix.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fairtix.auth.application.PasswordResetService;
import com.fairtix.events.application.EventService;
import com.fairtix.events.domain.Event;
import com.fairtix.events.infrastructure.EventRepository;
import com.fairtix.inventory.domain.Seat;
import com.fairtix.inventory.infrastructure.SeatRepository;
import com.fairtix.notifications.application.EmailService;
import com.fairtix.notifications.application.NotificationGate;
import com.fairtix.orders.domain.Order;
import com.fairtix.orders.domain.OrderStatus;
import com.fairtix.orders.infrastructure.OrderRepository;
import com.fairtix.tickets.domain.Ticket;
import com.fairtix.tickets.infrastructure.TicketRepository;
import com.fairtix.users.domain.User;
import com.fairtix.users.infrastructure.UserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * When mail fails, the log line names the user by id, never by email address. The
 * address must not come in through the exception message either: a mail failure's
 * message can quote the recipient (here it does, on purpose).
 *
 * <p>Red before the fix: the event-cancellation warning logged {@code user.getEmail()}
 * and the password-reset error logged {@code to=<address>}, both with the exception
 * message.
 *
 * <p>Same {@code EmailService} spy as {@code EventCancellationIntegrationTest}, so the
 * cached context is shared. Not {@code @Transactional}: cancellation mail goes out
 * after commit.
 */
@SpringBootTest
class MailFailureLogsNoAddressTest {

  @Autowired private EventService eventService;
  @Autowired private PasswordResetService passwordResetService;
  @Autowired private EventRepository eventRepository;
  @Autowired private SeatRepository seatRepository;
  @Autowired private OrderRepository orderRepository;
  @Autowired private TicketRepository ticketRepository;
  @Autowired private UserRepository userRepository;

  @MockitoSpyBean private EmailService emailService;

  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
  private Logger capturedLogger;

  @AfterEach
  void detach() {
    if (capturedLogger != null) {
      capturedLogger.detachAppender(logs);
    }
  }

  @Test
  void cancellationMailFailure_logsUserIdNotAddress() {
    User holder = newUser();
    UUID adminId = newUser().getId();
    Event event = eventRepository.save(
        new Event("Log Test Concert", null, Instant.now().plusSeconds(86400), (UUID) null));
    Seat seat = seatRepository.save(new Seat(event, "GA", "1", "1", new BigDecimal("50.00")));
    Order order = orderRepository.save(
        new Order(holder, List.of(), new BigDecimal("50.00"), "USD", OrderStatus.COMPLETED));
    ticketRepository.save(new Ticket(order, holder, seat, event, new BigDecimal("50.00")));
    failMailTo(holder);
    // The send runs on the mail executor, so the gate logs its failure, not the caller.
    capture(NotificationGate.class);

    eventService.cancelEvent(event.getId(), adminId, "Venue unavailable");

    assertLoggedFailureWithoutAddress(holder);
  }

  @Test
  void passwordResetMailFailure_logsUserIdNotAddress() {
    User user = newUser();
    failMailTo(user);
    capture(NotificationGate.class);

    passwordResetService.requestReset(user.getEmail());

    assertLoggedFailureWithoutAddress(user);
  }

  /** The unknown-account branch logs at debug; staging runs with debug on. Review finding. */
  @Test
  void passwordResetForUnknownAddress_logsNoAddress() {
    String unknown = UUID.randomUUID() + "@maillog.test";
    capture(PasswordResetService.class);
    Level before = capturedLogger.getLevel();
    capturedLogger.setLevel(Level.DEBUG);
    try {
      passwordResetService.requestReset(unknown);
    } finally {
      capturedLogger.setLevel(before);
    }

    List<String> lines = logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    assertThat(lines).anySatisfy(line -> assertThat(line).contains("unknown or deleted"));
    assertThat(lines).noneSatisfy(line -> assertThat(line).containsIgnoringCase(unknown));
  }

  private void failMailTo(User user) {
    doThrow(new IllegalStateException("Invalid Addresses: " + user.getEmail()))
        .when(emailService).sendEmail(eq(user.getEmail()), anyString(), anyString());
  }

  private void capture(Class<?> type) {
    capturedLogger = (Logger) LoggerFactory.getLogger(type);
    logs.start();
    capturedLogger.addAppender(logs);
  }

  private void assertLoggedFailureWithoutAddress(User user) {
    List<String> lines = logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    assertThat(lines).anySatisfy(line -> assertThat(line)
        .contains("Failed to send")
        .contains(user.getId().toString()));
    assertThat(lines).noneSatisfy(line -> assertThat(line).containsIgnoringCase(user.getEmail()));
  }

  private User newUser() {
    User user = new User();
    user.setEmail(UUID.randomUUID() + "@maillog.test");
    user.setPassword("$2a$10$dummyhashfortest");
    user.setEmailVerified(true);
    return userRepository.save(user);
  }
}
