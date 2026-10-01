-- Family records in other currencies (docs/adr/0003-family-budget-membership-and-cross-ledger-posting.md, topics D and
-- E; D-13; stage F4e). A record already keeps its original amount and currency next to its base amount (V7); this
-- migration adds how the base amount was found, and lets the posting service route the payer's own payment, or a
-- member's own side of a settlement, through their FX_EXCHANGE account when it is in another currency than the
-- family's base currency (rule 9), as personal currency exchanges do.
--
-- Additive (D-22): nothing is dropped or renamed, the new columns are nullable, and the image before this migration
-- runs on it unchanged: it writes only records in the base currency, which the new trigger accepts, and it never posts
-- to FX_EXCHANGE, which the guard still refuses everywhere else.

-- The rate a base amount was converted with, its source and the day of the rate (the latest on or before the record's
-- date). Not derivable (rule 13): the ECB's rates are loaded again, a member's manual rate can be changed or deleted,
-- and an entered base amount has no rate at all. NULL for a record in the base currency.
ALTER TABLE family_record ADD COLUMN base_rate NUMERIC(24, 12) CHECK (base_rate > 0);
ALTER TABLE family_record ADD COLUMN base_rate_source VARCHAR(10)
    CHECK (base_rate_source IN ('ECB', 'MANUAL', 'ENTERED'));
ALTER TABLE family_record ADD COLUMN base_rate_date DATE;
ALTER TABLE family_record ADD CONSTRAINT family_record_rate_source_check CHECK (
    coalesce(base_rate_source IN ('ECB', 'MANUAL'), FALSE) = (base_rate IS NOT NULL)
    AND (base_rate IS NULL) = (base_rate_date IS NULL));

-- A record in the family's base currency has the original amount as its base amount and no rate (D-13); one in another
-- currency has the source of its base amount.
CREATE FUNCTION family_record_check_currency() RETURNS trigger
    LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE
    base CHAR(3);
BEGIN
    SELECT base_currency INTO base FROM ledger WHERE id = NEW.ledger_id;
    IF NEW.original_currency = base THEN
        IF NEW.base_amount <> NEW.original_amount OR NEW.base_rate_source IS NOT NULL THEN
            RAISE EXCEPTION 'family record %: in the base currency %, the base amount is the amount, without a rate',
                NEW.id, base USING ERRCODE = 'check_violation';
        END IF;
    ELSIF NEW.base_rate_source IS NULL THEN
        RAISE EXCEPTION 'family record %: an amount in % needs the source of its base amount in %', NEW.id,
            NEW.original_currency, base USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER family_record_check_currency
    BEFORE INSERT OR UPDATE OF original_amount, original_currency, base_amount, base_rate_source ON family_record
    FOR EACH ROW EXECUTE FUNCTION family_record_check_currency();

-- The guard on postings (V7), with one more account the posting service's writer may post to: the member's own
-- FX_EXCHANGE, for a payment or a settlement side in another currency than the base currency, and only in the
-- personal ledger the writer declares as the acting member's own (app.own_ledger), since only the payer or the side
-- themselves names that currency (D-8, D-14). Everything else is as in V7.
CREATE OR REPLACE FUNCTION posting_family_guard() RETURNS trigger
    LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE
    writer       TEXT := family_writer();
    entry_kind   TEXT;
    entry_ledger BIGINT;
    account      account%ROWTYPE;
    allowed      BOOLEAN;
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
            AND account.type IN ('ASSET', 'LIABILITY') AND NOT account.requires_counterparty
    END;
    IF NOT coalesce(allowed, FALSE) OR NEW.counterparty_id IS NOT NULL
            OR NEW.category_id IS NOT NULL AND account.code <> 'UNALLOCATED' THEN
        RAISE EXCEPTION 'posting in journal entry %: the family posting may not post % to account %',
            NEW.entry_id, entry_kind, account.code USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;
