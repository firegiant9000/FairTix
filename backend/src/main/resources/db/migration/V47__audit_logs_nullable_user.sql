-- A failed login for an email with no account is audited with no user
-- (LOGIN_FAILED / ACCOUNT_LOCKED). Readers of audit_logs.user_id must allow NULL;
-- the behaviour sweep's DISTINCT user_id query filters it out.
-- Metadata-only on PostgreSQL: no table rewrite.
ALTER TABLE audit_logs ALTER COLUMN user_id DROP NOT NULL;
