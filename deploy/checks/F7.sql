-- The stage checks of F7 (deploy/deploy.sh run and verify), read only; the output must equal F7.expected.
-- F7 adds no migration and no table, so Flyway stays at V10 and numbers.sql is unchanged. Unlike F6c.sql, this file
-- pins no family count: F7 turns the switch on, so family rows become real and change from day to day. Nothing else
-- in the output changes from day to day either. No backslash in this file.
SELECT 'flyway latest ' || version || ' ' || description || ' ' || success
FROM app.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;

-- V10's column and functions, as F6c.sql checks them: F7's code relies on them, unchanged.
SELECT 'column ledger_member.claimed_seat ' || data_type || ' nullable ' || is_nullable || ' default '
       || column_default
FROM information_schema.columns
WHERE table_schema = 'app' AND table_name = 'ledger_member' AND column_name = 'claimed_seat';

SELECT 'function delete_family_ledger ' || count(*)
FROM pg_proc WHERE proname = 'delete_family_ledger' AND pronamespace = 'app'::regnamespace;

SELECT 'release_family_memberships calls delete_family_ledger '
       || coalesce(bool_and(position('delete_family_ledger' IN prosrc) > 0), false)
FROM pg_proc
WHERE proname = 'release_family_memberships' AND pronamespace = 'app'::regnamespace;
