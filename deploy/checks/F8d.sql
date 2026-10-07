-- The stage checks of F8d (D-79 to D-81 refunds, payments from accounts that require a counterparty and the payer's
-- payee, D-93 the journal's currency, D-48 the import's sync fields: V13; deploy/deploy.sh run and verify), read only;
-- the output must equal F8d.expected. F8d adds V13, which adds columns and constraints and replaces three trigger
-- functions, and no table, so numbers.sql is unchanged. This file pins no family counter and no count of refunds,
-- counterparty payments or imported records (those change from day to day by themselves, as real families record);
-- its data lines hold whatever users write: no record or share of the wrong sign (D-79), no journal row without its
-- currency (D-93). Its rollback target, b6258f6, is V12's code, which misreads exactly a refund and a payment line on an
-- account that requires a counterparty; deploy/rollback.sh refuses that, with a count of its own (ADR 0004, "F8d"),
-- and no line of this file is about it.
-- No backslash in this file.
SELECT 'flyway latest ' || version || ' ' || description || ' ' || success
FROM app.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;

-- V13: the journal's currency (D-93) and the import's sync fields (D-48), all nullable.
SELECT 'column ' || table_name || '.' || column_name || ' ' || data_type || ' nullable ' || is_nullable
FROM information_schema.columns
WHERE table_schema = 'app'
  AND ((table_name = 'family_record_change' AND column_name = 'currency')
    OR (table_name = 'family_record' AND column_name IN ('external_ref', 'content_hash', 'imported_version',
                                                         'imported_by_member_id', 'imported_at')))
ORDER BY table_name COLLATE "C", column_name COLLATE "C";

-- V13's constraints and index: a refund's signs (D-79), the sync fields together, a journal row's currency, a
-- reference once per family ledger; and V7's checks that an amount is above 0 are gone.
SELECT 'constraint ' || conname || ' ' || count(*)
FROM pg_constraint
WHERE conname IN ('family_record_amount_sign_check', 'family_record_import_fields_check',
                  'family_record_change_has_currency_check', 'family_record_imported_by_fkey')
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

-- D-79, whatever users write: a record's amount is never 0, its paying side has its sign, only an expense is
-- negative, and no share has the other sign.
SELECT 'records or shares of the wrong sign ' || count(*)
FROM (SELECT r.id FROM app.family_record r LEFT JOIN app.family_share s ON s.record_id = r.id
      WHERE r.base_amount = 0 OR sign(r.original_amount) <> sign(r.base_amount)
         OR (r.base_amount < 0 AND r.type <> 'EXPENSE') OR sign(s.amount) = -sign(r.base_amount)) AS wrong;

-- D-93: every journal row of a record has its currency, and a system change none.
SELECT 'journal rows without their currency ' || count(*)
FROM app.family_record_change WHERE (record_id IS NULL) <> (currency IS NULL);

-- Rule 2, whatever users write: no entry whose postings fail to sum to zero in a currency.
SELECT 'unbalanced entries ' || count(*)
FROM (SELECT entry_id FROM app.posting GROUP BY entry_id, currency HAVING sum(amount) <> 0) AS unbalanced;

-- D-49: the ECB has published no rouble rate since 2022-03-01.
SELECT 'ECB rouble rates after 2022-03-01 ' || count(*)
FROM app.exchange_rate
WHERE user_id IS NULL AND quote_currency = 'RUB' AND rate_date > DATE '2022-03-01';
