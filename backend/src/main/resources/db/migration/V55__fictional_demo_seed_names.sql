-- Rename the demo seed data from V27 and V29, which used real artists, teams, festivals and
-- venues, to fictional names, so nothing suggests they endorse or use FairTix. Seats,
-- prices, caps, queue flags, cities and map coordinates are unchanged.
--
-- Only seed rows change: an event must still have its seeded title at its seeded venue, and
-- a venue its seeded name and address. An organizer's own event or venue that happens to
-- share a name is left alone. A venue is skipped if its new name is already taken (names
-- are unique). On a database already renamed by hand, or one without the seed rows, nothing
-- matches and nothing changes.
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
   AND NOT EXISTS (SELECT 1 FROM venues taken WHERE taken.name = r.new_name);
