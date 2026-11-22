-- Rename the demo seed data from V27 and V29, which used real artists, teams, festivals and
-- venues, to fictional names, so nothing suggests they endorse or use FairTix. Seats,
-- prices, caps, queue flags, cities and map coordinates are unchanged.
--
-- Only seed-shaped rows change: an event must still have its seeded title at a venue with its
-- seeded name and address, and a venue its seeded name and address. An organizer's event or
-- venue that merely shares a name is left alone. A seed venue is also skipped while any
-- event that is not a seed event uses it, since venues are global; the seed event there is
-- still renamed, and the venue keeps its real name. "Seed event" here means any seed title
-- (old or new), so the cases this cannot tell apart are: an organizer's event with exactly
-- a seed title at that title's seed venue and address is renamed with the seed, and one
-- with any seed title at any seed venue does not stop that venue's rename. A venue is
-- skipped if its new name is already taken
-- (names are unique). On a database already renamed by hand, or one without the seed rows,
-- nothing matches and nothing changes.
--
-- scripts/demo-seed.sh re-runs V27/V29 when their events are missing (V29 seeds nothing on
-- a database with no users yet) and then re-runs this file, so it must stay idempotent.

WITH renames (old_title, venue_name, venue_address, new_title) AS (
    VALUES
        ('Jazz Fest 2026',                    'Smoothie King Center',  '1501 Dave Dixon Dr',   'Crescent Jazz Weekend'),
        ('Houston Rodeo Night',               'Toyota Center',         '1510 Polk St',         'Gulf Coast Rodeo Night'),
        ('Saints vs Panthers',                'Caesars Superdome',     '1500 Sugar Bowl Dr',   'Bayou Pelicans vs Harbor Foxes'),
        ('Taylor Swift — Eras Tour Finale',   'Madison Square Garden', '4 Pennsylvania Plaza', 'Nova Lane - Arena Tour Finale'),
        ('Beyoncé Renaissance Tour',          'SoFi Stadium',          '1001 S Stadium Dr',    'The Velvet Static - Live'),
        ('Coachella Day 1 — Headliner Stage', 'Empire Polo Club',      '81-800 Avenue 51',     'Sunfield Festival - Day 1'),
        ('Comedy Cellar — Late Show',         'Comedy Cellar',         '117 MacDougal St',     'Basement Comedy - Late Show')
)
UPDATE events e
   SET title = r.new_title
  FROM renames r, venues v
 WHERE e.venue_id = v.id
   AND e.title = r.old_title
   AND v.name = r.venue_name
   AND v.address = r.venue_address;

WITH renames (old_name, old_address, new_name, new_address) AS (
    VALUES
        ('Smoothie King Center',  '1501 Dave Dixon Dr',   'Crescent Demo Arena',   '100 Demo Plaza'),
        ('Toyota Center',         '1510 Polk St',         'Bayou Demo Center',     '200 Sample Ave'),
        ('Caesars Superdome',     '1500 Sugar Bowl Dr',   'Riverfront Demo Dome',  '300 Example Blvd'),
        ('Madison Square Garden', '4 Pennsylvania Plaza', 'Midtown Demo Garden',   '400 Demo Street'),
        ('SoFi Stadium',          '1001 S Stadium Dr',    'Harbor Demo Stadium',   '500 Sample Way'),
        ('Empire Polo Club',      '81-800 Avenue 51',     'Sunfield Demo Grounds', '600 Example Road'),
        ('Comedy Cellar',         '117 MacDougal St',     'The Demo Basement',     '700 Demo Lane')
)
UPDATE venues v
   SET name = r.new_name,
       address = r.new_address,
       updated_at = NOW()
  FROM renames r
 WHERE v.name = r.old_name
   AND v.address = r.old_address
   AND NOT EXISTS (SELECT 1 FROM venues taken WHERE taken.name = r.new_name)
   -- Venues are global, so skip a seed venue that any non-seed event uses. The events above
   -- are renamed first, so the seed events carry their new titles here; both sets are listed.
   AND NOT EXISTS (
       SELECT 1 FROM events e
        WHERE e.venue_id = v.id
          AND e.title NOT IN (
              'Jazz Fest 2026', 'Houston Rodeo Night', 'Saints vs Panthers',
              'Taylor Swift — Eras Tour Finale', 'Beyoncé Renaissance Tour',
              'Coachella Day 1 — Headliner Stage', 'Comedy Cellar — Late Show',
              'Crescent Jazz Weekend', 'Gulf Coast Rodeo Night', 'Bayou Pelicans vs Harbor Foxes',
              'Nova Lane - Arena Tour Finale', 'The Velvet Static - Live',
              'Sunfield Festival - Day 1', 'Basement Comedy - Late Show'));
