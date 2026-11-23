-- HIST-3 part 1 (ADR 0006, 7.1 and 7.4; D-1155 to D-1158): the counters of family acceptance. Additive only, and
-- nothing reads them yet: the image before this migration runs on it (each new column is nullable or has a constant
-- default, which Postgres adds without rewriting the table).
--
-- ledger_member.accepted_revision: the family ledger's revision the member has accepted up to; only an ACTIVE member
--     with an account of a SHARED ledger has one. The trigger below sets it to the ledger's head when a row becomes
--     one (join, claim, return) and clears it when a row stops being one (leave, removal, FORMER), whichever code
--     writes the row, as V17 does for shows_payees. HistoryService moves it with each revision taken.
-- family_record.last_revision: the revision of the record's latest change set (0: none since HIST-1).
-- family_entry_link.posted_revision: the record's last_revision its entry was last posted at.
--
-- No index (D-1155): the counters are read, later, per ledger and record together with rows found by the existing
-- keys (ledger_member by ledger, family_record by ledger, family_entry_link by family ledger and record).
ALTER TABLE ledger_member ADD COLUMN accepted_revision BIGINT CHECK (accepted_revision >= 0);
ALTER TABLE family_record ADD COLUMN last_revision BIGINT NOT NULL DEFAULT 0 CHECK (last_revision >= 0);
ALTER TABLE family_entry_link ADD COLUMN posted_revision BIGINT NOT NULL DEFAULT 0 CHECK (posted_revision >= 0);

-- The backfill (D-1157): after it, nothing is pending. A counter change is no content change, so the history trigger
-- marks nothing (history_mark compares version, deleted_at and comment); the link guard wants the posting's writer.
UPDATE family_record r SET last_revision = v.revision
    FROM (SELECT ledger_id, record_id, max(revision) AS revision FROM record_version
          WHERE record_type = 'FAMILY_RECORD' GROUP BY ledger_id, record_id) v
    WHERE v.ledger_id = r.ledger_id AND v.record_id = r.id AND v.revision > 0;

SELECT set_config('app.writer', 'family-posting', true);
UPDATE family_entry_link l SET posted_revision = r.last_revision
    FROM family_record r
    WHERE r.id = l.record_id AND r.ledger_id = l.family_ledger_id AND r.last_revision > 0;
SELECT set_config('app.writer', '', true);

UPDATE ledger_member m SET accepted_revision = l.revision
    FROM ledger l
    WHERE l.id = m.ledger_id AND m.ledger_type = 'SHARED' AND m.status = 'ACTIVE' AND m.user_sub IS NOT NULL;

CREATE FUNCTION ledger_member_accepted_revision() RETURNS trigger
    LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
    IF NEW.ledger_type <> 'SHARED' OR NEW.status <> 'ACTIVE' OR NEW.user_sub IS NULL THEN
        NEW.accepted_revision := NULL;
    ELSIF TG_OP = 'INSERT' OR OLD.status <> 'ACTIVE' OR OLD.user_sub IS NULL THEN
        -- Read under the ledger's row lock, which joining, claiming and returning hold: the head.
        NEW.accepted_revision := (SELECT revision FROM ledger WHERE id = NEW.ledger_id);
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER ledger_member_accepted_revision
    BEFORE INSERT OR UPDATE ON ledger_member
    FOR EACH ROW EXECUTE FUNCTION ledger_member_accepted_revision();
