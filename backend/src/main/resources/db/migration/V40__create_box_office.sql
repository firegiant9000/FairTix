-- Phase D (M2-09..M2-11): box-office walk-up sales and end-of-night reconciliation.
--
-- A session is the equivalent of a drawer shift: one staff member opens it
-- with an opening cash count, sells tickets through it, and closes it with a
-- closing count + manager sign-off. Sales are denormalized into
-- box_office_sales so reconciliation does not have to walk Order/Ticket
-- aggregates during settlement (which the M2-15 day-of-show report relies on).
--
-- Org-scoped: every session and sale is tied to organization_id so the
-- @OrgScoped interceptor (Phase A) authorizes access without cross-org leak.

CREATE TABLE IF NOT EXISTS box_office_sessions (
    id                       UUID PRIMARY KEY,
    organization_id          UUID NOT NULL REFERENCES organizations(id),
    staff_user_id            UUID NOT NULL REFERENCES users(id),
    status                   VARCHAR(16) NOT NULL,
    opening_cash             NUMERIC(10, 2) NOT NULL,
    closing_cash             NUMERIC(10, 2),
    expected_cash            NUMERIC(10, 2),
    variance                 NUMERIC(10, 2),
    variance_reason          TEXT,
    signed_off_by_user_id    UUID REFERENCES users(id),
    signed_off_at            TIMESTAMPTZ,
    opened_at                TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    closed_at                TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_box_office_sessions_org    ON box_office_sessions(organization_id);
CREATE INDEX IF NOT EXISTS idx_box_office_sessions_staff  ON box_office_sessions(staff_user_id);
CREATE INDEX IF NOT EXISTS idx_box_office_sessions_status ON box_office_sessions(status);

-- One open session per staff per org. A staff member opening a second drawer
-- without closing the first would silently break the variance math.
CREATE UNIQUE INDEX IF NOT EXISTS uq_box_office_sessions_open_per_staff
    ON box_office_sessions(organization_id, staff_user_id)
    WHERE status = 'OPEN';

CREATE TABLE IF NOT EXISTS box_office_sales (
    id                       UUID PRIMARY KEY,
    session_id               UUID NOT NULL REFERENCES box_office_sessions(id),
    organization_id          UUID NOT NULL REFERENCES organizations(id),
    event_id                 UUID NOT NULL REFERENCES events(id),
    order_id                 UUID REFERENCES orders(id),
    method                   VARCHAR(16) NOT NULL,
    amount                   NUMERIC(10, 2) NOT NULL,
    seat_count               INTEGER NOT NULL,
    customer_email           VARCHAR(255),
    customer_name            VARCHAR(255),
    comp_reason              TEXT,
    stripe_payment_intent_id VARCHAR(64),
    terminal_reader_id       VARCHAR(64),
    staff_user_id            UUID NOT NULL REFERENCES users(id),
    created_at               TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_box_office_sales_session ON box_office_sales(session_id);
CREATE INDEX IF NOT EXISTS idx_box_office_sales_org     ON box_office_sales(organization_id);
CREATE INDEX IF NOT EXISTS idx_box_office_sales_event   ON box_office_sales(event_id);
CREATE INDEX IF NOT EXISTS idx_box_office_sales_method  ON box_office_sales(method);
