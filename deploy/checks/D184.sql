-- D-184: whose rows beyond the starter ledger the database holds, read only. One block of lines per user with a
-- personal ledger, by the user's id (the Keycloak sub): whether it is an end-to-end test account (e2e-a, e2e-b), then
-- per day the entries and accounts beyond the starter ones were created, then the user's totals. No name, e-mail address,
-- account name or amount. How to read it: deploy/RUNBOOK.md, step 11.3. No backslash in this file.
WITH person AS (
    SELECT m.user_sub AS sub, m.ledger_id,
           coalesce((SELECT u.email LIKE 'e2e-%@finance-nl.com' FROM app.users u WHERE u.keycloak_id = m.user_sub),
                    false) AS e2e
    FROM app.ledger_member m
    WHERE m.ledger_type = 'PERSONAL'
),
starter_account (code) AS (VALUES ('CASH'), ('CREDITOR_DEBT'), ('CURRENT_ACCOUNT'), ('FAMILY_DEBT'), ('FX_EXCHANGE'),
    ('LOANS_ASSET'), ('OPENING_BALANCE'), ('RESERVE'), ('SAVINGS_ACCOUNT'), ('UNALLOCATED')),
starter_category (code) AS (VALUES ('BANK_FEES'), ('CLOTHING'), ('EATING_OUT'), ('GIFTS'), ('GIFTS_RECEIVED'),
    ('GROCERIES'), ('HEALTH'), ('HOUSING'), ('INTEREST'), ('LEISURE'), ('OTHER_EXPENSES'), ('OTHER_INCOME'),
    ('SALARY'), ('TRANSPORT'), ('UTILITIES')),
added_account AS (
    SELECT a.ledger_id, a.code, a.created_at
    FROM app.account a
    WHERE a.family_ledger_id IS NULL AND a.code NOT IN (SELECT code FROM starter_account)
),
per_day AS (
    SELECT p.sub, p.e2e, d.day,
           (SELECT count(*) FROM app.journal_entry e
            WHERE e.ledger_id = p.ledger_id AND e.created_at::date = d.day) AS entries,
           (SELECT count(*) FROM added_account a
            WHERE a.ledger_id = p.ledger_id AND a.created_at::date = d.day) AS accounts
    FROM person p
    JOIN LATERAL (SELECT e.created_at::date AS day FROM app.journal_entry e WHERE e.ledger_id = p.ledger_id
                  UNION SELECT a.created_at::date FROM added_account a WHERE a.ledger_id = p.ledger_id) AS d ON true
)
SELECT line FROM (
    SELECT p.sub AS sub, 0 AS part, d.day AS day,
           'user ' || p.sub || ' e2e ' || p.e2e || ' created ' || d.day || ': entries ' || d.entries
               || ', accounts beyond the starter ones ' || d.accounts AS line
    FROM per_day d JOIN person p ON p.sub = d.sub
    UNION ALL
    SELECT p.sub, 1, NULL,
           'user ' || p.sub || ' e2e ' || p.e2e || ' totals: entries '
               || (SELECT count(*) FROM app.journal_entry e WHERE e.ledger_id = p.ledger_id)
               || ', counterparties ' || (SELECT count(*) FROM app.counterparty c WHERE c.ledger_id = p.ledger_id)
               || ', accounts beyond the starter ones '
               || (SELECT count(*) FROM added_account a WHERE a.ledger_id = p.ledger_id)
               || ' (the demo''s CREDIT_CARD and USD_ACCOUNT among them: '
               || (SELECT count(*) FROM added_account a WHERE a.ledger_id = p.ledger_id
                   AND a.code IN ('CREDIT_CARD', 'USD_ACCOUNT')) || ')'
               || ', categories beyond the starter ones '
               || (SELECT count(*) FROM app.category c WHERE c.ledger_id = p.ledger_id
                   AND c.code NOT IN (SELECT code FROM starter_category))
               || ', import batches ' || (SELECT count(*) FROM app.import_batch b WHERE b.ledger_id = p.ledger_id)
               || coalesce(' (the first ' || (SELECT min(b.started_at)::date FROM app.import_batch b
                                               WHERE b.ledger_id = p.ledger_id) || ')', '')
    FROM person p
) AS lines
ORDER BY sub, part, day;
