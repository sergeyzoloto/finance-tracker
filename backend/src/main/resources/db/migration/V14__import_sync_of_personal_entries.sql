-- D3b: the import's sync of personal entries (D-139, D-142, D-150). Additive (D-22): two nullable columns and a table.
--
-- The import recognises a row of the owner's Excel ledger by its permanent ID (D-86), as journal_entry.external_ref
-- ('xls:<ID>' in the personal ledger), and family records by family_record.external_ref (V13). What V13 gave family
-- records, the content's hash and the version the import left, personal entries get here, so that an edited row
-- overwrites an entry nobody changed in the app and a changed entry is a conflict (D-139). An entry the import wrote and
-- that was then deleted in the app is remembered, so that the next import lists its row as a conflict and doesn't write
-- it again (D-150).
--
-- The code before V14 (fe35e26) ignores both columns and the table, and its deletes fill the table through the
-- trigger, as this code's do. A settlement's side on an account that requires a counterparty (D-148) or on FX_EXCHANGE
-- alone (D-71) needs no schema: V13's guard already takes them; that code's writer refuses to post them again, which
-- deploy/rollback.sh's refusal below V14 covers.

-- 1. The content's hash and the version (D-139) --------------------------------------------------------------------

-- SHA-256 (lowercase hex) of the source row's business content as it was imported last.
ALTER TABLE journal_entry ADD COLUMN import_hash CHAR(64) CHECK (import_hash ~ '^[0-9a-f]{64}$');
-- The entry's own version right after the import's last write: still this one, nobody changed it in the app since.
ALTER TABLE journal_entry ADD COLUMN import_version INTEGER CHECK (import_version >= 0);
ALTER TABLE journal_entry ADD CONSTRAINT journal_entry_import_sync_check
    CHECK ((import_hash IS NULL) = (import_version IS NULL) AND (import_hash IS NULL OR external_ref IS NOT NULL));

COMMENT ON COLUMN journal_entry.import_hash IS
    'SHA-256 of the source row''s business content as the import last wrote it (D-139); with import_version, or neither.';
COMMENT ON COLUMN journal_entry.import_version IS
    'The entry''s version right after the import''s last write: still equal, nobody changed it in the app since (D-139).';

-- 2. Imported entries deleted in the app (D-150) ---------------------------------------------------------------------

-- Deleted with its ledger, so "Delete all my data" and the runbook's "Delete a user" leave nothing of it.
CREATE TABLE imported_entry_deleted (
    ledger_id    BIGINT NOT NULL REFERENCES ledger (id) ON DELETE CASCADE,
    external_ref TEXT   NOT NULL CHECK (length(external_ref) BETWEEN 1 AND 200),
    deleted_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (ledger_id, external_ref)
);

COMMENT ON TABLE imported_entry_deleted IS
    'The references of entries the import wrote (import_hash set) and the app deleted (D-150): the next import lists '
    'their rows as conflicts and doesn''t write them again.';

-- Any code's delete of an imported entry, but none of "Delete all my data" (app.writer delete-all) and none while its
-- ledger goes (a cascade from the ledger, or the runbook's "Delete a user"): the ledger's row is gone by then.
CREATE FUNCTION journal_entry_remember_deleted_import() RETURNS trigger
    LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
    IF family_writer() = 'delete-all' OR NOT EXISTS (SELECT FROM ledger WHERE id = OLD.ledger_id) THEN
        RETURN NULL;
    END IF;
    INSERT INTO imported_entry_deleted (ledger_id, external_ref) VALUES (OLD.ledger_id, OLD.external_ref)
    ON CONFLICT (ledger_id, external_ref) DO NOTHING;
    RETURN NULL;
END
$$;

CREATE TRIGGER journal_entry_remember_deleted_import
    AFTER DELETE ON journal_entry
    FOR EACH ROW WHEN (OLD.import_hash IS NOT NULL)
    EXECUTE FUNCTION journal_entry_remember_deleted_import();
