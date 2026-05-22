-- Phase 2E: comps, event holds (artist/press/house), and will-call.
--
-- Adds a `kind` discriminator to tickets so dashboards can split PAID from
-- COMP/HOLD revenue without ambiguity, plus a separate event_holds table for
-- promoter-side reservations (distinct from the Redis-backed cart hold).

ALTER TABLE tickets
    ADD COLUMN IF NOT EXISTS kind                 VARCHAR(32) NOT NULL DEFAULT 'PAID',
    ADD COLUMN IF NOT EXISTS kind_reason          TEXT,
    ADD COLUMN IF NOT EXISTS kind_issued_by       UUID REFERENCES users(id),
    -- 2F stretch column piggybacked here per plan ("trivially cheap if added now")
    ADD COLUMN IF NOT EXISTS sold_by_user_id      UUID REFERENCES users(id),
    ADD COLUMN IF NOT EXISTS recipient_name       VARCHAR(255),
    ADD COLUMN IF NOT EXISTS recipient_email      VARCHAR(255),
    ADD COLUMN IF NOT EXISTS will_call            BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS will_call_claimed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS will_call_claimed_by UUID REFERENCES users(id);

ALTER TABLE tickets DROP CONSTRAINT IF EXISTS chk_ticket_kind;
ALTER TABLE tickets ADD CONSTRAINT chk_ticket_kind
    CHECK (kind IN ('PAID', 'COMP', 'HOLD_ARTIST', 'HOLD_PRESS', 'HOLD_HOUSE'));

CREATE INDEX IF NOT EXISTS idx_tickets_kind            ON tickets(kind);
CREATE INDEX IF NOT EXISTS idx_tickets_event_kind      ON tickets(event_id, kind);
CREATE INDEX IF NOT EXISTS idx_tickets_will_call_event ON tickets(event_id) WHERE will_call = TRUE;

ALTER TABLE events
    ADD COLUMN IF NOT EXISTS comp_limit INTEGER;

-- Promoter-side reservations: distinct from the Redis cart hold. They reserve
-- inventory without charging, can be converted to comp tickets, released back
-- to availability, or auto-released by the scheduled job.
CREATE TABLE IF NOT EXISTS event_holds (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id             UUID NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    seat_id              UUID NOT NULL REFERENCES seats(id),
    category             VARCHAR(32) NOT NULL,
    note                 TEXT,
    created_by           UUID NOT NULL REFERENCES users(id),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    auto_release_at      TIMESTAMPTZ,
    released_at          TIMESTAMPTZ,
    released_by          UUID REFERENCES users(id),
    converted_ticket_id  UUID REFERENCES tickets(id),
    CONSTRAINT chk_event_hold_category
        CHECK (category IN ('ARTIST', 'PRESS', 'HOUSE'))
);

-- A seat may have only one *active* event-side hold at a time. Partial unique
-- index lets historical (released/converted) rows accumulate without conflict.
CREATE UNIQUE INDEX IF NOT EXISTS uk_event_hold_active_seat
    ON event_holds(seat_id)
    WHERE released_at IS NULL AND converted_ticket_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_event_holds_event_category
    ON event_holds(event_id, category);
CREATE INDEX IF NOT EXISTS idx_event_holds_auto_release
    ON event_holds(auto_release_at) WHERE released_at IS NULL AND converted_ticket_id IS NULL;

-- Convenience view: revenue/sold queries should read from this (or filter
-- explicitly on kind='PAID'); never SUM(price) directly off tickets.
CREATE OR REPLACE VIEW paid_tickets AS
    SELECT * FROM tickets
    WHERE kind = 'PAID'
      AND status NOT IN ('CANCELLED', 'REFUNDED');
