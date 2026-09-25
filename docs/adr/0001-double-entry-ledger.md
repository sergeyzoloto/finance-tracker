# ADR 0001: Double-entry ledger

**Status:** Accepted, 2026-09-25. Not implemented yet: the code still has the single-entry model of
`V1__users_categories_transactions.sql`.

## Context

### The Excel ledger

The owner has kept his finances since 2017 in an Excel workbook that is a double-entry ledger. The
app has to take that history over without losing anything, so its model has to be able to hold
everything the workbook holds.

The workbook has three tables. Samples are exported to `data/private/`, which is git-ignored:
they hold personal data and are never committed.

- **Accounts** (`BalanceSheetItems.csv`, 20 rows): a name, a type, and a display order.
  - The type is `ASSET` (15 accounts) or `LIABILITIES` (5).
  - `LIABILITIES` mixes two kinds of account:
    - Real obligations: a creditor debt, an installment card, and `FAMILY_DEBT`, the owner's debt
      to the family budget.
    - The owner's own money: `RESERVE`, the part set aside, and `UNALLOCATED`, the part not set
      aside for anything.
  - The file has no account codes. Codes such as `CASH` appear only in the journal's formula
    columns.
- **Categories** (`CashFlowItems.csv`, 31 rows): a name and a type.
  - 23 are `EXPENSE` and 7 are `INCOME`.
  - One more row is named `-`, with type `CASHFLOW`. It means "no category".
- **Journal** (`Transactions_example.csv`, 299 rows, November 2017 to February 2018, all in
  RUB).
  - Each row holds one movement: date, category, sum, currency, counterparty, comment, debit
    account, credit account, and a Family flag.
  - The other 23 columns are formulas: month, year, account codes and types, the signed balance
    change on each side, the family half, loan sums per borrower, and check columns.

How a journal row works:

- The debit account's balance changes by +Sum if it is an asset and by −Sum otherwise. The credit
  account changes the opposite way.
- Categories only ever sit on `UNALLOCATED`. An expense debits `UNALLOCATED`, which lowers it.
  Income credits `UNALLOCATED`, which raises it.
- A Family row is an expense shared with the family budget:
  - Only half of it is the owner's.
  - The other half appears only in the formula column `FAMILY_EXP`. It isn't in the Debit or
    Credit columns.

### What the samples confirm

A script checked the three files (the numbers are from the 299-row sample):

| Check                                                                                                                                  | Result                                                                                                                                                                                                                                                                                                                                                 |
| -------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| a) Every row with a category has `UNALLOCATED` on exactly one side                                                                    | Holds for all 220 rows with a category. The other 79 rows (category `-`) never touch `UNALLOCATED`: they are transfers.                                                                                                                                                                                                                                        |
| b) Family rows change `UNALLOCATED` by half of Sum, and `FAMILY_EXP` holds the other half                                               | 81 Family rows, all expenses. 79 split exactly. The 2 rows with an odd number of cents round **both** halves up, so together the halves come to 0.01 more than Sum.                                                                                                                                                                                   |
| c) With the Family half posted to `FAMILY_DEBT`, the total change of ASSET accounts equals the total change of LIABILITIES accounts | Off by 0.02, which is exactly the two odd-cent Family rows at 0.01 each. Without the Family half the totals don't come close, so the half has to be a real posting. Rebuilt with the rules below (debit +, credit −, split by rule 7), all 299 entries balance exactly. |
| d) `Столбец1` is TRUE exactly when `CASH` is on one side                                                                                | All 68 TRUE rows have `CASH` on a side, and none of the 231 FALSE rows do. It is a derived filter column.                                                                                                                                                                                                                                                         |

More from the same run:

- **Signs.**
  - Most rows with a category have the usual sign: 189 expenses and 25 incomes.
  - 2 expense refunds and 1 income reversal are entered as a negative Sum under the same
    category.
  - 3 more rows have Sum 0.
  - One loan repayment is also entered with a negative Sum. So "Debit" and "Credit" in the
    workbook are column positions, not directions. The sign of the amount decides the direction.
- **Loans.**
  - The 8 `LOANS_ASSET` rows name the borrower in `LOAN_CA`. That is a different field from the
    row's counterparty: the two agree in 0 of the 8 rows.
  - `LOAN_SUM` is always minus the `LOANS_ASSET` change, so it is derived.
- **Derived columns.**
  - Month and year agree with Date in all 299 rows.
  - `CHECK_CORR` and `FAMILY_INC` are 0 in every row.
  - `EXP_ITEM_CODE` repeats `CODE_ITEM`.

### The current app

The app isn't deployed, and no production database exists for it.

- It stores single-entry transactions:
  `transactions(category_id NOT NULL, amount NUMERIC(12,2) CHECK (amount > 0), occurred_on, note)`.
- The sign comes from the category's type when the data is read.
- It has no accounts. So it can't hold the 79 transfers or the family split, and it can't show a
  balance per account.

## Decision

The core model is the workbook's double-entry journal, made explicit. These rules apply:

1. The core model is a double-entry journal. A journal_entry has entry_date (DATE, no time zone), an optional payee (a counterparty), memo and kind. A posting belongs to one entry and has account, currency (ISO 4217 code), amount, optional category and optional counterparty.
2. Sign convention: debit is positive, credit is negative. For every entry and every currency, the sum of posting amounts is exactly zero.
3. Money is NUMERIC(19,4) in PostgreSQL and BigDecimal in Java, never float or double. Rounding to 2 decimals uses RoundingMode.HALF_UP and happens only where a rule says so.
4. Account types are ASSET, LIABILITY, EQUITY. Displayed balance: ASSET = sum of amounts; LIABILITY and EQUITY = minus sum of amounts. Of the Excel type LIABILITIES, UNALLOCATED and RESERVE map to EQUITY and all other accounts map to LIABILITY. System EQUITY accounts: OPENING_BALANCE and FX_EXCHANGE.
5. Categories have type INCOME or EXPENSE and may be set only on postings to EQUITY accounts. An expense is a positive posting to UNALLOCATED with an EXPENSE category; income is a negative posting to UNALLOCATED with an INCOME category. A refund uses the same category with the opposite sign and is valid.
6. An entry with at least one categorized posting is income or expense; an entry without categories is a transfer. The stored kind is only a hint for the UI; reports are always computed from postings.
7. Shared expense: total T is paid from one account; share ratio r defaults to 0.50. other = round(T × r, 2); own = T − other. Postings: payment account −T; UNALLOCATED +own with the category; the user's shared account (FAMILY_DEBT by default, configurable) +other.
8. Accounts with requires_counterparty = true (LOANS_ASSET and CREDITOR_DEBT) require a counterparty on every posting. Their balances are also reported per counterparty.
9. Currency exchange of amount A in currency X into amount B in currency Y: source account −A in X, FX_EXCHANGE +A in X, FX_EXCHANGE −B in Y, target account +B in Y.
10. Opening balances are posted against OPENING_BALANCE.
11. Every owned row has user_id equal to the Keycloak "sub" claim. Every query is scoped by it. Access to another user's object returns 404.
12. Accounts, categories and counterparties referenced by postings are archived, never deleted.
13. Values derivable from postings (month, year, account type, signed changes, loan sums, check columns) are never stored.

How a workbook row maps onto the model, for the importer:

| Workbook                            | Model                                                                                    |
| ----------------------------------- | ---------------------------------------------------------------------------------------- |
| One row                             | One journal_entry with two postings, or three for a Family row                         |
| Date (`DD/MM/YYYY`)                 | entry_date                                                                               |
| Counteragent                        | payee                                                                                    |
| Comment                             | memo                                                                                     |
| Debit / Credit account, Sum         | debit account +Sum, credit account −Sum (Sum can be negative), in the row's Currency     |
| Exp. Item                           | category on the `UNALLOCATED` posting; `-` means none                                    |
| Family = да                         | the `UNALLOCATED` posting split by rule 7, the other half to `FAMILY_DEBT`              |
| LOAN_CA                             | counterparty on the `LOANS_ASSET` posting                                                |
| Every formula column                | dropped (rule 13); recomputed from postings to check the import against the workbook |

## Consequences

### What gets easier

- The workbook can be imported without losing anything. Each derived column can be recomputed
  from postings and compared with the workbook, which makes a ready-made check on the import.
- Every entry balances (rule 2), so every report built from postings adds up. A broken entry is
  caught when it's saved, not when a total looks wrong.
- One mechanism covers transfers, shared expenses, loans, currency exchange and opening balances.
  None of them needs its own table or a special case in the reports.
- Refunds net out within their category, with no workaround.
- Shared expenses no longer create a cent from nothing: the workbook's 0.01 overshoot on odd-cent
  rows goes away.
- Amounts become signed, which removes the reason category types were frozen (commit `20829c6`):
  changing a type no longer re-signs stored history. The type still decides how a category's
  postings read, though. After a flip, an expense reads as an income reversal. So the freeze stays
  until someone decides otherwise.

### What it costs

- **Schema.** V1's `transactions` and `categories` give way to `account`, `category`,
  `counterparty`, `journal_entry` and `posting`, plus the system accounts `OPENING_BALANCE` and
  `FX_EXCHANGE`.
  - No production data exists, so a new migration can drop the V1 tables.
  - V1 itself must not be edited: it has been applied to the development database on Supabase,
    and Flyway would refuse the changed checksum.
- **Ownership.** Every owned row is keyed by the Keycloak `sub`, not by the surrogate `users.id`
  (rule 11). The `users` table is then no longer needed to scope data.
- **The balance invariant has to be enforced twice.** A `CHECK` can't span rows, so:
  - The service validates every entry before writing it.
  - The database backs that up with a deferred constraint trigger that runs per entry at commit.

  An entry and its postings are always written, and replaced, together in one transaction.
- **Money precision.**
  - Storage moves from `NUMERIC(12,2)` to `NUMERIC(19,4)`.
  - Input validation stays per field: amounts typed by the user can keep 2 decimals.
  - The frontend holds amounts as JavaScript numbers today. It must not do money arithmetic with
    them. The shared-expense split and all totals come from the backend.
- **Reports.** Every report is a sum over postings. At the expected scale (thousands of entries
  per user), reports are computed on every request, backed by indexes on `posting (user_id,
  account_id)` and `journal_entry (user_id, entry_date)`.
  - Nothing is stored per month or per account (rule 13).
  - If a cache is ever needed, it must be rebuildable from postings.
- **Entry forms.** Entering data takes more UI.
  - There are templates for expense, income, transfer, shared expense, exchange and loan, and
    each template generates the postings.
  - Editing raw postings is for corrections only.
- **Currencies.** Balances are per account and currency, and the ledger never converts to a base
  currency. `FX_EXCHANGE` shows, per currency, what exchanges gave and took. Putting a value on
  that needs exchange rates, which this decision doesn't cover.
- **Archiving.**
  - Accounts, categories and counterparties get an archived flag.
  - Foreign keys from postings are `ON DELETE RESTRICT`.
  - Only unreferenced rows can still be deleted.

### What the import still has to settle

These are the points where the samples contradict the rules or can't confirm them:

- **Account codes.** The accounts file has none, and 8 of the 20 accounts don't occur in the
  sample. Those 8 include the accounts behind `CREDITOR_DEBT` and `RESERVE`. The owner has to
  supply a mapping from account names to codes.
- **The FX-gain income category.**
  - The workbook books exchange gains as income on `UNALLOCATED`.
  - Under rule 9 they are the balance of `FX_EXCHANGE` instead, so rows in this category would
    count twice.
  - In the sample it has 3 rows, all with Sum 0. Their amounts can be dropped as they stand. Rows
    elsewhere in the full history need review.
- **Zero-amount rows.** The same 3 rows have Sum 0. The rules don't forbid them, but they carry
  nothing. The importer should skip them.
- **Missing borrower.** One `LOANS_ASSET` row has the literal text `NULL` as its borrower, which
  breaks rule 8. It needs a counterparty before it can be imported. `NULL` also appears as text in
  `CODE_ITEM` on the 79 rows without a category.
- **Odd-cent Family rows.** The two rows import with `UNALLOCATED` 0.01 lower each than in the
  workbook, because rule 7 gives the extra half-cent to the other side. The workbook itself is out
  of balance by that amount.
- **Currency exchange.** `CUR_DEBIT`, `SUM_DEBIT`, `CUR_CREDIT` and `SUM_CREDIT` are empty
  throughout the sample, so how the workbook records an exchange is still unknown.
- **Opening balances.** The sample contains none. How the workbook's first rows establish
  balances is still unknown.
- **Shared income.** The workbook has a `FAMILY_INC` column, 0 everywhere in the sample. Rule 7
  defines shared expenses only.

## Rejected alternatives

### A single-entry transactions table, one category per row

This is the V1 model: each row has an amount, a category and a date, and the category's type
decides the sign.

- It can't represent transfers, which are 79 of the 299 sample rows. The same goes for loans,
  exchanges, and which account paid, so there is no balance per account.
- A shared expense is one payment with two parts: the owner's share and the family's. One amount
  per row can't hold both.
- Nothing ties the rows together. No invariant exists to check them against, so an entry error
  only surfaces when a total looks wrong.
- Deriving the sign from the category means:
  - A refund needs either a negative amount or its own category.
  - Changing a type re-signs history. That was the top finding of the September 2026 audit.
- Patching it with "from account" and "to account" columns gives a double entry with exactly two
  legs. That can't express a shared expense, which needs three postings, or an exchange, which
  involves two currencies.

### Each category as a separate income or expense account

This is the textbook chart of accounts: account types INCOME and EXPENSE, one account per
category, and income and expenses closed into equity.

- The workbook doesn't work this way. All 220 categorized rows post to a single equity account,
  `UNALLOCATED`.
  - Its balance is a figure the owner actually reads: the part of his money not set aside for
    anything.
  - With category accounts, that figure has to be rebuilt, either by closing entries or by summing
    every income and expense account.
- To the user, accounts and categories are different things:
  - Account pickers would mix wallets and cards with about 30 categories.
  - The category rules (fixed type, archiving, category reports) would have to move onto accounts.
- Re-categorizing a posting would move money between two accounts instead of relabeling it.
- It gains nothing:
  - A report per category is the same query either way.
  - Refunds work the same way under both models.
  - A trial balance comes from the invariant in rule 2 regardless.
