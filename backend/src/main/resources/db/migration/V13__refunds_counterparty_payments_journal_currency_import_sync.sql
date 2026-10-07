-- F8d (decisions D-79 to D-81, D-93, D-48 of docs/family-budget/requirements.md; docs/adr/0004-multi-currency-family-ledger.md,
-- "F8d"). Four changes to the family tables, none dropping or renaming a column or a table (D-22):
--
-- 1. Refunds (D-79). A refund is an expense with a minus: its amount, its paying side and its shares are stored negative,
--    so that every sum over the postings, shares and records (balances, the report, a member's debt account) reduces what
--    it should without a rule of its own. V7's checks that an amount is above 0 and a share isn't below 0 are replaced
--    by "not 0, and of the record's sign".
-- 2. A payment from an account that requires a counterparty (D-80): the guard of the family posting lets the payer's own
--    line carry a counterparty, and lets the payer's own account be one that requires it.
-- 3. A journal row's currency (D-93): every row of a record's journal says which currency its amounts are in.
-- 4. The import's sync fields (D-48): the stable reference of a row of the source, the hash of its content, the version
--    of the record as the import left it, and who imported it.
--
-- An image before this migration (the code of V12) runs on it as long as no row uses what it can't read: it writes only
-- positive amounts and no counterparty, which every new check accepts, and a journal row without a currency gets the
-- record's by trigger. What it would misread are refunds and payments on counterparty accounts; deploy/rollback.sh
-- refuses to go back below V13 while one exists (ADR 0004, "F8d").

-- 1. Refunds ----------------------------------------------------------------------------------------------------------

ALTER TABLE family_record DROP CONSTRAINT family_record_original_amount_check;
ALTER TABLE family_record DROP CONSTRAINT family_record_base_amount_check;
ALTER TABLE family_share DROP CONSTRAINT family_share_amount_check;

-- The record's amount and its paying side are of one sign, and only an expense (a refund) is negative; a settlement and
-- an income are above 0.
ALTER TABLE family_record ADD CONSTRAINT family_record_amount_sign_check CHECK (
    base_amount <> 0 AND original_amount <> 0 AND sign(base_amount) = sign(original_amount)
    AND (base_amount > 0 OR type = 'EXPENSE'));

COMMENT ON COLUMN family_record.base_amount IS
    'The record''s amount, in its currency (family_record.currency); negative for a refund (D-79), an expense with a '
    'minus; in the family ledger''s base currency before V11.';
COMMENT ON COLUMN family_share.amount IS
    'The member''s share, in the record''s currency (family_record.currency); 0 or of the record''s sign, so negative '
    'for a refund (D-79).';

-- V7's check at commit that the shares add up, with the sign: no share is of the other sign than its record's.
CREATE OR REPLACE FUNCTION family_record_check_shares() RETURNS trigger
    LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE
    checked BIGINT;
    record  family_record%ROWTYPE;
    total   NUMERIC;
    shares  INTEGER;
    wrong   INTEGER;
BEGIN
    IF TG_TABLE_NAME = 'family_record' THEN
        checked := NEW.id;
    ELSIF TG_OP = 'DELETE' THEN
        checked := OLD.record_id;
    ELSE
        checked := NEW.record_id;
    END IF;
    SELECT * INTO record FROM family_record WHERE id = checked;
    IF NOT FOUND OR record.deleted_at IS NOT NULL THEN
        RETURN NULL;
    END IF;
    SELECT coalesce(sum(amount), 0), count(*), count(*) FILTER (WHERE sign(amount) = -sign(record.base_amount))
        INTO total, shares, wrong FROM family_share WHERE record_id = checked;
    IF record.type = 'SETTLEMENT' THEN
        IF shares > 0 THEN
            RAISE EXCEPTION 'family record %: a settlement has no shares', checked USING ERRCODE = 'check_violation';
        END IF;
    ELSIF total <> record.base_amount THEN
        RAISE EXCEPTION 'family record %: the shares sum to %, not to the amount %', checked, total,
            record.base_amount USING ERRCODE = 'check_violation';
    ELSIF wrong > 0 THEN
        RAISE EXCEPTION 'family record %: a share has the other sign than the amount %', checked, record.base_amount
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END
$$;

-- 2. Payments from an account that requires a counterparty (D-80) ----------------------------------------------------

-- V8's guard on postings, with two changes: the payer's own account for a payment may be one that requires a
-- counterparty (the generic posting check still insists that such an account's posting names one), and only that line,
-- the payer's own account, may carry a counterparty. Everything else is as in V8.
CREATE OR REPLACE FUNCTION posting_family_guard() RETURNS trigger
    LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE
    writer       TEXT := family_writer();
    entry_kind   TEXT;
    entry_ledger BIGINT;
    account      account%ROWTYPE;
    allowed      BOOLEAN;
    own_line     BOOLEAN;
BEGIN
    IF TG_OP = 'DELETE' THEN
        -- A posting deleted with its entry finds the entry gone; the entry's own guard has run.
        IF family_entry_is_posted(OLD.entry_id) AND writer NOT IN ('family-posting', 'delete-all') THEN
            RAISE EXCEPTION 'journal entry %: posted from a family budget, it changes only through its family record',
                OLD.entry_id USING ERRCODE = 'check_violation';
        END IF;
        RETURN OLD;
    END IF;
    IF writer <> 'family-posting' THEN
        IF family_entry_is_posted(NEW.entry_id) OR TG_OP = 'UPDATE' AND family_entry_is_posted(OLD.entry_id) THEN
            RAISE EXCEPTION 'journal entry %: posted from a family budget, it changes only through its family record',
                NEW.entry_id USING ERRCODE = 'check_violation';
        END IF;
        IF EXISTS (SELECT FROM account WHERE id = NEW.account_id AND family_ledger_id IS NOT NULL) THEN
            RAISE EXCEPTION 'posting in journal entry %: account % is the debt account of a family budget, which only it posts to',
                NEW.entry_id, NEW.account_id USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;

    SELECT kind, ledger_id INTO entry_kind, entry_ledger FROM journal_entry WHERE id = NEW.entry_id;
    SELECT * INTO account FROM account WHERE id = NEW.account_id;
    -- The payer's own account: none of the accounts the writer posts to by its code or its debt account.
    own_line := account.family_ledger_id IS NULL
        AND NOT (account.code = 'UNSPECIFIED_PAYMENTS' AND account.is_system)
        AND account.code NOT IN ('UNALLOCATED', 'OPENING_BALANCE')
        AND NOT (account.code = 'FX_EXCHANGE' AND account.is_system);
    allowed := account.ledger_id = entry_ledger AND CASE
        WHEN account.family_ledger_id IS NOT NULL THEN TRUE
        WHEN account.code = 'UNSPECIFIED_PAYMENTS' AND account.is_system THEN
            entry_kind IN ('FAMILY_PAYMENT', 'FAMILY_SETTLEMENT')
        WHEN account.code = 'UNALLOCATED' THEN
            entry_kind = 'FAMILY_SHARE' AND NEW.category_id IS NOT NULL
            AND EXISTS (SELECT FROM category c JOIN ledger l ON l.id = c.ledger_id
                        WHERE c.id = NEW.category_id AND l.type = 'SHARED')
        WHEN account.code = 'OPENING_BALANCE' THEN entry_kind IN ('FAMILY_OPENING', 'FAMILY_CORRECTION')
        WHEN account.code = 'FX_EXCHANGE' AND account.is_system THEN
            entry_kind IN ('FAMILY_PAYMENT', 'FAMILY_SETTLEMENT')
            AND entry_ledger::text = current_setting('app.own_ledger', true)
        ELSE entry_kind IN ('FAMILY_PAYMENT', 'FAMILY_SETTLEMENT')
            AND entry_ledger::text = current_setting('app.own_ledger', true)
            AND account.type IN ('ASSET', 'LIABILITY')
    END;
    IF NOT coalesce(allowed, FALSE) OR NEW.counterparty_id IS NOT NULL AND NOT own_line
            OR NEW.category_id IS NOT NULL AND account.code <> 'UNALLOCATED' THEN
        RAISE EXCEPTION 'posting in journal entry %: the family posting may not post % to account %',
            NEW.entry_id, entry_kind, account.code USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

-- 3. The journal's currency (D-93) -------------------------------------------------------------------------------------

-- The currency the row's amounts are in: the record's currency right after the change. A row that changes the currency
-- has its old value in its own "currency" change; the old amounts of that row are in that one. NULL for a change that
-- belongs to no record (the split rule's fall back to EQUAL), which has no amount.
ALTER TABLE family_record_change ADD COLUMN currency CHAR(3) CHECK (currency ~ '^[A-Z]{3}$');

-- Existing rows. A row says its currency itself if it changes it (a record created in another currency than its
-- ledger's main one, or a change of currency): the new one. Otherwise it is the old one of the record's next change of
-- currency, since the record kept its currency until then, else the record's own.
UPDATE family_record_change c
SET currency = coalesce(
    (SELECT e ->> 'new' FROM jsonb_array_elements(c.changes) e WHERE e ->> 'field' = 'currency' LIMIT 1),
    (SELECT e ->> 'old'
     FROM family_record_change later CROSS JOIN LATERAL jsonb_array_elements(later.changes) e
     WHERE later.record_id = c.record_id AND later.id > c.id AND e ->> 'field' = 'currency'
     ORDER BY later.id LIMIT 1),
    r.currency)
FROM family_record r
WHERE r.id = c.record_id;

ALTER TABLE family_record_change ADD CONSTRAINT family_record_change_has_currency_check
    CHECK ((record_id IS NULL) = (currency IS NULL));

-- An image before this migration writes a row with no currency: it is the record's own as the image just wrote it (it
-- writes the journal after the record's change), which is what the new code stores.
CREATE FUNCTION family_record_change_fill_currency() RETURNS trigger
    LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
    IF NEW.currency IS NULL AND NEW.record_id IS NOT NULL THEN
        SELECT currency INTO NEW.currency FROM family_record WHERE id = NEW.record_id;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER family_record_change_fill_currency
    BEFORE INSERT ON family_record_change
    FOR EACH ROW EXECUTE FUNCTION family_record_change_fill_currency();

COMMENT ON COLUMN family_record_change.currency IS
    'The currency of the amounts in this row (D-93): the record''s currency right after the change; the old values of a '
    'change of currency are in the old one, named by the row''s own "currency" change. NULL for a system change.';

-- 4. The import's sync fields (D-48, D-86) ---------------------------------------------------------------------------

-- A record the import wrote is recognised again by its stable reference, the source's own permanent ID (D-86), never by
-- its content: an edited row of the source keeps its ID and changes its content. Written only by the import.
ALTER TABLE family_record ADD COLUMN external_ref TEXT CHECK (length(external_ref) BETWEEN 1 AND 200);
-- SHA-256 (lowercase hex) of the source row's business content as it was imported last.
ALTER TABLE family_record ADD COLUMN content_hash CHAR(64) CHECK (content_hash ~ '^[0-9a-f]{64}$');
-- The record's own version right after the import's last write: a record whose version is still this one has not been
-- changed in the app since, and an edited row of the source may overwrite it.
ALTER TABLE family_record ADD COLUMN imported_version INTEGER CHECK (imported_version >= 0);
-- The member who imported it (the last import to write it), and when.
ALTER TABLE family_record ADD COLUMN imported_by_member_id BIGINT;
ALTER TABLE family_record ADD COLUMN imported_at TIMESTAMPTZ;
ALTER TABLE family_record ADD CONSTRAINT family_record_imported_by_fkey
    FOREIGN KEY (ledger_id, imported_by_member_id) REFERENCES ledger_member (ledger_id, id);
ALTER TABLE family_record ADD CONSTRAINT family_record_import_fields_check CHECK (
    (external_ref IS NULL) = (content_hash IS NULL) AND (external_ref IS NULL) = (imported_version IS NULL)
    AND (external_ref IS NULL) = (imported_by_member_id IS NULL) AND (external_ref IS NULL) = (imported_at IS NULL));

-- One record per reference in a family ledger, a deleted one too: the numbers of deleted rows are never reused (D-86),
-- so a re-import never brings a deleted record back.
CREATE UNIQUE INDEX family_record_external_ref_key ON family_record (ledger_id, external_ref)
    WHERE external_ref IS NOT NULL;

COMMENT ON COLUMN family_record.external_ref IS
    'The source row''s permanent ID, as the import names it (D-86, D-48), such as "xls:<file>:<ID>"; NULL for a record '
    'entered in the app. Unique per family ledger, a deleted record included.';
COMMENT ON COLUMN family_record.content_hash IS 'SHA-256 of the source row''s content as last imported (D-48).';
COMMENT ON COLUMN family_record.imported_version IS
    'family_record.version right after the import''s last write: the same version later means nobody changed it in the '
    'app since (D-48).';
