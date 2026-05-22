package com.fairtix.notifications.application;

/**
 * Low-level email transport. <strong>Do not inject this directly from application
 * code.</strong> Inject {@link NotificationGate} instead so user notification
 * preferences are respected and suppressions are audit-logged. The
 * {@code notification-gate-guard} CI step fails the build if any class outside
 * {@code com.fairtix.notifications} references this type.
 */
public interface EmailService {
    void sendEmail(String to, String subject, String htmlBody);
}
