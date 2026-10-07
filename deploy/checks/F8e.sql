-- The stage checks of F8e (D-117 no implicit clock, D-118 a leaving date is never before the join date, D-119 tests read
-- answers as parsed values; no migration; deploy/deploy.sh run and verify), read only; the output must equal
-- F8e.expected. F8e adds no migration, so Flyway stays at V13 and numbers.sql is unchanged. Most of this file is
-- F8d's, whose V13 objects must still be there, and each line is about the schema or about data that users write: it
-- pins no family counter and no count of refunds, counterparty payments, imported records or members. The one new
-- line is D-118's: no member who LEFT has a left date before their join date (the server clamps it since F8e). It
-- looks at LEFT members only: a FORMER member's left date is release_family_memberships' current_date (the database
-- session's, UTC), which D-118 leaves alone, and a count over them could change from day to day by itself.
-- F8e's rollback target, 05397a7, is F8d's code on the same schema (V13), so no rollback condition is new; V13's own
-- (a live refund or a payment on an account that requires a counterparty) still holds for any target below V13, and
-- no line of this file is about it.
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
