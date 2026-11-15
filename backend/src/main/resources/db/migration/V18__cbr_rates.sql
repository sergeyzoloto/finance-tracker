-- FX-1 (D-851 to D-853): the Bank of Russia's official EUR rate in roubles, a second shared source beside the ECB's.
-- A CBR rate is shared by all users like the ECB's: source CBR, user_id NULL (exchange_rate_owner_check already says
-- so, since only MANUAL has a user).
ALTER TABLE exchange_rate DROP CONSTRAINT exchange_rate_source_check;
ALTER TABLE exchange_rate ADD CONSTRAINT exchange_rate_source_check CHECK (source IN ('ECB', 'CBR', 'MANUAL'));

-- The source is part of the key, so that the two shared sources never overwrite each other's rate of the same day and
-- currency. A manual rate stays one per user, day and currency: its source is always MANUAL.
ALTER TABLE exchange_rate DROP CONSTRAINT exchange_rate_key;
ALTER TABLE exchange_rate ADD CONSTRAINT exchange_rate_key
    UNIQUE NULLS NOT DISTINCT (base_currency, quote_currency, rate_date, user_id, source);
