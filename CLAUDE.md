# Finance Tracker

## Domain rules

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

## Project map

State on 2026-09-26. The REST API serves the double-entry ledger of [docs/adr/0001-double-entry-ledger.md](docs/adr/0001-double-entry-ledger.md) (`ledger/api/`), and the frontend uses it. The single-entry API of V1 (categories, transactions, dashboard) is gone. V1's tables are still there, unused apart from `users`.

- **Deployment:** not deployed, and no production database exists.
  - `docker-compose.yml` runs backend and nginx only, with no DB service, and reads `SUPABASE_*` from `.env`.
  - The backend downloads the ECB's rates from `www.ecb.europa.eu` over HTTPS at startup and on working days; `ECB_RATES_ENABLED=false` turns that off.
  - The launch plan, PostgreSQL 17 on the Hetzner host (`docs/database-hosting.md`), isn't built yet.
  - Local dev uses a Supabase project (eu-west-1, schema `app`, Flyway V1 applied 2026-09-23).
- **Backend** (`backend/`):
  - Stack: Java 21, Spring Boot 3.5, Maven wrapper, Spring Data **JDBC** (records, no JPA/Hibernate), Flyway, PostgreSQL.
  - Code lives in package `com.example.financetracker`. Every repository query is scoped by user id; another user's row returns 404.
  - `security/`: `SecurityConfig` makes the backend a BFF and an OAuth2 resource server. It runs oauth2Login with PKCE and keeps tokens in the session.
    - Every request's JWT is checked: issuer, `aud` = finance-tracker, a non-blank `sub`, and client role `user` → ROLE_USER (`ClientRoles`). Writes need the CSRF cookie and header.
    - `SessionAccessTokenFilter` turns the session token into a bearer token and refreshes it. It drops the session's login authentication, which grants nothing by itself.
    - `CurrentUserResolver` is the one place that works out the user: it fills the `CurrentUser(id)` parameter of controller methods with the JWT `sub`, and controllers pass `id` to services. On first sight of a user it inserts the `users` row (email and name) and calls `StarterLedger.seedIfNew`.
    - `MeController` serves GET `/api/me`.
  - `api/`: what all endpoints share.
    - `ApiExceptionHandler` is the one `@RestControllerAdvice`. Every error is an RFC 7807 problem detail: 400 with `errors` (field, message), also for a parameter value that no handler takes (a report's `currency`), 404 for another user's object, 409 for a stale version or a conflicting change, 422 with `violations` for broken ledger rules, 500 without details.
    - `JsonConfiguration` writes every `BigDecimal` as a JSON string. `@CurrencyCode` validates ISO 4217 codes.
    - `OpenApiConfiguration`: springdoc serves the OpenAPI 3.1 description at `/api/openapi`, behind login like the rest of `/api`.
  - `ledger/api/`: the REST controllers of the ledger. Request DTOs are nested records, or classes with setters for a PATCH whose field may be set to null.
    - `/api/accounts`, `/api/categories`, `/api/counterparties`: GET, POST, PATCH `/{id}`. System accounts can't be renamed or archived (409); a category's type can't change (409).
    - `/api/entries`: GET with `from`, `to`, `accountId`, `categoryId`, `counterpartyId`, `q`, `page`, `size`; GET `/{id}`; POST; PUT `/{id}?version=`; DELETE `/{id}?version=`. The body is a domain command; `EntryCommandJson` is the Jackson mix-in that maps its `kind` to the command record.
    - `/api/reports/{balances,cash-flow,counterparty-balances,net-worth,shared-settlement,integrity}`, `/api/import` (multipart, `dryRun` defaults to true), `/api/settings` (GET, PUT).
    - `currency=BASE` on balances, net worth and cash flow: the report in the user's base currency, from a second handler on the same path (`params`), and a different response shape. Springdoc merges the two into one operation whose response is `oneOf` both.
    - `/api/rates` (GET: the latest rate per currency and the days the user's postings lack a rate), `/api/rates/manual` (GET, POST one rate, DELETE `?date=&currency=`), `/api/rates/manual/csv` (multipart `file` with the columns date,base,quote,rate; all or nothing, 422 lists the bad rows).
  - `ledger/domain/`: the ledger's rules in plain Java, with no Spring and no database.
    - One command record per entry kind: expense, income, transfer, shared expense, loan given, loan repaid, currency exchange, opening balance, and manual (raw postings). A command's `postings(LedgerContext)` builds its postings. Its constructor rejects fields the builder can't work with.
    - `ImportedCommand` carries postings an importer built, with the kind it inferred.
    - `LedgerValidator` checks an entry against every rule the triggers enforce, and throws one `InvalidEntryException` that lists every violation.
  - `ledger/`: Spring Data JDBC records and repositories for every ledger table, and the services. Owned rows carry `String userId`, the Keycloak `sub`. Every service takes the user id as a parameter and returns `*View` records, never entities.
    - `AccountService`, `CategoryService`, `CounterpartyService` and `SettingsService` serve the reference data. A counterparty's `lastCategoryId` is computed on each read.
    - `StarterLedger` gives a user without `user_settings` their settings (base currency EUR) and the generic accounts and categories of `src/main/resources/seed/starter-ledger.json`. The settings row's key serializes concurrent first requests. OPENING_BALANCE and FX_EXCHANGE are the system accounts.
    - Exceptions for the API: `NotFoundException` (404), `ConflictException` (409), `RuleViolationException` (422, one or more violations).
    - `JournalEntry` is one aggregate with its `List<Posting>`, ordered by `line_no` and guarded by `@Version`. Saving it replaces all its postings.
    - `EntryService` offers create, update, delete, get and search (paginated, newest first). It builds the entry from a command and validates it, holding the rows it refers to under FOR SHARE locks. It returns `EntryView`, never an entity.
    - `EntryService.createImported` also records the import batch and `external_ref`.
  - `ledger/report/`: `ReportService` computes every report from postings on each call, one native SQL statement per report through `JdbcClient`, into records. It takes the user id as a parameter.
    - `*InBase` convert balances, net worth and cash flow to the base currency (`Converted*` records). Each reads postings and rates in two statements inside one REPEATABLE READ read-only transaction. `ConvertedSum` adds up one figure: null if any amount lacks a rate (with `missingRates`), never added up as if it were 0, and rounded HALF_UP to 4 decimals once.
    - FX results, never stored: net worth's `unrealizedRevaluation` (ASSET and LIABILITY balances at the rate on asOf, less each posting at its own day's rate) and `realizedExchangeResult` (FX_EXCHANGE's displayed balance, each posting at its own day's rate). Both are positive for a gain. Cash flow has both per month, with the unrealized one as the change over the month. See [docs/adr/0002-exchange-rates.md](docs/adr/0002-exchange-rates.md).
    - Reports: balances per account and currency, balances per counterparty, monthly cash flow per category, net worth, the shared-account settlement, and an integrity check.
    - Amounts come back `Money.normalize`d. There are no stored balance tables (rule 13).
  - `ledger/rates/`: exchange rates, all stored as units of a currency per 1 EUR.
    - `RateBook` is plain Java: an amount on day D uses the latest rate on or before D (no age limit), EUR is 1, and cross rates go through EUR. On the same day, the user's manual rate beats the ECB's. `RateService.rateBook` loads what a report needs.
    - `EcbClient` reads the ECB's daily XML and history ZIP, which were checked on 2026-09-26; XML is parsed without DTDs. `EcbRateLoader` writes rates with source ECB and `user_id` NULL. It reads the history on the first load and whenever a weekday between the latest stored day and the daily file's day has no rates. `EcbSchedule` runs it at startup and at 16:30 and 18:30 Europe/Berlin on weekdays (`app.rates.ecb.*`). It is off in tests and in the `import` profile.
    - `RateService` also handles manual rates (source MANUAL, keyed by the user's sub): `ManualRate` checks one rate, and one side must be EUR; a rate given as EUR per unit is inverted to 8 decimals. `ManualRateCsv` reads the upload.
  - `ledger/importer/`: imports the owner's Excel ledger (see "How to run the importer" below).
    - `ImportService.run(ImportRequest)` takes the files as bytes and returns an `ImportReport`, which POST `/api/import` returns as JSON. `ImportReportMarkdown` renders the report for the command line.
    - `Workbook` reads the CSV files with Commons CSV. `EntryMapper` turns a row into an entry, in plain Java without a database.
    - The whole run is one transaction, with a savepoint per row. A dry run always rolls back. A commit rolls back if any row has an error.
    - A row's `external_ref` is `xls:` + SHA-256 of its business columns + `:` + its occurrence among identical rows. A row whose ref the user already has is skipped, so re-running is safe.
    - `ImportRunner` is the command line. It runs only with the Spring profile `import` (`application-import.yml`: no web server, so `SecurityConfig` and `CurrentUserResolver` don't load).
  - `JdbcConfiguration` takes over Spring Data JDBC setup from Boot, to register the converters for JSONB (`ledger.Json`).
- **Database** (schema `app`, Flyway, `backend/src/main/resources/db/migration/`). Add `V<n>__*.sql`; never edit an applied one.
  - `V1__users_categories_transactions.sql`, the single-entry model. Only `users` is still used:
    - `users(id BIGINT, keycloak_id UNIQUE, email, display_name)`.
    - `categories(user_id → users.id, name, type INCOME|EXPENSE)`.
    - `transactions(user_id, category_id NOT NULL, amount NUMERIC(12,2) > 0, occurred_on DATE, note)`.
  - `V2__double_entry_ledger.sql`, the ledger of ADR 0001, not applied anywhere yet:
    - `account`, `category`, `counterparty`, `journal_entry`, `posting`, `import_batch` and `user_settings` are keyed by `user_id TEXT`, the Keycloak `sub`. `exchange_rate` is shared by all users.
    - Triggers enforce the cross-row rules. At commit, every entry the transaction touched needs at least 2 postings that sum to zero per currency. A posting's entry, account, category and counterparty belong to one user, only EQUITY postings carry a category, and `requires_counterparty` accounts get a counterparty on every posting. `user_id` never changes, and an account can't change so that its postings break these rules.
  - `V3__posting_line_no.sql`, not applied anywhere yet: `posting.line_no`, a posting's position in its entry, unique per entry.
  - `V4__exchange_rate_sources.sql`, not applied anywhere yet: `exchange_rate.user_id`, NULL for the ECB's shared rates and the sub for a MANUAL one. It checks `base_currency = 'EUR'`, and `exchange_rate_key` is UNIQUE NULLS NOT DISTINCT (base, quote, date, user_id) in place of the primary key.
- **Frontend** (`frontend/`): React 19, react-router 7, Vite 8, TypeScript. No state library: pages load with `useApi` and write with `useMutation`.
  - `api.ts` calls `/api/*` with the session cookie and `X-XSRF-TOKEN`; a 401 sends the browser to `/oauth2/authorization/keycloak`. It holds the API's types. A failed call throws `ApiError`, which carries the problem's `errors` (400) and `violations` (422).
  - `money.ts`: amounts are decimal strings, as the API sends them. All arithmetic goes through big.js in strict mode, never JS numbers, rounding HALF_UP. `formatMoney` formats the string with `Intl.NumberFormat` in the currency. `toChartNumber` is the one conversion to a JS number, for bar heights only.
  - `ledger.ts`: `useLedger` loads accounts, categories, counterparties and settings. `describeEntry` reads an entry from its postings for the list, such as "Current account → Groceries".
  - `entryForm.ts` is the entry form without React. It holds one `EntryForm` state for all tabs. `formFromEntry` opens an entry in its kind's tab if its postings have that tab's shape, or else in Advanced. `validate` checks the form, `toCommand` builds the command, and `serverErrors` maps the server's messages to fields. `EntryForms.tsx` renders it; `components.tsx` holds the shared controls.
  - `dashboard.ts` is the dashboard without React: the period from the URL (`period=` a preset, or `from` and `to`; `asOf` for balances, by default the period's end or today) and the cash flow report as a pivot per currency. With `currency=base` in the URL it shows balances, net worth and cash flow in the base currency: one pivot, with exchange-result lines after the net. A figure the backend couldn't convert is the `MISSING` cell, and every sum it is part of is MISSING too. `Dashboard.tsx` calls only `/api/reports/*` and never adds amounts in different currencies itself. Converted amounts are shown with the currency's usual decimals (`formatMoney(..., { rounded: true })`), and rates older than 7 days are flagged.
  - `Rates.tsx` (`/rates`): the latest rate per currency (the user's currencies first), the days whose entries lack a rate, a form for one rate as "1 EUR = …", the CSV upload, and the user's manual rates with delete. `CashFlowTable.tsx` renders the pivot; `IncomeExpenseChart.tsx` is the Recharts bar chart, loaded lazily.
  - Routes: `/` Dashboard (period in the URL), `/entries` (filters and page in the URL), `/entries/new?tab=`, `/entries/:id`, `/accounts`, `/categories`, `/rates`, `/import`. `/transactions` redirects to `/entries`.
  - The screens never say debit or credit. Users pick a direction instead (refund, lent or got back), and only Advanced shows signed amounts.
  - nginx (prod image) and the Vite dev server proxy `/api`, `/oauth2`, `/login/oauth2` and `/logout` to the backend.
- **Auth:** shared Keycloak, realm `myapps`, client `finance-tracker`. The prod issuer is `https://auth.finance-nl.com/realms/myapps`. See `docs/auth.md`.
- **Tests:**
  - Backend: JUnit 5 with MockMvcTester, Testcontainers `postgres:17-alpine` and an in-process `FakeKeycloak` that signs RS256 tokens. The JVM runs in time zone Pacific/Kiritimati. `LedgerSchemaTests` checks the ledger's triggers with plain JDBC on a freshly migrated database.
    - `EntryBuilderTests` and `LedgerValidatorTests` are plain unit tests of `ledger/domain/`.
    - `EntryServiceTests`, `LedgerRepositoryTests` and `ReportServiceTests` run against the database. `ReportServiceTests` writes its ledger through `EntryService`.
    - Rates: `RateBookTests` and `EcbClientTests` are unit tests. `BaseCurrencyReportTests` covers a missing rate, a cross rate through EUR, revaluation of a 100.00 EUR balance, an exchange's realized result, and posting-day rates, all with the test user's own manual rates. `EcbRateLoaderTests` serves the ECB's files from a local stand-in. It deletes all ECB rates before and after, so no other test may rely on them.
    - The importer: `ExcelValuesTests`, `WorkbookTests` and `EntryMapperTests` are unit tests. `ImportServiceTests` and `ImportRunnerTests` run against the database.
    - The API: `ApiTests` (401 on every endpoint, starter data seeded once under parallel first requests, 404 across users, the OpenAPI paths) and `ledger/api/*ApiTests` use MockMvc with spring-security-test's `jwt()` (`IntegrationTest.member`). `AccessTokenTests` and `BrowserLoginTests` use real signed tokens and sessions.
    - `src/test/resources/import/` is a synthetic workbook with invented names and the real exports' formatting quirks. Its row 10 names an unknown account on purpose.
  - Frontend: Vitest (`vite.config.ts`, jsdom) with Testing Library. `money.test.ts`, `entryForm.test.ts` and `dashboard.test.ts` test the logic; `EntryForms.test.tsx` renders the form (the split's own share, the Advanced balance indicator), and `CashFlowTable.test.tsx` the cash flow table's totals, missing figures in the base currency included. `testLedger.ts` is their reference data.
  - CI (`.github/workflows/ci.yml`) runs `./mvnw -B verify` and `npm ci && npm test && npm run build`.
- **Private data:** `data/private/` holds the owner's real Excel ledger as CSV and is git-ignored. Never commit it, print whole files from it, or copy names or amounts from it into the repository.

## How to run the importer

The importer reads the owner's Excel ledger, exported as CSV: accounts, categories, transactions, and optionally opening balances. It imports them for one user.

- It is a dry run unless `--commit` is given. A dry run writes everything, reports, and rolls back.
- `--commit` saves everything, or nothing if any row has an error.
- The Markdown report goes next to the transactions file as `import-report.md`, so for `data/private/` it is `data/private/import-report.md`. `--report=<file>` writes it elsewhere.
- Exit codes: 0 means no row errors, 1 means wrong arguments or an unreadable file, and 2 means row errors (nothing saved).

Flyway migrates the target database on startup. Until the production database exists, point the importer at a throwaway PostgreSQL rather than at Supabase, where V2 and V3 have not been applied:

```bash
docker run -d --rm --name finance-tracker-import-db -e POSTGRES_PASSWORD=import -p 127.0.0.1:5433:5432 postgres:17-alpine

cd backend
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5433/postgres \
SPRING_DATASOURCE_USERNAME=postgres SPRING_DATASOURCE_PASSWORD=import \
./mvnw -q spring-boot:run -Dspring-boot.run.profiles=import -Dspring-boot.run.arguments="\
--user-sub=<Keycloak sub> \
--accounts=../data/private/BalanceSheetItems.csv \
--categories=../data/private/CashFlowItems.csv \
--transactions=../data/private/Transactions_example.csv"
# add --opening-balances=<file> (columns line,currency,amount,date) and --commit as needed

docker stop finance-tracker-import-db   # --rm deletes it with its data
```

`--user-sub` is the owner's Keycloak user id, the `sub` claim that every ledger row is keyed by (rule 11). Import with the production sub. The dev Keycloak's users have different ids. To find it:

- **Admin console:** open realm `myapps`, go to Users, open the owner's user, and copy the **ID** field. For production, that is `https://auth.finance-nl.com`, reachable only from allowlisted IPs, with OTP.
- **The app's database,** once the owner has signed in to the app: `SELECT keycloak_id FROM app.users WHERE email = '<owner email>';`. Sign-in creates this row and stores the `sub` as `keycloak_id`.

## How to run tests

Backend tests need Docker, because Testcontainers starts PostgreSQL. They don't need Keycloak.

With JDK 21 installed:

```bash
cd backend
./mvnw test                   # all tests
./mvnw test -Dtest=ApiTests   # one test class
./mvnw -B verify              # what CI runs
```

Without a JDK (for example on a machine with only a JRE), run Maven in Docker from the repository root. Add `-Dtest=ApiTests` before `-Dmaven.repo.local` to run one class:

```bash
docker run --rm --network host \
  -u "$(id -u):$(id -g)" --group-add "$(stat -c %g /var/run/docker.sock)" \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v "$HOME/.m2:/var/maven/.m2" -e HOME=/var/maven \
  -e TESTCONTAINERS_HOST_OVERRIDE=localhost -e TESTCONTAINERS_RYUK_DISABLED=true \
  -v "$PWD/backend:/app" -w /app \
  maven:3.9-eclipse-temurin-21 mvn -B test -Dmaven.repo.local=/var/maven/.m2/repository
```

Frontend tests need no backend:

```bash
cd frontend
npm ci
npm test        # vitest run
npm run build   # tsc, then vite build
```

To try the screens against a local backend, don't use `./dev.sh` until V2 and V3 are applied to Supabase: the backend's Flyway would migrate it. Run the backend on the host against a throwaway PostgreSQL instead, like the importer above, with `SERVER_PORT=8081` and the `KEYCLOAK_*` values of `.env`. Then run `npx vite` in `frontend/` and sign in at http://localhost:5173 as testuser / test1234.
