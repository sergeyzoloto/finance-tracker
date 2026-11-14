-- The stage checks of UX-18 (the Entries summary shows family spending in full, D-796 to D-800; deploy/deploy.sh run
-- and verify), read only; the output must equal UX-18.expected. UX-18 changes what GET /api/entries/summary answers
-- (four fields added, the paying sides of counted family records out of Transfers) and the web image: no migration,
-- no stored entry, record, amount, report, import or export changed, and numbers.sql is unchanged (no table added).
-- So there is nothing new to check: these are IMP-1's 12 lines unchanged, the schema V17's as UX-9 left it and UX-10
-- to IMP-1 kept it, and its invariants.
-- Structure and invariants only: no count that an import, a user or a member's switch changes.
-- No backslash in this file.
SELECT 'flyway latest ' || version || ' ' || description || ' ' || success
FROM app.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;

-- D-483: the column, NOT NULL, false by default.
SELECT 'column ' || table_name || '.' || column_name || ' ' || data_type || ' ' || is_nullable || ' default '
    || column_default
FROM information_schema.columns
WHERE table_schema = 'app' AND table_name = 'ledger_member' AND column_name = 'shows_payees';

-- D-483, D-485: true only for a SHARED ledger's ACTIVE member with an account, and turned off by the trigger when a
-- row stops being one.
SELECT 'check ' || conname || ' ' || pg_get_constraintdef(oid)
FROM pg_constraint
WHERE conrelid = 'app.ledger_member'::regclass AND conname = 'ledger_member_shows_payees_check';

SELECT 'trigger ' || t.tgname || ' enabled ' || (t.tgenabled = 'O') || ' before update '
    || (t.tgtype & 2 = 2 AND t.tgtype & 16 = 16) || ' function ' || p.proname
FROM pg_trigger t JOIN pg_proc p ON p.oid = t.tgfoid
WHERE t.tgrelid = 'app.ledger_member'::regclass AND t.tgname = 'ledger_member_shows_payees';

-- What D-486's read, and D-523's on an entry (RecordPayees), rely on: a PAYMENT link that isn't detached has its entry
-- in its own member's personal ledger, and belongs to an expense or an income, never a settlement, whose sides are
-- SETTLEMENT links.
SELECT 'payment links outside their member''s personal ledger ' || count(*)
FROM app.family_entry_link l
JOIN app.ledger_member m ON m.ledger_id = l.family_ledger_id AND m.id = l.member_id
JOIN app.journal_entry e ON e.id = l.entry_id
JOIN app.ledger_member own ON own.ledger_id = e.ledger_id AND own.ledger_type = 'PERSONAL'
WHERE l.link_type = 'PAYMENT' AND l.detached_at IS NULL AND own.user_sub IS DISTINCT FROM m.user_sub;

SELECT 'payment links of a settlement ' || count(*)
FROM app.family_entry_link l JOIN app.family_record r ON r.id = l.record_id
WHERE l.link_type = 'PAYMENT' AND r.type = 'SETTLEMENT';

-- D-477: the category a payment's row shows is its record's, of the same family ledger.
SELECT 'records with a category of another ledger ' || count(*)
FROM app.family_record r JOIN app.category c ON c.id = r.category_id
WHERE c.ledger_id <> r.ledger_id;

-- Rule 5, which the Entries cell, its filter and its summary (D-524) read: a category only on a posting to an EQUITY
-- account.
SELECT 'categorized postings outside equity ' || count(*)
FROM app.posting p JOIN app.account a ON a.id = p.account_id
WHERE p.category_id IS NOT NULL AND a.type <> 'EQUITY';

-- What the payee filter (D-548) reads by name: an entry's payee and a posting's counterparty are of the entry's own
-- ledger.
SELECT 'entry payees of another ledger ' || count(*)
FROM app.journal_entry e JOIN app.counterparty c ON c.id = e.payee_id
WHERE c.ledger_id <> e.ledger_id;

SELECT 'posting counterparties of another ledger ' || count(*)
FROM app.posting p JOIN app.journal_entry e ON e.id = p.entry_id JOIN app.counterparty c ON c.id = p.counterparty_id
WHERE c.ledger_id <> e.ledger_id;

-- D-570: the daily report, like the cash flow, counts a posting's category of the entry's own ledger or of a family
-- budget (D-11); never one of another personal ledger.
SELECT 'categorized postings on another personal ledger''s category ' || count(*)
FROM app.posting p JOIN app.journal_entry e ON e.id = p.entry_id JOIN app.category c ON c.id = p.category_id
JOIN app.ledger l ON l.id = c.ledger_id
WHERE c.ledger_id <> e.ledger_id AND l.type <> 'SHARED';

-- Rule 2, whatever users write: no entry whose postings fail to sum to zero in a currency.
SELECT 'unbalanced entries ' || count(*)
FROM (SELECT entry_id FROM app.posting GROUP BY entry_id, currency HAVING sum(amount) <> 0) AS unbalanced;
