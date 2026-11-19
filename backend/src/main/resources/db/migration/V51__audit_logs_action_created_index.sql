-- Index audit_logs by (action, created_at) for the runbook check
-- stripe_charges_without_order (docs/runbooks/cap-queue-checks.sql).
--
-- That check keeps unbound charges (STRIPE_ORDER_FAILED with
-- error=PaymentIntentUnbound) listed past its 14-day window until the owner
-- records a STRIPE_CHARGE_REVIEWED row. Without an index that leads with
-- action, both the main scan and the per-row STRIPE_CHARGE_REVIEWED lookup read
-- all of audit_logs, and the check runs under a 5 s statement timeout.
--
-- No CONCURRENTLY: Flyway runs inside a transaction. A plain CREATE INDEX
-- blocks writes to audit_logs while it builds, so deploy this outside an
-- on-sale. If it is too slow to build in prod, promote it to an out-of-band
-- CREATE INDEX CONCURRENTLY with the same name; IF NOT EXISTS then skips it here.
CREATE INDEX IF NOT EXISTS idx_audit_logs_action_created
    ON audit_logs (action, created_at);
