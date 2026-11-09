-- The stage checks of UX-5 (income and expenses compared with the same period a year earlier and with the previous
-- one, D-393 to D-401; deploy/deploy.sh run and verify), read only; the output must equal UX-5.expected.
-- UX-5 changes only the frontend: no migration (Flyway stays at V16), no stored entry, import or export changed, and
-- numbers.sql is unchanged. Every figure is /api/reports/cash-flow's, asked for more date ranges.
-- Structure and invariants only: no count that an import or a user changes.
-- No backslash in this file.
SELECT 'flyway latest ' || version || ' ' || description || ' ' || success
FROM app.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;

-- What the cash flow report reads for any range: the entry's day, the posting's amount and currency, the category's
-- type, and the SHARE link its scopes classify by (D-302).
SELECT 'column ' || table_name || '.' || column_name || ' ' || data_type || ' ' || is_nullable
FROM information_schema.columns
WHERE table_schema = 'app' AND (table_name, column_name) IN (('journal_entry', 'entry_date'), ('posting', 'amount'),
    ('posting', 'currency'), ('category', 'type'), ('family_entry_link', 'link_type'))
ORDER BY table_name, column_name;

-- Rule 5, which the report's income and expense lines rest on: a category only on a posting to an EQUITY account.
SELECT 'categorized postings outside equity ' || count(*)
FROM app.posting p JOIN app.account a ON a.id = p.account_id
WHERE p.category_id IS NOT NULL AND a.type <> 'EQUITY';

-- Rule 2, whatever users write: no entry whose postings fail to sum to zero in a currency.
SELECT 'unbalanced entries ' || count(*)
FROM (SELECT entry_id FROM app.posting GROUP BY entry_id, currency HAVING sum(amount) <> 0) AS unbalanced;
