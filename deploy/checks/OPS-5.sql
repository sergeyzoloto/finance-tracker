-- The stage checks of OPS-5 (D-936 to D-944: Postgres 16.16 and Caddy 2.11.9 on the auth stack, the build cache's bound
-- and verify's comparison in deploy.sh; no migration, no application change), read only; the output must equal
-- OPS-5.expected. Nothing of the application's schema or rows changes, so these are BATCH-1's 18 lines unchanged: the
-- schema of V18 and invariants that hold whatever the loads and the users write (no count, no time, and no constraint's
-- own text, D-917, D-912). They show that what the stage deploys left the database as it was.
-- No backslash in this file.
SELECT 'flyway latest ' || version || ' ' || description || ' ' || success
FROM app.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;

-- FX-1, D-853: CBR allowed, and the source part of the key. No constraint's own text is printed (D-917, D-912): a
-- database restored from a dump renders a check constraint differently from one Flyway migrated, so the source check is
-- asked which sources its definition names (by position), the key which columns it holds, and the owner check that it
-- exists and names both columns.
SELECT 'exchange_rate_source_check names ' || coalesce(string_agg(s, ',' ORDER BY s), 'nothing')
FROM unnest(ARRAY['CBR', 'ECB', 'MANUAL', 'OTHER']) AS s
JOIN pg_constraint c ON c.conrelid = 'app.exchange_rate'::regclass AND c.conname = 'exchange_rate_source_check'
WHERE position(quote_literal(s) IN pg_get_constraintdef(c.oid)) > 0;

SELECT 'exchange_rate_key unique on ' || string_agg(a.attname, ',' ORDER BY a.attname)
       || ' nulls not distinct ' || (position('NULLS NOT DISTINCT' IN pg_get_constraintdef(c.oid)) > 0)
FROM pg_constraint c
JOIN LATERAL unnest(c.conkey) AS k(attnum) ON true
JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum
WHERE c.conrelid = 'app.exchange_rate'::regclass AND c.conname = 'exchange_rate_key' AND c.contype = 'u'
GROUP BY c.oid;

SELECT 'exchange_rate_owner_check names source and user_id '
       || (position('source' IN pg_get_constraintdef(c.oid)) > 0 AND position('user_id' IN pg_get_constraintdef(c.oid)) > 0)
FROM pg_constraint c
WHERE c.conrelid = 'app.exchange_rate'::regclass AND c.conname = 'exchange_rate_owner_check' AND c.contype = 'c';

-- FX-1, D-852, D-853: a CBR rate is shared, RUB per one euro, positive, and from 2022-03-02 on.
SELECT 'CBR rates with a user, another currency, a non-positive rate or before 2022-03-02 ' || count(*)
FROM app.exchange_rate
WHERE source = 'CBR'
  AND (user_id IS NOT NULL OR base_currency <> 'EUR' OR quote_currency <> 'RUB' OR rate <= 0
       OR rate_date < DATE '2022-03-02');

-- FX-1, D-853: every shared rate is the ECB's or the CBR's, and every manual rate a user's.
SELECT 'shared rates of another source ' || count(*)
FROM app.exchange_rate WHERE user_id IS NULL AND source NOT IN ('ECB', 'CBR');

SELECT 'manual rates without a user ' || count(*)
FROM app.exchange_rate WHERE source = 'MANUAL' AND user_id IS NULL;

-- Rule 2, whatever users write: no entry whose postings fail to sum to zero in a currency.
SELECT 'unbalanced entries ' || count(*)
FROM (SELECT entry_id FROM app.posting GROUP BY entry_id, currency HAVING sum(amount) <> 0) AS unbalanced;

-- ENT-3b, D-900: the family payees' set has one row per entry because an entry has one family link (V7's unique key on
-- entry_id), whatever its state; the classification of the summary, the Direction filter and the breakdown knows the
-- five link types and finds a posting on every entry.
SELECT 'unique index on family_entry_link(entry_id) ' || count(*)
FROM pg_index i
WHERE i.indrelid = 'app.family_entry_link'::regclass AND i.indisunique AND i.indnatts = 1
  AND (SELECT a.attname FROM pg_attribute a WHERE a.attrelid = i.indrelid AND a.attnum = i.indkey[0]) = 'entry_id';

SELECT 'entries with more than one family link ' || count(*)
FROM (SELECT entry_id FROM app.family_entry_link WHERE entry_id IS NOT NULL GROUP BY entry_id HAVING count(*) > 1) AS many;

SELECT 'family links of an unknown type ' || count(*)
FROM app.family_entry_link
WHERE link_type NOT IN ('SHARE', 'PAYMENT', 'SETTLEMENT', 'OPENING_BALANCE', 'CORRECTION');

SELECT 'entries without a posting ' || count(*)
FROM app.journal_entry e WHERE NOT EXISTS (SELECT 1 FROM app.posting p WHERE p.entry_id = e.id);

-- ENT-3b, D-898: the indexes the Entries statements use (the plans of the restored dump of 2026-11-15); no index is added.
SELECT 'index ' || indexname
FROM pg_indexes
WHERE schemaname = 'app'
  AND indexname IN ('journal_entry_ledger_id_entry_date_idx', 'posting_category_id_idx', 'posting_entry_id_line_no_key',
                    'posting_counterparty_id_idx', 'family_entry_link_entry_id_key', 'family_entry_link_key')
ORDER BY indexname;
