-- Exchange rates for reports in the user's base currency. The ECB's euro reference rates are shared by all users; a
-- rate the user enters is the user's own (rule 11), so it changes only that user's reports. The table had no rows
-- anywhere when this was added.

ALTER TABLE exchange_rate ADD COLUMN user_id TEXT;
ALTER TABLE exchange_rate ADD CONSTRAINT exchange_rate_source_check CHECK (source IN ('ECB', 'MANUAL'));
ALTER TABLE exchange_rate ADD CONSTRAINT exchange_rate_owner_check CHECK ((source = 'MANUAL') = (user_id IS NOT NULL));
-- Every rate is units of the quote currency for one euro, as the ECB quotes them, and a cross rate is computed through
-- the euro. A manual rate given the other way round is inverted when it is saved.
ALTER TABLE exchange_rate ADD CONSTRAINT exchange_rate_euro_check CHECK (base_currency = 'EUR');

-- One rate per day and currency from the ECB, and one per user. Reports look rates up by currency and date.
ALTER TABLE exchange_rate DROP CONSTRAINT exchange_rate_pkey;
ALTER TABLE exchange_rate ADD CONSTRAINT exchange_rate_key
    UNIQUE NULLS NOT DISTINCT (base_currency, quote_currency, rate_date, user_id);
