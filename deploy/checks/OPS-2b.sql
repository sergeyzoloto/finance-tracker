-- The stage checks of OPS-2b (deploy/deploy.sh run and verify), read only; the output must equal OPS-2b.expected.
-- OPS-2b changes the deploy scripts, the backend's tests and their configuration, CI's documents and the docs: no
-- application code, no migration and no table, so Flyway stays at V10 and numbers.sql is unchanged. As OPS-2.sql, this
-- file pins no family count: family budgets are switched on in production, so family rows change from day to day.
-- Nothing in the output changes from day to day. No backslash in this file.
SELECT 'flyway latest ' || version || ' ' || description || ' ' || success
FROM app.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;

-- V10's column and functions, as OPS-2.sql checks them: the application OPS-2b deploys again relies on them, unchanged.
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

-- Rule 2, whatever users write: no entry whose postings fail to sum to zero in a currency (the triggers enforce it at
-- commit; this reads that they did).
SELECT 'unbalanced entries ' || count(*)
FROM (SELECT entry_id FROM app.posting GROUP BY entry_id, currency HAVING sum(amount) <> 0) AS unbalanced;

-- D-49: the ECB has published no rouble rate since 2022-03-01, so the shared rates (user_id NULL) hold none after it;
-- RUB is the users' own manual rate after that day. RateApiTests failed on Ubuntu 26.04 over a test's made-up ECB RUB
-- rate of 2026-09-25 (OPS-2b); in production there is none, and none comes by itself.
SELECT 'ECB rouble rates after 2022-03-01 ' || count(*)
FROM app.exchange_rate
WHERE user_id IS NULL AND quote_currency = 'RUB' AND rate_date > DATE '2022-03-01';
