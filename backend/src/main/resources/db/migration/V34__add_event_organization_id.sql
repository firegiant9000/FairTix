-- Add organization_id to events. organizer_id is retained for one cycle (M2 removes it).

ALTER TABLE events ADD COLUMN IF NOT EXISTS organization_id UUID REFERENCES organizations(id);

-- Backfill: link each event to the organization owned by its organizer.
UPDATE events e
SET organization_id = om.organization_id
FROM organization_members om
WHERE om.user_id = e.organizer_id
  AND om.role   = 'OWNER'
  AND e.organization_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_events_organization_id ON events(organization_id);
