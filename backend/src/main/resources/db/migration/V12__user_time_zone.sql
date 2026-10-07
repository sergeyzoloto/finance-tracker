-- F8c, D-100 and D-101: each user has a time zone, and D-53's today is the date in it. The zone is an IANA id in the
-- user's settings (the api checks it); NULL means not set yet, and today is then the UTC date. Nothing reads or writes
-- the column but the new code, so the image before this migration (which ignores it) runs on this schema unchanged.
ALTER TABLE user_settings ADD COLUMN time_zone TEXT;

ALTER TABLE user_settings ADD CONSTRAINT user_settings_time_zone_length CHECK (length(time_zone) BETWEEN 1 AND 64);

-- V9's check of a claim's join date said "not after today", where today was the database session's date, which is UTC's
-- in production. A claim's join date is now decided in its owner's zone (D-101), which may be a day ahead of UTC's
-- (up to UTC+14) when the owner creates the invite, so the check lets through every date that is already today
-- somewhere: the date at UTC+14, whatever the session's zone is. The api still refuses a date after its owner's own
-- today. Everything else is as in V9.
CREATE OR REPLACE FUNCTION ledger_invite_check() RETURNS trigger
    LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NOT EXISTS (SELECT FROM ledger_member
                       WHERE id = NEW.created_by_member_id AND ledger_id = NEW.ledger_id AND role = 'OWNER'
                         AND status = 'ACTIVE' AND user_sub IS NOT NULL) THEN
            RAISE EXCEPTION 'ledger invite: member % is not an active owner of family ledger %',
                NEW.created_by_member_id, NEW.ledger_id USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.seat_member_id IS NOT NULL THEN
            IF NOT EXISTS (SELECT FROM ledger_member
                           WHERE id = NEW.seat_member_id AND ledger_id = NEW.ledger_id AND status = 'ACTIVE'
                             AND user_sub IS NULL) THEN
                RAISE EXCEPTION 'ledger invite: member % of family ledger % is not a seat without an account',
                    NEW.seat_member_id, NEW.ledger_id USING ERRCODE = 'check_violation';
            END IF;
            IF NEW.join_date > (now() AT TIME ZONE 'Pacific/Kiritimati')::date
                    OR NEW.join_date < (SELECT start_date FROM ledger WHERE id = NEW.ledger_id) THEN
                RAISE EXCEPTION 'ledger invite: the join date % is not between the start of family ledger % and today',
                    NEW.join_date, NEW.ledger_id USING ERRCODE = 'check_violation';
            END IF;
        END IF;
        RETURN NEW;
    END IF;
    IF (NEW.ledger_id, NEW.token_hash, NEW.seat_member_id, NEW.join_date, NEW.created_by_member_id, NEW.created_at,
        NEW.expires_at)
            IS DISTINCT FROM (OLD.ledger_id, OLD.token_hash, OLD.seat_member_id, OLD.join_date,
                              OLD.created_by_member_id, OLD.created_at, OLD.expires_at)
            OR num_nonnulls(OLD.revoked_at, OLD.used_at, OLD.declined_at) > 0
               AND (NEW.revoked_at, NEW.used_at, NEW.used_by_member_id, NEW.declined_at)
                   IS DISTINCT FROM (OLD.revoked_at, OLD.used_at, OLD.used_by_member_id, OLD.declined_at) THEN
        RAISE EXCEPTION 'ledger invite %: only a pending invite changes, and only to revoked, used or declined', OLD.id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;
