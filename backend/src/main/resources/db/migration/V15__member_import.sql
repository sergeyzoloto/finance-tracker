-- D3c: a member's own import after he took his seat (D-175, D-181). Additive (D-22): two tables.
--
-- Once a seat is claimed, each member imports only his own file (D-175). The owner's import no longer uploads the
-- partner's rows, so how far each seat's file has been imported is remembered per family budget: the family debt is
-- reconciled only up to the earlier of the members' cutoffs. And a member's import attaches his payment side (his
-- account, its currency and amount, LOAN_CA) to the family records that came from his file, and his side to the
-- settlements his settlement rows match (D-178); that side has its own content hash (D-181), with what the app held
-- right after the import wrote it, so that a later edit in Excel and a change in the app are told apart. It is the
-- member's own: it lives in his personal ledger and goes with it ("Delete all my data", the runbook's "Delete a user").
--
-- The code before V15 (1d3b811) ignores both tables. It refuses the owner's import while the partner's seat has an
-- account, so nothing it does makes them wrong; what this code writes elsewhere (a record's author, a member's payment
-- and settlement sides) is what that code writes too.

-- 1. Each seat's latest committed cutoff (D-175) ----------------------------------------------------------------------

CREATE TABLE family_import_cutoff (
    ledger_id             BIGINT NOT NULL REFERENCES ledger (id) ON DELETE CASCADE,
    -- The seat whose file it is: the owner's own seat, or the partner's (D-142).
    member_id             BIGINT NOT NULL,
    -- The last day the import compared: "Import up to", or the latest row of the files (D-157, D-165).
    cutoff                DATE   NOT NULL,
    imported_by_member_id BIGINT NOT NULL,
    imported_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (ledger_id, member_id),
    FOREIGN KEY (ledger_id, member_id) REFERENCES ledger_member (ledger_id, id) ON DELETE CASCADE,
    FOREIGN KEY (ledger_id, imported_by_member_id) REFERENCES ledger_member (ledger_id, id) ON DELETE CASCADE
);

COMMENT ON TABLE family_import_cutoff IS
    'Per family ledger and seat, the cutoff of the latest committed import of that seat''s file (D-175): the family '
    'debt is reconciled only up to the earlier of the seats'' cutoffs.';

-- 2. A member's imported side of a family record (D-181) -------------------------------------------------------------

CREATE TABLE imported_family_side (
    -- The member's personal ledger.
    ledger_id    BIGINT NOT NULL REFERENCES ledger (id) ON DELETE CASCADE,
    -- The row of his file: xls:<Excel ID>, as his personal entries (D-153).
    external_ref TEXT   NOT NULL CHECK (length(external_ref) BETWEEN 1 AND 200),
    record_id    BIGINT NOT NULL REFERENCES family_record (id) ON DELETE CASCADE,
    -- SHA-256 (lowercase hex) of his row's side as last imported, and of the side the app held right after.
    side_hash    CHAR(64) NOT NULL CHECK (side_hash ~ '^[0-9a-f]{64}$'),
    app_hash     CHAR(64) NOT NULL CHECK (app_hash ~ '^[0-9a-f]{64}$'),
    imported_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (ledger_id, record_id)
);

CREATE INDEX imported_family_side_ref ON imported_family_side (ledger_id, external_ref);

COMMENT ON TABLE imported_family_side IS
    'A member''s side of a family record that his own import attached (D-178, D-181): the row of his file, its side''s '
    'hash and the app''s side right after the import, in his personal ledger.';

-- Only a personal ledger holds a member's side.
CREATE FUNCTION imported_family_side_check() RETURNS trigger
    LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
    IF NOT EXISTS (SELECT FROM ledger WHERE id = NEW.ledger_id AND type = 'PERSONAL') THEN
        RAISE EXCEPTION 'imported_family_side: ledger % is no personal ledger', NEW.ledger_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER imported_family_side_check
    BEFORE INSERT OR UPDATE OF ledger_id ON imported_family_side
    FOR EACH ROW EXECUTE FUNCTION imported_family_side_check();
