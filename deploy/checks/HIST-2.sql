-- The stage checks of HIST-2 (the notifications, ADR 0006 sections 13 and 14, D-1077, D-1087 to D-1111; V20), read only;
-- the output must equal HIST-2.expected. numbers.sql adds one key for V20's table (notifications), which takes effect
-- from the next deploy. These are HIST-1's 43 lines (the schema of V19 and invariants that hold whatever the loads and
-- the users write, still true after V20: its functions keep their signatures, D-1040) and, after them, V20's: the
-- notification table, its columns and indexes by name, and invariants that hold whatever the application has written
-- (no count that moves by itself, no time, and no constraint's own text, D-917, D-912). V20 adds no baseline job, and
-- a version is written in the transaction of the change it describes, so the check that no live record lacks a version
-- is an invariant here (HIST-1's own checklist ran it as a block of its own, since its baseline job ran in the
-- background after the application started). activity_event.kind has no CHECK (D-1101) and nothing here asks for one,
-- as HIST-1's lines ask for none on the kinds of its own tables.
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
FROM app.activity_event
WHERE ledger_id IS NULL AND kind IN ('SIGN_IN', 'SIGN_OUT', 'SESSION_EXPIRED', 'SESSION_ENDED')
  AND at < now() - interval '14 months';

SELECT 'activity events with an email or an address in their details ' || count(*)
FROM app.activity_event WHERE details IS NOT NULL AND (details ? 'email' OR details ? 'ip' OR details ? 'userAgent');

SELECT 'pending marks left ' || count(*) FROM app.history_pending;

-- HIST-2, V20 (D-1077, D-1087): the notification table, its persistence and its columns in order: references, a kind, a
-- revision range and a count, and two times; no text and no content column.
SELECT 'table ' || c.relname || ' ' || c.relpersistence::text || ' '
       || (SELECT string_agg(a.attname, ',' ORDER BY a.attnum) FROM pg_attribute a
           WHERE a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped)
FROM pg_class c
WHERE c.relnamespace = 'app'::regnamespace AND c.relkind = 'r' AND c.relname = 'notification';

SELECT 'notification columns of a text, json or binary type besides kind ' || count(*)
FROM pg_attribute a
JOIN pg_type t ON t.oid = a.atttypid
WHERE a.attrelid = 'app.notification'::regclass AND a.attnum > 0 AND NOT a.attisdropped AND a.attname <> 'kind'
  AND t.typname IN ('text', 'varchar', 'bpchar', 'json', 'jsonb', 'bytea', 'xml');

-- HIST-2, D-1088, D-1092: the unread count's partial index and the three beside it.
SELECT 'index ' || indexname
FROM pg_indexes
WHERE schemaname = 'app' AND tablename = 'notification'
  AND indexname IN ('notification_unread_idx', 'notification_recipient_idx', 'notification_ledger_idx',
                    'notification_author_idx')
ORDER BY indexname;

SELECT 'notification_unread_idx is partial ' || (i.indpred IS NOT NULL)
FROM pg_index i WHERE i.indexrelid = 'app.notification_unread_idx'::regclass;

-- HIST-2, D-1077: only the five kinds, and nothing a recipient should not hold (D-1105, D-1108, D-1111): a range that
-- ends before it starts, a recipient of another ledger, the author as the recipient, a ledger that is not a family one.
SELECT 'notifications of a kind other than the five ' || count(*)
FROM app.notification
WHERE kind NOT IN ('FAMILY_CHANGES', 'FAMILY_IMPORTED', 'MEMBER_JOINED', 'MEMBER_LEFT', 'SEAT_CLAIMED');

SELECT 'notifications whose range ends before it starts ' || count(*)
FROM app.notification WHERE to_revision < from_revision;

SELECT 'notifications whose recipient belongs to another ledger ' || count(*)
FROM app.notification n JOIN app.ledger_member m ON m.id = n.recipient_member_id WHERE m.ledger_id <> n.ledger_id;

SELECT 'notifications to their own author ' || count(*)
FROM app.notification WHERE recipient_member_id = author_member_id;

SELECT 'notifications of a ledger that is not a family one ' || count(*)
FROM app.notification n JOIN app.ledger l ON l.id = n.ledger_id WHERE l.type <> 'SHARED';

-- HIST-2, ADR 0006 5.1 and D-1004: every live record has a version (the five record tables; the same rules as the
-- backstop's marks: no family-entry, no family-owned account, no UNSPECIFIED_PAYMENTS).
SELECT 'unversioned ' || t || ' ' || n
FROM (SELECT 'accounts' AS t, count(*) AS n
      FROM app.account a
      WHERE a.family_ledger_id IS NULL AND a.code <> 'UNSPECIFIED_PAYMENTS'
        AND NOT EXISTS (SELECT 1 FROM app.record_version v
                        WHERE v.ledger_id = a.ledger_id AND v.record_type = 'ACCOUNT' AND v.record_id = a.id)
      UNION ALL
      SELECT 'categories', count(*)
      FROM app.category c
      WHERE NOT EXISTS (SELECT 1 FROM app.record_version v
                        WHERE v.ledger_id = c.ledger_id AND v.record_type IN ('CATEGORY', 'FAMILY_CATEGORY')
                          AND v.record_id = c.id)
      UNION ALL
      SELECT 'counterparties', count(*)
      FROM app.counterparty p
      WHERE NOT EXISTS (SELECT 1 FROM app.record_version v
                        WHERE v.ledger_id = p.ledger_id AND v.record_type = 'COUNTERPARTY' AND v.record_id = p.id)
      UNION ALL
      SELECT 'entries', count(*)
      FROM app.journal_entry e
      WHERE left(e.kind, 7) <> 'FAMILY_'
        AND NOT EXISTS (SELECT 1 FROM app.record_version v
                        WHERE v.ledger_id = e.ledger_id AND v.record_type = 'ENTRY' AND v.record_id = e.id)
      UNION ALL
      SELECT 'family records', count(*)
      FROM app.family_record r
      WHERE NOT EXISTS (SELECT 1 FROM app.record_version v
                        WHERE v.ledger_id = r.ledger_id AND v.record_type = 'FAMILY_RECORD' AND v.record_id = r.id)) AS x
ORDER BY t;
