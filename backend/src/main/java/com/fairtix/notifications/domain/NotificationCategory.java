package com.fairtix.notifications.domain;

import java.util.function.Predicate;

public enum NotificationCategory {
    ACCOUNT_VERIFICATION(true, prefs -> true),
    PASSWORD_RESET(true, prefs -> true),
    QUEUE_ADMISSION(true, prefs -> true),
    EVENT_CANCELLED(true, prefs -> true),
    ORDER(false, NotificationPreference::isEmailOrder),
    REFUND(false, NotificationPreference::isEmailOrder),
    TICKET_TRANSFER(false, NotificationPreference::isEmailTicket),
    HOLD_EXPIRING(false, NotificationPreference::isEmailHold),
    SUPPORT(false, NotificationPreference::isEmailSupport),
    MARKETING(false, NotificationPreference::isEmailMarketing);

    private final boolean transactional;
    private final Predicate<NotificationPreference> emailEnabled;

    NotificationCategory(boolean transactional, Predicate<NotificationPreference> emailEnabled) {
        this.transactional = transactional;
        this.emailEnabled = emailEnabled;
    }

    public boolean isTransactional() {
        return transactional;
    }

    public boolean isEmailEnabledFor(NotificationPreference prefs) {
        return emailEnabled.test(prefs);
    }
}
