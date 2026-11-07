-- The stage checks of D4c (no commit while the check against the file fails, D-336; the owner's own side of his family
-- records compared and stored as a member's, D-337, D-342; a family record changed in the app explained, D-338;
-- deploy/deploy.sh run and verify), read only; the output must equal D4c.expected. D4c has no migration: Flyway stays
-- at V16, and numbers.sql is unchanged (its imported_family_sides grows by the owner's sides at his first commit on
-- D4c's code, D-342). Structure and invariants only: no count that an import or a user changes.
-- No backslash in this file.
SELECT 'flyway latest ' || version || ' ' || description || ' ' || success
FROM app.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;

-- V15's table, which since D4c holds the owner's sides too (D-342).
SELECT 'columns imported_family_side ' || string_agg(column_name || ':' || data_type || ':' || is_nullable, ','
                                                    ORDER BY ordinal_position)
FROM information_schema.columns
WHERE table_schema = 'app' AND table_name = 'imported_family_side';

SELECT 'constraint imported_family_side_pkey ' || pg_get_constraintdef(oid)
FROM pg_constraint
WHERE conname = 'imported_family_side_pkey' AND conrelid = 'app.imported_family_side'::regclass;

SELECT 'trigger imported_family_side_check ' || count(*)
FROM pg_trigger WHERE tgname = 'imported_family_side_check' AND NOT tgisinternal;

-- D-181, D-342, whatever users write: a side names one row of its member's file (xls:<ID>) or rows (xls:<ID>+<ID>),
-- lives in a personal ledger, and is a side of a record of a family budget that ledger's member belongs to (or left).
SELECT 'sides of another form ' || count(*)
FROM app.imported_family_side WHERE external_ref !~ '^xls:[0-9]+([+][0-9]+)*$';

SELECT 'sides outside a personal ledger ' || count(*)
FROM app.imported_family_side s JOIN app.ledger l ON l.id = s.ledger_id
WHERE l.type <> 'PERSONAL';

SELECT 'sides of a budget their member never joined ' || count(*)
FROM app.imported_family_side s
JOIN app.ledger_member pm ON pm.ledger_id = s.ledger_id AND pm.ledger_type = 'PERSONAL'
JOIN app.family_record r ON r.id = s.record_id
WHERE NOT EXISTS (SELECT FROM app.ledger_member fm WHERE fm.ledger_id = r.ledger_id AND fm.user_sub = pm.user_sub);

-- Rule 2, whatever users write: no entry whose postings fail to sum to zero in a currency.
SELECT 'unbalanced entries ' || count(*)
FROM (SELECT entry_id FROM app.posting GROUP BY entry_id, currency HAVING sum(amount) <> 0) AS unbalanced;
