-- Index users by upper(email) for the case-insensitive account lookups: login, password
-- reset, comp recipients and transfer recipients (UserEmailLookup), and registration's
-- duplicate check. Spring Data's IgnoreCase queries compare upper(email) = upper(?),
-- which the unique index on email cannot serve, so each lookup scanned users.
--
-- Not unique: case variants of one address can belong to different accounts (comp stubs,
-- and accounts registered before registration refused case variants). Finding and merging
-- them is a separate, manual data fix (see the PR); this migration changes no rows.
--
-- No CONCURRENTLY: Flyway runs inside a transaction. A plain CREATE INDEX blocks writes to
-- users (registrations among them) while it builds. If it
-- is too slow in prod, promote it to an out-of-band
-- CREATE INDEX CONCURRENTLY with the same name; IF NOT EXISTS then skips it here.
CREATE INDEX IF NOT EXISTS idx_users_email_upper
    ON users (upper(email));
