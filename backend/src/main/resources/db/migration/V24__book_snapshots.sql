-- HIST-4 book snapshots, part a (ADR 0006 section 15, D-1300 to D-1304): a snapshot covers the member's whole book.
-- The personal part stays on the snapshot row (D-1301); snapshot_ledger holds the revision of each family ledger the
-- maker was an ACTIVE member of. Additive (D-1040): the image before this migration never reads snapshot_ledger or
-- parent_run_id, always sets before_snapshot_id, and treats every snapshot as personal, so it runs unchanged on it.

CREATE TABLE snapshot_ledger (
    snapshot_id BIGINT NOT NULL REFERENCES snapshot (id) ON DELETE CASCADE,
    -- A family ledger; its deletion takes its rows (the snapshot stays as a smaller book, D-1303).
    ledger_id   BIGINT NOT NULL REFERENCES ledger (id) ON DELETE CASCADE,
    revision    BIGINT NOT NULL CHECK (revision >= 0),
    -- The snapshot maker's ledger_member id in that budget; no foreign key, as restore_item's versions.
    member_id   BIGINT NOT NULL,
    PRIMARY KEY (snapshot_id, ledger_id)
);

CREATE INDEX snapshot_ledger_ledger_idx ON snapshot_ledger (ledger_id);

-- Immutable as snapshot (V19's history_immutable): no update; a delete only under app.writer 'delete-all' or because
-- its family ledger is gone (the cascade).
CREATE TRIGGER snapshot_ledger_immutable
    BEFORE UPDATE OR DELETE ON snapshot_ledger
    FOR EACH ROW EXECUTE FUNCTION history_immutable();

-- One restore_run per ledger of a book restore (D-1304): a family run names the personal run, which goes with the
-- user's erasure while the family runs stay with their budget, so no foreign key; a family run has no
-- before_snapshot_id of its own (that snapshot is a personal-ledger row).
ALTER TABLE restore_run ADD COLUMN parent_run_id BIGINT;
ALTER TABLE restore_run ALTER COLUMN before_snapshot_id DROP NOT NULL;
ALTER TABLE restore_run ADD CONSTRAINT restore_run_before_or_parent
    CHECK (before_snapshot_id IS NOT NULL OR parent_run_id IS NOT NULL);
