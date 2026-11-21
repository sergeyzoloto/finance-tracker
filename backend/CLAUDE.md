# Backend

Java 21, Spring Boot 3.5, Maven wrapper, Spring Data **JDBC** (records, no JPA/Hibernate), Flyway, PostgreSQL. Package `com.example.financetracker`. The full class catalog, endpoint details and migration notes are in `docs/claude/backend-map.md`: grep it for the class or `V<n>` you need instead of reading it whole. Test coverage per area: `docs/claude/test-catalog.md`.

## Rules the architecture tests enforce

- Every query of a ledger table is scoped by the `ledger_id` of a `LedgerScope` (ADR 0003, topic C). A row of another ledger answers 404. What is about the person (the `users` row, settings, manual rates, delete-all) is keyed by the user id.
- Repositories of ledger rows extend `ledger.LedgerScopedRepository` (`save`, no lookup or delete by id alone). Callers use its default methods that take a `LedgerScope`. `JdbcClient` and `@Query` only in methods that take a `LedgerScope`, except the `PERSON_LEVEL` classes.
- `LedgerScope` is final with a non-public constructor called only by `LedgerAccess`, the one place that decides access (an ACTIVE `ledger_member` row, never `user_id`). Another user's ledger id answers like a missing one.
- Services take a `LedgerScope` (or the user id, for what is about the person) and return `*View` records, never entities.
- Nothing in `ledger..` depends on Spring Security except `ledger.api` on the `security` package. Only `FamilyPostingService` uses `CrossLedgerWriter`, which alone writes `FAMILY_*` entries, links and debt accounts.
- No decision number (`D-nnn`) in any string an API can return to a page (`ScreenTextTests`).
- Time: `Today` is the api's one "today", in the acting user's time zone (`date(userId)`, `date(LedgerScope)`; `utc()` only for work no user triggers). No `now()` without a clock or zone, no `Clock.systemDefaultZone()`, `ZoneId.systemDefault()`, `System.currentTimeMillis()` or `new Date()` (`TestHygieneTests`). Tests run in Pacific/Kiritimati: a test wanting the api's today for a user with no zone takes `utcToday()`.
- Tests read API answers through `Answers` (parsed values); never match numbers, ids or timestamps as substrings.

## Conventions

- Reports are computed from postings on every call; no stored balances (domain rule 13). Amounts come back `Money.normalize`d and `BigDecimal` is serialized as a JSON string.
- Errors are RFC 7807 problem details from `ApiExceptionHandler`: 400 `errors`, 404 another user's object, 409 stale version or conflict (`code`: `LAST_OWNER`, `ENTRIES_AFTER_RETURN`), 422 `violations` (family rules also `violationDetails`), 429 with `Retry-After`, 500 without details.
- Family endpoints exist only with the switch on (`app.family.enabled`, env `FAMILY_LEDGERS_ENABLED`, off by default, on in production); off, they answer 404 like an unknown path and no personal answer holds a family field. Family messages say "family budget", never "ledger".
- Exchange rates are stored as units of a currency per 1 EUR. `RateBook` picks the rate by D-49/D-90/D-91/D-854: ECB (at most 7 days old), CBR (at most 15), the user's manual rate (any age), most recent first.
- `JournalEntry` is one aggregate with its postings, `@Version`-guarded; saving replaces its postings.
- Auth is a BFF: oauth2Login with PKCE, tokens in the session, every JWT checked (issuer, `aud` = finance-tracker, `sub`, client role `user`). `CurrentUserResolver` and `PersonalLedgerResolver` provide `CurrentUser` and `LedgerScope` to controllers.

## Package map

`security/` BFF and session tokens · `api/` shared error handling, JSON, OpenAPI · `ledger/api/` REST controllers · `ledger/domain/` pure-Java rules (commands, validator) · `ledger/access/` scope and invites · `ledger/` repositories and services (accounts, entries, settings, starter ledger, delete-all) · `ledger/report/` reports · `ledger/family/` family ledgers, records, membership, invites, `posting/` · `ledger/history/` record versions and activity log · `ledger/rates/` ECB and CBR rates · `ledger/importer/` Excel import and export · `ledger/demo/` demo data.

## Database

Schema `app`, Flyway in `backend/src/main/resources/db/migration/`. Add `V<n>__*.sql`; never edit an applied one. Migrations are additive and written so the previous image still runs on them where possible; `deploy/rollback.sh` refuses specific rollbacks (below V7, V11, V13, V14) while data exists that older code can't read, so a migration that changes this must update that script and its tests. Triggers enforce the cross-row rules (entry sums per currency, one user's rows, writer guard `app.writer`); the per-migration notes are in `docs/claude/backend-map.md`.

## Tests

JUnit 5 with MockMvcTester, Testcontainers `postgres:17-alpine`, in-process `FakeKeycloak`. CI runs classes in random order with the run's number as seed; a failure is an isolation fault to fix, never an order to pin. The classes share one database: an assertion about a shared table counts only its own users' rows.
