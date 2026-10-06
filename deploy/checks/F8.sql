-- The stage checks of F8 (F8a and F8b, deployed together, D-77; deploy/deploy.sh run and verify), read only; the
-- output must equal F8.expected. F8 adds V11 (ADR 0004), which adds a column, replaces a trigger function and drops
-- one, and no table, so numbers.sql is unchanged. As OPS-2.sql, this file pins no family counter: family budgets are
-- switched on in production, so family rows change from day to day. The one family count it reads is ADR 0004's
-- rollback condition, which is 0 at the deploy (production holds no record in another currency than its budget's) and
-- after the end-to-end suite (its cleanup deletes what it made); once members record in other currencies it grows,
-- and rollback.sh then refuses to go below V11, as ADR 0004 says. Nothing else in the output changes from day to day.
-- No backslash in this file.
SELECT 'flyway latest ' || version || ' ' || description || ' ' || success
FROM app.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;

-- V11: every record has its own currency, never null.
SELECT 'column family_record.currency ' || data_type || ' nullable ' || is_nullable
FROM information_schema.columns
WHERE table_schema = 'app' AND table_name = 'family_record' AND column_name = 'currency';

-- V11's trigger function and its trigger, in place of V8's, and V7's freeze of the base currency dropped.
SELECT 'function family_record_check_currencies ' || count(*)
FROM pg_proc WHERE proname = 'family_record_check_currencies' AND pronamespace = 'app'::regnamespace;

SELECT 'trigger family_record_check_currencies ' || count(*)
FROM pg_trigger
WHERE tgname = 'family_record_check_currencies' AND tgrelid = 'app.family_record'::regclass AND NOT tgisinternal;

SELECT 'function family_record_check_currency (V8) ' || count(*)
FROM pg_proc WHERE proname = 'family_record_check_currency' AND pronamespace = 'app'::regnamespace;

SELECT 'function ledger_check_base_currency (V7) ' || count(*)
FROM pg_proc WHERE proname = 'ledger_check_base_currency' AND pronamespace = 'app'::regnamespace;

-- ADR 0004's rollback condition: the records an image below V11 would misread, deleted ones included.
SELECT 'records in another currency than their budget''s ' || count(*)
FROM app.family_record r JOIN app.ledger l ON l.id = r.ledger_id
WHERE r.currency <> l.base_currency;

-- Rule 2, whatever users write: no entry whose postings fail to sum to zero in a currency (the triggers enforce it at
-- commit; this reads that they did).
SELECT 'unbalanced entries ' || count(*)
FROM (SELECT entry_id FROM app.posting GROUP BY entry_id, currency HAVING sum(amount) <> 0) AS unbalanced;

-- D-49: the ECB has published no rouble rate since 2022-03-01, so the shared rates (user_id NULL) hold none after it;
-- since F8b no figure uses an ECB rate more than 7 days old, and RUB is the users' own manual rate.
SELECT 'ECB rouble rates after 2022-03-01 ' || count(*)
FROM app.exchange_rate
WHERE user_id IS NULL AND quote_currency = 'RUB' AND rate_date > DATE '2022-03-01';
