-- The stage checks of UX-2 (the dashboard's totals as net worth's, D-348; archived accounts that hold money listed in
-- the balances, D-355; open loans below a minor unit hidden, D-349; "Owed to you", D-350; the entry list and the
-- category lists on a phone, D-351, D-352; no decision number on a screen, D-353; deploy/deploy.sh run and verify),
-- read only; the output must equal UX-2.expected. UX-2 has no migration (D-354): Flyway stays at V16, and numbers.sql
-- is unchanged. Structure and invariants only: no count that an import or a user changes.
-- No backslash in this file.
SELECT 'flyway latest ' || version || ' ' || description || ' ' || success
FROM app.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;

-- D-355: the balances list an archived account by account.archived_at, which UX-2 reads and doesn't change.
SELECT 'column account.archived_at ' || data_type || ' ' || is_nullable
FROM information_schema.columns
WHERE table_schema = 'app' AND table_name = 'account' AND column_name = 'archived_at';

-- D-8, whatever users write: UNALLOCATED is never archived (V7's trigger), so the shares a family budget posts are
-- always on an account the balances list as not archived.
SELECT 'archived UNALLOCATED ' || count(*)
FROM app.account WHERE code = 'UNALLOCATED' AND archived_at IS NOT NULL;

-- V16's column, which the dashboard's dates follow (D-304).
SELECT 'constraint user_settings_date_format_check ' || pg_get_constraintdef(oid)
FROM pg_constraint
WHERE conname = 'user_settings_date_format_check' AND conrelid = 'app.user_settings'::regclass;

-- Rule 2, whatever users write: no entry whose postings fail to sum to zero in a currency.
SELECT 'unbalanced entries ' || count(*)
FROM (SELECT entry_id FROM app.posting GROUP BY entry_id, currency HAVING sum(amount) <> 0) AS unbalanced;
