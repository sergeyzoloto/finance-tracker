-- The stage checks of FX-1 (the Bank of Russia's EUR rate in roubles as a shared rate source, D-851 to D-858; V18),
-- read only; the output must equal FX-1.expected. numbers.sql is unchanged: V18 adds no table, and exchange_rate isn't
-- one of its keys. The CBR's rows grow every day and the first load deletes manual RUB rates (D-858), so nothing here
-- counts rates: the schema of V18, and invariants that hold whatever the loads have written.
-- No backslash in this file.
SELECT 'flyway latest ' || version || ' ' || description || ' ' || success
FROM app.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;

-- D-853: CBR allowed, and the source part of the key.
SELECT 'constraint ' || conname || ' ' || pg_get_constraintdef(oid)
FROM pg_constraint
WHERE conrelid = 'app.exchange_rate'::regclass
  AND conname IN ('exchange_rate_source_check', 'exchange_rate_key', 'exchange_rate_owner_check')
ORDER BY conname;

-- D-852, D-853: a CBR rate is shared, RUB per one euro, positive, and from 2022-03-02 on.
SELECT 'CBR rates with a user, another currency, a non-positive rate or before 2022-03-02 ' || count(*)
FROM app.exchange_rate
WHERE source = 'CBR'
  AND (user_id IS NOT NULL OR base_currency <> 'EUR' OR quote_currency <> 'RUB' OR rate <= 0
       OR rate_date < DATE '2022-03-02');

-- D-853: every shared rate is the ECB's or the CBR's, and every manual rate a user's.
SELECT 'shared rates of another source ' || count(*)
FROM app.exchange_rate WHERE user_id IS NULL AND source NOT IN ('ECB', 'CBR');

SELECT 'manual rates without a user ' || count(*)
FROM app.exchange_rate WHERE source = 'MANUAL' AND user_id IS NULL;

-- Rule 2, whatever users write: no entry whose postings fail to sum to zero in a currency.
SELECT 'unbalanced entries ' || count(*)
FROM (SELECT entry_id FROM app.posting GROUP BY entry_id, currency HAVING sum(amount) <> 0) AS unbalanced;
