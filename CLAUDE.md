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

State on 2026-09-25. The code still has the single-entry model and conflicts with the rules above; see [docs/adr/0001-double-entry-ledger.md](docs/adr/0001-double-entry-ledger.md). The ledger's schema (V2, V3), its domain and service layer (`ledger/`), its reports (`ledger/report/`) and the Excel importer (`ledger/importer/`) exist, but no API uses them yet.

- **Deployment:** not deployed, and no production database exists.
  - `docker-compose.yml` runs backend and nginx only, with no DB service, and reads `SUPABASE_*` from `.env`.
  - The launch plan, PostgreSQL 17 on the Hetzner host (`docs/database-hosting.md`), isn't built yet.
  - Local dev uses a Supabase project (eu-west-1, schema `app`, Flyway V1 applied 2026-09-23).
- **Backend** (`backend/`):
  - Stack: Java 21, Spring Boot 3.5, Maven wrapper, Spring Data **JDBC** (records, no JPA/Hibernate), Flyway, PostgreSQL.
  - Code lives in package `com.example.financetracker`. Every repository query is scoped by user id; another user's row returns 404.
  - `category/`: `Category(id, userId, name, type)` → `categories`. `/api/categories`: GET, GET/PUT/DELETE `/{id}`, POST. PUT renames only; a type change is 409. DELETE is 409 while the category is in use.
  - `transaction/`: `Transaction(id, userId, categoryId, amount, occurredOn, note)` → `transactions`. `/api/transactions`: GET `?from&to&categoryId`, GET/PUT/DELETE `/{id}`, POST.
  - `dashboard/`: GET `/api/dashboard/summary?from&to`. It runs SQL through `JdbcClient` and returns all-time balance plus income, expense and spendByCategory for the range.
  - `security/`: `SecurityConfig` makes the backend a BFF. It runs oauth2Login with PKCE and keeps tokens in the session.
    - Every request's JWT is checked: issuer, `aud` = finance-tracker, and client role `user` → ROLE_USER. Writes need the CSRF cookie and header.
    - `SessionAccessTokenFilter` turns the session token into a bearer token and refreshes it.
    - `CurrentUserConverter` maps the JWT `sub` to a `users` row, creating it if missing. `MeController` serves GET `/api/me`.
  - `ledger/domain/`: the ledger's rules in plain Java, with no Spring and no database.
    - One command record per entry kind: expense, income, transfer, shared expense, loan given, loan repaid, currency exchange, opening balance, and manual (raw postings). A command's `postings(LedgerContext)` builds its postings. Its constructor rejects fields the builder can't work with.
    - `ImportedCommand` carries postings an importer built, with the kind it inferred.
    - `LedgerValidator` checks an entry against every rule the triggers enforce, and throws one `InvalidEntryException` that lists every violation.
  - `ledger/`: Spring Data JDBC records and repositories for every ledger table. Owned rows carry `String userId`, the Keycloak `sub`.
    - `JournalEntry` is one aggregate with its `List<Posting>`, ordered by `line_no` and guarded by `@Version`. Saving it replaces all its postings.
    - `EntryService` offers create, update, delete and get, and takes the user id as a parameter. It builds the entry from a command and validates it, holding the rows it refers to under FOR SHARE locks. It returns `EntryView`, never an entity.
    - `EntryService.createImported` also records the import batch and `external_ref`.
  - `ledger/report/`: `ReportService` computes every report from postings on each call, one native SQL statement per report through `JdbcClient`, into records. It takes the user id as a parameter.
    - Reports: balances per account and currency, balances per counterparty, monthly cash flow per category, net worth, the shared-account settlement, and an integrity check.
    - Amounts come back `Money.normalize`d. There are no stored balance tables (rule 13).
  - `ledger/importer/`: imports the owner's Excel ledger (see "How to run the importer" below).
    - `ImportService.run(ImportRequest)` takes the files as bytes and returns an `ImportReport`, so a REST endpoint can reuse it. `ImportReportMarkdown` renders the report.
    - `Workbook` reads the CSV files with Commons CSV. `EntryMapper` turns a row into an entry, in plain Java without a database.
    - The whole run is one transaction, with a savepoint per row. A dry run always rolls back. A commit rolls back if any row has an error.
    - A row's `external_ref` is `xls:` + SHA-256 of its business columns + `:` + its occurrence among identical rows. A row whose ref the user already has is skipped, so re-running is safe.
    - `ImportRunner` is the command line. It runs only with the Spring profile `import` (`application-import.yml`: no web server, so `SecurityConfig` and `CurrentUserConverter` don't load).
  - `JdbcConfiguration` takes over Spring Data JDBC setup from Boot, to register the converters for JSONB (`ledger.Json`).
- **Database** (schema `app`, Flyway, `backend/src/main/resources/db/migration/`). Add `V<n>__*.sql`; never edit an applied one.
  - `V1__users_categories_transactions.sql`, the single-entry model the code uses today:
    - `users(id BIGINT, keycloak_id UNIQUE, email, display_name)`.
    - `categories(user_id → users.id, name, type INCOME|EXPENSE)`.
    - `transactions(user_id, category_id NOT NULL, amount NUMERIC(12,2) > 0, occurred_on DATE, note)`.
  - `V2__double_entry_ledger.sql`, the ledger of ADR 0001, not applied anywhere yet:
    - `account`, `category`, `counterparty`, `journal_entry`, `posting`, `import_batch` and `user_settings` are keyed by `user_id TEXT`, the Keycloak `sub`. `exchange_rate` is shared by all users.
    - Triggers enforce the cross-row rules. At commit, every entry the transaction touched needs at least 2 postings that sum to zero per currency. A posting's entry, account, category and counterparty belong to one user, only EQUITY postings carry a category, and `requires_counterparty` accounts get a counterparty on every posting. `user_id` never changes, and an account can't change so that its postings break these rules.
  - `V3__posting_line_no.sql`, not applied anywhere yet: `posting.line_no`, a posting's position in its entry, unique per entry.
- **Frontend** (`frontend/`): React 19, react-router 7, Vite 8, TypeScript.
  - `api.ts` calls `/api/*` with the session cookie and `X-XSRF-TOKEN`. A 401 sends the browser to `/oauth2/authorization/keycloak`.
  - Pages: `/` Dashboard, `/transactions`, `/categories`. Amounts are JS `number`.
  - nginx (prod image) and the Vite dev server proxy `/api`, `/oauth2`, `/login/oauth2` and `/logout` to the backend.
- **Auth:** shared Keycloak, realm `myapps`, client `finance-tracker`. The prod issuer is `https://auth.finance-nl.com/realms/myapps`. See `docs/auth.md`.
- **Tests:**
  - Backend: JUnit 5 with MockMvcTester, Testcontainers `postgres:17-alpine` and an in-process `FakeKeycloak` that signs RS256 tokens. The JVM runs in time zone Pacific/Kiritimati. `LedgerSchemaTests` checks the ledger's triggers with plain JDBC on a freshly migrated database.
    - `EntryBuilderTests` and `LedgerValidatorTests` are plain unit tests of `ledger/domain/`.
    - `EntryServiceTests`, `LedgerRepositoryTests` and `ReportServiceTests` run against the database. `ReportServiceTests` writes its ledger through `EntryService`.
    - The importer: `ExcelValuesTests`, `WorkbookTests` and `EntryMapperTests` are unit tests. `ImportServiceTests` and `ImportRunnerTests` run against the database.
    - `src/test/resources/import/` is a synthetic workbook with invented names and the real exports' formatting quirks. Its row 10 names an unknown account on purpose.
  - Frontend: no tests.
  - CI (`.github/workflows/ci.yml`) runs `./mvnw -B verify` and `npm ci && npm run build`.
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

The frontend has no tests. Type-checking and building it is the check:

```bash
cd frontend
npm ci
npm run build   # tsc, then vite build
```
