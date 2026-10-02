-- The stage checks of F6c (deploy/deploy.sh run and verify), read only; the output must equal F6c.expected.
-- F6c adds no migration and no table, so Flyway stays at V10 and numbers.sql is unchanged. Production's switch is off,
-- so the family tables stay empty, also after the smoke test's demo: with the switch off the demo creates no family
-- budget (H1). Nothing in the output changes from day to day. No backslash in this file.
SELECT 'flyway latest ' || version || ' ' || description || ' ' || success
FROM app.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;

SELECT key || ' ' || n
FROM (VALUES
    ('family_ledgers', (SELECT count(*) FROM app.ledger WHERE type = 'SHARED')),
    ('family_ledgers_named_demo_household', (SELECT count(*) FROM app.ledger WHERE name = 'Demo household')),
    ('family_members', (SELECT count(*) FROM app.ledger_member WHERE ledger_type = 'SHARED')),
    ('family_members_claimed_seat', (SELECT count(*) FROM app.ledger_member WHERE claimed_seat)),
    ('family_records', (SELECT count(*) FROM app.family_record)),
    ('family_shares', (SELECT count(*) FROM app.family_share)),
    ('family_links', (SELECT count(*) FROM app.family_entry_link)),
    ('family_journal', (SELECT count(*) FROM app.family_record_change)),
    ('family_accounts', (SELECT count(*) FROM app.account WHERE family_ledger_id IS NOT NULL)),
    ('family_categories', (SELECT count(*) FROM app.category WHERE user_id IS NULL)),
    ('family_invites', (SELECT count(*) FROM app.ledger_invite))
) AS counts (key, n)
ORDER BY key COLLATE "C";

-- V10's column, trigger and function, as F6b.sql checks them: F6c's code relies on them, unchanged.
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
