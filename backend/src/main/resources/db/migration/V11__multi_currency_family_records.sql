-- The multi-currency family ledger (docs/adr/0004-multi-currency-family-ledger.md; D-45, D-46, D-87; stage F8a). A
-- family record keeps its own currency: its amount and its shares are in it, and so are the lines it posts to the
-- members' debt accounts. The family ledger's base currency becomes its main currency, only the default for new records
-- and the currency of displayed totals (D-47), and it may change at any time. No exchange rate is used in a posting
-- (D-87): when a member's account is in another currency than the record's, the amount that went from or into it is
-- entered, and their FX_EXCHANGE takes the difference, as since V8.
--
-- Additive (D-22): nothing is dropped or renamed but V8's trigger function, which a new one replaces, and V7's freeze of
-- the base currency. Every existing record gets its family ledger's base currency, which is what its amount and shares
-- were in, so no other value changes. An image before this migration still starts and writes records: they get their
-- ledger's base currency, which is what that image means. What it would misread are records whose currency isn't their
-- ledger's base currency; deploy/rollback.sh refuses to go back below V11 while one exists (ADR 0004, "The rollback
-- condition").

-- The record's currency: the currency of base_amount (the record's amount; the name stays, D-22), of its shares, and of
-- the lines its shares and sides post to the debt accounts.
ALTER TABLE family_record ADD COLUMN currency CHAR(3) CHECK (currency ~ '^[A-Z]{3}$');
UPDATE family_record r SET currency = l.base_currency FROM ledger l WHERE l.id = r.ledger_id;
-- The update queued V7's deferred check that the shares add up; it runs now, since a table with pending trigger events
-- can't be altered.
SET CONSTRAINTS ALL IMMEDIATE;
ALTER TABLE family_record ALTER COLUMN currency SET NOT NULL;

COMMENT ON COLUMN family_record.currency IS
    'The record''s currency (D-45): of base_amount, of its shares, and of its lines on the debt accounts.';
COMMENT ON COLUMN family_record.base_amount IS
    'The record''s amount, in its currency (family_record.currency); in the family ledger''s base currency before V11.';
COMMENT ON COLUMN family_record.original_amount IS
    'The paying side (D-87): what went from or into the account of the payer, the receiver or a settlement''s recorder, '
    'in original_currency; the record''s amount when that is the record''s currency.';
COMMENT ON COLUMN family_record.base_rate_source IS
    'NULL when the paying side is in the record''s currency; ENTERED when both amounts were entered (D-87); ECB or '
    'MANUAL only for records converted at a rate before V11 (D-13).';
COMMENT ON COLUMN family_share.amount IS
    'The member''s share, in the record''s currency (family_record.currency).';

-- Replaces V8's family_record_check_currency, which compared the paying side with the ledger's base currency. A record's
-- currency, if an insert leaves it out (an image before V11), is its ledger's base currency, which that image's amounts
-- are in. A paying side in the record's currency is the record's amount, without a rate; one in another currency has
-- the source of the record's amount: ENTERED for a side entered with it (D-87), or the rate's (V8) for a record
-- converted before V11.
CREATE FUNCTION family_record_check_currencies() RETURNS trigger
    LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
    IF TG_OP = 'INSERT' AND NEW.currency IS NULL THEN
        SELECT base_currency INTO NEW.currency FROM ledger WHERE id = NEW.ledger_id;
    END IF;
    IF NEW.original_currency = NEW.currency THEN
        IF NEW.base_amount <> NEW.original_amount OR NEW.base_rate_source IS NOT NULL THEN
            RAISE EXCEPTION 'family record %: paid in its own currency %, the paying side is the amount, without a rate',
                NEW.id, NEW.currency USING ERRCODE = 'check_violation';
        END IF;
    ELSIF NEW.base_rate_source IS NULL THEN
        RAISE EXCEPTION 'family record %: a paying side in % for an amount in % needs the amount''s source', NEW.id,
            NEW.original_currency, NEW.currency USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

DROP TRIGGER family_record_check_currency ON family_record;
DROP FUNCTION family_record_check_currency();

CREATE TRIGGER family_record_check_currencies
    BEFORE INSERT OR UPDATE OF original_amount, original_currency, base_amount, base_rate_source, currency
    ON family_record
    FOR EACH ROW EXECUTE FUNCTION family_record_check_currencies();

-- The base currency is the main currency now (D-45): only a default and the currency of displayed totals, so it may
-- change while the ledger has records. V7's freeze goes.
DROP TRIGGER ledger_check_base_currency ON ledger;
DROP FUNCTION ledger_check_base_currency();
