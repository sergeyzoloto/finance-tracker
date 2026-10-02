-- The numbers of a deploy (deploy/deploy.sh): one key=value line per count, sorted by key, read only.
-- deploy.sh reads this file from the running commit before the merge and runs that same text before and after the
-- deploy, so a new version takes effect from the next deploy. Counts only: no Flyway line (postflight checks Flyway on
-- its own) and nothing that changes from day to day by itself. Add a key when a migration adds a table. No backslash
-- anywhere in this file: deploy.sh refuses one, since psql would read it as a meta-command.
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
