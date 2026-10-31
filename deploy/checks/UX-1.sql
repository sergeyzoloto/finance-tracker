-- The stage checks of UX-1 (the dashboard reworked, and the date format in Settings: D-295 to D-307; deploy/deploy.sh
-- run and verify), read only; the output must equal UX-1.expected. UX-1 adds V16, which adds one nullable column with
-- a check and no table, so numbers.sql is unchanged. Structure only: nothing here changes when someone picks a date
-- format (no count of users with one), and nothing about the reports, which read the same tables as before.
-- No backslash in this file.
SELECT 'flyway latest ' || version || ' ' || description || ' ' || success
FROM app.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;

-- V16: the user's date format, null for as the browser shows dates.
SELECT 'column user_settings.date_format ' || data_type || ' nullable ' || is_nullable
FROM information_schema.columns
WHERE table_schema = 'app' AND table_name = 'user_settings' AND column_name = 'date_format';

-- Its check names the four formats.
SELECT 'constraint user_settings_date_format_check '
    || (pg_get_constraintdef(oid) LIKE '%DD.MM.YYYY%DD/MM/YYYY%MM/DD/YYYY%YYYY-MM-DD%')
FROM pg_constraint
WHERE conname = 'user_settings_date_format_check' AND conrelid = 'app.user_settings'::regclass;

-- V12's time zone is still there beside it.
SELECT 'column user_settings.time_zone ' || data_type || ' nullable ' || is_nullable
FROM information_schema.columns
WHERE table_schema = 'app' AND table_name = 'user_settings' AND column_name = 'time_zone';

-- Rule 2, whatever users write: no entry whose postings fail to sum to zero in a currency.
SELECT 'unbalanced entries ' || count(*)
FROM (SELECT entry_id FROM app.posting GROUP BY entry_id, currency HAVING sum(amount) <> 0) AS unbalanced;
