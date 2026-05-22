-- Plan-tier scaffolding for organizations. Columns are wired but not enforced
-- until M5 billing lands. All existing orgs default to FREE with NULL credits
-- interpreted as "unlimited" so the unenforced check returns true.

ALTER TABLE organizations ADD COLUMN IF NOT EXISTS plan VARCHAR(32) NOT NULL DEFAULT 'FREE';
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS ticket_credits_remaining INT;
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS ticket_credits_reset_at TIMESTAMPTZ;
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS stripe_customer_id VARCHAR(64);
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS stripe_subscription_id VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_organizations_plan ON organizations(plan);
