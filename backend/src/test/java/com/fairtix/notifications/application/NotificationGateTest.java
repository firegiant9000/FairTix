package com.fairtix.notifications.application;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fairtix.notifications.domain.NotificationCategory;
import com.fairtix.notifications.domain.NotificationPreference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit coverage of the gate. Pre-M1, suppression was silently broken
 * because no call site consulted preferences; this suite locks the
 * transactional/gated split per category so a regression fails CI before it
 * reaches staging.
 */
class NotificationGateTest {

  private NotificationPreferenceService prefs;
  private EmailService email;
  private NotificationGate gate;
  private UUID userId;
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
  private Logger gateLogger;

  @BeforeEach
  void setUp() {
    prefs = mock(NotificationPreferenceService.class);
    email = mock(EmailService.class);
    gate = new NotificationGate(prefs, email);
    userId = UUID.randomUUID();
    gateLogger = (Logger) LoggerFactory.getLogger(NotificationGate.class);
    logs.start();
    gateLogger.addAppender(logs);
  }

  @AfterEach
  void detachLogCapture() {
    gateLogger.detachAppender(logs);
  }

  // ---- Transactional categories always send, no pref lookup ----

  @Test
  void accountVerificationAlwaysSendsAndSkipsPrefLookup() {
    gate.sendEmail(userId, NotificationCategory.ACCOUNT_VERIFICATION, "u@x.test", "s", "b");

    verify(email).sendEmail("u@x.test", "s", "b");
    verify(prefs, never()).getPreferences(any());
  }

  @Test
  void passwordResetAlwaysSends() {
    gate.sendEmail(userId, NotificationCategory.PASSWORD_RESET, "u@x.test", "s", "b");
    verify(email).sendEmail(anyString(), anyString(), anyString());
  }

  @Test
  void queueAdmissionAlwaysSends() {
    gate.sendEmail(userId, NotificationCategory.QUEUE_ADMISSION, "u@x.test", "s", "b");
    verify(email).sendEmail(anyString(), anyString(), anyString());
  }

  @Test
  void eventCancelledAlwaysSendsEvenIfUserOptedOutOfEverything() {
    when(prefs.getPreferences(userId)).thenReturn(allOptedOut(userId));

    gate.sendEmail(userId, NotificationCategory.EVENT_CANCELLED, "u@x.test", "s", "b");

    verify(email).sendEmail(anyString(), anyString(), anyString());
  }

  // ---- Gated categories respect prefs ----

  @Test
  void orderSendsWhenEmailOrderTrue() {
    NotificationPreference p = new NotificationPreference(userId);
    p.setEmailOrder(true);
    when(prefs.getPreferences(userId)).thenReturn(p);

    gate.sendEmail(userId, NotificationCategory.ORDER, "u@x.test", "s", "b");
    verify(email).sendEmail("u@x.test", "s", "b");
  }

  @Test
  void orderSuppressedWhenEmailOrderFalse() {
    NotificationPreference p = new NotificationPreference(userId);
    p.setEmailOrder(false);
    when(prefs.getPreferences(userId)).thenReturn(p);

    gate.sendEmail(userId, NotificationCategory.ORDER, "u@x.test", "s", "b");
    verify(email, never()).sendEmail(anyString(), anyString(), anyString());
  }

  @Test
  void refundConsultsEmailOrderPreference() {
    NotificationPreference p = new NotificationPreference(userId);
    p.setEmailOrder(false);
    when(prefs.getPreferences(userId)).thenReturn(p);

    gate.sendEmail(userId, NotificationCategory.REFUND, "u@x.test", "s", "b");
    verify(email, never()).sendEmail(anyString(), anyString(), anyString());
  }

  @Test
  void ticketTransferConsultsEmailTicketPreference() {
    NotificationPreference p = new NotificationPreference(userId);
    p.setEmailTicket(false);
    when(prefs.getPreferences(userId)).thenReturn(p);

    gate.sendEmail(userId, NotificationCategory.TICKET_TRANSFER, "u@x.test", "s", "b");
    verify(email, never()).sendEmail(anyString(), anyString(), anyString());
  }

  @Test
  void holdExpiringConsultsEmailHoldPreference() {
    NotificationPreference optIn = new NotificationPreference(userId);
    optIn.setEmailHold(true);
    when(prefs.getPreferences(userId)).thenReturn(optIn);
    gate.sendEmail(userId, NotificationCategory.HOLD_EXPIRING, "a@x.test", "s", "b");
    verify(email, times(1)).sendEmail(anyString(), anyString(), anyString());

    NotificationPreference optOut = new NotificationPreference(userId);
    optOut.setEmailHold(false);
    when(prefs.getPreferences(userId)).thenReturn(optOut);
    gate.sendEmail(userId, NotificationCategory.HOLD_EXPIRING, "b@x.test", "s", "b");
    // still exactly one — opt-out did not call sendEmail again
    verify(email, times(1)).sendEmail(anyString(), anyString(), anyString());
  }

  @Test
  void supportConsultsEmailSupportPreference() {
    NotificationPreference p = new NotificationPreference(userId);
    p.setEmailSupport(false);
    when(prefs.getPreferences(userId)).thenReturn(p);

    gate.sendEmail(userId, NotificationCategory.SUPPORT, "u@x.test", "s", "b");
    verify(email, never()).sendEmail(anyString(), anyString(), anyString());
  }

  @Test
  void marketingConsultsEmailMarketingPreference() {
    NotificationPreference p = new NotificationPreference(userId);
    p.setEmailMarketing(false);
    when(prefs.getPreferences(userId)).thenReturn(p);

    gate.sendEmail(userId, NotificationCategory.MARKETING, "u@x.test", "s", "b");
    verify(email, never()).sendEmail(anyString(), anyString(), anyString());
  }

  @Test
  void marketingSendsWhenOptedIn() {
    NotificationPreference p = new NotificationPreference(userId);
    p.setEmailMarketing(true);
    when(prefs.getPreferences(userId)).thenReturn(p);

    gate.sendEmail(userId, NotificationCategory.MARKETING, "u@x.test", "s", "b");
    verify(email).sendEmail("u@x.test", "s", "b");
  }

  // ---- Edge cases ----

  @Test
  void nullUserIdSuppressesGatedMailButNotTransactional() {
    gate.sendEmail(null, NotificationCategory.ORDER, "u@x.test", "s", "b");
    verify(email, never()).sendEmail(anyString(), anyString(), anyString());

    gate.sendEmail(null, NotificationCategory.PASSWORD_RESET, "u@x.test", "s", "b");
    verify(email, times(1)).sendEmail("u@x.test", "s", "b");
  }

  @Test
  void shouldSendIsConsistentWithSendEmailDecision() {
    NotificationPreference optedOut = new NotificationPreference(userId);
    optedOut.setEmailMarketing(false);
    when(prefs.getPreferences(userId)).thenReturn(optedOut);

    assertThat(gate.shouldSend(userId, NotificationCategory.MARKETING)).isFalse();
    assertThat(gate.shouldSend(userId, NotificationCategory.PASSWORD_RESET)).isTrue();
    assertThat(gate.shouldSend(null, NotificationCategory.ORDER)).isFalse();
    assertThat(gate.shouldSend(null, NotificationCategory.EVENT_CANCELLED)).isTrue();
  }

  @Test
  void exactArgsForwardedToEmailService() {
    NotificationPreference p = new NotificationPreference(userId);
    p.setEmailOrder(true);
    when(prefs.getPreferences(userId)).thenReturn(p);

    gate.sendEmail(userId, NotificationCategory.ORDER, "alice@example.com",
        "Your order is confirmed", "<p>Thanks</p>");

    ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> subj = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
    verify(email).sendEmail(to.capture(), subj.capture(), body.capture());

    assertThat(to.getValue()).isEqualTo("alice@example.com");
    assertThat(subj.getValue()).isEqualTo("Your order is confirmed");
    assertThat(body.getValue()).isEqualTo("<p>Thanks</p>");
  }

  // ---- Sent-mail logging ----

  @Test
  void sentMailLogsCategoryWithoutAddressOrSubject() {
    NotificationPreference p = new NotificationPreference(userId);
    p.setEmailOrder(true);
    when(prefs.getPreferences(userId)).thenReturn(p);

    gate.sendEmail(userId, NotificationCategory.ORDER, "alice@example.com",
        "Your order is confirmed", "<p>Thanks</p>");

    List<String> sentLines = logs.list.stream()
        .map(ILoggingEvent::getFormattedMessage)
        .filter(m -> m.contains("Notification handed to mail service"))
        .toList();
    assertThat(sentLines).hasSize(1);
    // The gate can't tell whether mail is on; delivery is logged by the EmailService.
    assertThat(logs.list).noneMatch(e -> e.getFormattedMessage().contains("Notification sent"));
    assertThat(sentLines.get(0))
        .contains("category=ORDER")
        .doesNotContain("alice@example.com")
        .doesNotContain("Your order is confirmed");
  }

  @Test
  void failedSendLogsNoNotificationSentLine() {
    NotificationPreference p = new NotificationPreference(userId);
    p.setEmailOrder(true);
    when(prefs.getPreferences(userId)).thenReturn(p);
    doThrow(new IllegalStateException("Invalid Addresses: alice@example.com"))
        .when(email).sendEmail(anyString(), anyString(), anyString());

    // Not rethrown: on the mail pool the caller has already returned.
    gate.sendEmail(userId, NotificationCategory.ORDER,
        "alice@example.com", "Your order is confirmed", "<p>Thanks</p>");

    assertThat(logs.list)
        .noneMatch(e -> e.getFormattedMessage().contains("Notification handed to mail service"));
    List<String> failures = logs.list.stream()
        .map(ILoggingEvent::getFormattedMessage)
        .filter(m -> m.contains("Failed to send notification email"))
        .toList();
    assertThat(failures).hasSize(1);
    assertThat(failures.get(0))
        .contains("userId=" + userId)
        .contains("category=ORDER")
        .contains("error=IllegalStateException")
        .doesNotContain("alice@example.com");
  }

  /**
   * On a real pool the caller gets control back while SMTP is still running: sendEmail
   * returns with the send blocked, and the send then completes on a mail thread.
   */
  @Test
  void sendsOffTheCallingThread() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch sent = new CountDownLatch(1);
    AtomicReference<String> sendThread = new AtomicReference<>();
    doAnswer(inv -> {
      sendThread.set(Thread.currentThread().getName());
      release.await(10, TimeUnit.SECONDS);
      sent.countDown();
      return null;
    }).when(email).sendEmail(anyString(), anyString(), anyString());
    ThreadPoolTaskExecutor pool = pool("mail-test-");
    ThreadPoolTaskExecutor bulk = pool("bulk-test-");
    try {
      NotificationGate async = new NotificationGate(prefs, email, pool, bulk);

      async.sendEmail(userId, NotificationCategory.PASSWORD_RESET,
          "alice@example.com", "Reset your password", "<p>Link</p>");

      assertThat(sent.getCount()).as("returned before the send finished").isEqualTo(1);
      release.countDown();
      assertThat(sent.await(10, TimeUnit.SECONDS)).isTrue();
      assertThat(sendThread.get()).startsWith("mail-test-");
    } finally {
      pool.shutdown();
      bulk.shutdown();
    }
  }

  /** Cancellation mail goes to the bulk pool, so it can't queue ahead of one-recipient mail. */
  @Test
  void eventCancelledMail_usesTheBulkPool() throws Exception {
    CountDownLatch sent = new CountDownLatch(1);
    AtomicReference<String> sendThread = new AtomicReference<>();
    doAnswer(inv -> {
      sendThread.set(Thread.currentThread().getName());
      sent.countDown();
      return null;
    }).when(email).sendEmail(anyString(), anyString(), anyString());
    ThreadPoolTaskExecutor pool = pool("mail-test-");
    ThreadPoolTaskExecutor bulk = pool("bulk-test-");
    try {
      new NotificationGate(prefs, email, pool, bulk).sendEmail(userId,
          NotificationCategory.EVENT_CANCELLED, "alice@example.com", "Event Cancelled", "<p>Sorry</p>");

      assertThat(sent.await(10, TimeUnit.SECONDS)).isTrue();
      assertThat(sendThread.get()).startsWith("bulk-test-");
    } finally {
      pool.shutdown();
      bulk.shutdown();
    }
  }

  private static ThreadPoolTaskExecutor pool(String prefix) {
    ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
    pool.setCorePoolSize(1);
    pool.setThreadNamePrefix(prefix);
    pool.initialize();
    return pool;
  }

  // ---- helpers ----

  private NotificationPreference allOptedOut(UUID userId) {
    NotificationPreference p = new NotificationPreference(userId);
    p.setEmailOrder(false);
    p.setEmailTicket(false);
    p.setEmailHold(false);
    p.setEmailSupport(false);
    p.setEmailMarketing(false);
    return p;
  }
}
