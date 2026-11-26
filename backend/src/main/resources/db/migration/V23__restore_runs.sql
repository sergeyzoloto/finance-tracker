-- HIST-4 part 2a (ADR 0006, 6.3, 6.6, D-1038, D-1040, D-1224): the record of a restore. A restore run names its
-- target revision and the change set it made; a restore item names one record, what happened to it and the versions
-- involved. Additive (D-22): the image before this migration never reads or writes these tables, and the ledger's
-- deletion takes them by the cascade.

CREATE TABLE restore_run (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ledger_id       BIGINT      NOT NULL REFERENCES ledger (id) ON DELETE CASCADE,
    -- SNAPSHOT or VERSION (one record's earlier version); no CHECK, as snapshot.kind (D-1202).
    kind            VARCHAR(16) NOT NULL,
    -- The snapshot restored (a SNAPSHOT run), else null.
    snapshot_id     BIGINT REFERENCES snapshot (id),
    target_revision BIGINT      NOT NULL CHECK (target_revision >= 0),
    -- The change set of the restore: every version it wrote carries this revision.
    revision        BIGINT      NOT NULL CHECK (revision >= 0),
    -- The BEFORE_RESTORE snapshot taken for the undo (D-1011, D-1222).
    before_snapshot_id BIGINT NOT NULL REFERENCES snapshot (id),
    -- The ledger_member who restored; null for SYSTEM.
    actor_member    BIGINT,
    at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- How many records of each outcome, as the plan counted them.
    counts          JSONB       NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(counts) = 'object')
);

CREATE INDEX restore_run_ledger_idx ON restore_run (ledger_id, at DESC, id);

CREATE TABLE restore_item (
    run_id               BIGINT      NOT NULL REFERENCES restore_run (id) ON DELETE CASCADE,
    record_type          VARCHAR(16) NOT NULL,
    record_id            BIGINT      NOT NULL,
    -- RESTORED, RETURNED, DELETED, ARCHIVED (ADR 0006, 6.3); no CHECK, as above.
    outcome              VARCHAR(16) NOT NULL,
    -- The version the record's live state was before the restore, and the version the restore wrote. No foreign key:
    -- the erasure of a user deletes versions (V19), while the items are deleted with their run.
    replaced_version_id  BIGINT,
    written_version_id   BIGINT,
    PRIMARY KEY (run_id, record_type, record_id)
);

-- Immutable as record_version and snapshot: no update; a delete only under app.writer 'delete-all' (the erasure)
-- or with the ledger (a restore run goes with its ledger by the cascade; the items with their run).
CREATE TRIGGER restore_run_immutable
    BEFORE UPDATE OR DELETE ON restore_run
    FOR EACH ROW EXECUTE FUNCTION history_immutable();

-- The items have no ledger_id of their own, so history_immutable (which reads OLD.ledger_id) can't guard them: an item
-- goes only with its run, which the cascade removes first, or under app.writer 'delete-all'.
CREATE FUNCTION restore_item_immutable() RETURNS trigger
    LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
    IF family_writer() = 'delete-all' THEN
        RETURN OLD;
    END IF;
    IF TG_OP = 'DELETE' AND NOT EXISTS (SELECT FROM restore_run WHERE id = OLD.run_id) THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'restore_item: a row of restore_item is immutable' USING ERRCODE = 'check_violation';
END
$$;

CREATE TRIGGER restore_item_immutable
    BEFORE UPDATE OR DELETE ON restore_item
    FOR EACH ROW EXECUTE FUNCTION restore_item_immutable();

-- V14's trigger, with one more writer it skips (D-1224): a restore that archives an imported entry is not a deletion
-- in the app (D-1038), so no imported_entry_deleted row, and a later import of the file brings the entry back. Every
-- other writer, and the image before this migration (which never sets this writer), is as before.
CREATE OR REPLACE FUNCTION journal_entry_remember_deleted_import() RETURNS trigger
    LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
    IF family_writer() IN ('delete-all', 'restore-archive') OR NOT EXISTS (SELECT FROM ledger WHERE id = OLD.ledger_id) THEN
        RETURN NULL;
    END IF;
    INSERT INTO imported_entry_deleted (ledger_id, external_ref) VALUES (OLD.ledger_id, OLD.external_ref)
    ON CONFLICT (ledger_id, external_ref) DO NOTHING;
    RETURN NULL;
END
$$;
