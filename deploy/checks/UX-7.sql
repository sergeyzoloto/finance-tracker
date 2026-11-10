-- The stage checks of UX-7 (the header's two menus, Settings in parts, a family budget's header, the period as a list,
-- balances as of To, the net's % across 0, the family budget named once in Entries, D-429 to D-442; deploy/deploy.sh
-- run and verify), read only; the output must equal UX-7.expected. UX-7 changes only the frontend: no migration
-- (Flyway stays at V16), no stored entry, import or export changed, and numbers.sql is unchanged.
-- Structure and invariants only: no count that an import or a user changes.
-- No backslash in this file.
SELECT 'flyway latest ' || version || ' ' || description || ' ' || success
FROM app.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;

-- What D-442's row reads: an account's code and system flag and the family budget of its debt account, and an
-- entry's family link (its entry and family budget).
SELECT 'column ' || table_name || '.' || column_name || ' ' || data_type || ' ' || is_nullable
FROM information_schema.columns
WHERE table_schema = 'app' AND (table_name, column_name) IN (('account', 'code'), ('account', 'is_system'),
    ('account', 'family_ledger_id'), ('family_entry_link', 'entry_id'), ('family_entry_link', 'family_ledger_id'))
ORDER BY table_name, column_name;

-- D-442 tells the row's own debt account by its code: every debt account of a family budget is a system account whose
-- code is FAMILY_DEBT_ and that budget's id, with _2, _3 and so on after it if that was taken.
SELECT 'debt accounts not named by their family budget ' || count(*)
FROM app.account a
WHERE a.family_ledger_id IS NOT NULL
  AND (NOT a.is_system OR a.code !~ ('^FAMILY_DEBT_' || a.family_ledger_id || '(_[0-9]+)?$'));

-- Rule 5, which the report's income and expense lines rest on: a category only on a posting to an EQUITY account.
SELECT 'categorized postings outside equity ' || count(*)
FROM app.posting p JOIN app.account a ON a.id = p.account_id
WHERE p.category_id IS NOT NULL AND a.type <> 'EQUITY';

-- Rule 2, whatever users write: no entry whose postings fail to sum to zero in a currency.
SELECT 'unbalanced entries ' || count(*)
FROM (SELECT entry_id FROM app.posting GROUP BY entry_id, currency HAVING sum(amount) <> 0) AS unbalanced;
