-- Invites to a family ledger and taking a seat (docs/adr/0003-family-budget-membership-and-cross-ledger-posting.md,
-- topics B, E and G; D-17, D-18, D-20; stage F5). An owner creates an invite as a link that carries a random token;
-- only the token's SHA-256 is stored. The link lets one signed-in user in once before it expires: as a new member, or
-- into the seat of a member without an account, whose records from the invite's join date are then posted to them.
--
-- Additive (D-22): a new table, and release_family_memberships again with two statements more. The image before this
-- migration runs on it unchanged: it writes no invite, and the function it calls for "Delete all my data" and the
-- runbook's "Delete a user" does what V7's did, as there is no invite to revoke or delete.

-- An invite (topic G). A claim names the seat, a member without an account, and the join date the seat takes; a new
-- member joins on the day they accept (D-18). At most one of revoked, used and declined, each final; an invite past
-- its expiry is expired, which no column says. A declined invite keeps only when it was declined (D-17); a used one
-- names the membership it let in, so that owners see who accepted and when.
CREATE TABLE ledger_invite (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ledger_id            BIGINT NOT NULL,
    ledger_type          VARCHAR(8) NOT NULL DEFAULT 'SHARED' CHECK (ledger_type = 'SHARED'),
    -- SHA-256 of the token, which only the link holds.
    token_hash           BYTEA NOT NULL UNIQUE CHECK (octet_length(token_hash) = 32),
    -- The seat to take, and the date its records are posted from; both NULL for a new member.
    seat_member_id       BIGINT,
    join_date            DATE,
    created_by_member_id BIGINT NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at           TIMESTAMPTZ NOT NULL,
    revoked_at           TIMESTAMPTZ,
    used_at              TIMESTAMPTZ,
    used_by_member_id    BIGINT,
    declined_at          TIMESTAMPTZ,
    FOREIGN KEY (ledger_id, ledger_type) REFERENCES ledger (id, type) ON DELETE CASCADE,
    -- An owner removes a seat only while nobody took it; its invites go with it.
    FOREIGN KEY (ledger_id, seat_member_id) REFERENCES ledger_member (ledger_id, id) ON DELETE CASCADE,
    FOREIGN KEY (ledger_id, created_by_member_id) REFERENCES ledger_member (ledger_id, id),
    FOREIGN KEY (ledger_id, used_by_member_id) REFERENCES ledger_member (ledger_id, id),
    CHECK ((seat_member_id IS NULL) = (join_date IS NULL)),
    CHECK (expires_at > created_at AND expires_at <= created_at + interval '7 days'),
    CHECK (num_nonnulls(revoked_at, used_at, declined_at) <= 1),
    CHECK ((used_at IS NULL) = (used_by_member_id IS NULL))
);

CREATE INDEX ON ledger_invite (ledger_id, created_at DESC);

-- An invite is created by an ACTIVE owner with an account; a claim's seat is an ACTIVE member without an account, and
-- its join date is on or after the ledger's start date and not after today. What an invite is never changes, and once
-- revoked, used or declined it stays so.
CREATE FUNCTION ledger_invite_check() RETURNS trigger
    LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NOT EXISTS (SELECT FROM ledger_member
                       WHERE id = NEW.created_by_member_id AND ledger_id = NEW.ledger_id AND role = 'OWNER'
                         AND status = 'ACTIVE' AND user_sub IS NOT NULL) THEN
            RAISE EXCEPTION 'ledger invite: member % is not an active owner of family ledger %',
                NEW.created_by_member_id, NEW.ledger_id USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.seat_member_id IS NOT NULL THEN
            IF NOT EXISTS (SELECT FROM ledger_member
                           WHERE id = NEW.seat_member_id AND ledger_id = NEW.ledger_id AND status = 'ACTIVE'
                             AND user_sub IS NULL) THEN
                RAISE EXCEPTION 'ledger invite: member % of family ledger % is not a seat without an account',
                    NEW.seat_member_id, NEW.ledger_id USING ERRCODE = 'check_violation';
            END IF;
            IF NEW.join_date > current_date
                    OR NEW.join_date < (SELECT start_date FROM ledger WHERE id = NEW.ledger_id) THEN
                RAISE EXCEPTION 'ledger invite: the join date % is not between the start of family ledger % and today',
                    NEW.join_date, NEW.ledger_id USING ERRCODE = 'check_violation';
            END IF;
        END IF;
        RETURN NEW;
    END IF;
    IF (NEW.ledger_id, NEW.token_hash, NEW.seat_member_id, NEW.join_date, NEW.created_by_member_id, NEW.created_at,
        NEW.expires_at)
            IS DISTINCT FROM (OLD.ledger_id, OLD.token_hash, OLD.seat_member_id, OLD.join_date,
                              OLD.created_by_member_id, OLD.created_at, OLD.expires_at)
            OR num_nonnulls(OLD.revoked_at, OLD.used_at, OLD.declined_at) > 0
               AND (NEW.revoked_at, NEW.used_at, NEW.used_by_member_id, NEW.declined_at)
                   IS DISTINCT FROM (OLD.revoked_at, OLD.used_at, OLD.used_by_member_id, OLD.declined_at) THEN
        RAISE EXCEPTION 'ledger invite %: only a pending invite changes, and only to revoked, used or declined', OLD.id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER ledger_invite_check
    BEFORE INSERT OR UPDATE ON ledger_invite
    FOR EACH ROW EXECUTE FUNCTION ledger_invite_check();

-- V7's membership part of "Delete all my data" (D-20), which from F5 also revokes the member's pending invites, and
-- deletes a family ledger's invites with it. Everything else is as in V7.
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
            DELETE FROM ledger_invite WHERE ledger_id = family;
            DELETE FROM family_record_change WHERE ledger_id = family;
            DELETE FROM family_entry_link WHERE family_ledger_id = family;
            -- Their shares go with them (ON DELETE CASCADE).
            DELETE FROM family_record WHERE ledger_id = family;
            DELETE FROM category WHERE ledger_id = family;
            -- Its members go with it (ON DELETE CASCADE), and debt accounts that name it keep their balances.
            DELETE FROM ledger WHERE id = family;
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
