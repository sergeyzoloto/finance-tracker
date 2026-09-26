# ADR 0002: Exchange rates and reports in the base currency

**Status:** Accepted, 2026-09-26. The schema change is `V4__exchange_rate_sources.sql`.

## Context

ADR 0001 keeps every balance per account and currency, and the ledger never converts between
currencies. The owner wants to see balances, net worth and cash flow in their base currency. They
also want to see what exchange rates did to their money: what exchanges gained or lost, and what
rate changes did to money still held in other currencies.

- The ECB publishes euro reference rates for about 30 currencies every TARGET working day. Its
  daily file and its history since 1999 were checked on 2026-09-26:
  - The daily file is XML with one `Cube time=` per day.
  - The history is a ZIP file with one CSV file, newest day first. It writes `N/A` where a currency
    had no rate that day, and every line ends with an empty column.
- The ECB published RUB from 2005-04-01 to 2022-03-01. The owner's ledger is in RUB, so rates for
  later days have to be entered by hand.
- The imported sample (299 rows, all RUB, 2017-11-01 to 2018-02-18) has an ECB rate on or before
  every posting day.

## Decision

**Sources.**

- A scheduled job loads the ECB's rates into `exchange_rate` with source ECB. It runs at startup and
  at 16:30 and 18:30 Europe/Berlin on weekdays.
  - The first load reads the history.
  - After that, the job reads the daily file.
  - If a weekday between the latest stored day and the daily file's day has no rates, the job reads
    the history again. That happens after a failed load, and on the ECB's holidays.
- A user can enter manual rates, one at a time or as a CSV file with the columns
  `date,base,quote,rate`. They are stored with source MANUAL.
- **Manual rates belong to the user who entered them** (`user_id` = the sub, rule 11). ECB rates are
  shared (`user_id` NULL).
  - A rate one user enters must not change another user's reports.
  - On the same day, the user's own rate takes precedence over the ECB's.

**Storage.** Every rate is units of a currency for one euro (`base_currency = 'EUR'`, checked by the
database).

- A cross rate is computed through the euro: X to Y is Y per euro divided by X per euro.
- A manual rate must have EUR on one side. One given as euros per unit of another currency is
  inverted and stored with 8 decimals, the scale of `NUMERIC(19,8)`.

**Conversion.** An amount on day D uses the latest rate on or before D.

- **There is no age limit.** For RUB after 2022-03-01 without manual rates, the rule picks the ECB's
  last RUB rate of 117.201.
- Instead of hiding such a rate, the screens show the date of every rate they use. The rates page
  and the dashboard flag rates more than 7 days older than the day they convert.
- If a currency has no rate on or before D, the figure is **missing**: null, with `missingRates`
  naming the currency and the days.
  - A missing amount is never added up as if it were 0.
  - A missing rate is never replaced by a later rate or by 1.
  - An amount of zero needs no rate.

**Rounding.** Each figure is added up from unrounded conversions and then rounded HALF_UP to
4 decimals (the money scale, rule 3), once. The screens show converted amounts with the currency's
usual decimals.

**FX results.** They are computed from postings and rates on every call and never posted
(rule 13). Both are **effects on net worth, positive for a gain**:

- **Unrealized revaluation**, over every ASSET and LIABILITY account and currency: the signed
  balance at the rate on asOf, less each posting at the rate on its own day. It is zero for the base
  currency.
- **Realized result of exchanges**: minus the sum of the FX_EXCHANGE postings, each converted at
  the rate on its own day. That is FX_EXCHANGE's displayed balance (rule 4) in the base currency. The
  request that led to this ADR said "the sum of FX_EXCHANGE postings". The sign is flipped so that a
  gain reads positive, like the revaluation.
- Every entry balances per currency, and all its postings share one day. So net worth in the base
  currency equals equity at historical rates excluding FX_EXCHANGE, plus the realized result, plus
  the unrealized revaluation.
- The cash flow shows both per month: the realized result of that month's exchanges, and the change
  of the unrealized revaluation from the day before the month (or before the period) to its last day.
  Neither is income or an expense, so neither is part of the net.

**API.** Balances, net worth and cash flow take `currency=BASE`. It is served by a second handler on
the same path, with its own response shape. Any other value of `currency` is a 400.

## Consequences

- RUB after March 2022 is converted at a rate from 2022 until manual rates exist. The screens show
  that rate's date, and the rates page lists the days on which entries have no rate at all.
- If silent use of old rates ever becomes a problem, a maximum rate age per source would turn such
  figures into missing ones. That would change the rule above, so it is left to a later decision.
- The ECB table grows by about 30 rows per working day. The first load writes about 221,000 rows in
  about 13 seconds.
- Reports in the base currency read postings and rates in two statements, in one REPEATABLE READ
  transaction. The other reports read one statement each.
- Tests that need rates use their own users' manual rates. The ECB's rates are shared, and
  `EcbRateLoaderTests` deletes them.
