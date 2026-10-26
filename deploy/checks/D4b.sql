-- The stage checks of D4b (the import from one transactions file, D-228 to D-234, D-252 to D-259), read only; the
-- output must equal D4b.expected. D4b has no migration and writes no data at its deploy: what it changes is how the
-- next import reads a file and checks the ledger. These lines check what that import relies on: the references it
-- recognises rows by, that it never wrote a row the app made (D-259), the names its rows find accounts by (D-262), and
-- that no import keeps a cutoff any more (D-258, D-267). Its rollback target, 1fa5043, is D4a's code on the same V15,
-- so rollback.sh has no condition of its own. No line counts rows a user writes from day to day. No backslash here.
SELECT 'flyway latest ' || version || ' ' || description || ' ' || success
FROM app.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;

-- D-236, D-142: a personal entry's reference is an Excel ID (xls:<ID>) or the opening balances' (xls-opening:<date>).
SELECT 'personal references of another form ' || count(*)
FROM app.journal_entry
WHERE external_ref IS NOT NULL AND external_ref !~ '^xls:[0-9]+$' AND external_ref !~ '^xls-opening:[0-9]{4}-[0-9]{2}-[0-9]{2}$';

-- D-259: no import writes a row the app made: no reference is an app- ID.
SELECT 'references to rows the app made ' || ((SELECT count(*) FROM app.journal_entry WHERE external_ref LIKE '%app-%')
    + (SELECT count(*) FROM app.family_record WHERE external_ref LIKE '%app-%'));

-- D-262: rows name accounts by name in any case, so no ledger has two accounts of one name, whatever its case.
SELECT 'accounts sharing a name in a ledger ' || count(*)
FROM (SELECT ledger_id, lower(name) FROM app.account GROUP BY 1, 2 HAVING count(*) > 1) AS shared;

-- D-258, D-267: the cutoffs D-175 kept are neither read nor written any more; the table stays as it was (no migration).
SELECT 'family import cutoffs ' || count(*) FROM app.family_import_cutoff;

-- Rule 2, whatever users write: no entry whose postings fail to sum to zero in a currency.
SELECT 'unbalanced entries ' || count(*)
FROM (SELECT entry_id FROM app.posting GROUP BY entry_id, currency HAVING sum(amount) <> 0) AS unbalanced;
