# ADR 0004: The multi-currency family ledger

Status: accepted for F8a (2026-10-06). Implements D-45, D-46, D-87, D-12 per currency and D-66 of
[the requirements](../family-budget/requirements.md); D-47 and D-49 (displayed totals and rates) are F8b's. Amended in
F8b (2026-10-06) for D-88 (who sees the paying side) and D-89 (the paying currency chosen per payment). Supersedes
D-13 and the parts of [ADR 0003](0003-family-budget-membership-and-cross-ledger-posting.md) (topic D, "The base
currency"; topic E, "F4e as built: other currencies") that keep shares, balances and debt in one base currency.

## Context

Since F4e (D-13) a family ledger has one base currency. Every record keeps its original amount and currency, but its
shares, the members' balances and the lines on their "Debt to family budget" are in the base currency, and a record in
another currency is converted at the ECB's rate of its date (any age), else at the acting member's own manual rate,
else with its base amount entered (`RATE_MISSING`). The rate and its source are stored on the record (V8).

That doesn't fit the owner's household, whose family debt is kept per currency, and it converts at stale rates: the ECB
stopped publishing RUB on 2022-03-01, so a rouble record after that date took a rate of 2022 (D-66). The owner decided
one multi-currency family ledger (D-45), with settlements in one currency (D-46); the PM added that no exchange rate is
ever used in a posting (D-87).

Production holds no family record (every `family_*` count is 0 since OPS-2b's deploy); local and test databases do.

## Decision

### The model

- **A record has its own currency**, `family_record.currency` (V11). Its amount (`family_record.base_amount`, whose name
  stays, D-22) and every share (`family_share.amount`) are in it. Shares are split by D-12 at that currency's minor
  unit (`ShareSplit` with the record currency's scale): each share is cut down to the minor unit and the remainder goes
  to the largest share, on a tie to the payer, then by join order.
- **The ledger's base currency is its main currency**: the default currency of a new record or settlement, and the
  currency of a total shown for display (D-47, F8b). It may change at any time; V11 drops V7's freeze
  (`ledger_check_base_currency`), and the service no longer refuses the change.
- **Balances per currency.** For each currency c, B_c(m) = expense shares − expenses paid + incomes received − income
  shares − settlements paid + settlements received, over the records in c that aren't deleted (ADR 0003, topic D's
  formula, restricted to c). They sum to zero in each currency, since each record's shares add up to its amount.
- **A settlement is in one currency** (D-46), the currency of the debt it settles. It moves balances in that currency
  only. Offsetting a debt in one currency against a debt in another is "Later".
- **Posting per currency.** A share's two lines (UNALLOCATED with the family category, and `Debt(L)`) and the debt line
  of every side are in the record's currency. `Debt(L)` holds every currency; its default currency (the main currency
  when it was created) is only a default, as for any account (V2). D-10 holds per currency: in each currency, the
  displayed balance of a member's `Debt(L)` equals their family balance in that currency.
- **Opening balance and correction** (D-18, D-26, D-31, D-35, D-39): one entry per member, as V7's unique index
  `family_entry_link_opening_key` requires, with one pair of lines (`Debt(L)` and OPENING_BALANCE) per currency whose
  amount isn't 0. A currency whose amount becomes 0 loses its pair; a member with no pair left loses the entry.
- **Leaving and returning** (D-19, D-36, D-39): the detach is unchanged; the returning member's correction is per
  currency, and so are the entries a return lists (D-37), which already carry their currency.
- **Delete all my data** (D-20): `release_family_memberships` stores no balance and is unchanged; the preview lists the
  member's balance in each currency.

### The paying side (D-87)

The payer's payment, an income's receipt and the recorder's side of a settlement are on the account the member names.

- The side's currency, the paying currency, is chosen per payment (D-89, F8b: `accountCurrency` in the request). It
  defaults to the account's currency (its `default_currency`), else, for an account without one, to the record's;
  the member, or the import, may name another. An account holds several currencies (V2), so a dollar record paid in
  dollars from an account whose default is euros is an ordinary side in dollars. F8a had no `accountCurrency`: the
  side's currency was always the account's default, which is still the default.
- If the paying currency is the record's, the side is the record's amount in the record's currency: the account and
  `Debt(L)`, as before F4e.
- Otherwise the member enters what went from or into the account, in the paying currency (`accountAmount`), and the side goes
  through their FX_EXCHANGE as rule 9 has it and F4e built it: the account and FX_EXCHANGE in the account's currency,
  FX_EXCHANGE and `Debt(L)` in the record's currency. No rate is looked up: without `accountAmount` the answer is 422
  `ACCOUNT_AMOUNT`, never a conversion. FX_EXCHANGE shows the difference, as for any personal exchange.
- `accountAmount` is asked again (422 `ACCOUNT_AMOUNT` without it) when the record's amount or currency, the account
  or the paying currency changes, and kept when only the date, the comment, the category or the split changes (D-89).
- "Specify later" is in the record's currency; moving it to an account in another currency asks for `accountAmount`.
- The other side of a settlement works as F4e built it, with the record's currency where F4e had the base currency:
  their placeholder in the record's currency, or their own account with the amount they name for it, which only their
  entry holds.
- A payer without an account has no side, as before.

**Storage.** `original_amount` and `original_currency` hold the paying side of the payer, the receiver or the recorder:
what went from or into their account, in its currency. They equal the record's amount and currency when the side is in
the record's currency. When they differ, `base_rate_source` is `ENTERED` (both amounts were entered), never `ECB` or
`MANUAL`, which only records converted before V11 keep. V11's trigger `family_record_check_currencies` (replacing V8's
`family_record_check_currency`) checks exactly that, against the record's currency instead of the ledger's. Only the
member changes their side's amount (D-8, D-14): it changes neither the record's version nor its journal (D-16), like the
account it is on.

**Who sees it** (D-88, F8b; replaces F4e's choice). The paying side's amount and currency are the member's whose side it
is, as the other side of a settlement and the payee (D-81) are: only `yourPayment` holds them, in that member's own
answers. Every other member sees the record's amount, currency, date, category and shares. `originalAmount`,
`originalCurrency`, `rate`, `rateSource` and `rateDate` are removed from the answer (F8b), and the journal shows a
change of the paying side's amount (`originalAmount`) only to the member whose side it is. Until F8b, as in F4e, the
deprecated `originalAmount` and `originalCurrency` were in every member's answer.

### No rate in the family path (D-66, D-87)

`FamilyRecordService` looks up no rate. `RateService.recordRate`, `RecordRate`, the ECB-or-manual lookup behind them and
the code `RATE_MISSING` are removed: they were used only for D-13's conversion, and F8b's displayed totals follow D-49's
own rules (an ECB rate at most 7 days old, month-end rates for the report, manual rates labelled), so nothing would reuse
them. With them goes D-66's stale-rate defect. `GET /{ledgerId}/conversion` stays until F8b's forms stop calling it,
deprecated and rate-free: it answers the amount itself in the main currency, and no amount and no rate otherwise. The
request field `baseAmount` is accepted and ignored, deprecated likewise.

### Existing records: converted, not a legacy mode

V11 sets every record's currency to its family ledger's base currency, deleted records included. That keeps their
meaning exactly: before V11, a record's `base_amount` and shares were in the base currency, and `original_amount` in
`original_currency` was the payer's side, posted through FX_EXCHANGE when it wasn't the base currency. Under this ADR the
same row reads as a record in the base currency paid from an account in `original_currency` with that amount. No share,
entry, posting or link changes, so the balances, the debt accounts and the integrity check give the same numbers
(`FamilyRecordsV11MigrationTests`: a V10 database with records in euros, a dollar payment converted at the ECB's rate, an
entered amount paid by a seat and a settlement; V10's own statements against the application's answers on V11). Their
rate columns stay as the history of how their amount was found; a later change never converts them again.

A legacy mode, a flag keeping D-13's conversion for old records, was rejected: the posting service, the record service
and the forms would carry two rule sets, for records that exist only in local and test databases.

### Why V11 is additive

- `family_record.currency` is added, filled from the ledger, then made NOT NULL. The new trigger function fills it from
  the ledger's base currency when an insert leaves it out, as an image before V11 does; that image's amounts are in the
  base currency, so the fill is what it means.
- V8's trigger function is replaced by a new function; V8's file is unchanged. An older image's writes satisfy the new
  one: a record in the base currency has its original amount as its amount and no source, one paid in another currency
  has a rate's source.
- V7's freeze of the base currency is dropped; an older image keeps its own service check.
- Nothing else is dropped or renamed; the rate columns stay.

### The rollback condition

An image below V11 reads `family_record.base_amount` and every share of a record as amounts in its ledger's
`base_currency`. It misreads exactly the records whose currency differs from their ledger's base currency: their
amounts as the wrong currency in balances, reports, posting and the integrity check, and, for a deleted one, its
journal's amounts at the wrong minor unit. Every other record reads the same under both: its paying side in
`original_*` is what F4e's code posts too. So `deploy/rollback.sh` refuses a target whose highest migration is below
V11 while this count isn't 0:

```sql
SELECT count(*) FROM app.family_record r JOIN app.ledger l ON l.id = r.ledger_id WHERE r.currency <> l.base_currency
```

It covers records in another currency and records whose ledger's main currency changed after they were written. Once an
older image runs on V11 by passing that check, it behaves as it did: for example it converts a record's amount again at
a rate when its payer changes the date of a record paid in another currency (F4e).

### API changes (additive)

Old fields stay, deprecated in code, until F8b moves the frontend to the new ones. F8b then removed them, with
`/conversion` and the request field `baseAmount`: a record's `originalAmount`, `originalCurrency`, `rate`,
`rateSource` and `rateDate`; the balances' `currency` and `members`; the report's `currency`, `rows` and `totals`;
the invite lookup's `openingBalance` and `correction`; the deletion preview's `balance`.

- A record's `amount` and `currency` are the record's own (they were the base amount and the base currency; the same
  for records in the main currency). `originalAmount`, `originalCurrency`, `rate`, `rateSource` and `rateDate` are
  deprecated.
- Requests: `currency` is the record's or settlement's currency (the main currency when left out), `amount` its amount,
  and `accountAmount` the paying side's amount when the named account is in another currency. `baseAmount` is ignored.
- `balances` adds `byCurrency`; `currency` and `members` stay as the main currency's.
- The report, the invite lookup's opening balance and correction, and the deletion preview add per-currency lists next
  to their single amounts, which stay as the main currency's.
- The integrity check's family rows are per currency.

## Totals for display (F8b; D-47, D-49, D-90, D-91)

`GET …/balances` and `GET …/report` carry `total`: each member's figures in the main currency, for display only
(`FamilyTotals`). It is never stored, posted or settled; balances, settlements and posting stay per currency.

- The rates are those of the member who reads (their own manual rates, the shared ECB ones) by `RateBook`'s rules
  (ADR 0002, "Conversion since F8b"): an ECB rate at most 7 days old, a manual one from its date until the next, the
  most recent applicable date winning, the manual one on the same date, and the "stale" mark past 31 days.
- The balances' total converts each currency's balance at the rate that applies today. The report's total converts
  each month's amounts at the month's month-end rate, the current month (and any later one) at today's.
- Each total is rounded HALF_UP once, at its end, to the main currency's minor unit, and names the rates it used
  (`rates`: currency, date, perEuro, source, `stale`).
- If a currency the total needs has no applicable rate, there is no total: `members` (or `totals`) is empty and
  `missingCurrencies` names it, such as `RUB` after the ECB stopped publishing it.

## What F8b and F8c add

- **F8b:** the interface (balances and settle-up per currency, the forms' currency and `accountAmount`, the record pages),
  the report's screen per currency, the "≈" totals in the main currency (D-47) with D-49's rates, D-53, D-54, the
  end-to-end specs, `deploy/checks/F8.sql` and `F8.expected`, and the deploy checklist; the deprecated fields' last
  users go.
- **F8c, before D3b:** refunds (D-79: an expense with a minus; V7's `original_amount > 0`, `base_amount > 0` and
  `family_share.amount >= 0` would need a migration), payments from accounts that require a counterparty (D-80: V7's
  and V8's guard and `CrossLedgerWriter` refuse them today), the payer's payee on their own entry (D-81), and the
  import's sync fields on records (D-48). V11 adds no constraint against any of them.

## Consequences

- D-66's defect is gone: no family amount depends on a rate.
- A member's personal reports show family shares in the record's currency, as every other posting.
- Display totals across currencies need rates, which F8b brings with D-49's rules, and never feed back into postings.
- A rollback below V11 is safe until the first record in another currency than its ledger's main currency exists, and
  refused afterwards, with the reason.
