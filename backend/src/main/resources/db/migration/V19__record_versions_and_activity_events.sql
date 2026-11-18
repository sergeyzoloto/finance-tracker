-- HIST-1 (ADR 0006, D-989 to D-1042): record versions, the activity log, the ledger's revision, the backstop and the
-- erasure of history. Additive (D-22), and written so that the image before it (d44df66) runs on it and writes
-- normally (D-1040): nothing here refuses a write of a connection that doesn't say it is version-aware, and the two
-- replaced functions keep their signatures and what the old callers rely on.

-- The ledger's change-set counter (D-1003): one revision per transaction that writes a version of the ledger's.
ALTER TABLE ledger ADD COLUMN revision BIGINT NOT NULL DEFAULT 0;

-- One immutable version per change of a record (D-990, D-1000). The record's id has no foreign key: the live row
-- may be gone (D-1002). `content` is the canonical aggregate (D-1001), ids and never names, so a FORMER member's
-- anonymisation and a rename need no rewrite.
CREATE TABLE record_version (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ledger_id     BIGINT      NOT NULL REFERENCES ledger (id) ON DELETE CASCADE,
    record_type   VARCHAR(16) NOT NULL
        CHECK (record_type IN ('ENTRY', 'ACCOUNT', 'CATEGORY', 'COUNTERPARTY', 'FAMILY_RECORD', 'FAMILY_CATEGORY')),
    record_id     BIGINT      NOT NULL,
    seq           INTEGER     NOT NULL CHECK (seq >= 1),
    revision      BIGINT      NOT NULL CHECK (revision >= 0),
    op            VARCHAR(10) NOT NULL CHECK (op IN ('BASELINE', 'CREATE', 'UPDATE', 'DELETE', 'ARCHIVE', 'RESTORE')),
    content       JSONB       NOT NULL CHECK (jsonb_typeof(content) = 'object'),
    content_hash  BYTEA       NOT NULL CHECK (length(content_hash) = 32),
    content_ver   SMALLINT    NOT NULL,
    actor_kind    VARCHAR(8)  NOT NULL CHECK (actor_kind IN ('USER', 'SYSTEM', 'IMPORT')),
    actor_member  BIGINT,
    at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    cause         VARCHAR(12) NOT NULL
        CHECK (cause IN ('EDIT', 'IMPORT', 'DEMO', 'PROVISION', 'LEAVE', 'RETURN', 'MERGE', 'RESTORE', 'BASELINE',
                         'RECONCILE')),
    cause_batch   BIGINT,
    restored_from BIGINT,
    tx_id         BIGINT      NOT NULL DEFAULT txid_current(),
    UNIQUE (ledger_id, record_type, record_id, seq)
);

CREATE INDEX record_version_revision_idx ON record_version (ledger_id, revision);

-- The activity log of what isn't a record (D-999): sign-in, sign-out, expiry, settings, memberships and so on, by
-- the Keycloak sub, with no email, no address and no browser (D-1007, D-1033). `ledger_id` is null for what belongs
-- to no ledger (a sign-in). Immutable; deleted with the user's data, with the ledger, and after 13 months for
-- sign-in events (D-1042).
CREATE TABLE activity_event (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_sub    TEXT         NOT NULL CHECK (actor_sub <> ''),
    ledger_id    BIGINT REFERENCES ledger (id) ON DELETE CASCADE,
    at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    kind         VARCHAR(32)  NOT NULL,
    subject_type VARCHAR(16),
    subject_id   BIGINT,
    revision     BIGINT,
    details      JSONB CHECK (details IS NULL OR jsonb_typeof(details) = 'object')
);

CREATE INDEX activity_event_sub_idx ON activity_event (actor_sub, at);
CREATE INDEX activity_event_ledger_idx ON activity_event (ledger_id, at) WHERE ledger_id IS NOT NULL;
CREATE INDEX activity_event_at_idx ON activity_event (at) WHERE ledger_id IS NULL;

-- Versions and events are immutable (D-1000): no update, and a delete only with a user's data (app.writer
-- 'delete-all', set by release_family_memberships, delete_family_ledger and UserDataService), by the daily job that
-- deletes old sign-in events ('history-purge'), or because their ledger is gone (the cascade).
CREATE FUNCTION history_immutable() RETURNS trigger
    LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE
    writer TEXT := family_writer();
BEGIN
    IF writer = 'delete-all' THEN
        RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    END IF;
    IF TG_OP = 'DELETE' THEN
        IF writer = 'history-purge' AND TG_TABLE_NAME = 'activity_event' THEN
            RETURN OLD;
        END IF;
        IF OLD.ledger_id IS NOT NULL AND NOT EXISTS (SELECT FROM ledger WHERE id = OLD.ledger_id) THEN
            RETURN OLD;
        END IF;
    END IF;
    RAISE EXCEPTION '%: a row of % is immutable', TG_TABLE_NAME, TG_TABLE_NAME USING ERRCODE = 'check_violation';
END
$$;

CREATE TRIGGER record_version_immutable
    BEFORE UPDATE OR DELETE ON record_version
    FOR EACH ROW EXECUTE FUNCTION history_immutable();
CREATE TRIGGER activity_event_immutable
    BEFORE UPDATE OR DELETE ON activity_event
    FOR EACH ROW EXECUTE FUNCTION history_immutable();

-- The backstop (D-1004, D-1040). A version-aware connection (the application sets app.history to 'on' on every
-- connection it takes from its pool; the previous image, psql and the runbook's SQL never do) that changes a live
-- record must write that record's version in the same transaction, or its commit fails. The row triggers below run
-- at the write, where the writer is known (family_writer(): the posting service's derived rows and a user's deletion
-- are exempt), and mark the record in history_pending; a deferred trigger on that table checks at commit and removes
-- the mark. The table is empty at rest. Derived rows are no records: the entries of a family kind, the debt accounts
-- and the placeholder account (ADR 0006, 2.1). A posting alone is not marked: every save of an entry changes its
-- row's version too.
CREATE UNLOGGED TABLE history_pending (
    tx_id       BIGINT      NOT NULL DEFAULT txid_current(),
    ledger_id   BIGINT      NOT NULL,
    record_type VARCHAR(16) NOT NULL,
    record_id   BIGINT      NOT NULL,
    PRIMARY KEY (tx_id, record_type, record_id)
);

CREATE FUNCTION history_mark() RETURNS trigger
    LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE
    r     RECORD;
    rtype TEXT;
BEGIN
    IF coalesce(current_setting('app.history', true), '') <> 'on'
       OR family_writer() IN ('family-posting', 'delete-all') THEN
        RETURN NULL;
    END IF;
    r := CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    IF TG_TABLE_NAME = 'journal_entry' THEN
        IF r.kind LIKE 'FAMILY\_%' THEN RETURN NULL; END IF;
        IF TG_OP = 'UPDATE' AND NOT (OLD.version IS DISTINCT FROM NEW.version
                                     OR OLD.entry_date IS DISTINCT FROM NEW.entry_date
                                     OR OLD.kind IS DISTINCT FROM NEW.kind
                                     OR OLD.payee_id IS DISTINCT FROM NEW.payee_id
                                     OR OLD.memo IS DISTINCT FROM NEW.memo
                                     OR OLD.external_ref IS DISTINCT FROM NEW.external_ref) THEN
            RETURN NULL;
        END IF;
        rtype := 'ENTRY';
    ELSIF TG_TABLE_NAME = 'account' THEN
        IF r.family_ledger_id IS NOT NULL OR r.code = 'UNSPECIFIED_PAYMENTS' THEN RETURN NULL; END IF;
        IF TG_OP = 'UPDATE' AND NOT (OLD.code IS DISTINCT FROM NEW.code OR OLD.name IS DISTINCT FROM NEW.name
                                     OR OLD.type IS DISTINCT FROM NEW.type
                                     OR OLD.default_currency IS DISTINCT FROM NEW.default_currency
                                     OR OLD.requires_counterparty IS DISTINCT FROM NEW.requires_counterparty
                                     OR OLD.is_system IS DISTINCT FROM NEW.is_system
                                     OR (OLD.archived_at IS NULL) IS DISTINCT FROM (NEW.archived_at IS NULL)) THEN
            RETURN NULL;
        END IF;
        rtype := 'ACCOUNT';
    ELSIF TG_TABLE_NAME = 'category' THEN
        IF TG_OP = 'UPDATE' AND NOT (OLD.code IS DISTINCT FROM NEW.code OR OLD.name IS DISTINCT FROM NEW.name
                                     OR OLD.type IS DISTINCT FROM NEW.type
                                     OR (OLD.archived_at IS NULL) IS DISTINCT FROM (NEW.archived_at IS NULL)) THEN
            RETURN NULL;
        END IF;
        rtype := CASE WHEN r.user_id IS NULL THEN 'FAMILY_CATEGORY' ELSE 'CATEGORY' END;
    ELSIF TG_TABLE_NAME = 'counterparty' THEN
        IF TG_OP = 'UPDATE' AND NOT (OLD.name IS DISTINCT FROM NEW.name OR OLD.kind IS DISTINCT FROM NEW.kind
                                     OR (OLD.archived_at IS NULL) IS DISTINCT FROM (NEW.archived_at IS NULL)) THEN
            RETURN NULL;
        END IF;
        rtype := 'COUNTERPARTY';
    ELSE
        IF TG_OP = 'UPDATE' AND NOT (OLD.version IS DISTINCT FROM NEW.version
                                     OR (OLD.deleted_at IS NULL) IS DISTINCT FROM (NEW.deleted_at IS NULL)
                                     OR OLD.comment IS DISTINCT FROM NEW.comment) THEN
            RETURN NULL;
        END IF;
        rtype := 'FAMILY_RECORD';
    END IF;
    INSERT INTO history_pending (ledger_id, record_type, record_id) VALUES (r.ledger_id, rtype, r.id)
        ON CONFLICT DO NOTHING;
    RETURN NULL;
END
$$;

CREATE TRIGGER journal_entry_history AFTER INSERT OR UPDATE OR DELETE ON journal_entry
    FOR EACH ROW EXECUTE FUNCTION history_mark();
CREATE TRIGGER account_history AFTER INSERT OR UPDATE OR DELETE ON account
    FOR EACH ROW EXECUTE FUNCTION history_mark();
CREATE TRIGGER category_history AFTER INSERT OR UPDATE OR DELETE ON category
    FOR EACH ROW EXECUTE FUNCTION history_mark();
CREATE TRIGGER counterparty_history AFTER INSERT OR UPDATE OR DELETE ON counterparty
    FOR EACH ROW EXECUTE FUNCTION history_mark();
CREATE TRIGGER family_record_history AFTER INSERT OR UPDATE OR DELETE ON family_record
    FOR EACH ROW EXECUTE FUNCTION history_mark();

CREATE FUNCTION history_check() RETURNS trigger
    LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
    DELETE FROM history_pending WHERE tx_id = NEW.tx_id AND record_type = NEW.record_type AND record_id = NEW.record_id;
    -- A ledger deleted meanwhile took its versions with it.
    IF EXISTS (SELECT FROM ledger WHERE id = NEW.ledger_id)
       AND NOT EXISTS (SELECT FROM record_version
                       WHERE ledger_id = NEW.ledger_id AND record_type = NEW.record_type
                         AND record_id = NEW.record_id AND tx_id = NEW.tx_id AND at = now()) THEN
        RAISE EXCEPTION '% % of ledger % was changed without a version (ADR 0006, D-1004)', NEW.record_type,
            NEW.record_id, NEW.ledger_id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER history_pending_check AFTER INSERT ON history_pending
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION history_check();

-- delete_family_ledger as V10's, plus the ledger's versions and events (they go with the ledger's row by the cascade;
-- the explicit delete keeps the order of the others). Same signature and behaviour for every caller.
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
    DELETE FROM category WHERE ledger_id = family;
    DELETE FROM ledger WHERE id = family;
    PERFORM set_config('app.writer', writer, true);
END
$$;

-- release_family_memberships as V10's, plus (D-1018): the leaver's comments are erased from every version that carries
-- a value they wrote, and the user's activity events go. A comment value belongs to the member of the version in which
-- it last changed (a window over the record's versions), as V9's erasure reads the journal; a version that carries it
-- loses the key, which the canonical form reads as no comment (D-1001). The key is `comment` of the content of a
-- FAMILY_RECORD version.
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
