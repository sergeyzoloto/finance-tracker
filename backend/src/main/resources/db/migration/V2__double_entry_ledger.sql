-- The double-entry ledger of docs/adr/0001-double-entry-ledger.md. V1's tables are left as they are.
-- Owned rows are keyed by user_id, the Keycloak "sub" claim (rule 11), and don't reference users.

CREATE TABLE account (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id               TEXT NOT NULL,
    code                  VARCHAR(50) NOT NULL,
    name                  VARCHAR(100) NOT NULL,
    type                  VARCHAR(9) NOT NULL CHECK (type IN ('ASSET', 'LIABILITY', 'EQUITY')),
    -- Preselected in entry forms. Optional: an account can hold any currency, and FX_EXCHANGE has no natural default.
    default_currency      CHAR(3) CHECK (default_currency ~ '^[A-Z]{3}$'),
    requires_counterparty BOOLEAN NOT NULL DEFAULT FALSE,
    is_system             BOOLEAN NOT NULL DEFAULT FALSE,
    archived_at           TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, code),
    UNIQUE (user_id, id)
);

CREATE TABLE category (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     TEXT NOT NULL,
    code        VARCHAR(50) NOT NULL,
    name        VARCHAR(100) NOT NULL,
    type        VARCHAR(7) NOT NULL CHECK (type IN ('INCOME', 'EXPENSE')),
    archived_at TIMESTAMPTZ,
    UNIQUE (user_id, code)
);

CREATE TABLE counterparty (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     TEXT NOT NULL,
    name        VARCHAR(100) NOT NULL,
    -- NULL until classified: the Excel ledger doesn't say what kind a counterparty is.
    kind        VARCHAR(12) CHECK (kind IN ('MERCHANT', 'PERSON', 'ORGANIZATION')),
    archived_at TIMESTAMPTZ,
    UNIQUE (user_id, id)
);

CREATE UNIQUE INDEX ON counterparty (user_id, lower(name));

CREATE TABLE import_batch (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     TEXT NOT NULL,
    file_name   VARCHAR(255) NOT NULL,
    file_sha256 CHAR(64) NOT NULL CHECK (file_sha256 ~ '^[0-9a-f]{64}$'),
    dry_run     BOOLEAN NOT NULL,
    started_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ,
    report      JSONB,
    UNIQUE (user_id, id)
);

CREATE TABLE journal_entry (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id         TEXT NOT NULL,
    entry_date      DATE NOT NULL,
    -- A hint for the UI only; reports are computed from postings (rule 6).
    kind            VARCHAR(20),
    payee_id        BIGINT,
    memo            VARCHAR(500),
    import_batch_id BIGINT,
    -- The entry's identity in an imported file, so a second import of the same row is refused.
    external_ref    VARCHAR(200),
    -- Optimistic locking.
    version         INTEGER NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Payee and import batch of the same user.
    FOREIGN KEY (user_id, payee_id) REFERENCES counterparty (user_id, id) ON DELETE RESTRICT,
    FOREIGN KEY (user_id, import_batch_id) REFERENCES import_batch (user_id, id) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX ON journal_entry (user_id, external_ref) WHERE external_ref IS NOT NULL;
CREATE INDEX ON journal_entry (user_id, entry_date DESC);

-- Postings have no user_id of their own: they belong to their entry's user, which the triggers below check.
CREATE TABLE posting (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    entry_id        BIGINT NOT NULL REFERENCES journal_entry ON DELETE CASCADE,
    account_id      BIGINT NOT NULL REFERENCES account ON DELETE RESTRICT,
    currency        CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    -- Debit positive, credit negative (rule 2).
    amount          NUMERIC(19, 4) NOT NULL CHECK (amount <> 0),
    category_id     BIGINT REFERENCES category ON DELETE RESTRICT,
    counterparty_id BIGINT REFERENCES counterparty ON DELETE RESTRICT
);

CREATE INDEX ON posting (entry_id);
CREATE INDEX ON posting (account_id, currency);
CREATE INDEX ON posting (category_id);
CREATE INDEX ON posting (counterparty_id);

-- Shared by all users. The ledger itself never converts between currencies.
CREATE TABLE exchange_rate (
    rate_date      DATE NOT NULL,
    base_currency  CHAR(3) NOT NULL CHECK (base_currency ~ '^[A-Z]{3}$'),
    quote_currency CHAR(3) NOT NULL CHECK (quote_currency ~ '^[A-Z]{3}$'),
    -- Units of quote_currency for one unit of base_currency.
    rate           NUMERIC(19, 8) NOT NULL CHECK (rate > 0),
    source         VARCHAR(50) NOT NULL,
    PRIMARY KEY (rate_date, base_currency, quote_currency),
    CHECK (base_currency <> quote_currency)
);

CREATE TABLE user_settings (
    user_id             TEXT PRIMARY KEY,
    base_currency       CHAR(3) NOT NULL DEFAULT 'EUR' CHECK (base_currency ~ '^[A-Z]{3}$'),
    -- Receives the other part of a shared expense (rule 7). NULL means the account FAMILY_DEBT.
    shared_account_id   BIGINT,
    default_share_ratio NUMERIC(5, 4) NOT NULL DEFAULT 0.5000 CHECK (default_share_ratio > 0 AND default_share_ratio < 1),
    FOREIGN KEY (user_id, shared_account_id) REFERENCES account (user_id, id) ON DELETE RESTRICT
);

-- Rules that span rows, and so can't be constraints. The service checks them too; these are the backstop.
-- SET search_path FROM CURRENT fixes the schema Flyway migrated into, so the functions work from any session.

-- A posting's entry, account, category and counterparty belong to one user; only postings to EQUITY accounts have
-- a category (rule 5); postings to an account that requires a counterparty have one (rule 8).
CREATE FUNCTION posting_check_references() RETURNS trigger
    LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE
    entry_user_id      TEXT;
    account_user_id    TEXT;
    account_code       TEXT;
    account_type       TEXT;
    needs_counterparty BOOLEAN;
BEGIN
    -- A row that doesn't exist reads as NULL, passes these comparisons and is left to the foreign keys.
    SELECT user_id INTO entry_user_id FROM journal_entry WHERE id = NEW.entry_id;
    -- FOR SHARE: a concurrent change of the account's type or requires_counterparty waits until this transaction
    -- ends, and then account_check_postings sees this posting.
    SELECT user_id, code, type, requires_counterparty
        INTO account_user_id, account_code, account_type, needs_counterparty
        FROM account WHERE id = NEW.account_id FOR SHARE;

    IF account_user_id <> entry_user_id THEN
        RAISE EXCEPTION 'posting in journal entry %: account % belongs to another user', NEW.entry_id, NEW.account_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;
    IF NEW.category_id IS NOT NULL THEN
        IF (SELECT user_id FROM category WHERE id = NEW.category_id) <> entry_user_id THEN
            RAISE EXCEPTION 'posting in journal entry %: category % belongs to another user',
                NEW.entry_id, NEW.category_id USING ERRCODE = 'foreign_key_violation';
        END IF;
        IF account_type <> 'EQUITY' THEN
            RAISE EXCEPTION 'posting in journal entry %: account % is %, and only postings to EQUITY accounts can have a category',
                NEW.entry_id, account_code, account_type USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    IF NEW.counterparty_id IS NOT NULL THEN
        IF (SELECT user_id FROM counterparty WHERE id = NEW.counterparty_id) <> entry_user_id THEN
            RAISE EXCEPTION 'posting in journal entry %: counterparty % belongs to another user',
                NEW.entry_id, NEW.counterparty_id USING ERRCODE = 'foreign_key_violation';
        END IF;
    ELSIF needs_counterparty THEN
        RAISE EXCEPTION 'posting in journal entry %: account % requires a counterparty', NEW.entry_id, account_code
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER posting_check_references
    BEFORE INSERT OR UPDATE ON posting
    FOR EACH ROW EXECUTE FUNCTION posting_check_references();

-- Rule 2, at commit, so that an entry and its postings can be written in any order: every entry the transaction
-- touched, unless it was deleted, has at least two postings, and they sum to zero in each currency.
CREATE FUNCTION journal_entry_check_balanced() RETURNS trigger
    LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE
    entries             BIGINT[];
    entry               BIGINT;
    posting_count       INTEGER;
    unbalanced_currency TEXT;
    unbalanced_sum      NUMERIC;
BEGIN
    IF TG_TABLE_NAME = 'journal_entry' THEN
        entries := ARRAY[NEW.id];
    ELSIF TG_OP = 'INSERT' THEN
        entries := ARRAY[NEW.entry_id];
    ELSIF TG_OP = 'DELETE' OR OLD.entry_id = NEW.entry_id THEN
        entries := ARRAY[OLD.entry_id];
    ELSE
        -- An update that moved the posting to another entry changed both.
        entries := ARRAY[OLD.entry_id, NEW.entry_id];
    END IF;

    FOREACH entry IN ARRAY entries LOOP
        CONTINUE WHEN NOT EXISTS (SELECT FROM journal_entry WHERE id = entry);

        SELECT count(*) INTO posting_count FROM posting WHERE entry_id = entry;
        IF posting_count < 2 THEN
            RAISE EXCEPTION 'journal entry % needs at least 2 postings, has %', entry, posting_count
                USING ERRCODE = 'check_violation';
        END IF;

        SELECT currency, sum(amount) INTO unbalanced_currency, unbalanced_sum
            FROM posting WHERE entry_id = entry
            GROUP BY currency HAVING sum(amount) <> 0
            ORDER BY currency LIMIT 1;
        IF FOUND THEN
            RAISE EXCEPTION 'journal entry % does not balance in %: postings sum to %',
                entry, unbalanced_currency, unbalanced_sum USING ERRCODE = 'check_violation';
        END IF;
    END LOOP;
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER posting_balanced
    AFTER INSERT OR UPDATE OR DELETE ON posting
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION journal_entry_check_balanced();

-- An entry saved without postings fires no posting trigger, so a new entry is checked too.
CREATE CONSTRAINT TRIGGER journal_entry_balanced
    AFTER INSERT ON journal_entry
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION journal_entry_check_balanced();

-- The posting rules above must stay true when an account changes after it has postings.
CREATE FUNCTION account_check_postings() RETURNS trigger
    LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
    IF NEW.type <> 'EQUITY'
            AND EXISTS (SELECT FROM posting WHERE account_id = NEW.id AND category_id IS NOT NULL) THEN
        RAISE EXCEPTION 'account % has postings with a category and must stay EQUITY', NEW.code
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.requires_counterparty
            AND EXISTS (SELECT FROM posting WHERE account_id = NEW.id AND counterparty_id IS NULL) THEN
        RAISE EXCEPTION 'account % has postings without a counterparty and cannot require one', NEW.code
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER account_check_postings
    BEFORE UPDATE OF type, requires_counterparty ON account
    FOR EACH ROW
    WHEN (OLD.type <> NEW.type OR OLD.requires_counterparty <> NEW.requires_counterparty)
    EXECUTE FUNCTION account_check_postings();

-- Rows never change owner: posting_check_references compared user_id values as they were when the posting was
-- written.
CREATE FUNCTION forbid_user_id_change() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% %: user_id cannot change', TG_TABLE_NAME, OLD.id USING ERRCODE = 'check_violation';
END
$$;

CREATE TRIGGER account_user_id_immutable
    BEFORE UPDATE OF user_id ON account
    FOR EACH ROW WHEN (OLD.user_id <> NEW.user_id) EXECUTE FUNCTION forbid_user_id_change();
CREATE TRIGGER category_user_id_immutable
    BEFORE UPDATE OF user_id ON category
    FOR EACH ROW WHEN (OLD.user_id <> NEW.user_id) EXECUTE FUNCTION forbid_user_id_change();
CREATE TRIGGER counterparty_user_id_immutable
    BEFORE UPDATE OF user_id ON counterparty
    FOR EACH ROW WHEN (OLD.user_id <> NEW.user_id) EXECUTE FUNCTION forbid_user_id_change();
CREATE TRIGGER journal_entry_user_id_immutable
    BEFORE UPDATE OF user_id ON journal_entry
    FOR EACH ROW WHEN (OLD.user_id <> NEW.user_id) EXECUTE FUNCTION forbid_user_id_change();
