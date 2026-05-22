-- M2-04: indexes for the organizer dashboard hot paths.
--
-- The query surface lives in DashboardQueryRepository. Each query below
-- repeatedly filters by (event.organization_id, ...) or by
-- (tickets.event_id, tickets.status, tickets.issued_at). The single-column
-- indexes on tickets.user_id / tickets.event_id already exist (V1 baseline);
-- the compound and org-scoped indexes do not. Explain plans on a seeded
-- dataset show seq scans on events and tickets for the overview widgets;
-- these indexes turn them into index range scans.
--
-- All indexes are CREATE INDEX IF NOT EXISTS so re-applying against a
-- partially-migrated env is safe. No CONCURRENTLY because Flyway runs
-- inside a transaction; if any of these become slow to build in prod they
-- should be promoted to an out-of-band migration.

-- events.(organization_id, start_time) — drives buildTodayShows,
-- eventInventoryRollup, listEventsForOrg, topEventsByVelocity.
CREATE INDEX IF NOT EXISTS idx_events_org_start
    ON events (organization_id, start_time);

-- events.(organization_id, status) — drives topEventsByVelocity filter.
CREATE INDEX IF NOT EXISTS idx_events_org_status
    ON events (organization_id, status);

-- tickets.(event_id, status, issued_at) — drives velocity, ticketsSoldBetween,
-- grossRevenueBetween, eventInventoryRollup. The leading event_id is already
-- indexed but the compound makes the status+date scan an index-only operation.
CREATE INDEX IF NOT EXISTS idx_tickets_event_status_issued
    ON tickets (event_id, status, issued_at);

-- tickets.(issued_at DESC) — drives the org-wide recentSales ORDER BY.
-- Postgres can scan a single-column btree backwards, so a plain index works.
CREATE INDEX IF NOT EXISTS idx_tickets_issued_at
    ON tickets (issued_at);

-- refund_requests.(order_id, status) — drives pendingRefundCount,
-- oldestPendingRefund, refundsPendingForEvent, refundsCompletedForEvent.
CREATE INDEX IF NOT EXISTS idx_refund_requests_order_status
    ON refund_requests (order_id, status);

-- audit_logs.(resource_id, action, created_at) — drives the M2-18
-- TaxThresholdAlertScheduler dedup query and any future organizer-scoped
-- audit lookups. Matches Appendix B V49 intent without conflicting names.
CREATE INDEX IF NOT EXISTS idx_audit_logs_resource_action_created
    ON audit_logs (resource_id, action, created_at);
