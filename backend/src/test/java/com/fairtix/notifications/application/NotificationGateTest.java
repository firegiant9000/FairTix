package com.fairtix.notifications.application;

import com.fairtix.notifications.domain.NotificationCategory;
import com.fairtix.notifications.domain.NotificationPreference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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

  @BeforeEach
  void setUp() {
    prefs = mock(NotificationPreferenceService.class);
    email = mock(EmailService.class);
    gate = new NotificationGate(prefs, email);
    userId = UUID.randomUUID();
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
