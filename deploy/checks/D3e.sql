-- The stage checks of D3e (the import compares what it would write with what the ledger holds, D-219 and D-223; the
-- family budget's codes in the owner's import), read only; the output must equal D3e.expected. D3e has no migration and
-- writes no data at its deploy (D-224): the personal twins merge and any hash is stored again at the next import. Its
-- rollback target, 2cfeec0, is D3c's code on the same V15 and reads everything D3e's import writes, so rollback.sh has
-- no condition of its own. No line counts rows an import or a user writes from day to day. No backslash in this file.
SELECT 'flyway latest ' || version || ' ' || description || ' ' || success
FROM app.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;

-- D-139, whatever users write: an entry with an import's hash has a reference and a version.
SELECT 'imported entries without their reference or version ' || count(*)
FROM app.journal_entry
WHERE import_hash IS NOT NULL AND (external_ref IS NULL OR import_version IS NULL);

-- D-219: storing a hash again or re-planning a row never sets the imported version beyond the version.
SELECT 'imported entries ahead of their version ' || count(*)
FROM app.journal_entry WHERE import_version > version;

SELECT 'imported records ahead of their version ' || count(*)
FROM app.family_record WHERE imported_version > version;

-- D-11 (D3e): no member of a family budget has a personal category of a family category's code and type that postings
-- use; an unused one is merged by the next import.
SELECT 'used personal twins of family categories ' || count(*)
FROM app.category p
JOIN app.ledger_member pm ON pm.ledger_id = p.ledger_id AND pm.ledger_type = 'PERSONAL'
JOIN app.ledger_member fm ON fm.user_sub = pm.user_sub AND fm.ledger_type = 'SHARED' AND fm.status = 'ACTIVE'
JOIN app.category f ON f.ledger_id = fm.ledger_id AND f.code = p.code AND f.type = p.type
WHERE EXISTS (SELECT FROM app.posting x WHERE x.category_id = p.id);

-- Rule 2, whatever users write: no entry whose postings fail to sum to zero in a currency.
SELECT 'unbalanced entries ' || count(*)
FROM (SELECT entry_id FROM app.posting GROUP BY entry_id, currency HAVING sum(amount) <> 0) AS unbalanced;
