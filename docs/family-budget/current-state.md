# Family budget: the current state of the code (F1)

What the code does today, on branch `main` at `d0656b9` (2026-09-28), as the starting point of the
family budget ([requirements.md](requirements.md)). Paths are relative to the repository root. Java
classes are in `backend/src/main/java/com/example/financetracker/` unless a path says otherwise;
tests are in `backend/src/test/java/com/example/financetracker/`. The decisions this leads to are in
[ADR 0003](../adr/0003-family-budget-membership-and-cross-ledger-posting.md).

Where the task's background differs from the code, the code is described here and the difference is
listed under [Contradictions with the task](#contradictions-with-the-task) at the end.

## 1. Ownership

### Tables with an owner column

| Table | Owner column | Migration | Notes |
| --- | --- | --- | --- |
| `users` | `keycloak_id TEXT UNIQUE` (the sub); `id BIGINT` is a surrogate | V1 | Email and display name from the token, for display and for finding a sub by email. |
| `categories` (V1) | `user_id BIGINT → users.id` | V1 | Unused since the ledger replaced it; only deleted by `UserDataService`. |
| `transactions` (V1) | `user_id BIGINT`, FK `(user_id, category_id) → categories (user_id, id)` | V1 | Unused, as above. |
| `account` | `user_id TEXT NOT NULL` | V2 | `UNIQUE (user_id, code)`, `UNIQUE (user_id, id)`. |
| `category` | `user_id TEXT NOT NULL` | V2 | `UNIQUE (user_id, code)`. No `UNIQUE (user_id, id)`. |
| `counterparty` | `user_id TEXT NOT NULL` | V2 | `UNIQUE (user_id, id)`, unique index `(user_id, lower(name))`. |
| `import_batch` | `user_id TEXT NOT NULL` | V2 | `UNIQUE (user_id, id)`. |
| `journal_entry` | `user_id TEXT NOT NULL` | V2 | Composite FKs to `counterparty (user_id, id)` (payee) and `import_batch (user_id, id)`. |
| `posting` | none | V2, V3 | Belongs to its entry's user; the triggers check its references (section 3). |
| `user_settings` | `user_id TEXT PRIMARY KEY` | V2 | FK `(user_id, shared_account_id) → account (user_id, id)`. |
| `exchange_rate` | `user_id TEXT NULL` | V4 | NULL for the ECB's shared rates, the sub for a MANUAL rate (`exchange_rate_owner_check`). |

There is no other owner column, and no table for ledgers (see [section 2](#2-the-ledgers-table)).

### How the sub reaches the services

1. `security/SecurityConfig` checks every request's access token (issuer, `aud`, a non-blank `sub`
   through the `subjectRequired` validator, and the client role `user` through `ClientRoles`). For a
   browser, `security/SessionAccessTokenFilter` supplies the session's access token as a bearer token.
2. `security/CurrentUserResolver`, a `HandlerMethodArgumentResolver`, fills every controller
   parameter of type `security/CurrentUser` with `jwt.getSubject()`. On the first request of a sub it
   provisions the user: `INSERT … INTO users … ON CONFLICT DO NOTHING`, then
   `StarterLedger.seedIfNew(sub)`. It remembers provisioned subs in memory and forgets one after
   `UserDataDeleted` commits.
3. Controllers in `ledger/api/` pass `user.id()` to services as a `String userId`. No service reads
   the security context. Request bodies and parameters never carry a user; `DataIsolationApiTests.
   theUserIsTheAccessTokensSubjectAndNothingElse` checks that planted `userId`, `user_id`, `sub` and
   `X-User-Id` values change nothing.

### How `OwnedRepository` scopes

`ledger/OwnedRepository` extends Spring Data's `Repository` (not `CrudRepository`) and declares only
`save`. It doesn't refuse anything: it leaves out `findById`, `findAll`, `existsById`, `count` and
`deleteById`, and each repository declares its lookups with the user id:

| Repository | Extends | Lookups |
| --- | --- | --- |
| `AccountRepository` | `OwnedRepository` | `findByIdAndUserId`, `findByUserIdAndCode`, `findAllByUserIdOrderByCode`, `findAllByUserIdAndCodeIn`, `findAllByUserIdAndIdIn` (FOR SHARE) |
| `LedgerCategoryRepository` | `OwnedRepository` | `findByIdAndUserId`, `findAllByUserIdOrderByName`, `findAllByUserIdAndIdIn` (FOR SHARE) |
| `CounterpartyRepository` | `OwnedRepository` | `findByIdAndUserId`, `findAllByUserIdOrderByName`, `findAllByUserIdAndIdIn` (FOR SHARE) |
| `JournalEntryRepository` | `OwnedRepository` | `findByIdAndUserId`, `findExternalRefsByUserId` (`@Query`), `delete(JournalEntry)` (by the loaded entity, with its version) |
| `ImportBatchRepository` | `OwnedRepository` | `findByIdAndUserId` |
| `UserSettingsRepository` | `Repository` | `findById(userId)`, `save` → `upsert` (`@Query`, `ON CONFLICT (user_id)`) |
| `ExchangeRateRepository` | `Repository` | `find(…, userId)`, `findManual(userId)`, `latestSharedDate()`, `upsert`, `deleteManual(userId, …)`, all `@Query` |

A `save` of an entity with an id updates that row by id alone. Services only save what they loaded
with the user id, or build for the user, so the id always belongs to the user.

### Paths that bypass `OwnedRepository`

All of them use `JdbcClient` with native SQL; there is no `JdbcTemplate` and no JPQL (the project uses
Spring Data JDBC, not JPA). Each one filters by `user_id` itself.

| Where | What | Scoping |
| --- | --- | --- |
| `security/CurrentUserResolver.provision` | `INSERT INTO users` | by the sub |
| `ledger/StarterLedger.seedIfNew`, `restore`, `insertSeed` | `INSERT INTO user_settings … ON CONFLICT DO NOTHING`; `INSERT INTO account` and `category … ON CONFLICT (user_id, code)` | by the sub; the settings row's key serializes concurrent first requests |
| `ledger/EntryService.search`, `withPostings` | the entry list: count, page, postings | `e.user_id = :userId`; the payee join is unfiltered (the composite FK guarantees the owner); postings are read by entry ids from the scoped page |
| `ledger/CounterpartyService.views` | counterparties with `lastCategoryId` | `e.user_id` and `c.user_id` |
| `ledger/report/ReportService` | every report, see [section 8](#8-reports-balances-the-dashboard-and-the-integrity-check) | every table with a `user_id` that a query reads |
| `ledger/rates/RateService.rateBook`, `overview`, `missing` | rates, the user's currencies, days without a rate | `user_id IS NULL OR user_id = :userId` for rates; `e.user_id` and `account.user_id` for the ledger |
| `ledger/rates/EcbRateLoader.store` | bulk `INSERT INTO exchange_rate … source 'ECB'` | shared rows, `user_id` NULL |
| `ledger/UserDataService.deleteAll` | bulk deletes, see [section 9](#9-delete-all-my-data) | `WHERE user_id = :userId`, and for V1 through `users.keycloak_id` |
| `ledger/demo/DemoLedgerService.load`, `hasLedgerOfOwn`, `ids` | `SELECT … FOR UPDATE` on the settings row; `INSERT INTO account`, `category`, `counterparty`; `UPDATE user_settings`; id maps | by the sub; the entries themselves go through `EntryService.create` |
| `ledger/importer/ImportService` | reads and writes through the repositories and `EntryService.createImported`; one `SET CONSTRAINTS ALL IMMEDIATE` per row savepoint | by the request's user id; `ImportRunner` (profile `import`) takes `--user-sub` from the command line |
| `JournalEntryRepository.findExternalRefsByUserId` | `@Query` | `user_id = :userId` |

The integrity check is `ReportService.integrityCheck` (in the table above). There are no other bulk
deletes, and no native queries outside these classes (`grep` for `jdbc.sql` and `@Query` in
`backend/src/main/java`).

## 2. The ledgers table

**There is no `ledgers` table**, and no ledger entity or id in the code or the migrations
(`V1` to `V4`). A user's ledger is implicit: the rows keyed by their sub, with the `user_settings`
row as its head.

- The `user_settings` row is created by `StarterLedger.seedIfNew` on the user's first request, and
  its key serializes concurrent first requests. `DemoLedgerService.load` locks it `FOR UPDATE`.
- The word "ledgers" appears in production only in the restore test of the backups:
  `deploy/pg-backup/finance.conf` has `CHECK_LEDGERS=SELECT count(*) FROM app.user_settings`, which
  the change log (D3) reported as "ledgers 1".
- Nothing references a ledger, and nothing reads one, since none exists.

## 3. Database integrity that assumes one owner per row

All in `backend/src/main/resources/db/migration/V2__double_entry_ledger.sql` unless noted.

**Constraints.**

- `UNIQUE (user_id, code)` on `account` and `category`; `UNIQUE (user_id, id)` on `account`,
  `counterparty` and `import_batch`; the unique index `counterparty (user_id, lower(name))`; the
  partial unique index `journal_entry (user_id, external_ref) WHERE external_ref IS NOT NULL`.
- Composite foreign keys that force the same owner: `journal_entry (user_id, payee_id) →
  counterparty (user_id, id)`, `journal_entry (user_id, import_batch_id) → import_batch (user_id,
  id)`, `user_settings (user_id, shared_account_id) → account (user_id, id)`, and V1's
  `transactions (user_id, category_id) → categories (user_id, id)`.
- `posting` references `journal_entry` (`ON DELETE CASCADE`), and `account`, `category` and
  `counterparty` by id alone (`ON DELETE RESTRICT`); its owner checks are in the trigger below.
- V4: `exchange_rate_owner_check` (`(source = 'MANUAL') = (user_id IS NOT NULL)`) and
  `exchange_rate_key` UNIQUE NULLS NOT DISTINCT `(base_currency, quote_currency, rate_date,
  user_id)`.

**Triggers.**

- `posting_check_references` (BEFORE INSERT OR UPDATE ON `posting`): this is the rule that rejects
  postings referencing rows of different `user_id` values. It reads the entry's `user_id`, locks the
  account `FOR SHARE`, and raises `foreign_key_violation` if the account's, the category's or the
  counterparty's `user_id` differs from the entry's. It also enforces rule 5 (a category only on an
  EQUITY account) and rule 8 (a counterparty on every posting to a `requires_counterparty` account).
  A family category used in a personal entry (D-11) fails this check today.
- `posting_balanced` and `journal_entry_balanced` (deferred constraint triggers,
  `journal_entry_check_balanced`): at commit, at least two postings and a zero sum per currency.
  They don't look at owners.
- `account_check_postings` (BEFORE UPDATE OF `type`, `requires_counterparty`): keeps rules 5 and 8
  true for existing postings.
- `forbid_user_id_change` on `account`, `category`, `counterparty` and `journal_entry`
  (`*_user_id_immutable`): `user_id` never changes, because `posting_check_references` compared the
  values as they were when a posting was written.

**Indexes.** `journal_entry (user_id, entry_date DESC)`; `posting (account_id, currency)`,
`posting (category_id)`, `posting (counterparty_id)`; `UNIQUE (entry_id, line_no)` from V3 serves
lookups by entry. There is no index on `posting` by owner (it has none) and none on `account
(user_id)` beyond the unique constraints.

`LedgerSchemaTests.postingThatReferencesAnotherUsersRowFails` and `rowsNeverChangeOwner` test the
owner rules with plain JDBC.

## 4. DataIsolationApiTests

`ledger/api/DataIsolationApiTests` extends `ledger/api/LedgerApiTest`, which extends
`IntegrationTest` (Spring Boot with MockMvc, Testcontainers `postgres:17-alpine`, `FakeKeycloak`).

- **Users.** Two fresh subs per test, `alice` and `bob` (`LedgerApiTest.newUser`, random UUIDs).
  Requests carry spring-security-test's `jwt()` with the subject and `ROLE_USER`
  (`IntegrationTest.member`); `theUserIsTheAccessTokensSubjectAndNothingElse` also uses tokens signed
  by `FakeKeycloak`.
- **Data.** `writeBobsLedger` writes a few entries (one `SHARED_EXPENSE`), a counterparty and a
  manual rate. His answers to every read (`READS`, 18 URIs) are recorded. Then `writeAlicesLedger`
  writes a ledger with every kind of owned row: her own accounts, categories and counterparties,
  settings with her own shared account and ratio, manual rates (single and CSV), an entry of every
  kind, an import of the synthetic workbook, and one posting changed past the triggers so that her
  integrity check reports something.
- **What is asserted.**
  - `bobsAnswersAreTheSameBeforeAndAfterAliceWritesHerLedger`: every read of Bob's is unchanged,
    contains neither "Alice" nor her sub, and differs from hers; her rates don't convert his amounts.
  - `bobCanNeitherReadNorChangeNorDeleteAnythingOfAlices`: GET, PUT and DELETE of each of her
    entries (with her version and a stale one), PATCH of each of her accounts, categories and
    counterparties, and DELETE of her manual rates all answer 404, word for word as for a missing id
    (`answersAsIfMissing`, digits masked).
  - `bobCannotReferToAlicesObjectsInHisOwnWrites`: 14 commands that name one of her accounts,
    categories or counterparties answer 422 exactly as for a missing id, in a new entry and in an
    update of his; her shared account in his settings; her ids as list filters.
  - `anImportSeesOnlyTheUsersOwnLedger`, `bobsDemoDataAndDeletingAllOfHisDataLeaveAlicesAsTheyWere`
    (row counts per table through `LedgerApiTest.rowsOf`, whose `OWNED_ROWS` lists the owned
    tables), and `noAnswerOfTheApiMayBeStored` (`Cache-Control: no-store` on every read).
- **How endpoints are discovered.** `everyOperationOfTheApiIsCheckedHere` reads `/api/openapi`,
  collects every `method path` pair, and asserts that the set equals the hand-maintained constant
  `CHECKED`. A new endpoint makes it fail until its operation is added to `CHECKED`. The test
  doesn't check that an operation in `CHECKED` is actually exercised: registering an endpoint means
  adding it to `CHECKED` (and to `READS` for a read) and writing its check in one of the tests above.
- **Owned tables.** `LedgerApiTest.OWNED_ROWS` counts a user's rows in `user_settings`, `account`,
  `category`, `counterparty`, `journal_entry`, `import_batch`, `exchange_rate`, `posting` (through
  the entry) and `users`. A new owned table has to be added there too.

## 5. SharedExpense

**Builder.** `ledger/domain/SharedExpenseCommand` (kind `SHARED_EXPENSE`, rule 7). Fields: date,
payee, memo, the paying account, currency, total T, an EXPENSE category, and an optional share ratio
r (0 < r < 1). `otherShare` = round(T × r, 2) HALF_UP; own = T − other, so the rounding remainder
stays with the payer. Postings:

| Line | Account | Amount | Category |
| --- | --- | --- | --- |
| 0 | the paying account | −T | |
| 1 | `UNALLOCATED` (`AccountRole.UNALLOCATED`) | +own | the category |
| 2 | the shared account (`AccountRole.SHARED`) | +other | |

A negative T is a refund and splits the same way. The shared account's displayed balance (rule 4,
LIABILITY) goes down by `other`: the budget owes the user.

**Settings.** `EntryService.context` builds the `LedgerContext`: `AccountRole.SHARED` is the
account with code `FAMILY_DEBT`, replaced by `user_settings.shared_account_id` when that is set;
`defaultShareRatio` is `user_settings.default_share_ratio`, or `EntryService.DEFAULT_SHARE_RATIO`
(0.50) for a user without settings. `SettingsService.update` checks that the shared account is the
user's (422 otherwise). The starter seed names `FAMILY_DEBT` "Family budget", a LIABILITY in EUR
(`backend/src/main/resources/seed/starter-ledger.json`).

**API and UI.**

- POST and PUT `/api/entries` with `"kind": "SHARED_EXPENSE"` (`ledger/api/EntryCommandJson`).
- GET and PUT `/api/settings` (`ledger/api/SettingsController`) carry `sharedAccountId` and
  `defaultShareRatio`. The Settings screen doesn't show them; they are set only through the API and
  by the demo.
- GET `/api/reports/shared-settlement` (`ReportService.sharedSettlement`): the shared account's
  displayed balance per currency, with `USER_OWES` or `USER_IS_OWED`.
- Frontend: the Expense tab's switch "Split with family" with "Family's share, %" and a live split
  preview (`frontend/src/EntryForms.tsx`, `ExpenseFields`; disabled when there is no shared account);
  `frontend/src/entryForm.ts` builds the command (`toCommand`, `SHARED_EXPENSE`) and opens a
  shared expense in the Expense tab (`formFromEntry`); `frontend/src/ledger.ts` `sharedAccount`;
  the dashboard's card "Shared budget" (`frontend/src/Dashboard.tsx`, `Settlement`,
  `dashboard.ts` `settlementSentence`); the import report's label "Shared expenses"
  (`frontend/src/Import.tsx`).

**What depends on it.**

- Reports: only `sharedSettlement` knows the shared account. Balances, net worth and the cash flow
  treat its postings like any other (rule 6).
- The demo: `ledger/demo/DemoLedger` writes a `SharedExpenseCommand` for the weekly shop twice a
  month and a monthly transfer "Partner's share of the groceries" from `FAMILY_DEBT` to the current
  account; `DemoLedgerService.load` sets `shared_account_id` NULL and the ratio 0.50.
  `DemoLedgerTests` and `DemoDataApiTests` check the result.
- The importer: `ledger/importer/EntryMapper.splitWithFamily` maps a row with Family = "да" to three
  postings with the workbook's own split (the family's part is minus `FAMILY_EXP`, not the ratio),
  kind `SHARED_EXPENSE`, to the account the settings name or `FAMILY_DEBT` (`ImportService`,
  "Rule 7"). A non-zero `FAMILY_INC` is a row error ("shared income is for manual review").
- Tests: `EntryBuilderTests`, `LedgerValidatorTests`, `EntryServiceTests`, `ReportServiceTests`,
  `DataIsolationApiTests`, `EntryMapperTests`, `ImportServiceTests`, and the frontend's
  `entryForm.test.ts` and `EntryForms.test.tsx` (the split's own share).

**How many exist in production.** Read-only SQL, for a root shell on the server (the entries of the
two test users of D3 were deleted; the owner's ledger had no entries on 2026-09-28):

```bash
# On the server (read only)
cd /opt/finance-tracker/deploy/app && docker compose exec -T postgres psql -X -A -U finance -d finance -c 'SET default_transaction_read_only = on' -c "SELECT count(*) AS shared_expense_entries, count(DISTINCT user_id) AS users, min(entry_date) AS first_day, max(entry_date) AS last_day FROM app.journal_entry WHERE kind = 'SHARED_EXPENSE'" -c "SELECT count(DISTINCT e.id) AS entries_on_the_shared_account FROM app.journal_entry e JOIN app.posting p ON p.entry_id = e.id JOIN app.account a ON a.id = p.account_id AND a.user_id = e.user_id LEFT JOIN app.user_settings s ON s.user_id = e.user_id WHERE a.id = s.shared_account_id OR (s.shared_account_id IS NULL AND a.code = 'FAMILY_DEBT')" -c "SELECT count(*) AS settings_rows, count(*) FILTER (WHERE shared_account_id IS NOT NULL) AS own_shared_account, count(*) FILTER (WHERE default_share_ratio <> 0.5) AS own_ratio FROM app.user_settings" </dev/null
```

The second query also counts manual and imported entries that touch the shared account.

## 6. Categories and accounts

- **Tables.** `account` (record `Account`) and `category` (record `LedgerCategory`), both per user with a `code` unique per user. Codes are set at creation and never
  change (no API changes them). `category` has a name and a type (INCOME or EXPENSE); `account` a
  name, a type (ASSET, LIABILITY, EQUITY), an optional default currency, `requires_counterparty`,
  `is_system` and `created_at`.
- **Seed.** `StarterLedger` gives each new user the 10 accounts and 15 categories of
  `seed/starter-ledger.json`. `OPENING_BALANCE` and `FX_EXCHANGE` are the system accounts.
  `LOANS_ASSET` and `CREDITOR_DEBT` require a counterparty. The demo adds `CREDIT_CARD`,
  `USD_ACCOUNT`, three categories and ten counterparties.
- **Roles.** `ledger/domain/AccountRole` finds the accounts that commands post to by code:
  `UNALLOCATED`, `OPENING_BALANCE`, `FX_EXCHANGE`, `LOANS_ASSET`, `FAMILY_DEBT` (the last replaceable
  by `shared_account_id`). `UNALLOCATED` is not a system account: it can be renamed and archived.
- **Archive rules.** `AccountService.update`: a system account can't be renamed or archived (409);
  type and `requires_counterparty` never change. `CategoryService.update`: the type can't change
  (409, decided 2026-09-25). Both are archived, never deleted: there is no delete endpoint, and the
  posting FKs are `ON DELETE RESTRICT` (rule 12). `archivedAt` keeps its time when archived again.
  Balances hide archived accounts; net worth and the integrity check include them.
- **How categories attach to postings.** `posting.category_id`, allowed only on a posting to an
  EQUITY account (rule 5; `LedgerValidator` and `posting_check_references`). Expense and income
  commands put the category on the `UNALLOCATED` posting. The entry's kind restricts the category's
  type (`EntryKind.categoryType`). `EntryService.references` loads the entry's categories with the
  user id `FOR SHARE`; a category of another user reads as missing (422 in the same words).

## 7. Money and currency

- **Types.** `NUMERIC(19,4)` for amounts (`posting.amount`, `CHECK (amount <> 0)`), `NUMERIC(19,8)`
  for rates, `NUMERIC(5,4)` for the share ratio. `BigDecimal` in Java;
  `api/JsonConfiguration` writes every `BigDecimal` as a JSON string. The frontend keeps amounts as
  strings and computes with big.js (`frontend/src/money.ts`).
- **Rounding.** Only where a rule says so: the shared expense's other part (round(T × r, 2)
  HALF_UP), a manual rate given as EUR per unit (inverted to 8 decimals, `ledger/rates/ManualRate`),
  and converted report figures (`ledger/report/ConvertedSum`, HALF_UP to 4 decimals once, after
  adding up unrounded conversions). `Money.normalize` only strips trailing zeros beyond the cents.
- **Rates.** `exchange_rate` holds units of the quote currency per 1 EUR (`base_currency = 'EUR'`
  checked). `EcbRateLoader` loads the ECB's history and daily files (`EcbClient`) at startup and at
  16:30 and 18:30 Europe/Berlin on weekdays (`EcbSchedule`, `app.rates.ecb.*`), `user_id` NULL.
  Manual rates (`RateService`, `ManualRate`, `ManualRateCsv`) belong to the user. `RateBook`: the
  latest rate on or before the day, no age limit, EUR = 1, cross rates through EUR, the user's
  manual rate first on the same day. See ADR 0002.
- **Cross-currency entries.** `CurrencyExchangeCommand` (rule 9): source −A in X, `FX_EXCHANGE` +A in
  X, `FX_EXCHANGE` −B in Y, target +B in Y. Each entry balances per currency, so no posting ever mixes
  currencies. `FX_EXCHANGE`'s displayed balance is the realized result of exchanges, and the
  unrealized revaluation is computed on each call (`ReportService.netWorthInBase`,
  `cashFlowInBase`); neither is stored.
- **Base currency.** `user_settings.base_currency` (EUR by default), used only by the `*InBase`
  reports and the rates page. The ledger itself never converts.

## 8. Reports, balances, the dashboard and the integrity check

**Where each is computed.** All in `ledger/report/ReportService`, one native statement per figure
set through `JdbcClient`, from postings on every call (rule 13), served by
`ledger/api/ReportController`:

| Report | Method | Scoping in SQL |
| --- | --- | --- |
| Balances per account and currency | `balances` | `e.user_id`, `account.user_id` |
| Balances per counterparty | `counterpartyBalances` (account by code via `AccountRepository`) | `e.user_id`, `a.user_id`, `c.user_id` |
| Cash flow per month and category | `cashFlow` | `e.user_id`, `c.user_id` |
| Net worth per currency | `netWorth` | `e.user_id`, `a.user_id` |
| The three in the base currency | `balancesInBase`, `netWorthInBase`, `cashFlowInBase` (REPEATABLE READ, plus `RateService.rateBook`) | as above, rates `user_id IS NULL OR = :userId` |
| Shared settlement | `sharedSettlement` | `e.user_id`, `a.user_id` |
| Integrity check | `integrityCheck`: per currency, the sum of the user's postings through their entries, and assets − liabilities − equity through their accounts | `e.user_id`, `a.user_id` |

`ImportService` calls `balances` and `integrityCheck` for its report. The frontend never adds up
amounts in different currencies: `frontend/src/dashboard.ts` pivots the cash flow per currency and
sums per row and column with big.js, `Dashboard.tsx` calls only `/api/reports/*` (and
`/api/entries?size=1` to tell an empty ledger), and `CashFlowTable.tsx` renders the pivot.

**What would change for read-only posted rows with a source link.**

- Personal reports need no change for the amounts: a posted share is an ordinary entry with
  postings, so balances, net worth, cash flow and the integrity check include it. The one change is
  the category filter: `cashFlow` and `cashFlowInBase` join `category c` with `c.user_id = :userId`,
  which drops a posting whose category belongs to a family ledger. Their category condition has to
  accept the family categories the entry's postings reference.
- The entry list and entry reads need the link, so that the UI can show the family and "read-only":
  `EntryService.search`, `get` and `EntryView` gain the link's type and the family ledger's name.
  PUT and DELETE of a posted entry must answer 409.
- `sharedSettlement` knows one shared account; the per-family debt accounts need their own figure
  (or the family balance endpoint replaces it on the dashboard).

**What would change for a "family only" filter (E3).** A parameter on `cashFlow`, `cashFlowInBase`
and the entry search that keeps (or drops) entries with a family link, as an `EXISTS` on the link
table, like the existing `accountId` filter in `EntryService.search`. `dashboard.ts` would put it in
the URL next to the period.

## 9. Delete all my data

`DELETE /api/me/data` (`ledger/api/UserDataController`) → `ledger/UserDataService.deleteAll(userId)`,
one `@Transactional` method:

1. `DELETE FROM … WHERE user_id = :userId` in this order, following the foreign keys:
   `user_settings` (names an account), `journal_entry` (postings cascade; entries name counterparties
   and import batches), `import_batch`, `account`, `category`, `counterparty`, `exchange_rate` (the
   user's manual rates; the ECB's stay).
2. V1's `transactions` and `categories`, through `users.keycloak_id`.
3. `users` by `keycloak_id`.
4. It publishes `UserDataDeleted`; `CurrentUserResolver.forget` runs after the commit, so the next
   request provisions the user again with a new starter ledger. The Keycloak account is untouched.

The runbook's "Delete a user" (`deploy/RUNBOOK.md`) repeats the same ten statements by hand with
`psql`, then deletes the Keycloak user; it has to follow every change of `deleteAll`.

Tests: `ledger/api/UserDataApiTests` (a demo ledger and an imported ledger deleted to zero rows in
every owned table, an empty ledger deleted twice, then the user starts again as new) and
`DataIsolationApiTests.bobsDemoDataAndDeletingAllOfHisDataLeaveAlicesAsTheyWere`. The frontend's
confirmation ("type DELETE") is in `frontend/src/Settings.tsx`, tested in `StartPages.test.tsx`.

## 10. Versioning and audit

- `@Version` only on `JournalEntry` (`journal_entry.version`): `EntryService.update` and `delete`
  compare the caller's version first, and Spring Data JDBC's save refuses a concurrent change;
  both answer 409 (`ApiExceptionHandler`, `OptimisticLockingFailureException`). A save replaces all
  the entry's postings (delete and insert), so posting ids change on every update.
- Timestamps: `journal_entry.created_at` and `updated_at` (set by `JournalEntry.create` and
  `replacedBy`), `account.created_at`, `import_batch.started_at` and `finished_at`. `category`,
  `counterparty`, `user_settings`, `users` and `exchange_rate` have none. Archiving sets
  `archived_at`.
- Nothing records who changed what: there is one user per ledger, and no audit table, trigger or log
  of changes. `import_batch.report` (JSONB) keeps each import's report.

## 11. Frontend

- **Routing** (`frontend/src/App.tsx`, react-router 7): `/` Dashboard, `/entries`, `/entries/new`,
  `/entries/:id`, `/accounts`, `/categories`, `/rates`, `/import`, `/settings`; `/transactions` and
  unknown paths redirect. State that should survive a reload lives in the URL (the dashboard's period
  and currency, the entry list's filters and page). `main.tsx` asks `/api/me` before rendering.
- **API client** (`frontend/src/api.ts`): `api(path, method, body)` calls `/api${path}` with the
  session cookie and, for writes, `X-XSRF-TOKEN`; a 401 calls `logIn()`; a failed call throws
  `ApiError` with `errors` (400) and `violations` (422). Pages read with `useApi(path)` (null path
  = don't load) and write with `useMutation`. There is no state library and no global context but
  the router; `useLedger` (`ledger.ts`) loads accounts, categories, counterparties and settings per
  page.
- **Passing a current ledger.** The API has no notion of one today. The options:
  - *Path*: `/api/ledgers/{id}/…` or `/api/family-ledgers/{id}/…`. It fits `useApi(path)` without
    change, shows in OpenAPI (so `everyOperationOfTheApiIsCheckedHere` sees each operation), and a
    frontend route such as `/family/:ledgerId/…` can supply it from `useParams`.
  - *Header* (`X-Ledger-Id`): `api()` would add it from a global, and every `useApi` key would have
    to include it to reload on a switch; invisible in URLs, links and OpenAPI paths.
  - *Session*: a server-side "current ledger" is shared by all tabs of the browser, so two tabs on
    two ledgers would write into whichever was chosen last.
- **Where a switcher fits.** The header's navigation in `App.tsx` (it holds the nav links and the
  user's name). The personal ledger's routes can stay as they are, with family pages under their own
  route prefix.
- **Read-only rows.** `Entries.tsx` lists entries with `describeEntry` (`ledger.ts`); `EntryEditor.tsx`
  opens `/entries/:id` with `formFromEntry` (`entryForm.ts`). A posted entry would need a flag in
  `Entry` (`api.ts`), a badge in the list, and in the editor a disabled form with a link to the family
  record instead of Save and Delete. Components in `components.tsx` take plain props, so disabling
  is local to `EntryForms.tsx`.

## 12. BFF login flow and what survives it

- **The requested URL.** The backend doesn't keep it: `SecurityConfig` sets
  `requestCache(new NullRequestCache())`, API callers get 401 (never a redirect), and every
  successful login lands on `/?login=done` (`defaultSuccessUrl(…, true)`). The frontend keeps it:
  `logIn()` (`frontend/src/auth.ts`) stores `location.pathname + location.search` in
  **`sessionStorage`** (`returnTo`) and goes to `/oauth2/authorization/keycloak`; after the callback,
  `finishLogin()` replaces `/?login=done` with the stored page. A URL fragment is not stored.
- **Registration with email verification** (production realm): the user registers in tab A; the
  verification link opens tab B, which finishes the sign-in and calls back with tab A's
  authorization request. Tab B has an empty `sessionStorage`, so it lands on `/`. Tab A carries on by
  itself, and its late second callback (`authorization_request_not_found`) lands on
  `/?login=done` since D3a, where its own `returnTo` still applies. If the link is opened in another
  browser or on another device, that browser has no session: it gets the login-failed notice, and
  tab A still carries on.
- **A value in the HTTP session.** Sessions are Tomcat's, in the api's memory
  (`HttpSessionOAuth2AuthorizedClientRepository` keeps the tokens there). The cookie is
  `HttpOnly; Secure; SameSite=Lax` (`application.yml`); Lax cookies are sent on Keycloak's
  top-level GET redirect back, which is how the authorization request, itself stored in the session,
  is found. Spring Security's default session fixation protection changes the session id at login
  and keeps its attributes. So a value stored in the session before the redirect survives the
  sign-in, and survives a registration whose verification link opens in the same browser (same
  cookie). It does not survive another browser or device, a restart of the api (every deploy), or
  30 minutes idle. Storing anything in a session before sign-in would need a public endpoint:
  today everything but `/actuator/health` requires the role `user`.
- `localStorage` is shared by all tabs of the origin and survives restarts of the api; the frontend
  doesn't use it (only `sessionStorage` for `returnTo` and the chunk reload guard).

## 13. Rate limiting

- **In the app:** none. No dependency provides one (`backend/pom.xml` has no Bucket4j, Resilience4j
  or similar), and no filter counts requests.
- **In Caddy:** none. `deploy/finance.caddy` sets headers, a request body limit of 20 MiB for `/api`
  and the proxies. The auth server runs the official image `caddy:2.11.4` (its
  `deploy/docker-compose.yml`), which has no rate-limit directive; that needs a third-party module
  and a custom build, and the image belongs to the auth repository.
- **In Keycloak:** brute-force detection for logins only (the auth server's `PRODUCTION.md`). The
  runbook's "Not covered" notes open registration without a rate limit or captcha.
- **For an invite-token endpoint:** an in-memory limiter in the api is enough, since one instance
  runs (the same assumption as the sessions and the refresh lock): per sub and a global ceiling,
  answered with 429 by `ApiExceptionHandler`. A fixed-window counter needs no new dependency. If the
  lookup stays behind sign-in (D-17), the sub is always known.

## 14. Flyway

- **Latest version:** `V4__exchange_rate_sources.sql`; production is at version 4 (D3: "Successfully
  applied 4 migrations … now at version v4"). The restore test compares
  `max(installed_rank)`.
- **Convention:** `backend/src/main/resources/db/migration/V<n>__snake_case_description.sql`, schema
  `app` (`spring.flyway.schemas`), a comment at the top that says why. Applied migrations are never
  edited (CLAUDE.md). V3 and V4 note that their tables had no rows anywhere when they were added; the
  next migration is the first that has to transform production data.
- **In production:** Spring Boot runs Flyway when the api starts, as the database owner `finance`
  (no superuser; `deploy/app/postgres-init.sh`). An update is back up, `git pull`, build, `docker
  compose up -d` (runbook "Update the app"). A rollback starts the `:previous` image, and Flyway in
  the older code ignores migrations it doesn't know; if the older code can't work with the newer
  schema, the runbook restores the dump taken before the update.
- **In tests:** `IntegrationTest` starts one Testcontainers `postgres:17-alpine` with
  `@ServiceConnection`; Spring Boot's Flyway migrates it once for the whole run, and tests keep apart
  by using fresh subs. `LedgerSchemaTests` creates its own database `ledger_schema_tests` in the same
  container and migrates it with plain `Flyway.configure()…migrate()`, then tests the triggers with
  JDBC. No test migrates to an intermediate version or checks a data migration.

## 15. Production as of 2026-09-29

What the owner read from production on 2026-09-29 with the read-only queries of the F1 report and of
[section 5](#5-sharedexpense), after the F1 review:

- 1 sub, present in both `users` and `user_settings`;
- 10 accounts, 15 categories, 0 counterparties, 0 journal entries, 0 import batches;
- therefore no SharedExpense entries.

These are the starter ledger's rows of the owner and nothing else. V5's backfill (ADR 0003, topic J)
meets exactly this: one personal ledger with one member, and `ledger_id` on 25 rows.

## Contradictions with the task

Where the task's background or decisions assume something the code doesn't do:

1. **"A ledgers table exists with one ledger per user."** No such table exists (section 2). The
   restore test's "ledgers" counts `user_settings`. F2 has to create the ledger table and backfill it.
2. **"OwnedRepository refuses unscoped queries."** It only lacks unscoped methods. Ten classes
   bypass it with native SQL through `JdbcClient` (section 1), three repositories declare `@Query`
   SQL, and `UserSettingsRepository` and `ExchangeRateRepository` don't extend it. The scoping is a convention that each query follows, backed by the triggers and
   `DataIsolationApiTests`.
3. **"DataIsolationApiTests fails when an endpoint is not covered by an isolation check."** It fails
   when an endpoint is missing from the hand-kept list `CHECKED`, not when its check is missing
   (section 4).
4. **The FAMILY_DEBT account is named "Family budget"** in the starter seed, not "Debt to family
   budget". The partner's part goes to `user_settings.shared_account_id` when set, else to
   `FAMILY_DEBT`; the Settings screen doesn't expose either setting. The importer splits Family rows
   by the workbook's own `FAMILY_EXP`, not by `default_share_ratio` (section 5).
5. **D-8 and rule 5.** A share entry has to post the member's share to their `UNALLOCATED` account
   with the family category, since categories sit only on EQUITY postings and expenses on
   `UNALLOCATED`. `UNALLOCATED` is one of the member's own accounts, not a system account: it can be
   renamed and archived. D-8's list of what the posting service may touch has to include it.
6. **D-7's figures for "debt to family"** are displayed balances (rule 4). The postings on that
   LIABILITY account have the opposite sign: the payer's −50 is a posting of +50. Not a conflict, but
   the ADR states signs as postings.
7. **D-21 postpones the Excel import** until family posting ships, while CLAUDE.md, the runbook
   (step 10) and every change log entry since D3 list it as the next step. The code doesn't
   conflict; the plan does.
8. **Stale statements in the docs**, not in the task: ADR 0001's status says the code still uses V1,
   and CLAUDE.md says V2 to V4 are "not applied anywhere yet"; production runs all four since D3.
