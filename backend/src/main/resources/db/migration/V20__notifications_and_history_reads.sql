-- HIST-2 step 2 (ADR 0006, section 13, D-1077, D-1086 to D-1088): the notifications' table and its erasure. Additive
-- (D-22). The image before it (V19) runs on it and writes normally (D-1040): it never reads or writes the new table,
-- no trigger is added to any table it writes, and the two replaced functions keep their signatures and what the old
-- callers rely on; they only also delete notifications.

-- A notification is a reference, never text or content (D-1077, D-1087): what kind of thing happened, who caused it
-- (null for the system), the range of the ledger's revisions it covers (D-1017, D-1034) and how many changes, for
-- display only. Its text is built when it is read. The recipient is an ACTIVE member of the family ledger.
CREATE TABLE notification (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ledger_id           BIGINT      NOT NULL REFERENCES ledger (id) ON DELETE CASCADE,
    recipient_member_id BIGINT      NOT NULL REFERENCES ledger_member (id) ON DELETE CASCADE,
    kind                VARCHAR(32) NOT NULL
        CHECK (kind IN ('FAMILY_CHANGES', 'FAMILY_IMPORTED', 'MEMBER_JOINED', 'MEMBER_LEFT', 'SEAT_CLAIMED')),
    author_member_id    BIGINT REFERENCES ledger_member (id) ON DELETE CASCADE,
    from_revision       BIGINT CHECK (from_revision >= 0),
    to_revision         BIGINT,
    changes_count       INTEGER CHECK (changes_count >= 0),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    read_at             TIMESTAMPTZ,
    CHECK (to_revision >= from_revision)
);

-- The unread count is one statement over this index (D-1088).
CREATE INDEX notification_unread_idx ON notification (recipient_member_id) WHERE read_at IS NULL;
CREATE INDEX notification_recipient_idx ON notification (recipient_member_id, created_at);
CREATE INDEX notification_ledger_idx ON notification (ledger_id);
CREATE INDEX notification_author_idx ON notification (author_member_id) WHERE author_member_id IS NOT NULL;

-- delete_family_ledger as V19's, plus the ledger's notifications (they go with the ledger's row by the cascade; the
-- explicit delete keeps the order of the others). Same signature and behaviour for every caller.
CREATE OR REPLACE FUNCTION delete_family_ledger(family BIGINT) RETURNS VOID
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
    DELETE FROM record_version WHERE ledger_id = family;
    DELETE FROM activity_event WHERE ledger_id = family;
    DELETE FROM notification WHERE ledger_id = family;
    DELETE FROM category WHERE ledger_id = family;
    DELETE FROM ledger WHERE id = family;
    PERFORM set_config('app.writer', writer, true);
END
$$;

-- release_family_memberships as V19's, plus: the user's notifications go, those they received by their member rows
-- (by the sub, before the rows lose it), as their activity events do. A notification another member received about
-- the user's change stays, its author read as "Former member".
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
    DELETE FROM activity_event WHERE actor_sub = sub;
    DELETE FROM notification WHERE recipient_member_id IN (SELECT id FROM ledger_member WHERE user_sub = sub);
    FOR family IN
        SELECT ledger_id FROM ledger_member WHERE user_sub = sub AND ledger_type = 'SHARED' ORDER BY ledger_id
    LOOP
        PERFORM FROM ledger WHERE id = family FOR UPDATE;
        -- Read after the lock, so that another member's release that committed meanwhile is seen.
        SELECT id, share_bp INTO membership, share FROM ledger_member WHERE ledger_id = family AND user_sub = sub;
        CONTINUE WHEN membership IS NULL;
        -- A notification that arrived since the first delete.
        DELETE FROM notification WHERE recipient_member_id = membership;

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

        -- The same for the versions (D-1018): a version's comment is the leaver's when the version where that value
        -- first appeared (the start of its run of equal values) was made by them. A run that starts at a BASELINE
        -- version (the record's state when versions began) belongs to whoever the journal says changed the comment last
        -- at that time.
        WITH s AS (
            SELECT v.id, v.record_id, v.seq, v.actor_member, v.cause, v.at, v.content -> 'comment' AS comment,
                   CASE WHEN row_number() OVER w = 1
                             OR (v.content -> 'comment') IS DISTINCT FROM lag(v.content -> 'comment') OVER w
                        THEN 1 ELSE 0 END AS starts
            FROM record_version v
            WHERE v.ledger_id = family AND v.record_type = 'FAMILY_RECORD'
            WINDOW w AS (PARTITION BY v.record_id ORDER BY v.seq)
        ), g AS (
            SELECT s.*, sum(s.starts) OVER (PARTITION BY s.record_id ORDER BY s.seq) AS run FROM s
        ), o AS (
            SELECT g.id, g.record_id, g.comment,
                   first_value(g.actor_member) OVER w AS first_actor,
                   first_value(g.cause) OVER w AS first_cause,
                   first_value(g."at") OVER w AS first_at
            FROM g WINDOW w AS (PARTITION BY g.record_id, g.run ORDER BY g.seq)
        )
        UPDATE record_version v SET content = v.content - 'comment'
            FROM o
            WHERE o.id = v.id AND o.comment IS NOT NULL
              AND CASE WHEN o.first_cause = 'BASELINE'
                       THEN (SELECT c.changed_by_member_id FROM family_record_change c
                             WHERE c.record_id = o.record_id AND c.changes @> '[{"field": "comment"}]'
                               AND c.changed_at <= o.first_at
                             ORDER BY c.id DESC LIMIT 1)
                       ELSE o.first_actor END = membership;

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
