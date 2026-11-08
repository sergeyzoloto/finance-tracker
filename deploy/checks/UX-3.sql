-- The stage checks of UX-3 (equity by period on the dashboard, D-367 to D-369; an archived account below a minor unit
-- not listed, D-364; an account archived only at zero, D-366; deploy/deploy.sh run and verify), read only; the output
-- must equal UX-3.expected. UX-3 has no migration and changes no stored entry (D-370): Flyway stays at V16, and
-- numbers.sql is unchanged. Structure and invariants only: no count that an import or a user changes.
-- No backslash in this file.
SELECT 'flyway latest ' || version || ' ' || description || ' ' || success
FROM app.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;

-- D-367: the Balances card keeps OPENING_BALANCE and FX_EXCHANGE on lines of their own, by code, and shows every other
-- equity account in the reserve and the result. Whatever users write, those codes are the system EQUITY accounts.
SELECT 'OPENING_BALANCE or FX_EXCHANGE not a system equity account ' || count(*)
FROM app.account
WHERE code IN ('OPENING_BALANCE', 'FX_EXCHANGE') AND (NOT is_system OR type <> 'EQUITY');

-- D-366 reads an account's postings over all their dates and archives by account.archived_at, which UX-3 doesn't
-- change; accounts archived before it stay archived.
SELECT 'column account.archived_at ' || data_type || ' ' || is_nullable
FROM information_schema.columns
WHERE table_schema = 'app' AND table_name = 'account' AND column_name = 'archived_at';

-- D-8, whatever users write: UNALLOCATED is never archived (V7's trigger).
SELECT 'archived UNALLOCATED ' || count(*)
FROM app.account WHERE code = 'UNALLOCATED' AND archived_at IS NOT NULL;

-- Rule 2, whatever users write: no entry whose postings fail to sum to zero in a currency.
SELECT 'unbalanced entries ' || count(*)
FROM (SELECT entry_id FROM app.posting GROUP BY entry_id, currency HAVING sum(amount) <> 0) AS unbalanced;
