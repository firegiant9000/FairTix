-- Phase 2G (M2-19..M2-23): per-org branding, per-event page customization,
-- semantic slug history, and custom domains for organizer-facing event pages.
--
-- Branding columns live on `organizations` (one-to-one); event-page customization
-- columns live on `events`. We add a separate `event_slug_history` table so
-- renaming an event slug keeps old shared links alive via 301 redirect. Custom
-- domains live in their own table because an org may attach multiple subdomains
-- over time (apex, www, brand-specific) and verification state is per-domain.

-- --- M2-19: per-org branding -------------------------------------------------

ALTER TABLE organizations
    ADD COLUMN IF NOT EXISTS logo_url           VARCHAR(1024),
    ADD COLUMN IF NOT EXISTS primary_color      VARCHAR(7),
    ADD COLUMN IF NOT EXISTS email_sender_name  VARCHAR(120),
    ADD COLUMN IF NOT EXISTS email_reply_to     VARCHAR(255),
    ADD COLUMN IF NOT EXISTS dark_mode_enabled  BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS statement_descriptor_suffix VARCHAR(22);

ALTER TABLE organizations DROP CONSTRAINT IF EXISTS chk_org_primary_color;
ALTER TABLE organizations
    ADD CONSTRAINT chk_org_primary_color
    CHECK (primary_color IS NULL OR primary_color ~ '^#[0-9a-fA-F]{6}$');

-- Stripe statement_descriptor_suffix is limited to 22 chars, alphanumerics +
-- spaces + a few punctuation marks. Keep the DB check loose; the service layer
-- does the strict Stripe-shaped validation.
ALTER TABLE organizations DROP CONSTRAINT IF EXISTS chk_org_stmt_descriptor;
ALTER TABLE organizations
    ADD CONSTRAINT chk_org_stmt_descriptor
    CHECK (statement_descriptor_suffix IS NULL
           OR statement_descriptor_suffix ~ '^[A-Za-z0-9 .\-]{1,22}$');

-- --- M2-20: per-event page customization ------------------------------------

ALTER TABLE events
    ADD COLUMN IF NOT EXISTS slug                  VARCHAR(140),
    ADD COLUMN IF NOT EXISTS hero_image_url        VARCHAR(1024),
    ADD COLUMN IF NOT EXISTS description_markdown  TEXT,
    ADD COLUMN IF NOT EXISTS doors_open_time       TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS set_times             TEXT,
    ADD COLUMN IF NOT EXISTS age_restriction       VARCHAR(16),
    ADD COLUMN IF NOT EXISTS accessibility_info    TEXT,
    ADD COLUMN IF NOT EXISTS accessibility_tags    TEXT,
    ADD COLUMN IF NOT EXISTS parking_info          TEXT,
    ADD COLUMN IF NOT EXISTS transit_info          TEXT,
    ADD COLUMN IF NOT EXISTS seo_description       VARCHAR(320);

ALTER TABLE events DROP CONSTRAINT IF EXISTS chk_event_age_restriction;
ALTER TABLE events
    ADD CONSTRAINT chk_event_age_restriction
    CHECK (age_restriction IS NULL OR age_restriction IN ('ALL_AGES', 'EIGHTEEN_PLUS', 'TWENTY_ONE_PLUS'));

-- Slug is unique within an organization, not globally — organizers share the
-- same slug across different orgs ("summer-fest" at both Blue Note NYC and
-- Tokyo is fine, the org slug disambiguates the URL).
CREATE UNIQUE INDEX IF NOT EXISTS uk_events_org_slug
    ON events(organization_id, slug)
    WHERE slug IS NOT NULL;

-- --- M2-21: SEO — semantic slug history (preserve old URLs via 301) ---------

CREATE TABLE IF NOT EXISTS event_slug_history (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id        UUID NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    old_slug        VARCHAR(140) NOT NULL,
    retired_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Old slug must be unique within an org so resolver can look up unambiguously.
CREATE UNIQUE INDEX IF NOT EXISTS uk_event_slug_history_org_slug
    ON event_slug_history(organization_id, old_slug);

CREATE INDEX IF NOT EXISTS idx_event_slug_history_event
    ON event_slug_history(event_id);

-- --- M2-22: custom domain CNAME mappings ------------------------------------

CREATE TABLE IF NOT EXISTS org_custom_domains (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    hostname            VARCHAR(255) NOT NULL,
    verification_token  VARCHAR(64) NOT NULL,
    verified_at         TIMESTAMPTZ,
    active              BOOLEAN NOT NULL DEFAULT FALSE,
    last_health_check_at TIMESTAMPTZ,
    last_health_ok      BOOLEAN,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Hostname is globally unique — only one org can claim "tickets.bluenote.com".
CREATE UNIQUE INDEX IF NOT EXISTS uk_org_custom_domain_hostname
    ON org_custom_domains(LOWER(hostname));

CREATE INDEX IF NOT EXISTS idx_org_custom_domains_org
    ON org_custom_domains(organization_id);

CREATE INDEX IF NOT EXISTS idx_org_custom_domains_active
    ON org_custom_domains(active) WHERE active = TRUE;
