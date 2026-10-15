-- The stage checks of D3c (the partner's claim and his own import: V15's family_import_cutoff, D-175, and
-- imported_family_side, D-181), read only; the output must equal D3c.expected. D3b's invariants still hold. D3c's
-- rollback target, 1d3b811, is D3b-accept-fix's code on V14: it ignores both tables, refuses the owner's import while the
-- partner's place has an account, and reads everything else D3c writes (a record's author, a member's payment and
-- settlement sides) as its own code writes it, so rollback.sh has no condition for V15. No line counts the claim's or
-- the import's rows: D3c-run writes them after this deploy. No backslash in this file.
SELECT 'flyway latest ' || version || ' ' || description || ' ' || success
FROM app.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;

-- V15's tables, their keys and the trigger that keeps a member's imported side in a personal ledger.
SELECT 'table ' || table_name || ' ' || count(*)
FROM information_schema.tables
WHERE table_schema = 'app' AND table_name IN ('family_import_cutoff', 'imported_family_side')
GROUP BY table_name ORDER BY table_name COLLATE "C";

SELECT 'constraint ' || conname || ' ' || count(*)
FROM pg_constraint
WHERE connamespace = 'app'::regnamespace
  AND conname IN ('family_import_cutoff_pkey', 'imported_family_side_pkey')
GROUP BY conname ORDER BY conname COLLATE "C";

SELECT 'trigger imported_family_side_check ' || count(*)
FROM pg_trigger WHERE tgname = 'imported_family_side_check' AND NOT tgisinternal;

-- D-181, whatever users write: a member's imported side lives in a personal ledger.
SELECT 'imported sides outside a personal ledger ' || count(*)
FROM app.imported_family_side s JOIN app.ledger l ON l.id = s.ledger_id
WHERE l.type <> 'PERSONAL';

-- D-175, whatever users write: a cutoff belongs to a member of its family ledger.
SELECT 'cutoffs outside a family ledger ' || count(*)
FROM app.family_import_cutoff c JOIN app.ledger l ON l.id = c.ledger_id
WHERE l.type <> 'SHARED';

-- D-79, whatever users write: a record's amount is never 0, its paying side has its sign, only an expense is
-- negative, and no share has the other sign.
SELECT 'records or shares of the wrong sign ' || count(*)
FROM (SELECT r.id FROM app.family_record r LEFT JOIN app.family_share s ON s.record_id = r.id
      WHERE r.base_amount = 0 OR sign(r.original_amount) <> sign(r.base_amount)
         OR (r.base_amount < 0 AND r.type <> 'EXPENSE') OR sign(s.amount) = -sign(r.base_amount)) AS wrong;

-- D-93: every journal row of a record has its currency, and a system change none.
SELECT 'journal rows without their currency ' || count(*)
FROM app.family_record_change WHERE (record_id IS NULL) <> (currency IS NULL);

-- D-139, whatever users write: an entry with an import's hash has a reference and a version.
SELECT 'imported entries without their reference or version ' || count(*)
FROM app.journal_entry
WHERE import_hash IS NOT NULL AND (external_ref IS NULL OR import_version IS NULL);

-- Rule 2, whatever users write: no entry whose postings fail to sum to zero in a currency.
SELECT 'unbalanced entries ' || count(*)
FROM (SELECT entry_id FROM app.posting GROUP BY entry_id, currency HAVING sum(amount) <> 0) AS unbalanced;
