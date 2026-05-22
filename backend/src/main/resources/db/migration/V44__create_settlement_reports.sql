-- Phase 2F (M2-15..M2-18): Settlement reports, tax helper, payout report.
--
-- The DOS and settlement reports compute live from tickets/orders/refunds; the
-- only state we persist is the per-event split configuration and the
-- finalization stamp once the artist's settlement is signed off. Stripe payout
-- mapping is cached in stripe_payouts so the dashboard does not hammer Stripe
-- on every load and so we can reconcile historical payouts after the connected
-- account is deauthorized.

-- Settlement split + tax configuration per event. One row per event; absent
-- rows mean "no split configured" and the artist payout column on the
-- settlement is null until config exists.
CREATE TABLE IF NOT EXISTS event_settlements (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id               UUID NOT NULL UNIQUE REFERENCES events(id) ON DELETE CASCADE,
    split_type             VARCHAR(32),
    artist_pct             NUMERIC(5, 4),
    venue_take_off_top     NUMERIC(10, 2),
    tax_rate_pct           NUMERIC(5, 4),
    notes                  TEXT,
    finalized_at           TIMESTAMPTZ,
    finalized_by_user_id   UUID REFERENCES users(id),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_settlement_split_type
        CHECK (split_type IS NULL OR split_type IN ('FLAT_PCT', 'DOOR_DEAL')),
    CONSTRAINT chk_settlement_artist_pct
        CHECK (artist_pct IS NULL OR (artist_pct >= 0 AND artist_pct <= 1)),
    CONSTRAINT chk_settlement_tax_rate
        CHECK (tax_rate_pct IS NULL OR (tax_rate_pct >= 0 AND tax_rate_pct <= 1))
);

-- Stripe payout cache. Synced from Stripe Connect webhooks (payout.paid /
-- payout.failed) and on demand. event mapping uses payment_records →
-- orders → tickets → events; we materialize the joined event ids per payout
-- so the per-payout drill-down does not re-join four tables.
CREATE TABLE IF NOT EXISTS stripe_payouts (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id          UUID NOT NULL REFERENCES organizations(id),
    stripe_payout_id         VARCHAR(64) NOT NULL UNIQUE,
    amount                   NUMERIC(12, 2) NOT NULL,
    currency                 VARCHAR(3) NOT NULL,
    status                   VARCHAR(32) NOT NULL,
    arrival_date             DATE,
    paid_at                  TIMESTAMPTZ,
    failure_code             VARCHAR(64),
    failure_message          VARCHAR(255),
    raw_json                 TEXT,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_stripe_payouts_org ON stripe_payouts(organization_id);
CREATE INDEX IF NOT EXISTS idx_stripe_payouts_arrival ON stripe_payouts(arrival_date);

-- State-level sales tax rate per organization, with optional per-event override
-- in event_settlements.tax_rate_pct. Roadmap M2 scope: configurable flat rate;
-- dynamic / TaxJar-style lookup is deferred to M5+.
ALTER TABLE organizations
    ADD COLUMN IF NOT EXISTS default_tax_rate_pct NUMERIC(5, 4),
    ADD COLUMN IF NOT EXISTS tax_state VARCHAR(2),
    ADD COLUMN IF NOT EXISTS tax_id_ein VARCHAR(32),
    ADD COLUMN IF NOT EXISTS tax_legal_name VARCHAR(255);

ALTER TABLE organizations
    DROP CONSTRAINT IF EXISTS chk_org_default_tax_rate;
ALTER TABLE organizations
    ADD CONSTRAINT chk_org_default_tax_rate
        CHECK (default_tax_rate_pct IS NULL OR (default_tax_rate_pct >= 0 AND default_tax_rate_pct <= 1));
