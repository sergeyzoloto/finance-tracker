-- F6b (ADR 0003 topic J, "After the OPS-1 deploy" and "F6b as built"): D-35 as clarified, and D-36.
--
-- Additive (D-22): a column with a default, a trigger that keeps it, a new function, and release_family_memberships
-- again with its deletion moved into that function, unchanged. The image before this migration runs on it unchanged:
-- it never reads the column, the trigger sets it on the claims it makes, and "Delete all my data" does what V9's did.

-- D-35 (clarified by the PM on 2026-10-02): a claim changes nothing about who takes part in which record, only where the
-- claimer's postings go: before the claim's date (the member's join_date from then on) into their opening balance, from
-- it as entries. A seat without an account takes part in records from the family ledger's start date (D-18 as
-- amended), and a claimed seat keeps doing so; a new member, and a member who returns (D-39), take part from their join
-- date. The claim's join date replaces the seat's own (V9's ledger_invite.join_date), so whether a member took a seat is
-- stored here.
ALTER TABLE ledger_member ADD COLUMN claimed_seat BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE ledger_member ADD CONSTRAINT ledger_member_claimed_seat_shared CHECK (NOT claimed_seat OR ledger_type = 'SHARED');

-- The members of a family ledger whose last used invite took their own seat: none in production, which holds no family
-- row; in a database where F5's and F6a's code ran, the seats claimed and not left and returned since.
UPDATE ledger_member m SET claimed_seat = TRUE
WHERE m.ledger_type = 'SHARED' AND m.user_sub IS NOT NULL
  AND (SELECT i.seat_member_id FROM ledger_invite i
       WHERE i.ledger_id = m.ledger_id AND i.used_by_member_id = m.id
       ORDER BY i.used_at DESC, i.id DESC LIMIT 1) = m.id;

-- The database keeps the column, whichever code writes the membership: a new member never took a seat; a seat takes
-- one when it gets its sub (a claim, D-18); a LEFT member who returns takes part from their return date (D-39), so not
-- as a claimed seat any more; nothing else changes it.
CREATE FUNCTION ledger_member_claimed_seat() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        NEW.claimed_seat := FALSE;
    ELSIF OLD.user_sub IS NULL AND NEW.user_sub IS NOT NULL THEN
        NEW.claimed_seat := TRUE;
    ELSIF OLD.status = 'LEFT' AND NEW.status = 'ACTIVE' THEN
        NEW.claimed_seat := FALSE;
    ELSE
        NEW.claimed_seat := OLD.claimed_seat;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER ledger_member_claimed_seat
    BEFORE INSERT OR UPDATE ON ledger_member
    FOR EACH ROW EXECUTE FUNCTION ledger_member_claimed_seat();

-- D-36: a family ledger without an ACTIVE member with an account is deleted, with its invites, journal, links, records
-- (their shares cascade), categories and members (cascade); the debt accounts that name it keep their balances
-- (ON DELETE SET NULL). One path for both ways to get there: the last member with an account leaving or removed
-- (FamilyMembershipService), and deleting their data (release_family_memberships, D-20). Its caller has locked the
-- ledger's row; the personal ledgers of the members who left were detached when they left, so nothing of theirs refers
-- to its categories. Refused while an ACTIVE member with an account remains.
CREATE FUNCTION delete_family_ledger(family BIGINT) RETURNS VOID
    LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE
    writer TEXT := family_writer();
BEGIN
    IF NOT EXISTS (SELECT FROM ledger WHERE id = family AND type = 'SHARED') THEN
        RAISE EXCEPTION 'delete_family_ledger: % is no family ledger', family USING ERRCODE = 'check_violation';
    END IF;
    IF EXISTS (SELECT FROM ledger_member WHERE ledger_id = family AND status = 'ACTIVE' AND user_sub IS NOT NULL) THEN
        RAISE EXCEPTION 'delete_family_ledger: family ledger % has an active member with an account', family
            USING ERRCODE = 'check_violation';
    END IF;
    PERFORM set_config('app.writer', 'delete-all', true);
    DELETE FROM ledger_invite WHERE ledger_id = family;
    DELETE FROM family_record_change WHERE ledger_id = family;
    DELETE FROM family_entry_link WHERE family_ledger_id = family;
    DELETE FROM family_record WHERE ledger_id = family;
    DELETE FROM category WHERE ledger_id = family;
    DELETE FROM ledger WHERE id = family;
    PERFORM set_config('app.writer', writer, true);
END
$$;

-- V9's membership part of "Delete all my data" (D-20), with its deletion of a family ledger in delete_family_ledger.
-- Everything else is as in V9.
CREATE OR REPLACE FUNCTION release_family_memberships(sub TEXT) RETURNS INTEGER
    LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE
    family     BIGINT;
    membership BIGINT;
    share      INTEGER;
    released   INTEGER := 0;
    writer     TEXT := family_writer();
BEGIN
    IF sub IS NULL THEN
        RAISE EXCEPTION 'release_family_memberships needs a user' USING ERRCODE = 'null_value_not_allowed';
    END IF;
    PERFORM set_config('app.writer', 'delete-all', true);
    FOR family IN
        SELECT ledger_id FROM ledger_member WHERE user_sub = sub AND ledger_type = 'SHARED' ORDER BY ledger_id
    LOOP
        PERFORM FROM ledger WHERE id = family FOR UPDATE;
        -- Read after the lock, so that another member's release that committed meanwhile is seen.
        SELECT id, share_bp INTO membership, share FROM ledger_member WHERE ledger_id = family AND user_sub = sub;
        CONTINUE WHEN membership IS NULL;

        UPDATE family_record r SET comment = NULL
            WHERE r.ledger_id = family AND r.comment IS NOT NULL
              AND (SELECT c.changed_by_member_id FROM family_record_change c
                   WHERE c.record_id = r.id AND c.changes @> '[{"field": "comment"}]'
                   ORDER BY c.id DESC LIMIT 1) = membership;
        WITH comment_changes AS (
            SELECT c.id, c.changed_by_member_id AS author,
                   lag(c.changed_by_member_id) OVER (PARTITION BY c.record_id ORDER BY c.id) AS previous_author
            FROM family_record_change c
            WHERE c.ledger_id = family AND c.changes @> '[{"field": "comment"}]'
        )
        UPDATE family_record_change c
            SET changes = (SELECT jsonb_agg(CASE WHEN e ->> 'field' = 'comment' THEN e
                                                   || CASE WHEN cc.author = membership
                                                           THEN '{"new": null}'::jsonb ELSE '{}'::jsonb END
                                                   || CASE WHEN cc.previous_author = membership
                                                           THEN '{"old": null}'::jsonb ELSE '{}'::jsonb END
                                                 ELSE e END ORDER BY n)
                           FROM jsonb_array_elements(c.changes) WITH ORDINALITY AS x (e, n))
            FROM comment_changes cc
            WHERE cc.id = c.id AND (cc.author = membership OR cc.previous_author = membership);

        -- The member's invites that are still pending stop working (D-20).
        UPDATE ledger_invite SET revoked_at = now()
            WHERE ledger_id = family AND created_by_member_id = membership AND revoked_at IS NULL
              AND used_at IS NULL AND declined_at IS NULL AND expires_at > now();
        UPDATE ledger_member
            SET status = 'FORMER', role = 'MEMBER', user_sub = NULL, display_name = 'Former member', share_bp = NULL,
                left_date = coalesce(left_date, current_date)
            WHERE id = membership;
        UPDATE family_entry_link SET detached_at = now(), system_owned = FALSE
            WHERE family_ledger_id = family AND member_id = membership AND detached_at IS NULL;
        released := released + 1;

        IF NOT EXISTS (SELECT FROM ledger_member
                       WHERE ledger_id = family AND status = 'ACTIVE' AND user_sub IS NOT NULL) THEN
            PERFORM delete_family_ledger(family);
            -- delete_family_ledger put back the writer it found, which is this function's.
            CONTINUE;
        END IF;
        IF share > 0 THEN
            UPDATE ledger SET split_rule = 'EQUAL' WHERE id = family;
            UPDATE ledger_member SET share_bp = NULL WHERE ledger_id = family AND share_bp IS NOT NULL;
            INSERT INTO family_record_change (ledger_id, about_member_id, action, changes)
                VALUES (family, membership, 'SPLIT_RULE_RESET',
                        jsonb_build_array(jsonb_build_object('field', 'splitRule', 'old', 'CUSTOM', 'new', 'EQUAL')));
        END IF;
        IF NOT EXISTS (SELECT FROM ledger_member WHERE ledger_id = family AND role = 'OWNER') THEN
            UPDATE ledger_member SET role = 'OWNER'
                WHERE id = (SELECT id FROM ledger_member
                            WHERE ledger_id = family AND status = 'ACTIVE' AND user_sub IS NOT NULL
                            ORDER BY join_date, id LIMIT 1);
        END IF;
    END LOOP;
    PERFORM set_config('app.writer', writer, true);
    RETURN released;
END
$$;
