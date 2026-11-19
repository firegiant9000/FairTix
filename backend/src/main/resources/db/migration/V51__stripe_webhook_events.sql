-- Ledger of the Stripe webhook events FairTix has handled, keyed on Stripe's event id.
-- The row is inserted in the same transaction as the handling, so a redelivered event
-- is answered 200 without being processed (or audited) again, and an event whose
-- handling rolled back is processed again when Stripe retries it.
CREATE TABLE stripe_webhook_events (
    event_id    VARCHAR(255) PRIMARY KEY,
    event_type  VARCHAR(100) NOT NULL,
    received_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
