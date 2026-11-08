-- The stage checks of UX-4 (the Accounts page's archived accounts and its width at 375 px, D-382; no decision number
-- in a text a screen shows, D-384; deploy/deploy.sh run and verify), read only; the output must equal UX-4.expected.
-- UX-4 has no migration and changes no stored entry (D-385): Flyway stays at V16, and numbers.sql is unchanged.
-- Structure and invariants only: no count that an import or a user changes.
-- No backslash in this file.
SELECT 'flyway latest ' || version || ' ' || description || ' ' || success
FROM app.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;

-- D-385: the import's sync fields, which D-384's wording leaves alone (the hashes come from the rows, never from a
-- message), keep their columns and their rule: all together or none.
SELECT 'column ' || table_name || '.' || column_name || ' ' || data_type || ' ' || is_nullable
FROM information_schema.columns
WHERE table_schema = 'app' AND (table_name, column_name) IN (('journal_entry', 'import_hash'),
    ('journal_entry', 'import_version'), ('family_record', 'content_hash'), ('family_record', 'imported_version'))
ORDER BY table_name, column_name;

SELECT 'personal entries with a hash but no version, or the other way ' || count(*)
FROM app.journal_entry WHERE (import_hash IS NULL) <> (import_version IS NULL);

SELECT 'family records with only some sync fields ' || count(*)
FROM app.family_record
WHERE NOT ((external_ref IS NULL AND content_hash IS NULL AND imported_version IS NULL)
    OR (external_ref IS NOT NULL AND content_hash IS NOT NULL AND imported_version IS NOT NULL));

-- D-382 reads account.archived_at, which UX-4 doesn't change.
SELECT 'column account.archived_at ' || data_type || ' ' || is_nullable
FROM information_schema.columns
WHERE table_schema = 'app' AND table_name = 'account' AND column_name = 'archived_at';

-- Rule 2, whatever users write: no entry whose postings fail to sum to zero in a currency.
SELECT 'unbalanced entries ' || count(*)
FROM (SELECT entry_id FROM app.posting GROUP BY entry_id, currency HAVING sum(amount) <> 0) AS unbalanced;
