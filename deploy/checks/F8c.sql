-- The stage checks of F8c (D-100 and D-101: each user's time zone, V12; deploy/deploy.sh run and verify), read only;
-- the output must equal F8c.expected. F8c adds V12, which adds a nullable column with a length check and replaces one
-- trigger function (V9's check of a claim's join date), and no table, so numbers.sql is unchanged. As F8.sql, this file
-- pins no family counter but the one ADR 0004 makes a rollback condition, and no count of users who have saved a zone
-- (those change from day to day by themselves). The records-in-another-currency line is 0 while no member has
-- recorded in another currency than their budget's; once one has, it is that number, the deploy's step 4.4 stops on
-- the difference, and the PM judges it (it is the rollback condition of ADR 0004, which F8c doesn't change).
-- No backslash in this file.
SELECT 'flyway latest ' || version || ' ' || description || ' ' || success
FROM app.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;

-- V12: the user's time zone, an IANA id, null until it is set.
SELECT 'column user_settings.time_zone ' || data_type || ' nullable ' || is_nullable
FROM information_schema.columns
WHERE table_schema = 'app' AND table_name = 'user_settings' AND column_name = 'time_zone';

SELECT 'constraint user_settings_time_zone_length ' || count(*)
FROM pg_constraint
WHERE conname = 'user_settings_time_zone_length' AND conrelid = 'app.user_settings'::regclass;

-- V12's replacement of V9's invite check: a claim's join date is checked against the date at UTC+14, in place of the
-- session's current_date (true), and the old text is gone (false).
SELECT 'function ledger_invite_check at UTC+14 ' || (prosrc LIKE '%Pacific/Kiritimati%')
    || ' current_date ' || (prosrc LIKE '%current_date%')
FROM pg_proc WHERE proname = 'ledger_invite_check' AND pronamespace = 'app'::regnamespace;

-- ADR 0004's rollback condition, unchanged by V12.
SELECT 'records in another currency than their budget''s ' || count(*)
FROM app.family_record r JOIN app.ledger l ON l.id = r.ledger_id
WHERE r.currency <> l.base_currency;

-- Rule 2, whatever users write: no entry whose postings fail to sum to zero in a currency.
SELECT 'unbalanced entries ' || count(*)
FROM (SELECT entry_id FROM app.posting GROUP BY entry_id, currency HAVING sum(amount) <> 0) AS unbalanced;

-- D-49: the ECB has published no rouble rate since 2022-03-01.
SELECT 'ECB rouble rates after 2022-03-01 ' || count(*)
FROM app.exchange_rate
WHERE user_id IS NULL AND quote_currency = 'RUB' AND rate_date > DATE '2022-03-01';
