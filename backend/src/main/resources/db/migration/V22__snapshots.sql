-- HIST-4 part 1 (ADR 0006, D-1008, D-1040, D-1193 to D-1195): snapshots of a ledger. A snapshot names a revision; the
-- state at it is computed from record_version (the latest version of each record at or before it). Additive (D-22):
-- the image before it never reads this table, and the ledger's deletion takes its snapshots by the cascade.
CREATE TABLE snapshot (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ledger_id  BIGINT      NOT NULL REFERENCES ledger (id) ON DELETE CASCADE,
    revision   BIGINT      NOT NULL CHECK (revision >= 0),
    name       VARCHAR(80) NOT NULL,
    -- MANUAL, BEFORE_IMPORT, BEFORE_RESTORE; no CHECK, as activity_event.kind (D-1101).
    kind       VARCHAR(16) NOT NULL,
    -- The ledger_member who made it; null for SYSTEM.
    created_by BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX snapshot_ledger_idx ON snapshot (ledger_id, created_at DESC, id);

-- Immutable as record_version (V19's history_immutable, not gated by app.history as V19's guards aren't): no update;
-- a delete only under app.writer 'delete-all' or because the ledger is gone (the cascade).
CREATE TRIGGER snapshot_immutable
    BEFORE UPDATE OR DELETE ON snapshot
    FOR EACH ROW EXECUTE FUNCTION history_immutable();
