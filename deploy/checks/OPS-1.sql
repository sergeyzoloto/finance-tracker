-- The stage checks of OPS-1 (deploy/deploy.sh run and verify), read only; the output must equal OPS-1.expected.
-- OPS-1 changes no application code and no migration: the database is as F5 (V9) left it, and production's switch is
-- off, so the family tables are empty. Nothing in the output changes from day to day. No backslash in this file.
SELECT 'flyway latest ' || version || ' ' || description || ' ' || success
FROM app.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;

SELECT key || ' ' || n
FROM (VALUES
    ('family_ledgers', (SELECT count(*) FROM app.ledger WHERE type = 'SHARED')),
    ('family_members', (SELECT count(*) FROM app.ledger_member WHERE ledger_type = 'SHARED')),
    ('family_records', (SELECT count(*) FROM app.family_record)),
    ('family_shares', (SELECT count(*) FROM app.family_share)),
    ('family_links', (SELECT count(*) FROM app.family_entry_link)),
    ('family_journal', (SELECT count(*) FROM app.family_record_change)),
    ('family_accounts', (SELECT count(*) FROM app.account WHERE family_ledger_id IS NOT NULL)),
    ('family_invites', (SELECT count(*) FROM app.ledger_invite))
) AS counts (key, n)
ORDER BY key COLLATE "C";

SELECT 'trigger ledger_invite_check on app.ledger_invite ' || EXISTS (
    SELECT FROM pg_trigger
    WHERE tgrelid = 'app.ledger_invite'::regclass AND tgname = 'ledger_invite_check' AND NOT tgisinternal);

SELECT 'release_family_memberships refers to ledger_invite ' || coalesce(bool_and(position('ledger_invite' IN prosrc) > 0), false)
FROM pg_proc
WHERE proname = 'release_family_memberships' AND pronamespace = 'app'::regnamespace;
