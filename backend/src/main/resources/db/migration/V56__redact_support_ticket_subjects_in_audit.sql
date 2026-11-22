-- Redact user-written support-ticket subjects from existing audit rows.
--
-- Until PR #340, SupportTicketService.createTicket wrote audit_logs rows with
-- details = 'subject=' || <the user's free-text subject>. #340 writes
-- details = 'category=' || <category> instead, but rows already in dev and staging
-- still hold the subjects. The ticket itself keeps its subject in support_tickets.
--
-- This only cleans the database. Application logs that already printed those
-- subjects are out of scope.
--
-- Merge order: after #340 (so no new 'subject=' rows are written) and after V55
-- (origin/chore/fictional-demo-seed-names), which already claimed the number below this one.
--
-- Idempotent: after one run no row matches the first statement, and the second
-- statement skips rows that are already redacted. Only details changes.

-- 1. Tickets that still exist: same value #340 now writes.
UPDATE audit_logs a
SET details = 'category=' || t.category
FROM support_tickets t
WHERE a.resource_id = t.id
  AND a.action = 'CREATE'
  AND a.resource_type = 'SUPPORT_TICKET'
  AND a.details LIKE 'subject=%';

-- 2. Tickets that no longer exist: nothing to derive a category from.
UPDATE audit_logs
SET details = 'subject=[redacted]'
WHERE action = 'CREATE'
  AND resource_type = 'SUPPORT_TICKET'
  AND details LIKE 'subject=%'
  AND details <> 'subject=[redacted]';
