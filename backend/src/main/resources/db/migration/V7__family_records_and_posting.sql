-- Family records and posting (docs/adr/0003-family-budget-membership-and-cross-ledger-posting.md, topics C, D, E, F, H
-- and J; stage F4a): a family ledger's start date (D-27), its records and their shares, the links from the members'
-- personal entries to them, the change journal, and the members' "Debt to family budget" accounts. A share is posted
-- into each member's personal ledger by the posting service (ledger.family.posting), the only code that may write
-- into another user's ledger (D-8); the triggers below are its backstop.
--
-- Additive (D-22): nothing is dropped or renamed, and every new column is nullable or has a default. The code before
-- this migration runs on it unchanged: it writes no family record, and a family ledger it creates gets its start date
-- from the trigger below. What it can no longer do is archive UNALLOCATED (D-8). No personal ledger gets a row here:
-- the debt accounts and "Payments without a specified account" are created when first needed, never by this
-- migration.

-- The start date (D-27): chosen at creation, today by default, and the creator's join date. A family ledger created
-- before F4a starts on its creation date. It never changes, and a personal ledger has none.
ALTER TABLE ledger ADD COLUMN start_date DATE;
UPDATE ledger SET start_date = created_at::date WHERE type = 'SHARED';
ALTER TABLE ledger ADD CONSTRAINT ledger_start_date_check CHECK ((type = 'SHARED') = (start_date IS NOT NULL));

-- For code that creates a family ledger without a start date (the image before this migration): today, which is its
-- creator's join date there. The start date never changes afterwards.
CREATE FUNCTION ledger_fill_start_date() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.type = 'SHARED' AND NEW.start_date IS NULL THEN
            NEW.start_date := current_date;
        END IF;
    ELSIF NEW.start_date IS DISTINCT FROM OLD.start_date THEN
        RAISE EXCEPTION 'ledger %: the start date cannot change', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER ledger_fill_start_date
    BEFORE INSERT OR UPDATE OF start_date ON ledger
    FOR EACH ROW EXECUTE FUNCTION ledger_fill_start_date();

-- A family record (topic D): an expense, an income or a settlement of a family ledger, the source of truth (D-6).
-- Amounts are in the family's base currency, rounded to its minor unit by the service; the original amount and
-- currency are the payment's (D-13), the same as the base ones until F4c brings other currencies. A deleted record
-- keeps its row for the journal, and its posted entries are removed.
CREATE TABLE family_record (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ledger_id            BIGINT NOT NULL,
    ledger_type          VARCHAR(8) NOT NULL DEFAULT 'SHARED' CHECK (ledger_type = 'SHARED'),
    type                 VARCHAR(10) NOT NULL CHECK (type IN ('EXPENSE', 'INCOME', 'SETTLEMENT')),
    record_date          DATE NOT NULL,
    -- A category of this family ledger, of the record's type; none for a settlement.
    category_id          BIGINT,
    -- Who paid an expense, received an income, or pays in a settlement.
    payer_member_id      BIGINT NOT NULL,
    -- Who is paid in a settlement.
    payee_member_id      BIGINT,
    original_amount      NUMERIC(19, 4) NOT NULL CHECK (original_amount > 0),
    original_currency    CHAR(3) NOT NULL CHECK (original_currency ~ '^[A-Z]{3}$'),
    base_amount          NUMERIC(19, 4) NOT NULL CHECK (base_amount > 0),
    -- How the shares were split (D-12); none for a settlement.
    split_method         VARCHAR(10) CHECK (split_method IN ('EQUAL', 'PERCENT', 'AMOUNT', 'ONE_MEMBER')),
    comment              VARCHAR(500),
    author_member_id     BIGINT NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by_member_id BIGINT NOT NULL,
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at           TIMESTAMPTZ,
    deleted_by_member_id BIGINT,
    version              INTEGER NOT NULL DEFAULT 0,
    UNIQUE (ledger_id, id),
    FOREIGN KEY (ledger_id, ledger_type) REFERENCES ledger (id, type),
    FOREIGN KEY (ledger_id, category_id) REFERENCES category (ledger_id, id),
    FOREIGN KEY (ledger_id, payer_member_id) REFERENCES ledger_member (ledger_id, id),
    FOREIGN KEY (ledger_id, payee_member_id) REFERENCES ledger_member (ledger_id, id),
    FOREIGN KEY (ledger_id, author_member_id) REFERENCES ledger_member (ledger_id, id),
    FOREIGN KEY (ledger_id, updated_by_member_id) REFERENCES ledger_member (ledger_id, id),
    FOREIGN KEY (ledger_id, deleted_by_member_id) REFERENCES ledger_member (ledger_id, id),
    CHECK ((type = 'SETTLEMENT') = (category_id IS NULL)),
    CHECK ((type = 'SETTLEMENT') = (payee_member_id IS NOT NULL)),
    CHECK ((type = 'SETTLEMENT') = (split_method IS NULL)),
    CHECK (payee_member_id IS DISTINCT FROM payer_member_id),
    CHECK ((deleted_at IS NULL) = (deleted_by_member_id IS NULL))
);

CREATE INDEX ON family_record (ledger_id, record_date DESC, id DESC);
CREATE INDEX ON family_record (category_id);

CREATE TRIGGER family_record_ledger_id_immutable
    BEFORE UPDATE OF ledger_id ON family_record
    FOR EACH ROW WHEN (OLD.ledger_id <> NEW.ledger_id) EXECUTE FUNCTION forbid_ledger_id_change();

-- A record is dated on or after its ledger's start date (D-27), and its category has the record's type.
CREATE FUNCTION family_record_check() RETURNS trigger
    LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE
    starts        DATE;
    category_type TEXT;
BEGIN
    SELECT start_date INTO starts FROM ledger WHERE id = NEW.ledger_id;
    IF NEW.record_date < starts THEN
        RAISE EXCEPTION 'family record %: dated %, before its family ledger % starts on %', NEW.id, NEW.record_date,
            NEW.ledger_id, starts USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.category_id IS NOT NULL THEN
        SELECT type INTO category_type FROM category WHERE id = NEW.category_id;
        IF category_type <> NEW.type THEN
            RAISE EXCEPTION 'family record %: category % is %, and a record of type % needs one of its type',
                NEW.id, NEW.category_id, category_type, NEW.type USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER family_record_check
    BEFORE INSERT OR UPDATE OF ledger_id, type, record_date, category_id ON family_record
    FOR EACH ROW EXECUTE FUNCTION family_record_check();

-- A member's share of a record, in the base currency, stored (D-6), with its percentage in basis points where one was
-- entered (D-12). Shares go to ACTIVE members only when they are written; a member who leaves keeps theirs.
CREATE TABLE family_share (
    ledger_id            BIGINT NOT NULL,
    record_id            BIGINT NOT NULL,
    member_id            BIGINT NOT NULL,
    amount               NUMERIC(19, 4) NOT NULL CHECK (amount >= 0),
    share_bp             INTEGER CHECK (share_bp BETWEEN 0 AND 10000),
    updated_by_member_id BIGINT NOT NULL,
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (record_id, member_id),
    FOREIGN KEY (ledger_id, record_id) REFERENCES family_record (ledger_id, id) ON DELETE CASCADE,
    FOREIGN KEY (ledger_id, member_id) REFERENCES ledger_member (ledger_id, id),
    FOREIGN KEY (ledger_id, updated_by_member_id) REFERENCES ledger_member (ledger_id, id)
);

CREATE INDEX ON family_share (ledger_id, member_id);

CREATE FUNCTION family_share_check_member() RETURNS trigger
    LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
    IF NOT EXISTS (SELECT FROM ledger_member WHERE id = NEW.member_id AND status = 'ACTIVE') THEN
        RAISE EXCEPTION 'family record %: member % is not an active member and gets no share', NEW.record_id,
            NEW.member_id USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER family_share_check_member
    BEFORE INSERT OR UPDATE ON family_share
    FOR EACH ROW EXECUTE FUNCTION family_share_check_member();

-- At commit, the shares of every expense and income that isn't deleted add up to its base amount exactly, and a
-- settlement has none. So the members' balances always sum to zero (D-1).
CREATE FUNCTION family_record_check_shares() RETURNS trigger
    LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE
    checked BIGINT;
    record  family_record%ROWTYPE;
    total   NUMERIC;
    shares  INTEGER;
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
    SELECT coalesce(sum(amount), 0), count(*) INTO total, shares FROM family_share WHERE record_id = checked;
    IF record.type = 'SETTLEMENT' THEN
        IF shares > 0 THEN
            RAISE EXCEPTION 'family record %: a settlement has no shares', checked USING ERRCODE = 'check_violation';
        END IF;
    ELSIF total <> record.base_amount THEN
        RAISE EXCEPTION 'family record %: the shares sum to %, not to the amount %', checked, total,
            record.base_amount USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER family_record_shares_add_up
    AFTER INSERT OR UPDATE ON family_record
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION family_record_check_shares();
CREATE CONSTRAINT TRIGGER family_share_shares_add_up
    AFTER INSERT OR UPDATE OR DELETE ON family_share
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION family_record_check_shares();

-- A family ledger's base currency can't change once it has a record (D-13): shares and balances are in it.
CREATE FUNCTION ledger_check_base_currency() RETURNS trigger
    LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
    IF EXISTS (SELECT FROM family_record WHERE ledger_id = NEW.id) THEN
        RAISE EXCEPTION 'family ledger %: the base currency cannot change once the ledger has a record', NEW.id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER ledger_check_base_currency
    BEFORE UPDATE OF base_currency ON ledger
    FOR EACH ROW WHEN (OLD.base_currency IS DISTINCT FROM NEW.base_currency)
    EXECUTE FUNCTION ledger_check_base_currency();

-- A member's "Debt to family budget" for a family ledger (topic E), in their personal ledger: a system LIABILITY
-- that names the family ledger, one per personal ledger and family ledger. When the family ledger goes (D-20), the
-- account stays as an ordinary liability, as after a detach (D-19).
ALTER TABLE account ADD COLUMN family_ledger_id BIGINT REFERENCES ledger ON DELETE SET NULL;
ALTER TABLE account ADD CONSTRAINT account_family_debt_check
    CHECK (family_ledger_id IS NULL OR (type = 'LIABILITY' AND is_system));
CREATE UNIQUE INDEX account_family_debt_key ON account (ledger_id, family_ledger_id)
    WHERE family_ledger_id IS NOT NULL;

-- The link from a member's personal entry to what it was posted for (D-9, topic E). The entry's reference is set to
-- NULL when the entry is deleted: by the posting service, by its owner once detached (D-19), or by "Delete all my
-- data" (D-20). So a link never blocks deleting a personal entry, and it stays as the family's history. F4a chose
-- this over a link without a foreign key, which would keep an id of nothing.
CREATE TABLE family_entry_link (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    entry_id         BIGINT UNIQUE REFERENCES journal_entry ON DELETE SET NULL,
    family_ledger_id BIGINT NOT NULL,
    member_id        BIGINT NOT NULL,
    -- None for an opening balance or a correction, which are about the member rather than a record.
    record_id        BIGINT,
    link_type        VARCHAR(15) NOT NULL
        CHECK (link_type IN ('SHARE', 'PAYMENT', 'SETTLEMENT', 'OPENING_BALANCE', 'CORRECTION')),
    -- Written by the posting service and changed only through the family record; a payment with the payer's own
    -- account is the payer's (topic E).
    system_owned     BOOLEAN NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- The member left (D-19) or deleted their data (D-20).
    detached_at      TIMESTAMPTZ,
    FOREIGN KEY (family_ledger_id, member_id) REFERENCES ledger_member (ledger_id, id),
    FOREIGN KEY (family_ledger_id, record_id) REFERENCES family_record (ledger_id, id),
    CHECK ((record_id IS NULL) = (link_type IN ('OPENING_BALANCE', 'CORRECTION')))
);

-- Re-posting is keyed by record, member and link type (topic E), over the links that are not detached.
CREATE UNIQUE INDEX family_entry_link_key ON family_entry_link (record_id, member_id, link_type)
    WHERE detached_at IS NULL;
CREATE UNIQUE INDEX family_entry_link_opening_key ON family_entry_link (family_ledger_id, member_id)
    WHERE link_type = 'OPENING_BALANCE' AND detached_at IS NULL;
CREATE INDEX ON family_entry_link (family_ledger_id, member_id);

-- The change journal (D-16, topic H): who changed which family fields of a record, when, with the old and the new
-- values as [{"field": ..., "old": ..., "new": ...}], members and categories by id. A system change, such as the split
-- rule's fall back to EQUAL when a member with a custom share became FORMER, belongs to the ledger: no record and no
-- author, and it names the member it is about.
CREATE TABLE family_record_change (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ledger_id            BIGINT NOT NULL REFERENCES ledger,
    record_id            BIGINT,
    changed_by_member_id BIGINT,
    about_member_id      BIGINT,
    changed_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    action               VARCHAR(16) NOT NULL CHECK (action IN ('CREATE', 'UPDATE', 'DELETE', 'SPLIT_RULE_RESET')),
    changes              JSONB NOT NULL CHECK (jsonb_typeof(changes) = 'array'),
    FOREIGN KEY (ledger_id, record_id) REFERENCES family_record (ledger_id, id),
    FOREIGN KEY (ledger_id, changed_by_member_id) REFERENCES ledger_member (ledger_id, id),
    FOREIGN KEY (ledger_id, about_member_id) REFERENCES ledger_member (ledger_id, id),
    CHECK ((action = 'SPLIT_RULE_RESET') = (record_id IS NULL)),
    CHECK ((action = 'SPLIT_RULE_RESET') = (changed_by_member_id IS NULL)),
    CHECK ((action = 'SPLIT_RULE_RESET') = (about_member_id IS NOT NULL))
);

CREATE INDEX ON family_record_change (ledger_id, id DESC);
CREATE INDEX ON family_record_change (record_id);

-- Which code is writing, for the triggers below: 'family-posting' while the posting service's writer writes
-- (ledger.family.posting.CrossLedgerWriter), 'delete-all' while a user's data is deleted (UserDataService.deleteAll,
-- the runbook's "Delete a user", release_family_memberships), else ''. Set with set_config(…, true), which ends with
-- the transaction. One database login serves all code, so this catches mistakes, not attacks (topic E).
CREATE FUNCTION family_writer() RETURNS TEXT
    LANGUAGE sql STABLE AS $$
    SELECT coalesce(current_setting('app.writer', true), '')
$$;

-- Whether a system-owned link that isn't detached names the entry: then only the posting service changes it.
CREATE FUNCTION family_entry_is_posted(entry BIGINT) RETURNS BOOLEAN
    LANGUAGE sql STABLE SET search_path FROM CURRENT AS $$
    SELECT EXISTS (SELECT FROM family_entry_link WHERE entry_id = entry AND system_owned AND detached_at IS NULL)
$$;

-- The guard of cross-ledger writes (D-8, topic E), on entries: the posting service's writer writes only the family
-- kinds, and nothing else writes them; a posted entry changes only through it, and is deleted only through it or with
-- all of its user's data.
CREATE FUNCTION journal_entry_family_guard() RETURNS trigger
    LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE
    writer TEXT := family_writer();
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF family_entry_is_posted(OLD.id) AND writer NOT IN ('family-posting', 'delete-all') THEN
            RAISE EXCEPTION 'journal entry %: posted from a family budget, it changes only through its family record',
                OLD.id USING ERRCODE = 'check_violation';
        END IF;
        RETURN OLD;
    END IF;
    IF writer = 'family-posting' THEN
        IF NEW.kind IS NULL OR NEW.kind NOT LIKE 'FAMILY\_%' THEN
            RAISE EXCEPTION 'journal entry %: the family posting writes only family kinds, not %', NEW.id, NEW.kind
                USING ERRCODE = 'check_violation';
        END IF;
    ELSIF TG_OP = 'INSERT' AND NEW.kind LIKE 'FAMILY\_%'
            OR TG_OP = 'UPDATE' AND (family_entry_is_posted(OLD.id)
                                     OR NEW.kind LIKE 'FAMILY\_%' AND OLD.kind IS DISTINCT FROM NEW.kind) THEN
        RAISE EXCEPTION 'journal entry %: posted from a family budget, it changes only through its family record',
            coalesce(NEW.id, OLD.id) USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER journal_entry_family_guard
    BEFORE INSERT OR UPDATE OR DELETE ON journal_entry
    FOR EACH ROW EXECUTE FUNCTION journal_entry_family_guard();

-- The guard on postings: a posted entry's postings change only through the posting service's writer, and only it
-- posts to a debt account. What it writes goes only to the accounts D-8 lists: the member's debt account for the
-- family, their "Payments without a specified account", UNALLOCATED for a share, OPENING_BALANCE for an opening
-- balance or a correction; and the payer's own account for a payment, in the ledger that the writer declared as the
-- caller's own (app.own_ledger).
CREATE FUNCTION posting_family_guard() RETURNS trigger
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

CREATE TRIGGER posting_family_guard
    BEFORE INSERT OR UPDATE OR DELETE ON posting
    FOR EACH ROW EXECUTE FUNCTION posting_family_guard();

-- The guard on links: only the posting service's writer writes them. Deleting an entry sets its link's reference to
-- NULL, whoever deletes it; deleting a user's data detaches their links and deletes a family ledger's.
CREATE FUNCTION family_entry_link_guard() RETURNS trigger
    LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE
    writer TEXT := family_writer();
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.entry_id IS NULL AND OLD.entry_id IS NOT NULL
            AND (NEW.family_ledger_id, NEW.member_id, NEW.record_id, NEW.link_type, NEW.system_owned, NEW.detached_at)
                IS NOT DISTINCT FROM
                (OLD.family_ledger_id, OLD.member_id, OLD.record_id, OLD.link_type, OLD.system_owned, OLD.detached_at)
            OR writer = 'family-posting'
            OR writer = 'delete-all' AND TG_OP <> 'INSERT' THEN
        RETURN CASE TG_OP WHEN 'DELETE' THEN OLD ELSE NEW END;
    END IF;
    RAISE EXCEPTION 'family entry link %: only the family posting writes links', coalesce(NEW.id, OLD.id)
        USING ERRCODE = 'check_violation';
END
$$;

CREATE TRIGGER family_entry_link_guard
    BEFORE INSERT OR UPDATE OR DELETE ON family_entry_link
    FOR EACH ROW EXECUTE FUNCTION family_entry_link_guard();

-- The guard on accounts. A debt account is created only by the posting service's writer, in the personal ledger of
-- an ACTIVE member of the family ledger it names, and never comes to name one afterwards. UNALLOCATED, where every
-- share is posted, can be renamed but not archived (D-8); no endpoint deletes an account, and deleting all of a
-- user's data deletes it with the rest.
CREATE FUNCTION account_family_guard() RETURNS trigger
    LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
    IF NEW.family_ledger_id IS NOT NULL
            AND (TG_OP = 'INSERT' OR OLD.family_ledger_id IS DISTINCT FROM NEW.family_ledger_id) THEN
        IF family_writer() <> 'family-posting' THEN
            RAISE EXCEPTION 'account %: only the family posting creates a family budget''s debt account', NEW.code
                USING ERRCODE = 'check_violation';
        END IF;
        IF NOT EXISTS (SELECT FROM ledger_member p JOIN ledger_member m ON m.user_sub = p.user_sub
                       WHERE p.ledger_id = NEW.ledger_id AND p.ledger_type = 'PERSONAL'
                         AND m.ledger_id = NEW.family_ledger_id AND m.ledger_type = 'SHARED'
                         AND m.status = 'ACTIVE') THEN
            RAISE EXCEPTION 'account %: ledger % is not the personal ledger of an active member of family ledger %',
                NEW.code, NEW.ledger_id, NEW.family_ledger_id USING ERRCODE = 'foreign_key_violation';
        END IF;
    END IF;
    IF TG_OP = 'UPDATE' AND NEW.code = 'UNALLOCATED' AND OLD.archived_at IS NULL AND NEW.archived_at IS NOT NULL THEN
        RAISE EXCEPTION 'account UNALLOCATED: every share of a family budget is posted there, so it cannot be archived'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER account_family_guard
    BEFORE INSERT OR UPDATE OF family_ledger_id, archived_at ON account
    FOR EACH ROW EXECUTE FUNCTION account_family_guard();

-- V6's check of a posting's references, with the family case of the category rule (D-11, topic C): a posting of a
-- personal entry may use a category of a family ledger in which the personal ledger's member is an ACTIVE member.
-- A LEFT member needs no exception: leaving detaches them (D-19), so none of their postings uses a family category.
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
        -- A family category has no user_id, so the users' comparison passes it, and the ledgers' decides.
        SELECT user_id, ledger_id INTO owner_user_id, owner_ledger_id FROM category WHERE id = NEW.category_id;
        IF owner_user_id <> entry_user_id THEN
            RAISE EXCEPTION 'posting in journal entry %: category % belongs to another user',
                NEW.entry_id, NEW.category_id USING ERRCODE = 'foreign_key_violation';
        END IF;
        IF owner_ledger_id <> entry_ledger_id
                AND NOT EXISTS (SELECT FROM ledger f
                                JOIN ledger_member m ON m.ledger_id = f.id AND m.status = 'ACTIVE'
                                JOIN ledger_member p ON p.user_sub = m.user_sub AND p.ledger_type = 'PERSONAL'
                                WHERE f.id = owner_ledger_id AND f.type = 'SHARED' AND p.ledger_id = entry_ledger_id)
                THEN
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

-- V6's membership part of "Delete all my data" (D-20), which from F4a also:
-- - erases the comments the member wrote, in the records and in the journal (topic H): the new value of each of their
--   changes of a comment, the old value of the change that replaced one of theirs, and a record's comment if they
--   wrote it last;
-- - detaches the member's links, which stay as the family's history (D-19); "Delete all my data" deletes the member's
--   personal entries before it calls this, so the links' references are NULL by then;
-- - deletes a family ledger's records, shares, links and journal with it;
-- - journals the split rule's fall back to EQUAL as a system change about the member (topic H).
-- It sets app.writer to 'delete-all' while it runs, and back afterwards.
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

        UPDATE ledger_member
            SET status = 'FORMER', role = 'MEMBER', user_sub = NULL, display_name = 'Former member', share_bp = NULL,
                left_date = coalesce(left_date, current_date)
            WHERE id = membership;
        UPDATE family_entry_link SET detached_at = now(), system_owned = FALSE
            WHERE family_ledger_id = family AND member_id = membership AND detached_at IS NULL;
        released := released + 1;

        IF NOT EXISTS (SELECT FROM ledger_member
                       WHERE ledger_id = family AND status = 'ACTIVE' AND user_sub IS NOT NULL) THEN
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
