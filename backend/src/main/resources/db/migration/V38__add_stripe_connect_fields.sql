-- M2-06: Stripe Connect Standard onboarding fields per organization.
-- Tracks the connected account, KYC + payout enablement, and the most recent
-- requirements / disabled-reason payloads Stripe ships on account.updated.

ALTER TABLE organizations ADD COLUMN IF NOT EXISTS stripe_connect_account_id VARCHAR(64);
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS stripe_connect_country     VARCHAR(2);
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS stripe_charges_enabled     BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS stripe_payouts_enabled     BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS stripe_details_submitted   BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS stripe_disabled_reason     VARCHAR(255);
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS stripe_requirements_json   TEXT;
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS stripe_payouts_frozen      BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS stripe_connected_at        TIMESTAMPTZ;

CREATE UNIQUE INDEX IF NOT EXISTS uq_organizations_stripe_connect_account_id
    ON organizations(stripe_connect_account_id)
    WHERE stripe_connect_account_id IS NOT NULL;
