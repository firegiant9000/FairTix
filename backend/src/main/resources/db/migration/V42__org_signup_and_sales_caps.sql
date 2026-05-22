-- Phase H (M2-24, M2-25): organizer signup wizard, admin approval queue,
-- and new-org sales rate limits.
--
-- The signup wizard collects legal/tax/contact details that don't fit
-- on the bare organizations row, plus a reference-check field for the
-- Phase 6 referral-credit work. EIN is encrypted at rest in the application
-- layer; we store it ciphertext-only here.
--
-- Sales caps are enforced in PaymentService: gross dollars per rolling
-- 24h, ratcheting up as the org completes successful payout cycles with
-- zero disputes.

ALTER TABLE organizations
    ADD COLUMN IF NOT EXISTS legal_name                  VARCHAR(255),
    ADD COLUMN IF NOT EXISTS dba                         VARCHAR(255),
    ADD COLUMN IF NOT EXISTS address_line1               VARCHAR(255),
    ADD COLUMN IF NOT EXISTS address_line2               VARCHAR(255),
    ADD COLUMN IF NOT EXISTS address_city                VARCHAR(120),
    ADD COLUMN IF NOT EXISTS address_region              VARCHAR(120),
    ADD COLUMN IF NOT EXISTS address_postal_code         VARCHAR(32),
    ADD COLUMN IF NOT EXISTS address_country             VARCHAR(2),
    ADD COLUMN IF NOT EXISTS primary_contact_name        VARCHAR(255),
    ADD COLUMN IF NOT EXISTS primary_contact_phone       VARCHAR(64),
    -- EIN stored encrypted; the application layer is the source of truth for
    -- key management. Never read this column directly outside the encryption
    -- helper.
    ADD COLUMN IF NOT EXISTS ein_encrypted               TEXT,
    ADD COLUMN IF NOT EXISTS referred_by                 VARCHAR(255),
    -- Approval queue
    ADD COLUMN IF NOT EXISTS submitted_for_review_at     TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS reviewed_at                 TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS reviewed_by_user_id         UUID REFERENCES users(id),
    ADD COLUMN IF NOT EXISTS rejection_reason            TEXT,
    -- Sales caps. cap_cents NULL means "use the tier default for this org's age";
    -- non-null is an admin override (paired with plan_overrides_until).
    ADD COLUMN IF NOT EXISTS daily_sales_cap_cents       BIGINT,
    ADD COLUMN IF NOT EXISTS plan_overrides_until        TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS successful_payout_cycles    INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS dispute_count               INTEGER NOT NULL DEFAULT 0;

-- PENDING_REVIEW is the post-wizard, awaiting-admin state.
-- Existing PENDING/ACTIVE/SUSPENDED rows are unaffected; the check is
-- application-side (OrganizationStatus enum), but document the allowed
-- values for downstream consumers / reports.
COMMENT ON COLUMN organizations.status IS
    'PENDING | PENDING_REVIEW | ACTIVE | REJECTED | SUSPENDED';

CREATE INDEX IF NOT EXISTS idx_organizations_status_submitted
    ON organizations(status, submitted_for_review_at);

-- Reference-check field doubles as the referral source for Phase 6 credit
-- attribution. Index because the GTM dashboard groups by it.
CREATE INDEX IF NOT EXISTS idx_organizations_referred_by
    ON organizations(referred_by) WHERE referred_by IS NOT NULL;

-- Sales ledger feeds the rolling-24h cap check. Joining payment_records →
-- orders → holds → seats → events → organizations on every intent creation
-- would be too slow and too fragile to org-id leakage in middle layers, so
-- we record gross per org per charge once and aggregate against this.
-- Box-office cash/comp sales are recorded here too, so the cap is honest.
CREATE TABLE IF NOT EXISTS org_sales_ledger (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL REFERENCES organizations(id),
    amount_cents        BIGINT NOT NULL,
    channel             VARCHAR(16) NOT NULL, -- 'ONLINE' | 'BOX_OFFICE'
    source_id           VARCHAR(128),         -- pi_xxx or box_office_sales.id
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_org_sales_ledger_channel
        CHECK (channel IN ('ONLINE', 'BOX_OFFICE'))
);

CREATE INDEX IF NOT EXISTS idx_org_sales_ledger_org_time
    ON org_sales_ledger(organization_id, created_at DESC);
