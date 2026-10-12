-- The stage checks of D3b (the Excel import: V14's sync of personal entries, D-139 and D-150; settlement sides on
-- accounts that require a counterparty and on FX_EXCHANGE, D-148 and D-71), read only; the output must equal
-- D3b.expected. V13's objects and the invariants of F8f's file still hold; V14 adds journal_entry.import_hash and
-- import_version with their check, the table imported_entry_deleted and its trigger. D3b's rollback target, fe35e26,
-- is F8f's code on V13: rollback.sh refuses below V14 while a settlement side is on an account that requires a
-- counterparty or on FX_EXCHANGE alone (D-148); no line of this file counts them, since the import (D3b-run) writes
-- them. No backslash in this file.
SELECT 'flyway latest ' || version || ' ' || description || ' ' || success
FROM app.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;

-- V13: the journal's currency (D-93) and the import's sync fields (D-48), all nullable.
SELECT 'column ' || table_name || '.' || column_name || ' ' || data_type || ' nullable ' || is_nullable
FROM information_schema.columns
WHERE table_schema = 'app'
  AND ((table_name = 'family_record_change' AND column_name = 'currency')
    OR (table_name = 'family_record' AND column_name IN ('external_ref', 'content_hash', 'imported_version',
                                                         'imported_by_member_id', 'imported_at'))
    OR (table_name = 'journal_entry' AND column_name IN ('import_hash', 'import_version')))
ORDER BY table_name COLLATE "C", column_name COLLATE "C";

-- V13's constraints and index: a refund's signs (D-79), the sync fields together, a journal row's currency, a
-- reference once per family ledger; and V7's checks that an amount is above 0 are gone.
SELECT 'constraint ' || conname || ' ' || count(*)
FROM pg_constraint
WHERE conname IN ('family_record_amount_sign_check', 'family_record_import_fields_check',
                  'family_record_change_has_currency_check', 'family_record_imported_by_fkey',
                  'journal_entry_import_sync_check')
GROUP BY conname ORDER BY conname COLLATE "C";

SELECT 'constraint ' || name || ' ' || count(c.conname)
FROM (VALUES ('family_record_base_amount_check'), ('family_record_original_amount_check'),
             ('family_share_amount_check')) AS gone(name)
LEFT JOIN pg_constraint c ON c.conname = gone.name AND c.connamespace = 'app'::regnamespace
GROUP BY name ORDER BY name COLLATE "C";

SELECT 'index family_record_external_ref_key ' || count(*)
FROM pg_indexes WHERE schemaname = 'app' AND indexname = 'family_record_external_ref_key';

-- V13's functions: the guard lets the payer's own line carry a counterparty (D-80), the shares' check looks at their
-- sign, and a journal row without a currency gets the record's.
SELECT 'function posting_family_guard own line ' || (prosrc LIKE '%own_line%')
FROM pg_proc WHERE proname = 'posting_family_guard' AND pronamespace = 'app'::regnamespace;

SELECT 'function family_record_check_shares sign ' || (prosrc LIKE '%the other sign than the amount%')
FROM pg_proc WHERE proname = 'family_record_check_shares' AND pronamespace = 'app'::regnamespace;

SELECT 'trigger family_record_change_fill_currency ' || count(*)
FROM pg_trigger WHERE tgname = 'family_record_change_fill_currency' AND NOT tgisinternal;

-- V14: the deleted imports' table (D-150) and the trigger that fills it.
SELECT 'table imported_entry_deleted ' || count(*)
FROM information_schema.tables WHERE table_schema = 'app' AND table_name = 'imported_entry_deleted';

SELECT 'trigger journal_entry_remember_deleted_import ' || count(*)
FROM pg_trigger WHERE tgname = 'journal_entry_remember_deleted_import' AND NOT tgisinternal;

-- D-139, whatever users write: an entry with an import's hash has a reference and a version.
SELECT 'imported entries without their reference or version ' || count(*)
FROM app.journal_entry
WHERE import_hash IS NOT NULL AND (external_ref IS NULL OR import_version IS NULL);

-- D-79, whatever users write: a record's amount is never 0, its paying side has its sign, only an expense is
-- negative, and no share has the other sign.
SELECT 'records or shares of the wrong sign ' || count(*)
FROM (SELECT r.id FROM app.family_record r LEFT JOIN app.family_share s ON s.record_id = r.id
      WHERE r.base_amount = 0 OR sign(r.original_amount) <> sign(r.base_amount)
         OR (r.base_amount < 0 AND r.type <> 'EXPENSE') OR sign(s.amount) = -sign(r.base_amount)) AS wrong;

-- D-93: every journal row of a record has its currency, and a system change none.
SELECT 'journal rows without their currency ' || count(*)
FROM app.family_record_change WHERE (record_id IS NULL) <> (currency IS NULL);

-- D-118, whatever users write: a member who left or was removed has a left date on or after their join date.
SELECT 'left members whose left date is before their join date ' || count(*)
FROM app.ledger_member WHERE status = 'LEFT' AND left_date < join_date;

-- Rule 2, whatever users write: no entry whose postings fail to sum to zero in a currency.
SELECT 'unbalanced entries ' || count(*)
FROM (SELECT entry_id FROM app.posting GROUP BY entry_id, currency HAVING sum(amount) <> 0) AS unbalanced;

-- D-49: the ECB has published no rouble rate since 2022-03-01.
SELECT 'ECB rouble rates after 2022-03-01 ' || count(*)
FROM app.exchange_rate
WHERE user_id IS NULL AND quote_currency = 'RUB' AND rate_date > DATE '2022-03-01';
