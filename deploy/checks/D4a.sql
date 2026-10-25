-- The stage checks of D4a (the export of a user's ledger as one transactions file, D-235 to D-243), read only; the
-- output must equal D4a.expected. D4a has no migration and writes no data: the export only reads. These lines check
-- what the export reads the ledger by: every reference a row of the export names its ID from, and every entry the
-- family budget posted linked to what it stands for. Its rollback target, b3013d2, is D3e's code on the same V15, so
-- rollback.sh has no condition of its own. No line counts rows a user writes from day to day. No backslash in this file.
SELECT 'flyway latest ' || version || ' ' || description || ' ' || success
FROM app.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;

-- D-236: a personal entry's reference is an Excel ID (xls:<ID>) or the opening balances' (xls-opening:<date>).
SELECT 'personal references of another form ' || count(*)
FROM app.journal_entry
WHERE external_ref IS NOT NULL AND external_ref !~ '^xls:[0-9]+$' AND external_ref !~ '^xls-opening:[0-9]{4}-[0-9]{2}-[0-9]{2}$';

-- D-236: a family record's reference is a member's Excel ID, a conversion's leg (…:<ID>:<currency>) or a lump (D-83).
SELECT 'family references of another form ' || count(*)
FROM app.family_record
WHERE external_ref IS NOT NULL
  AND external_ref !~ '^xls:[0-9]+:[0-9]+(:[A-Z]{3})?$' AND external_ref !~ '^xls:[0-9]+:PL:[0-9]{4}-[0-9]{2}:[A-Z]{3}$';

-- D-181, D-198: a member's side names his row (xls:<ID>) or his rows (xls:<ID>+<ID>…).
SELECT 'member sides of another form ' || count(*)
FROM app.imported_family_side WHERE external_ref !~ '^xls:[0-9]+([+][0-9]+)*$';

-- D-239: every entry the family budget posted is linked to what it stands for, so the export tells his rows from the
-- other members' records.
SELECT 'family entries without a link ' || count(*)
FROM app.journal_entry e
WHERE e.kind LIKE 'FAMILY%' AND NOT EXISTS (SELECT FROM app.family_entry_link l WHERE l.entry_id = e.id);

-- Rule 2, whatever users write: no entry whose postings fail to sum to zero in a currency.
SELECT 'unbalanced entries ' || count(*)
FROM (SELECT entry_id FROM app.posting GROUP BY entry_id, currency HAVING sum(amount) <> 0) AS unbalanced;
