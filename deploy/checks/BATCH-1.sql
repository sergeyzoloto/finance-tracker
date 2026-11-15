-- The stage checks of BATCH-1 (D-867, D-879): FX-1 (the Bank of Russia's EUR rate in roubles as a shared rate source,
-- D-851 to D-858; V18) and ENT-1 to ENT-4 with ENT-3b (the Entries page's period, summary, filters, breakdown, monthly
-- chart and the queries behind them, D-869 to D-906; no migration), read only; the output must equal BATCH-1.expected.
-- numbers.sql is unchanged: V18 adds no table, and exchange_rate isn't one of its keys. The CBR's rows grow every day
-- and the first load deletes manual RUB rates (D-858), so nothing here counts rates: the schema of V18, and invariants
-- that hold whatever the loads and the users have written. The last part is what ENT-3b's queries rely on (D-900): an
-- entry has at most one family link, and the indexes the plans of D-898's targets use.
-- No backslash in this file.
SELECT 'flyway latest ' || version || ' ' || description || ' ' || success
FROM app.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;

-- FX-1, D-853: CBR allowed, and the source part of the key.
SELECT 'constraint ' || conname || ' ' || pg_get_constraintdef(oid)
FROM pg_constraint
WHERE conrelid = 'app.exchange_rate'::regclass
  AND conname IN ('exchange_rate_source_check', 'exchange_rate_key', 'exchange_rate_owner_check')
ORDER BY conname;

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
