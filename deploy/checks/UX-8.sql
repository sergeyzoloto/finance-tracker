-- The stage checks of UX-8 (similar entries on New entry, Copy, "Save and add another" keeping the form, two fixes at
-- 375 px, D-452 to D-464; deploy/deploy.sh run and verify), read only; the output must equal UX-8.expected. UX-8 adds
-- one read-only endpoint, GET /api/entries/matches, and changes the frontend: no migration (Flyway stays at V16), no
-- stored entry, import or export changed, and numbers.sql is unchanged.
-- Structure and invariants only: no count that an import or a user changes.
-- No backslash in this file.
SELECT 'flyway latest ' || version || ' ' || description || ' ' || success
FROM app.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;

-- D-458 folds case with the database's lower, and reads the letter yo as ye: the database's locale must fold Latin,
-- Cyrillic and yo, as the endpoint's tests and production's dump showed it does.
SELECT 'case folded for Latin, Cyrillic and yo ' || (lower('ЁЛКА Albert') = 'ёлка albert')
    || ', yo read as ye ' || (translate(lower('ЁЛКА ёлка'), 'ёЁ', 'ее') = 'елка елка');

-- What the endpoint reads (D-457, D-459, D-463): an entry's kind, payee and memo, its postings' accounts, amounts and
-- categories, an account's type, a counterparty's name, a category's type, the user's PAYMENT links and their records.
SELECT 'column ' || table_name || '.' || column_name || ' ' || data_type || ' ' || is_nullable
FROM information_schema.columns
WHERE table_schema = 'app' AND (table_name, column_name) IN (('journal_entry', 'kind'),
    ('journal_entry', 'payee_id'), ('journal_entry', 'memo'), ('posting', 'amount'), ('posting', 'currency'),
    ('posting', 'category_id'), ('posting', 'line_no'), ('account', 'type'), ('counterparty', 'name'),
    ('category', 'type'), ('family_entry_link', 'link_type'), ('family_entry_link', 'detached_at'),
    ('family_entry_link', 'member_id'), ('family_record', 'deleted_at'), ('family_record', 'base_amount'),
    ('family_record', 'currency'), ('family_record', 'category_id'), ('family_record', 'type'))
ORDER BY table_name, column_name;

-- D-457 reads a family row from a PAYMENT link: that link belongs to an expense or an income, never a settlement,
-- whose sides are SETTLEMENT links.
SELECT 'payment links of a settlement ' || count(*)
FROM app.family_entry_link l JOIN app.family_record r ON r.id = l.record_id
WHERE l.link_type = 'PAYMENT' AND r.type = 'SETTLEMENT';

-- Rule 5, which tells a personal expense or income by its categorized line: a category only on a posting to an
-- EQUITY account.
SELECT 'categorized postings outside equity ' || count(*)
FROM app.posting p JOIN app.account a ON a.id = p.account_id
WHERE p.category_id IS NOT NULL AND a.type <> 'EQUITY';

-- Rule 2, whatever users write: no entry whose postings fail to sum to zero in a currency.
SELECT 'unbalanced entries ' || count(*)
FROM (SELECT entry_id FROM app.posting GROUP BY entry_id, currency HAVING sum(amount) <> 0) AS unbalanced;
