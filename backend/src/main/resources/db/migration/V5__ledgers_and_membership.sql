-- Ledgers and their members (docs/adr/0003-family-budget-membership-and-cross-ledger-posting.md, topics A, B and J;
-- stage F2a). Until now a user's ledger was implicit: the rows keyed by their sub. From here every row of account,
-- category, counterparty, journal_entry and import_batch also names its ledger, and access will follow membership
-- (F2b). In F2a every ledger is PERSONAL, one per sub, and the application doesn't know the new column: the triggers
-- below fill it from user_id and check that the two agree, so the code before this migration runs on this schema
-- unchanged, which is what a rollback to the previous image relies on. user_id stays everywhere, and nothing is
-- dropped or renamed (D-22). This is the first migration that transforms rows that exist in production.

CREATE TABLE ledger (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    type          VARCHAR(8) NOT NULL CHECK (type IN ('PERSONAL', 'SHARED')),
    -- A shared ledger's own settings (F3). A personal ledger's base currency is user_settings.base_currency.
    name          VARCHAR(100),
    base_currency CHAR(3) CHECK (base_currency ~ '^[A-Z]{3}$'),
    archived_at   TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- The target of ledger_member's foreign key, which copies the type.
    UNIQUE (id, type),
    CHECK (type = 'PERSONAL' OR (name IS NOT NULL AND base_currency IS NOT NULL))
);

CREATE TABLE ledger_member (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ledger_id    BIGINT NOT NULL,
    -- The ledger's type, copied so that the partial unique indexes below can see it. The foreign key keeps it equal.
    ledger_type  VARCHAR(8) NOT NULL,
    -- NULL for a member without an account, and for a FORMER one (D-3). Only this table holds a sub of a member.
    user_sub     TEXT,
    display_name VARCHAR(100) NOT NULL,
    role         VARCHAR(6) NOT NULL CHECK (role IN ('OWNER', 'MEMBER')),
    status       VARCHAR(6) NOT NULL CHECK (status IN ('ACTIVE', 'LEFT', 'FORMER')),
    -- Family records dated on or after it are posted to this member (D-3, D-18).
    join_date    DATE NOT NULL,
    left_date    DATE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (ledger_id, ledger_type) REFERENCES ledger (id, type) ON DELETE CASCADE,
    -- A sub at most once per ledger (D-4). NULLs are seats and former members, of which a ledger may have several.
    UNIQUE (ledger_id, user_sub),
    -- The target of the composite foreign keys from family rows (F3 on).
    UNIQUE (ledger_id, id),
    CHECK ((status = 'ACTIVE') = (left_date IS NULL)),
    CHECK (status <> 'FORMER' OR user_sub IS NULL),
    CHECK (role = 'MEMBER' OR (user_sub IS NOT NULL AND status = 'ACTIVE')),
    -- A personal ledger's member is its user, who owns it.
    CONSTRAINT ledger_member_personal_owner_check
        CHECK (ledger_type = 'SHARED' OR (user_sub IS NOT NULL AND role = 'OWNER' AND status = 'ACTIVE'))
);

-- Each user has one personal ledger, and a personal ledger has one member (D-4).
CREATE UNIQUE INDEX ledger_member_personal_sub_key ON ledger_member (user_sub) WHERE ledger_type = 'PERSONAL';
CREATE UNIQUE INDEX ledger_member_personal_ledger_key ON ledger_member (ledger_id) WHERE ledger_type = 'PERSONAL';
-- A user's memberships, for the access check of every request (F2b).
CREATE INDEX ON ledger_member (user_sub);

-- A ledger's type never changes (D-22): a personal ledger never becomes shared, nor the other way round.
CREATE FUNCTION forbid_ledger_type_change() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'ledger %: type cannot change', OLD.id USING ERRCODE = 'check_violation';
END
$$;

CREATE TRIGGER ledger_type_immutable
    BEFORE UPDATE OF type ON ledger
    FOR EACH ROW WHEN (OLD.type <> NEW.type) EXECUTE FUNCTION forbid_ledger_type_change();

-- At commit, a personal ledger has its member: the unique index above allows at most one, this at least one.
CREATE FUNCTION ledger_check_personal_member() RETURNS trigger
    LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE
    checked BIGINT;
BEGIN
    IF TG_TABLE_NAME = 'ledger' THEN
        checked := NEW.id;
    ELSE
        checked := OLD.ledger_id;
    END IF;
    IF EXISTS (SELECT FROM ledger WHERE id = checked AND type = 'PERSONAL')
            AND NOT EXISTS (SELECT FROM ledger_member WHERE ledger_id = checked) THEN
        RAISE EXCEPTION 'personal ledger % has no member', checked USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER ledger_personal_has_member
    AFTER INSERT ON ledger
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_check_personal_member();
CREATE CONSTRAINT TRIGGER ledger_member_personal_kept
    AFTER DELETE ON ledger_member
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_check_personal_member();

-- How a membership may change (D-3, D-18, D-20, D-26). A member stays in their ledger. A seat gets a sub when it is
-- claimed, and a sub becomes NULL when its user deletes their data (FORMER, which is final), but a membership never
-- passes from one sub to another. The join date is fixed once a user has joined, except when a LEFT member returns.
CREATE FUNCTION ledger_member_check_update() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.ledger_id <> OLD.ledger_id OR NEW.ledger_type <> OLD.ledger_type THEN
        RAISE EXCEPTION 'ledger member %: the ledger cannot change', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.user_sub <> OLD.user_sub THEN
        RAISE EXCEPTION 'ledger member %: user_sub cannot pass to another user', OLD.id
            USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.status = 'FORMER' AND (NEW.status <> 'FORMER' OR NEW.join_date <> OLD.join_date) THEN
        RAISE EXCEPTION 'ledger member %: a former member stays as they are', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.join_date <> OLD.join_date AND OLD.user_sub IS NOT NULL
            AND NOT (OLD.status = 'LEFT' AND NEW.status = 'ACTIVE') THEN
        RAISE EXCEPTION 'ledger member %: join_date cannot change once the member has joined', OLD.id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER ledger_member_check_update
    BEFORE UPDATE ON ledger_member
    FOR EACH ROW EXECUTE FUNCTION ledger_member_check_update();

-- The sub's personal ledger, created with its one OWNER member if the sub has none yet: by provisioning, by the
-- backfill below, and by the fill trigger for code that doesn't know ledger_id. Safe under concurrent calls without a
-- lock: the member's insert does nothing if another transaction's member for the sub won the unique index, and then
-- this call removes its own new ledger and returns the other. The name comes from users, if the sub has a row there.
CREATE FUNCTION personal_ledger_id(sub TEXT) RETURNS BIGINT
    LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE
    found_id BIGINT;
    new_id   BIGINT;
BEGIN
    SELECT ledger_id INTO found_id FROM ledger_member WHERE user_sub = sub AND ledger_type = 'PERSONAL';
    IF found_id IS NOT NULL THEN
        RETURN found_id;
    END IF;
    IF sub IS NULL THEN
        RAISE EXCEPTION 'a personal ledger needs a user' USING ERRCODE = 'not_null_violation';
    END IF;

    INSERT INTO ledger (type) VALUES ('PERSONAL') RETURNING id INTO new_id;
    INSERT INTO ledger_member (ledger_id, ledger_type, user_sub, display_name, role, status, join_date)
        VALUES (new_id, 'PERSONAL', sub,
                coalesce((SELECT left(display_name, 100) FROM users WHERE keycloak_id = sub), ''),
                'OWNER', 'ACTIVE', current_date)
        ON CONFLICT (user_sub) WHERE ledger_type = 'PERSONAL' DO NOTHING
        RETURNING ledger_id INTO found_id;
    IF found_id IS NULL THEN
        DELETE FROM ledger WHERE id = new_id;
        SELECT ledger_id INTO STRICT found_id FROM ledger_member WHERE user_sub = sub AND ledger_type = 'PERSONAL';
    END IF;
    RETURN found_id;
END
$$;

-- The backfill: a personal ledger for every sub that any owned table knows, whether or not it has a users or a
-- user_settings row. The ECB's rates have no sub.
DO $$
BEGIN
    PERFORM personal_ledger_id(sub)
    FROM (SELECT keycloak_id FROM users
          UNION SELECT user_id FROM user_settings
          UNION SELECT user_id FROM account
          UNION SELECT user_id FROM category
          UNION SELECT user_id FROM counterparty
          UNION SELECT user_id FROM journal_entry
          UNION SELECT user_id FROM import_batch
          UNION SELECT user_id FROM exchange_rate WHERE user_id IS NOT NULL) AS subs (sub)
    ORDER BY sub;
END
$$;

-- Every ledger-scoped row names its ledger: its user's personal ledger. posting belongs to its entry and gets no
-- column; user_settings and exchange_rate stay per user (ADR 0003, topic A).
ALTER TABLE account ADD COLUMN ledger_id BIGINT REFERENCES ledger;
ALTER TABLE category ADD COLUMN ledger_id BIGINT REFERENCES ledger;
ALTER TABLE counterparty ADD COLUMN ledger_id BIGINT REFERENCES ledger;
ALTER TABLE journal_entry ADD COLUMN ledger_id BIGINT REFERENCES ledger;
ALTER TABLE import_batch ADD COLUMN ledger_id BIGINT REFERENCES ledger;

UPDATE account t SET ledger_id = m.ledger_id FROM ledger_member m
    WHERE m.user_sub = t.user_id AND m.ledger_type = 'PERSONAL';
UPDATE category t SET ledger_id = m.ledger_id FROM ledger_member m
    WHERE m.user_sub = t.user_id AND m.ledger_type = 'PERSONAL';
UPDATE counterparty t SET ledger_id = m.ledger_id FROM ledger_member m
    WHERE m.user_sub = t.user_id AND m.ledger_type = 'PERSONAL';
UPDATE journal_entry t SET ledger_id = m.ledger_id FROM ledger_member m
    WHERE m.user_sub = t.user_id AND m.ledger_type = 'PERSONAL';
UPDATE import_batch t SET ledger_id = m.ledger_id FROM ledger_member m
    WHERE m.user_sub = t.user_id AND m.ledger_type = 'PERSONAL';

ALTER TABLE account ALTER COLUMN ledger_id SET NOT NULL;
ALTER TABLE category ALTER COLUMN ledger_id SET NOT NULL;
ALTER TABLE counterparty ALTER COLUMN ledger_id SET NOT NULL;
ALTER TABLE journal_entry ALTER COLUMN ledger_id SET NOT NULL;
ALTER TABLE import_batch ALTER COLUMN ledger_id SET NOT NULL;

-- The keys of V2 per ledger, beside the ones per user, which stay until the cleanup after F7. While a ledger is its
-- user's, each pair agrees, so an INSERT … ON CONFLICT on the per-user key never meets the per-ledger one alone.
ALTER TABLE account ADD UNIQUE (ledger_id, code), ADD UNIQUE (ledger_id, id);
ALTER TABLE category ADD UNIQUE (ledger_id, code), ADD UNIQUE (ledger_id, id);
ALTER TABLE counterparty ADD UNIQUE (ledger_id, id);
CREATE UNIQUE INDEX ON counterparty (ledger_id, lower(name));
ALTER TABLE import_batch ADD UNIQUE (ledger_id, id);
CREATE UNIQUE INDEX ON journal_entry (ledger_id, external_ref) WHERE external_ref IS NOT NULL;
CREATE INDEX ON journal_entry (ledger_id, entry_date DESC);
-- An entry's payee and import batch are of its ledger. These, and ledger_member's to ledger, are the only composite
-- foreign keys. None covers a posting, which has no ledger_id: posting_check_references checks its account, category
-- and counterparty, so that F4's family categories on personal postings stay a rule of the trigger.
ALTER TABLE journal_entry
    ADD FOREIGN KEY (ledger_id, payee_id) REFERENCES counterparty (ledger_id, id) ON DELETE RESTRICT,
    ADD FOREIGN KEY (ledger_id, import_batch_id) REFERENCES import_batch (ledger_id, id) ON DELETE RESTRICT;

-- For code that inserts without ledger_id, the row goes to its user's personal ledger. A row with a ledger_id must be
-- in the personal ledger of its user_id: in F2a, every row is.
CREATE FUNCTION fill_and_check_ledger_id() RETURNS trigger
    LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
    IF TG_OP = 'INSERT' AND NEW.ledger_id IS NULL THEN
        NEW.ledger_id := personal_ledger_id(NEW.user_id);
    ELSIF NOT EXISTS (SELECT FROM ledger_member
                      WHERE ledger_id = NEW.ledger_id AND user_sub = NEW.user_id AND ledger_type = 'PERSONAL') THEN
        RAISE EXCEPTION '% %: ledger % is not the personal ledger of its user', TG_TABLE_NAME, NEW.id, NEW.ledger_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;
    RETURN NEW;
END
$$;

-- Named to run after the *_user_id_immutable triggers of V2, whose messages stay as they were.
CREATE TRIGGER account_user_ledger_fill
    BEFORE INSERT OR UPDATE OF user_id, ledger_id ON account
    FOR EACH ROW EXECUTE FUNCTION fill_and_check_ledger_id();
CREATE TRIGGER category_user_ledger_fill
    BEFORE INSERT OR UPDATE OF user_id, ledger_id ON category
    FOR EACH ROW EXECUTE FUNCTION fill_and_check_ledger_id();
CREATE TRIGGER counterparty_user_ledger_fill
    BEFORE INSERT OR UPDATE OF user_id, ledger_id ON counterparty
    FOR EACH ROW EXECUTE FUNCTION fill_and_check_ledger_id();
CREATE TRIGGER journal_entry_user_ledger_fill
    BEFORE INSERT OR UPDATE OF user_id, ledger_id ON journal_entry
    FOR EACH ROW EXECUTE FUNCTION fill_and_check_ledger_id();
CREATE TRIGGER import_batch_user_ledger_fill
    BEFORE INSERT OR UPDATE OF user_id, ledger_id ON import_batch
    FOR EACH ROW EXECUTE FUNCTION fill_and_check_ledger_id();

-- Rows never move to another ledger: posting_check_references compared ledger_id values as they were when a posting
-- was written.
CREATE FUNCTION forbid_ledger_id_change() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% %: ledger_id cannot change', TG_TABLE_NAME, OLD.id USING ERRCODE = 'check_violation';
END
$$;

CREATE TRIGGER account_ledger_id_immutable
    BEFORE UPDATE OF ledger_id ON account
    FOR EACH ROW WHEN (OLD.ledger_id IS DISTINCT FROM NEW.ledger_id) EXECUTE FUNCTION forbid_ledger_id_change();
CREATE TRIGGER category_ledger_id_immutable
    BEFORE UPDATE OF ledger_id ON category
    FOR EACH ROW WHEN (OLD.ledger_id IS DISTINCT FROM NEW.ledger_id) EXECUTE FUNCTION forbid_ledger_id_change();
CREATE TRIGGER counterparty_ledger_id_immutable
    BEFORE UPDATE OF ledger_id ON counterparty
    FOR EACH ROW WHEN (OLD.ledger_id IS DISTINCT FROM NEW.ledger_id) EXECUTE FUNCTION forbid_ledger_id_change();
CREATE TRIGGER journal_entry_ledger_id_immutable
    BEFORE UPDATE OF ledger_id ON journal_entry
    FOR EACH ROW WHEN (OLD.ledger_id IS DISTINCT FROM NEW.ledger_id) EXECUTE FUNCTION forbid_ledger_id_change();
CREATE TRIGGER import_batch_ledger_id_immutable
    BEFORE UPDATE OF ledger_id ON import_batch
    FOR EACH ROW WHEN (OLD.ledger_id IS DISTINCT FROM NEW.ledger_id) EXECUTE FUNCTION forbid_ledger_id_change();

-- Deleting a users row deletes the sub's personal ledger and its member, so that "Delete all my data"
-- (UserDataService.deleteAll) and the runbook's "Delete a user", which delete the users row last, leave nothing of the
-- sub behind without a change. Rows still in the ledger make it fail, by the foreign keys above.
CREATE FUNCTION users_delete_personal_ledger() RETURNS trigger
    LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
    DELETE FROM ledger WHERE id IN
        (SELECT ledger_id FROM ledger_member WHERE user_sub = OLD.keycloak_id AND ledger_type = 'PERSONAL');
    RETURN NULL;
END
$$;

CREATE TRIGGER users_delete_personal_ledger
    AFTER DELETE ON users
    FOR EACH ROW EXECUTE FUNCTION users_delete_personal_ledger();

-- V2's check of a posting's references, which now also compares ledgers, after the users, so that its messages about
-- users stay as they were. While each ledger is its user's, the users' check fails first.
CREATE OR REPLACE FUNCTION posting_check_references() RETURNS trigger
    LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE
    entry_user_id      TEXT;
    entry_ledger_id    BIGINT;
    account_user_id    TEXT;
    account_ledger_id  BIGINT;
    account_code       TEXT;
    account_type       TEXT;
    needs_counterparty BOOLEAN;
    owner_user_id      TEXT;
    owner_ledger_id    BIGINT;
BEGIN
    -- A row that doesn't exist reads as NULL, passes these comparisons and is left to the foreign keys.
    SELECT user_id, ledger_id INTO entry_user_id, entry_ledger_id FROM journal_entry WHERE id = NEW.entry_id;
    -- FOR SHARE: a concurrent change of the account's type or requires_counterparty waits until this transaction
    -- ends, and then account_check_postings sees this posting.
    SELECT user_id, ledger_id, code, type, requires_counterparty
        INTO account_user_id, account_ledger_id, account_code, account_type, needs_counterparty
        FROM account WHERE id = NEW.account_id FOR SHARE;

    IF account_user_id <> entry_user_id THEN
        RAISE EXCEPTION 'posting in journal entry %: account % belongs to another user', NEW.entry_id, NEW.account_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;
    IF account_ledger_id <> entry_ledger_id THEN
        RAISE EXCEPTION 'posting in journal entry %: account % belongs to another ledger', NEW.entry_id,
            NEW.account_id USING ERRCODE = 'foreign_key_violation';
    END IF;
    IF NEW.category_id IS NOT NULL THEN
        SELECT user_id, ledger_id INTO owner_user_id, owner_ledger_id FROM category WHERE id = NEW.category_id;
        IF owner_user_id <> entry_user_id THEN
            RAISE EXCEPTION 'posting in journal entry %: category % belongs to another user',
                NEW.entry_id, NEW.category_id USING ERRCODE = 'foreign_key_violation';
        END IF;
        IF owner_ledger_id <> entry_ledger_id THEN
            RAISE EXCEPTION 'posting in journal entry %: category % belongs to another ledger',
                NEW.entry_id, NEW.category_id USING ERRCODE = 'foreign_key_violation';
        END IF;
        IF account_type <> 'EQUITY' THEN
            RAISE EXCEPTION 'posting in journal entry %: account % is %, and only postings to EQUITY accounts can have a category',
                NEW.entry_id, account_code, account_type USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    IF NEW.counterparty_id IS NOT NULL THEN
        SELECT user_id, ledger_id INTO owner_user_id, owner_ledger_id FROM counterparty WHERE id = NEW.counterparty_id;
        IF owner_user_id <> entry_user_id THEN
            RAISE EXCEPTION 'posting in journal entry %: counterparty % belongs to another user',
                NEW.entry_id, NEW.counterparty_id USING ERRCODE = 'foreign_key_violation';
        END IF;
        IF owner_ledger_id <> entry_ledger_id THEN
            RAISE EXCEPTION 'posting in journal entry %: counterparty % belongs to another ledger',
                NEW.entry_id, NEW.counterparty_id USING ERRCODE = 'foreign_key_violation';
        END IF;
    ELSIF needs_counterparty THEN
        RAISE EXCEPTION 'posting in journal entry %: account % requires a counterparty', NEW.entry_id, account_code
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;
