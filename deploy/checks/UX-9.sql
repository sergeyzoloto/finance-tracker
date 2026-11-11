-- The stage checks of UX-9 (a family payment's category in Entries and its Category filter; the payees a member
-- chooses to show in a family budget, D-475 to D-492; deploy/deploy.sh run and verify), read only; the output must
-- equal UX-9.expected. UX-9 adds V17 (one column of ledger_member, its check and its trigger), one endpoint
-- (PATCH /api/family-ledgers/{id}/me) and additive fields: no stored entry, record, amount, import or export changed,
-- and numbers.sql is unchanged (no table added).
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

-- What D-486's read relies on: a PAYMENT link that isn't detached has its entry in its own member's personal ledger,
-- and belongs to an expense or an income, never a settlement, whose sides are SETTLEMENT links.
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

-- Rule 5, which the Entries cell and filter read: a category only on a posting to an EQUITY account.
SELECT 'categorized postings outside equity ' || count(*)
FROM app.posting p JOIN app.account a ON a.id = p.account_id
WHERE p.category_id IS NOT NULL AND a.type <> 'EQUITY';

-- Rule 2, whatever users write: no entry whose postings fail to sum to zero in a currency.
SELECT 'unbalanced entries ' || count(*)
FROM (SELECT entry_id FROM app.posting GROUP BY entry_id, currency HAVING sum(amount) <> 0) AS unbalanced;
