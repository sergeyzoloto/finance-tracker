-- UX-9, D-479 to D-483: a member may let the other members of a family budget see the payees of their family
-- payments. Each member decides for their own payees, in each budget separately; off by default for everyone.
--
-- Only an ACTIVE member with an account of a SHARED ledger may have it on (D-480). The trigger below turns it off
-- whenever a row stops being one (leaving, removal, "Delete all my data"'s release, a FORMER member), whichever code
-- writes the row, so that the image before this migration, which ignores the column, can make a member LEFT or FORMER
-- on this schema without breaking the check (D-483: a rollback hides every payee again, the safe side). It acts only
-- when a row stops being one, so the check still refuses a statement that sets it on for a member without an account,
-- a LEFT or a FORMER one.
ALTER TABLE ledger_member ADD COLUMN shows_payees BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE ledger_member ADD CONSTRAINT ledger_member_shows_payees_check
    CHECK (NOT shows_payees OR (ledger_type = 'SHARED' AND status = 'ACTIVE' AND user_sub IS NOT NULL));

CREATE FUNCTION ledger_member_shows_payees() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status = 'ACTIVE' AND OLD.user_sub IS NOT NULL AND (NEW.status <> 'ACTIVE' OR NEW.user_sub IS NULL) THEN
        NEW.shows_payees := FALSE;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER ledger_member_shows_payees
    BEFORE UPDATE ON ledger_member
    FOR EACH ROW EXECUTE FUNCTION ledger_member_shows_payees();
