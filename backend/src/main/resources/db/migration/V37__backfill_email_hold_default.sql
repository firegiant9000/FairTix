-- #162 follow-up: V8 created notification_preferences with email_hold defaulting
-- to FALSE. Before #162, the codebase ignored the preference and sent
-- hold-expiring emails to everyone. Now that NotificationGate enforces the
-- preference, every existing user silently stops receiving them — a regression
-- they never opted into.
--
-- Strategy: flip email_hold to TRUE only for rows that were never touched
-- (updated_at = created_at, i.e. the user never visited the prefs page). Rows
-- where the user explicitly set the value have differing timestamps and are
-- left alone, respecting any deliberate opt-out.
--
-- Idempotent: re-running is a no-op because we only flip rows that still match
-- the "untouched" signature.

UPDATE notification_preferences
SET email_hold = TRUE,
    updated_at = NOW()
WHERE email_hold = FALSE
  AND updated_at = created_at;

-- We do NOT change the column default. New users land with email_hold = FALSE
-- by design (per V8); the preferences page is responsible for surfacing the
-- toggle on first signup.
