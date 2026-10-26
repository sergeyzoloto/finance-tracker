-- The stage checks of OPS-3 (D-273 to D-279: Hetzner's backups, the external monitor, logs kept 14 days), read only; the
-- output must equal OPS-3.expected. OPS-3 has no migration and writes no data: it changes the deploy gate, the logging
-- driver and the privacy policy, outside the database. So its check is that the database is exactly as D4b's deploy
-- left it: Flyway V15, the 24 numbers of deploy/checks/numbers.sql (the same text) as `verify D4b` printed them on
-- 2026-10-26T19:41:53Z, and no unbalanced entry. Unlike the stages before, these numbers move when a participant writes
-- in the app; the checklist says so, and a difference in a participant's own lines is judged, not a fault by itself.
-- No backslash here.
SELECT 'flyway latest ' || version || ' ' || description || ' ' || success
FROM app.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;

-- The 24 numbers, as numbers.sql counts them.
SELECT key || '=' || n
FROM (VALUES
    ('users', (SELECT count(*) FROM app.users)),
    ('settings', (SELECT count(*) FROM app.user_settings)),
    ('personal_ledgers', (SELECT count(*) FROM app.ledger WHERE type = 'PERSONAL')),
    ('personal_members', (SELECT count(*) FROM app.ledger_member WHERE ledger_type = 'PERSONAL')),
    ('accounts', (SELECT count(*) FROM app.account)),
    ('categories', (SELECT count(*) FROM app.category)),
    ('counterparties', (SELECT count(*) FROM app.counterparty)),
    ('entries', (SELECT count(*) FROM app.journal_entry)),
    ('import_batches', (SELECT count(*) FROM app.import_batch)),
    ('imported_entries_deleted', (SELECT count(*) FROM app.imported_entry_deleted)),
    ('family_ledgers', (SELECT count(*) FROM app.ledger WHERE type = 'SHARED')),
    ('family_members', (SELECT count(*) FROM app.ledger_member WHERE ledger_type = 'SHARED')),
    ('family_members_active', (SELECT count(*) FROM app.ledger_member WHERE ledger_type = 'SHARED' AND status = 'ACTIVE')),
    ('family_members_left', (SELECT count(*) FROM app.ledger_member WHERE ledger_type = 'SHARED' AND status = 'LEFT')),
    ('family_members_former', (SELECT count(*) FROM app.ledger_member WHERE ledger_type = 'SHARED' AND status = 'FORMER')),
    ('family_records', (SELECT count(*) FROM app.family_record)),
    ('family_shares', (SELECT count(*) FROM app.family_share)),
    ('family_links', (SELECT count(*) FROM app.family_entry_link)),
    ('family_journal', (SELECT count(*) FROM app.family_record_change)),
    ('family_accounts', (SELECT count(*) FROM app.account WHERE family_ledger_id IS NOT NULL)),
    ('family_invites', (SELECT count(*) FROM app.ledger_invite)),
    ('family_import_cutoffs', (SELECT count(*) FROM app.family_import_cutoff)),
    ('imported_family_sides', (SELECT count(*) FROM app.imported_family_side)),
    -- As F6a's checklist counted it: personal rows without a PERSONAL membership of their user in their ledger, and
    -- categories without a user (family categories) outside every SHARED ledger.
    ('outside_their_ledger',
        (SELECT count(*)
         FROM (SELECT ledger_id, user_id FROM app.account
               UNION ALL SELECT ledger_id, user_id FROM app.category WHERE user_id IS NOT NULL
               UNION ALL SELECT ledger_id, user_id FROM app.counterparty
               UNION ALL SELECT ledger_id, user_id FROM app.journal_entry
               UNION ALL SELECT ledger_id, user_id FROM app.import_batch) AS r
         WHERE NOT EXISTS (SELECT FROM app.ledger_member m
                           WHERE m.ledger_id = r.ledger_id AND m.user_sub = r.user_id AND m.ledger_type = 'PERSONAL'))
        + (SELECT count(*) FROM app.category c
           WHERE c.user_id IS NULL
             AND NOT EXISTS (SELECT FROM app.ledger l WHERE l.id = c.ledger_id AND l.type = 'SHARED')))
) AS numbers (key, n)
ORDER BY key COLLATE "C";

-- Rule 2, whatever users write: no entry whose postings fail to sum to zero in a currency.
SELECT 'unbalanced entries ' || count(*)
FROM (SELECT entry_id FROM app.posting GROUP BY entry_id, currency HAVING sum(amount) <> 0) AS unbalanced;
