-- The reconcile of the family acceptance counters (HIST-3, ADR 0006 section 13, D-1160, D-1165, D-1176): V21's
-- backfill again, as ledger.history.AcceptanceCounters does it, for after an image older than V21 ran (a rollback) and
-- took revisions without keeping the counters, and before "deploy/deploy.sh acceptance on". Run by hand only through
-- deploy/RUNBOOK.md, "Family acceptance (HIST-3)", whose block holds this text and whose guard step stops first unless
-- FAMILY_ACCEPTANCE_ENABLED in .env is absent or false: this file can't read .env. As the app's login (finance), the
-- way "Delete a user" runs its SQL. deploy/tests/run.sh (manual_reconcile_matches_the_runbook) checks that the
-- runbook's block is this file byte for byte.
--
-- D-1181: the counts before and after are of attached links only (detached_at IS NULL), the only links the app stamps;
-- the UPDATE of the links is V21's own, over every link, detached ones included, as V21 and AcceptanceCounters do: it
-- sets a detached link's posted_revision to its record's last_revision, which nothing reads.
--
-- Rerunnable: a second run changes nothing (after: 0 0 0). It refuses, changing nothing, while a member has a pending
-- record by the counters as they are, which a reconcile would mark accepted without posting it.
\set ON_ERROR_STOP on
\echo 'Before: records, attached links and members whose counter differs from the backfill'
SELECT 'records ' || (SELECT count(*) FROM app.family_record r
                      WHERE r.last_revision <> coalesce((SELECT max(v.revision) FROM app.record_version v
                                                         WHERE v.ledger_id = r.ledger_id AND v.record_id = r.id
                                                           AND v.record_type = 'FAMILY_RECORD' AND v.revision > 0), 0))
       || ' links ' || (SELECT count(*) FROM app.family_entry_link l
                        JOIN app.family_record r ON r.id = l.record_id AND r.ledger_id = l.family_ledger_id
                        WHERE l.detached_at IS NULL AND l.posted_revision <> r.last_revision)
       || ' members ' || (SELECT count(*) FROM app.ledger_member m JOIN app.ledger g ON g.id = m.ledger_id
                          WHERE m.ledger_type = 'SHARED' AND m.status = 'ACTIVE' AND m.user_sub IS NOT NULL
                            AND m.accepted_revision IS DISTINCT FROM g.revision);
BEGIN;
SELECT 'family ledgers locked ' || count(*)
FROM (SELECT id FROM app.ledger WHERE type = 'SHARED' ORDER BY id FOR NO KEY UPDATE) AS locked;
DO $$
DECLARE
    pending bigint;
BEGIN
    SELECT count(*) INTO pending FROM app.family_record r
    JOIN app.ledger_member m ON m.ledger_id = r.ledger_id AND m.ledger_type = 'SHARED' AND m.status = 'ACTIVE'
                            AND m.user_sub IS NOT NULL
    WHERE r.last_revision > m.accepted_revision
       OR EXISTS (SELECT FROM app.family_entry_link l
                  WHERE l.record_id = r.id AND l.family_ledger_id = r.ledger_id AND l.member_id = m.id
                    AND l.detached_at IS NULL AND l.posted_revision < r.last_revision);
    IF pending > 0 THEN
        RAISE EXCEPTION 'STOP: % pending record changes: the members accept them first; nothing changed', pending;
    END IF;
END
$$;
UPDATE app.family_record r SET last_revision = coalesce(v.revision, 0)
FROM app.family_record x
LEFT JOIN (SELECT ledger_id, record_id, max(revision) AS revision FROM app.record_version
           WHERE record_type = 'FAMILY_RECORD' AND revision > 0 GROUP BY ledger_id, record_id) v
    ON v.ledger_id = x.ledger_id AND v.record_id = x.id
WHERE x.id = r.id AND r.last_revision <> coalesce(v.revision, 0);
SELECT 'writer ' || set_config('app.writer', 'family-posting', true);
UPDATE app.family_entry_link l SET posted_revision = r.last_revision
FROM app.family_record r
WHERE r.id = l.record_id AND r.ledger_id = l.family_ledger_id AND l.posted_revision <> r.last_revision;
SELECT 'writer ' || set_config('app.writer', '', true);
UPDATE app.ledger_member m SET accepted_revision = l.revision
FROM app.ledger l
WHERE l.id = m.ledger_id AND m.ledger_type = 'SHARED' AND m.status = 'ACTIVE' AND m.user_sub IS NOT NULL
  AND m.accepted_revision IS DISTINCT FROM l.revision;
COMMIT;
\echo 'After (0 0 0 expected): the same counts'
SELECT 'records ' || (SELECT count(*) FROM app.family_record r
                      WHERE r.last_revision <> coalesce((SELECT max(v.revision) FROM app.record_version v
                                                         WHERE v.ledger_id = r.ledger_id AND v.record_id = r.id
                                                           AND v.record_type = 'FAMILY_RECORD' AND v.revision > 0), 0))
       || ' links ' || (SELECT count(*) FROM app.family_entry_link l
                        JOIN app.family_record r ON r.id = l.record_id AND r.ledger_id = l.family_ledger_id
                        WHERE l.detached_at IS NULL AND l.posted_revision <> r.last_revision)
       || ' members ' || (SELECT count(*) FROM app.ledger_member m JOIN app.ledger g ON g.id = m.ledger_id
                          WHERE m.ledger_type = 'SHARED' AND m.status = 'ACTIVE' AND m.user_sub IS NOT NULL
                            AND m.accepted_revision IS DISTINCT FROM g.revision);
