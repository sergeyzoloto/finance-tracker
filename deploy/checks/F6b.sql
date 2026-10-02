-- The stage checks of F6b (deploy/deploy.sh run and verify), read only; the output must equal F6b.expected.
-- F6b adds V10 (claimed seats, D-35; delete_family_ledger, D-36) and no table, so numbers.sql is unchanged. Production's
-- switch is off, so the family tables are empty. Nothing in the output changes from day to day. No backslash in this
-- file.
SELECT 'flyway latest ' || version || ' ' || description || ' ' || success
FROM app.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;

SELECT key || ' ' || n
FROM (VALUES
    ('family_ledgers', (SELECT count(*) FROM app.ledger WHERE type = 'SHARED')),
    ('family_members', (SELECT count(*) FROM app.ledger_member WHERE ledger_type = 'SHARED')),
    ('family_members_claimed_seat', (SELECT count(*) FROM app.ledger_member WHERE claimed_seat)),
    ('family_records', (SELECT count(*) FROM app.family_record)),
    ('family_shares', (SELECT count(*) FROM app.family_share)),
    ('family_links', (SELECT count(*) FROM app.family_entry_link)),
    ('family_journal', (SELECT count(*) FROM app.family_record_change)),
    ('family_accounts', (SELECT count(*) FROM app.account WHERE family_ledger_id IS NOT NULL)),
    ('family_invites', (SELECT count(*) FROM app.ledger_invite))
) AS counts (key, n)
ORDER BY key COLLATE "C";

-- V10's column, trigger and function.
SELECT 'column ledger_member.claimed_seat ' || data_type || ' nullable ' || is_nullable || ' default '
       || column_default
FROM information_schema.columns
WHERE table_schema = 'app' AND table_name = 'ledger_member' AND column_name = 'claimed_seat';

SELECT 'trigger ledger_member_claimed_seat on app.ledger_member ' || EXISTS (
    SELECT FROM pg_trigger
    WHERE tgrelid = 'app.ledger_member'::regclass AND tgname = 'ledger_member_claimed_seat' AND NOT tgisinternal);

SELECT 'function delete_family_ledger ' || count(*)
FROM pg_proc WHERE proname = 'delete_family_ledger' AND pronamespace = 'app'::regnamespace;

SELECT 'release_family_memberships calls delete_family_ledger '
       || coalesce(bool_and(position('delete_family_ledger' IN prosrc) > 0), false)
FROM pg_proc
WHERE proname = 'release_family_memberships' AND pronamespace = 'app'::regnamespace;

-- F5's, as OPS-1.sql checks them.
SELECT 'trigger ledger_invite_check on app.ledger_invite ' || EXISTS (
    SELECT FROM pg_trigger
    WHERE tgrelid = 'app.ledger_invite'::regclass AND tgname = 'ledger_invite_check' AND NOT tgisinternal);

SELECT 'release_family_memberships refers to ledger_invite ' || coalesce(bool_and(position('ledger_invite' IN prosrc) > 0), false)
FROM pg_proc
WHERE proname = 'release_family_memberships' AND pronamespace = 'app'::regnamespace;
