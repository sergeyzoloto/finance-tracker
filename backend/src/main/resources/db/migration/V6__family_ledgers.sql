-- Family ledgers (docs/adr/0003-family-budget-membership-and-cross-ledger-posting.md, topics B, C, F and J; stage F3a):
-- a family ledger's split rule, members without an account with their display names, family categories, and "Delete
-- all my data" for memberships (D-20). Additive (D-22): nothing is dropped or renamed, and category.user_id only
-- loses its NOT NULL. The code before this migration never writes a family ledger, so it runs on this schema
-- unchanged, which is what a rollback to the previous image relies on. The posting trigger stays as it is: a family
-- category is in another ledger than any personal entry, so no posting can use one yet (F4a).

-- The default split rule (D-12, topic B): EQUAL, or CUSTOM with every member's share in basis points. A personal
-- ledger has none. A family ledger's name is never blank.
ALTER TABLE ledger ADD COLUMN split_rule VARCHAR(6) CHECK (split_rule IN ('EQUAL', 'CUSTOM'));
ALTER TABLE ledger ADD CONSTRAINT ledger_shared_split_rule_check CHECK ((type = 'SHARED') = (split_rule IS NOT NULL));
ALTER TABLE ledger ADD CONSTRAINT ledger_name_check CHECK (type = 'PERSONAL' OR btrim(name) <> '');

-- A member's share under a CUSTOM rule, out of 10000. Only members that are ACTIVE (not LEFT or FORMER) have one, and
-- only in a family ledger.
ALTER TABLE ledger_member ADD COLUMN share_bp INTEGER CHECK (share_bp BETWEEN 0 AND 10000);
ALTER TABLE ledger_member ADD CONSTRAINT ledger_member_share_check
    CHECK (share_bp IS NULL OR (ledger_type = 'SHARED' AND status = 'ACTIVE'));
-- What the other members see (D-3): never blank in a family ledger, and one name per person there, whatever its
-- case. FORMER members are all "Former member" (D-20), so they don't count.
ALTER TABLE ledger_member ADD CONSTRAINT ledger_member_display_name_check
    CHECK (ledger_type = 'PERSONAL' OR btrim(display_name) <> '');
CREATE UNIQUE INDEX ledger_member_display_name_key ON ledger_member (ledger_id, lower(display_name))
    WHERE ledger_type = 'SHARED' AND status <> 'FORMER';

-- At commit, a family ledger's split rule fits its members: under EQUAL nobody has a share; under CUSTOM every ACTIVE
-- member has one, and they sum to exactly 10000.
CREATE FUNCTION ledger_check_split_rule() RETURNS trigger
    LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE
    checked BIGINT;
    rule    TEXT;
    missing BIGINT;
    total   BIGINT;
BEGIN
    IF TG_TABLE_NAME = 'ledger' THEN
        checked := NEW.id;
    ELSIF TG_OP = 'DELETE' THEN
        checked := OLD.ledger_id;
    ELSE
        checked := NEW.ledger_id;
    END IF;
    -- NULL for a personal ledger, and for one deleted since.
    SELECT split_rule INTO rule FROM ledger WHERE id = checked AND type = 'SHARED';
    IF rule = 'EQUAL' THEN
        IF EXISTS (SELECT FROM ledger_member WHERE ledger_id = checked AND share_bp IS NOT NULL) THEN
            RAISE EXCEPTION 'family ledger %: an equal split has no custom shares', checked
                USING ERRCODE = 'check_violation';
        END IF;
    ELSIF rule = 'CUSTOM' THEN
        SELECT count(*) FILTER (WHERE share_bp IS NULL), coalesce(sum(share_bp), 0) INTO missing, total
            FROM ledger_member WHERE ledger_id = checked AND status = 'ACTIVE';
        IF missing > 0 THEN
            RAISE EXCEPTION 'family ledger %: a custom split needs a share for each of its % members without one',
                checked, missing USING ERRCODE = 'check_violation';
        END IF;
        IF total <> 10000 THEN
            RAISE EXCEPTION 'family ledger %: the custom shares sum to %, not 10000', checked, total
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER ledger_split_rule_fits
    AFTER INSERT OR UPDATE OF split_rule ON ledger
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_check_split_rule();
CREATE CONSTRAINT TRIGGER ledger_member_split_rule_fits
    AFTER INSERT OR UPDATE OR DELETE ON ledger_member
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_check_split_rule();

-- A family category is a row of the family ledger without a user (D-11, topics A and F).
ALTER TABLE category ALTER COLUMN user_id DROP NOT NULL;

-- V5's fill and check, with family ledgers: a row of a family ledger is a category without user_id, and nothing else
-- goes there for now. A row of a personal ledger is checked as before, so a category without user_id can't be one.
CREATE OR REPLACE FUNCTION fill_and_check_ledger_id() RETURNS trigger
    LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
    IF TG_OP = 'INSERT' AND NEW.ledger_id IS NULL THEN
        NEW.ledger_id := personal_ledger_id(NEW.user_id);
    ELSIF EXISTS (SELECT FROM ledger WHERE id = NEW.ledger_id AND type = 'SHARED') THEN
        IF TG_TABLE_NAME <> 'category' OR NEW.user_id IS NOT NULL THEN
            RAISE EXCEPTION '% %: ledger % is a family ledger, which holds only categories without user_id',
                TG_TABLE_NAME, NEW.id, NEW.ledger_id USING ERRCODE = 'foreign_key_violation';
        END IF;
    ELSIF NOT EXISTS (SELECT FROM ledger_member
                      WHERE ledger_id = NEW.ledger_id AND user_sub = NEW.user_id AND ledger_type = 'PERSONAL') THEN
        RAISE EXCEPTION '% %: ledger % is not the personal ledger of its user', TG_TABLE_NAME, NEW.id, NEW.ledger_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;
    RETURN NEW;
END
$$;

-- The membership part of "Delete all my data" (D-20), for UserDataService.deleteAll and the runbook's "Delete a user"
-- alike, before the personal ledger goes. In each family ledger of the sub, one ledger at a time and under its row's
-- lock, so that members deleting their data at once see each other's changes:
-- - the membership becomes FORMER: no sub, "Former member", no share;
-- - if no ACTIVE member with an account remains, the family ledger goes, with its categories and members (LEFT
--   members never count, D-19);
-- - else, if the membership had a custom share above 0, the split rule falls back to EQUAL, since nobody else may
--   decide what the others' shares become;
-- - and if no owner remains, the ACTIVE member with an account who joined earliest becomes one.
-- Returns how many memberships it released.
CREATE FUNCTION release_family_memberships(sub TEXT) RETURNS INTEGER
    LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE
    family     BIGINT;
    membership BIGINT;
    share      INTEGER;
    released   INTEGER := 0;
BEGIN
    IF sub IS NULL THEN
        RAISE EXCEPTION 'release_family_memberships needs a user' USING ERRCODE = 'null_value_not_allowed';
    END IF;
    FOR family IN
        SELECT ledger_id FROM ledger_member WHERE user_sub = sub AND ledger_type = 'SHARED' ORDER BY ledger_id
    LOOP
        PERFORM FROM ledger WHERE id = family FOR UPDATE;
        -- Read after the lock, so that another member's release that committed meanwhile is seen.
        SELECT id, share_bp INTO membership, share FROM ledger_member WHERE ledger_id = family AND user_sub = sub;
        CONTINUE WHEN membership IS NULL;
        UPDATE ledger_member
            SET status = 'FORMER', role = 'MEMBER', user_sub = NULL, display_name = 'Former member', share_bp = NULL,
                left_date = coalesce(left_date, current_date)
            WHERE id = membership;
        released := released + 1;

        IF NOT EXISTS (SELECT FROM ledger_member
                       WHERE ledger_id = family AND status = 'ACTIVE' AND user_sub IS NOT NULL) THEN
            DELETE FROM category WHERE ledger_id = family;
            -- Its members go with it (ON DELETE CASCADE).
            DELETE FROM ledger WHERE id = family;
            CONTINUE;
        END IF;
        IF share > 0 THEN
            UPDATE ledger SET split_rule = 'EQUAL' WHERE id = family;
            UPDATE ledger_member SET share_bp = NULL WHERE ledger_id = family AND share_bp IS NOT NULL;
        END IF;
        IF NOT EXISTS (SELECT FROM ledger_member WHERE ledger_id = family AND role = 'OWNER') THEN
            UPDATE ledger_member SET role = 'OWNER'
                WHERE id = (SELECT id FROM ledger_member
                            WHERE ledger_id = family AND status = 'ACTIVE' AND user_sub IS NOT NULL
                            ORDER BY join_date, id LIMIT 1);
        END IF;
    END LOOP;
    RETURN released;
END
$$;
