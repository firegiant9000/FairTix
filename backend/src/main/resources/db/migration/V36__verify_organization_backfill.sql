-- Sanity checks for the V33/V34 organization backfill. Fails the migration if any
-- invariant is broken so a corrupted prod restore cannot silently pass through.

DO $$
DECLARE
    v_orgs_without_owner   INT;
    v_events_with_orphan_organizer INT;
BEGIN
    -- Invariant 1: every organization must have at least one OWNER.
    SELECT COUNT(*) INTO v_orgs_without_owner
    FROM organizations o
    WHERE NOT EXISTS (
        SELECT 1 FROM organization_members m
        WHERE m.organization_id = o.id AND m.role = 'OWNER'
    );

    IF v_orgs_without_owner > 0 THEN
        RAISE EXCEPTION 'Backfill check failed: % organization(s) have no OWNER member', v_orgs_without_owner;
    END IF;

    -- Invariant 2: every event whose organizer_id references an existing user
    -- must end up with an organization_id. NULL organizer_id is an acceptable
    -- pre-org orphan; orphaned organizer_id (user deleted) is also acceptable.
    SELECT COUNT(*) INTO v_events_with_orphan_organizer
    FROM events e
    JOIN users u ON u.id = e.organizer_id
    WHERE e.organization_id IS NULL;

    IF v_events_with_orphan_organizer > 0 THEN
        RAISE EXCEPTION 'Backfill check failed: % event(s) with a valid organizer_id were not linked to an organization. Re-run V33/V34 against a fresh schema.',
            v_events_with_orphan_organizer;
    END IF;

    RAISE NOTICE 'Organization backfill verified: every org has an OWNER and every owned event is linked.';
END $$;
