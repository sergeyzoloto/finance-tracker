-- The stage checks of HIST-1 (record versions and the activity log, ADR 0006, D-989 to D-1061; V19), read only; the
-- output must equal HIST-1.expected. numbers.sql adds three keys for V19's tables (record_versions, activity_events,
-- history_pending), which take effect from the next deploy. These are WEB-1's 18 lines (the schema of V18 and
-- invariants that hold whatever the loads and the users write) and, after them, V19's: its tables, columns and
-- triggers by name, its functions by their argument types (the old callers' signatures, D-1040), and invariants that
-- hold whatever the application has written (no count that moves by itself, no time, and no constraint's own text,
-- D-917, D-912). What depends on the baseline job having run (live records without a version) is the checklist's own
-- block, not this file, since the job runs in the background after the application starts.
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

-- D-939: the indexes that the release notes of 17.12 advise to REINDEX (see deploy/checks/db-image-indexes.sql): none.
SELECT format('%s|%s|%s', c.oid::regclass, am.amname, why.text)
FROM pg_index i
JOIN pg_class c ON c.oid = i.indexrelid
JOIN pg_namespace n ON n.oid = c.relnamespace
JOIN pg_am am ON am.oid = c.relam
CROSS JOIN LATERAL (
  SELECT 'btree on bpchar without a length'::text AS text
  WHERE am.amname = 'btree'
    AND EXISTS (SELECT 1 FROM pg_attribute a
                WHERE a.attrelid = c.oid AND a.attnum > 0 AND a.atttypid = 'bpchar'::regtype AND a.atttypmod = -1)
  UNION ALL
  SELECT 'GIN'::text WHERE am.amname = 'gin'
  UNION ALL
  SELECT 'GiST on gist_trgm_ops'::text
  WHERE am.amname = 'gist'
    AND EXISTS (SELECT 1 FROM unnest(i.indclass::oid[]) AS k(oid) JOIN pg_opclass o ON o.oid = k.oid
                WHERE o.opcname = 'gist_trgm_ops')
) AS why
WHERE n.nspname NOT IN ('pg_catalog', 'information_schema', 'pg_toast')
ORDER BY 1;

-- HIST-1, V19: the tables, their persistence (p permanent, u unlogged: the pending marks are rows that live inside one
-- transaction) and their columns in order.
SELECT 'table ' || c.relname || ' ' || c.relpersistence::text || ' '
       || (SELECT string_agg(a.attname, ',' ORDER BY a.attnum) FROM pg_attribute a
           WHERE a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped)
FROM pg_class c
WHERE c.relnamespace = 'app'::regnamespace AND c.relkind = 'r'
  AND c.relname IN ('record_version', 'activity_event', 'history_pending')
ORDER BY c.relname;

SELECT 'ledger.revision ' || data_type || ' ' || is_nullable || ' ' || column_default
FROM information_schema.columns
WHERE table_schema = 'app' AND table_name = 'ledger' AND column_name = 'revision';

-- HIST-1, D-1004, D-1040: the immutability guards, the marks on the five live record tables, and the deferred check
-- (deferrable, initially deferred).
SELECT 'trigger ' || c.relname || ' ' || t.tgname
FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid
WHERE NOT t.tgisinternal AND c.relnamespace = 'app'::regnamespace
  AND t.tgname IN ('record_version_immutable', 'activity_event_immutable', 'journal_entry_history', 'account_history',
                   'category_history', 'counterparty_history', 'family_record_history', 'history_pending_check')
ORDER BY c.relname, t.tgname;

SELECT 'deferred ' || t.tgname || ' ' || t.tgdeferrable || ' ' || t.tginitdeferred
FROM pg_trigger t
WHERE t.tgname = 'history_pending_check' AND t.tgrelid = 'app.history_pending'::regclass;

-- HIST-1, D-1040: the two replaced functions keep their signatures and results (the image before HIST-1 calls them), and
-- the new ones are as written.
SELECT 'function ' || p.proname || '(' || pg_get_function_identity_arguments(p.oid) || ') returns '
       || pg_get_function_result(p.oid)
FROM pg_proc p
WHERE p.pronamespace = 'app'::regnamespace
  AND p.proname IN ('release_family_memberships', 'delete_family_ledger', 'history_mark', 'history_check',
                    'history_immutable')
ORDER BY p.proname;

-- HIST-1, ADR 0006 5.1: a version's keys. The ledger's revision never falls behind its versions, a record's versions
-- are numbered 1, 2, ... without a gap, a sign-in event never outlives 14 months (the daily job keeps 13), and nothing
-- is left marked between transactions.
SELECT 'versions after their ledger''s revision ' || count(*)
FROM app.record_version v JOIN app.ledger l ON l.id = v.ledger_id WHERE v.revision > l.revision;

SELECT 'records whose versions have a gap in seq ' || count(*)
FROM (SELECT ledger_id, record_type, record_id FROM app.record_version
      GROUP BY ledger_id, record_type, record_id HAVING max(seq) <> count(*) OR min(seq) <> 1) AS gaps;

SELECT 'versions of an unknown type or op ' || count(*)
FROM app.record_version
WHERE record_type NOT IN ('ENTRY', 'ACCOUNT', 'CATEGORY', 'COUNTERPARTY', 'FAMILY_RECORD', 'FAMILY_CATEGORY')
   OR op NOT IN ('BASELINE', 'CREATE', 'UPDATE', 'DELETE', 'ARCHIVE', 'RESTORE');

SELECT 'versions that name an email-like or address-like key ' || count(*)
FROM app.record_version WHERE content ? 'email' OR content ? 'ip' OR content ? 'account_name';

SELECT 'activity events older than 14 months ' || count(*)
FROM app.activity_event WHERE ledger_id IS NULL AND at < now() - interval '14 months';

SELECT 'activity events with an email or an address in their details ' || count(*)
FROM app.activity_event WHERE details IS NOT NULL AND (details ? 'email' OR details ? 'ip' OR details ? 'userAgent');

SELECT 'pending marks left ' || count(*) FROM app.history_pending;
