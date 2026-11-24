-- The pre-check of deploy/deploy.sh acceptance on|off (HIST-3, ADR 0006 section 7, D-1175), read only, as the role
-- finance_checks like every check file. One line, which deploy.sh reads (it has no .expected file, as numbers.sql has
-- none): "acceptance members=<m> links=<l> pending=<p>", where m counts the ACTIVE family members with an account whose
-- accepted revision isn't their ledger's revision, l the attached family links (detached_at IS NULL) not posted at their record's last revision, and
-- p the records pending for some member by the counters (the reconcile's own count, ledger.history.AcceptanceCounters).
-- "acceptance on" goes on only with all three 0; "acceptance off" only with p 0. No backslash in this file.
SELECT 'acceptance members=' || (
         SELECT count(*) FROM app.ledger_member m JOIN app.ledger l ON l.id = m.ledger_id
         WHERE m.ledger_type = 'SHARED' AND m.status = 'ACTIVE' AND m.user_sub IS NOT NULL
           AND m.accepted_revision IS DISTINCT FROM l.revision)
       || ' links=' || (
         SELECT count(*) FROM app.family_entry_link k
         JOIN app.family_record r ON r.id = k.record_id AND r.ledger_id = k.family_ledger_id
         WHERE k.detached_at IS NULL AND k.posted_revision <> r.last_revision)
       || ' pending=' || (
         SELECT count(*) FROM app.family_record r
         JOIN app.ledger_member m ON m.ledger_id = r.ledger_id AND m.ledger_type = 'SHARED' AND m.status = 'ACTIVE'
                                 AND m.user_sub IS NOT NULL
         WHERE r.last_revision > m.accepted_revision
            OR EXISTS (SELECT FROM app.family_entry_link l
                       WHERE l.record_id = r.id AND l.family_ledger_id = r.ledger_id AND l.member_id = m.id
                         AND l.detached_at IS NULL AND l.posted_revision < r.last_revision));
