-- The stage checks of UX-6 (the dashboard's scope chooses the family budget, a shared icon in the cash flow table, and
-- the cash flow's scope FAMILY, D-418 to D-421; deploy/deploy.sh run and verify), read only; the output must equal
-- UX-6.expected. No migration (Flyway stays at V16), no stored entry, import or export changed, and numbers.sql is
-- unchanged. The backend adds only the scope FAMILY, which reads what FAMILY_MINE and FAMILY_OTHERS read.
-- Structure and invariants only: no count that an import or a user changes.
-- No backslash in this file.
SELECT 'flyway latest ' || version || ' ' || description || ' ' || success
FROM app.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;

-- What the cash flow's scopes read (D-302, D-421): the entry's day, the posting's amount and currency, the category's
-- type, the SHARE link of an entry (its entry, record, family budget and member) and its record's payer.
SELECT 'column ' || table_name || '.' || column_name || ' ' || data_type || ' ' || is_nullable
FROM information_schema.columns
WHERE table_schema = 'app' AND (table_name, column_name) IN (('journal_entry', 'entry_date'), ('posting', 'amount'),
    ('posting', 'currency'), ('category', 'type'), ('family_entry_link', 'link_type'),
    ('family_entry_link', 'entry_id'), ('family_entry_link', 'record_id'), ('family_entry_link', 'family_ledger_id'),
    ('family_entry_link', 'member_id'), ('family_record', 'payer_member_id'))
ORDER BY table_name, column_name;

-- D-421's FAMILY is FAMILY_MINE and FAMILY_OTHERS together only while every SHARE link reaches a record of its own
-- family budget that names a payer.
SELECT 'share links without a record and payer ' || count(*)
FROM app.family_entry_link l
LEFT JOIN app.family_record r ON r.id = l.record_id AND r.ledger_id = l.family_ledger_id
WHERE l.link_type = 'SHARE' AND (r.id IS NULL OR r.payer_member_id IS NULL);

-- Rule 5, which the report's income and expense lines rest on: a category only on a posting to an EQUITY account.
SELECT 'categorized postings outside equity ' || count(*)
FROM app.posting p JOIN app.account a ON a.id = p.account_id
WHERE p.category_id IS NOT NULL AND a.type <> 'EQUITY';

-- Rule 2, whatever users write: no entry whose postings fail to sum to zero in a currency.
SELECT 'unbalanced entries ' || count(*)
FROM (SELECT entry_id FROM app.posting GROUP BY entry_id, currency HAVING sum(amount) <> 0) AS unbalanced;
