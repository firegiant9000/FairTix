-- M2-01 (Phase A): per-event ACL overrides for door/box-office staff.
--
-- Roadmap notes the M3 scanner phase needs to grant DOOR access for a single
-- show without giving permanent org-wide access. Seeding the table now (UI
-- deferred per the plan extras) lets the M3 work focus on UI + scanning, not
-- data model churn under time pressure.
--
-- The (user_id, event_id, role) triple is the natural identity: a user can
-- hold different per-event roles across different shows.

CREATE TABLE IF NOT EXISTS event_staff_assignments (
    id          UUID PRIMARY KEY,
    user_id     UUID NOT NULL REFERENCES users(id),
    event_id    UUID NOT NULL REFERENCES events(id),
    role        VARCHAR(32) NOT NULL,
    starts_at   TIMESTAMPTZ,
    ends_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by  UUID REFERENCES users(id),
    UNIQUE (user_id, event_id, role)
);

CREATE INDEX IF NOT EXISTS idx_event_staff_user  ON event_staff_assignments(user_id);
CREATE INDEX IF NOT EXISTS idx_event_staff_event ON event_staff_assignments(event_id);
