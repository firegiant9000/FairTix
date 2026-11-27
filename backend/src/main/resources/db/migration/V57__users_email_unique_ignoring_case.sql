-- Make users.email unique ignoring case. Until now only the exact address was unique, so
-- accounts that differed only by case could exist (comp stubs made before comp lookups ignored
-- case, accounts registered before registration refused case variants), and two concurrent
-- registrations of such variants could both pass the application's check.
--
-- The index is on upper(email), the expression the IgnoreCase lookups compare, so it replaces
-- V54's non-unique index of the same expression and still serves those lookups. upper() under
-- the database locale folds a little more than Java's toLowerCase (the dotless i, the long s),
-- so this is at least as strict as the application's own key.
--
-- Fails instead of changing data: if any address already belongs to more than one account
-- ignoring case, the migration stops and names the count, not the addresses. An operator
-- merges or renames those accounts, then restarts. To list them:
--   SELECT upper(email), count(*) FROM users GROUP BY 1 HAVING count(*) > 1;
--
-- SHARE mode blocks inserts and updates (registrations) from the check until the commit, so
-- no duplicate can slip in between the check and the index build. No CONCURRENTLY: Flyway runs
-- in a transaction.
LOCK TABLE users IN SHARE MODE;

DO $$
DECLARE
    v_duplicates integer;
BEGIN
    SELECT count(*) INTO v_duplicates
    FROM (SELECT 1 FROM users GROUP BY upper(email) HAVING count(*) > 1) d;

    IF v_duplicates > 0 THEN
        RAISE EXCEPTION 'V57: % email address(es) belong to more than one account ignoring case. Merge or rename those accounts, then restart (SELECT upper(email), count(*) FROM users GROUP BY 1 HAVING count(*) > 1).',
            v_duplicates;
    END IF;
END $$;

CREATE UNIQUE INDEX uk_users_email_upper ON users (upper(email));
DROP INDEX IF EXISTS idx_users_email_upper;
