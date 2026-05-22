-- Backfill: for each distinct events.organizer_id, create an organization with the
-- owning user as OWNER. Slug is derived from the user's email local-part with a
-- numeric suffix on collision.

WITH organizer_users AS (
    SELECT DISTINCT u.id   AS user_id,
                    u.email AS email
    FROM events e
    JOIN users  u ON u.id = e.organizer_id
    WHERE e.organizer_id IS NOT NULL
),
new_orgs AS (
    INSERT INTO organizations (id, name, slug, contact_email, status, created_at, updated_at)
    SELECT gen_random_uuid(),
           COALESCE(split_part(email, '@', 1), 'organizer') || ' (auto)',
           lower(regexp_replace(split_part(email, '@', 1), '[^a-z0-9-]', '-', 'g'))
               || '-' || substring(replace(user_id::text, '-', '') for 8),
           email,
           'ACTIVE',
           NOW(),
           NOW()
    FROM organizer_users
    RETURNING id, contact_email
)
INSERT INTO organization_members (id, organization_id, user_id, role, created_at)
SELECT gen_random_uuid(), o.id, u.id, 'OWNER', NOW()
FROM new_orgs o
JOIN users u ON u.email = o.contact_email
ON CONFLICT (organization_id, user_id) DO NOTHING;
