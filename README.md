# Finance Tracker

A personal finance web app built on a double-entry ledger: accounts, income and expenses by category, loans and
currency exchange, in several currencies, with a family budget that members share. Spring Boot backend, React
frontend, PostgreSQL. Sign-in goes through a shared Keycloak server.

> [!NOTE]
> **Live at <https://app.finance-nl.com>** since 2026-09-28, on a Hetzner host it shares with the auth server.
> Anyone can create an account and load the demo data (see [Try the demo](#try-the-demo)). It is a one-person
> project in active development; [Status](#status) says where it stands.

## Features

- **Ledger:** every entry is a balanced double-entry journal entry ([ADR 0001](docs/adr/0001-double-entry-ledger.md)).
  The forms have tabs for expense (with a refund toggle), income, transfer, loan given or repaid, currency exchange,
  and Advanced for raw postings, which saves only when every currency adds up to zero. Nobody needs to know debit and
  credit. New entry lists similar earlier entries to copy.
- **Dashboard:** a headline strip (what you have, what you owe, net worth, the period's income and expenses), income
  and expenses compared with a year earlier and with the previous period, a chart by range, month or year, daily
  spending, cash flow by category with each category's share, balances, open loans and the rates used. A period list
  (this month, past year, previous quarter and so on, or From and To), and figures in the base currency at the ECB's
  or your own rates, or in each currency ([ADR 0002](docs/adr/0002-exchange-rates.md)).
- **Entries:** newest first, 50 per page, filtered by period, account, category, payee or person and text, with a
  summary of everything that matches (counts, dates, and per currency what came in, went out and moved).
- **Accounts and categories:** accounts by type with balances per currency and per person for loans, compared between
  two days; an account is archived only once it holds nothing. Categories are income or expense, fixed once created.
- **Family budget:** a budget shared by several users, each with their own private ledger
  ([ADR 0003](docs/adr/0003-family-budget-membership-and-cross-ledger-posting.md),
  [ADR 0004](docs/adr/0004-multi-currency-family-ledger.md)). Members record expenses, refunds and incomes in any
  currency, split equally, by percentage, by amount or to one member; each member's share and payment is posted into
  their own ledger. Balances and settle-up per currency, settlements, a journal of every change, a report by month
  and category. Invites by link, for a new member or to take over a place of a member without an account; leaving,
  removal and returning. Each member chooses whether the others see their payees.
- **Import and export:** the owner's Excel ledger, exported as one transactions file, imported with a dry run, a
  check of the ledger against the file, "Import up to" a date, and no commit while the check fails. The export writes
  the same format, so an export imported back changes nothing
  ([ADR 0005](docs/adr/0005-excel-import-and-export.md)).
- **Rates:** the ECB's daily rates, loaded by the backend, and your own manual rates, one at a time or as a CSV file.
- **Settings:** time zone (every "today" in the app is yours), date format, theme (Blue, Light or Dark), import,
  export, and Delete all my data after typing `DELETE`; the login account itself stays on the auth server.
- **Sign-in:** single sign-on through the shared Keycloak, realm `myapps`, whose pages, account console and emails
  share the app's look. The app has no passwords of its own, and no token ever reaches the browser
  ([docs/auth.md](docs/auth.md)).
- **Data isolation:** a user sees their own ledger and the family budgets they are an active member of, nothing else.
  Another user's objects answer 404.
- **Landing page and privacy policy:** `/` introduces the app to visitors who aren't signed in, also while the
  backend is down, and `/privacy` is a static page that needs neither JavaScript nor the backend.

## Try the demo

1. Open <https://app.finance-nl.com>, or a local stack (see [Getting started](#getting-started)), and choose
   **Sign in or create an account**. Keycloak's sign-in page has a **Register** link; production also offers Google
   and GitHub.
2. A new ledger starts empty. On the dashboard, press **Load demo data**: 36 months of entries in euros and US
   dollars up to today, with every kind of entry the app has: salary and rent, each raised once a year, a credit card
   paid off every month, a summer trip every year, freelance income in dollars exchanged into euros, and a loan to a
   friend who pays part of it back. With family budgets on, it also creates "Demo household", shared with an invented
   member, Sam. The dashboard then opens on the past year, each month beside the same month a year earlier.
3. Look around: the dashboard in each currency or in euros, the entries, the accounts, the family budget. Change
   anything.
4. **Settings → Delete all my data** empties the ledger again, and the demo can be loaded again.

The demo is invented from scratch in
[`DemoLedger`](backend/src/main/java/com/example/financetracker/ledger/demo/DemoLedger.java) and
[`DemoFamily`](backend/src/main/java/com/example/financetracker/ledger/demo/DemoFamily.java): no real people,
companies or brands. Loaded on the same day it is the same every time; its amounts follow the calendar, so another day
may give other amounts.

## Architecture

In production:

```text
Browser ──▶ Caddy (the auth server's, TLS) ──┬─ /api, /oauth2, /login/oauth2, /logout ──▶ finance-tracker-api
                                             │                                           (Spring Boot)
                                             │                                             │      │
                                             │                    finance-tracker-postgres ◀┘      └─▶ Keycloak, realm "myapps"
                                             │                    (PostgreSQL 17, schema "app")        (auth server)
                                             └─ everything else ──▶ finance-tracker-web
                                                                    (nginx: the built SPA, /privacy)
```

Locally, one nginx container (or the Vite dev server) serves the SPA and proxies the same paths to the backend.

Key decisions:

- **Backend-for-frontend login.** The backend is a confidential OpenID Connect client. It runs the authorization code
  flow with PKCE and keeps the tokens in its server-side session. The browser only gets an `HttpOnly` session cookie.
  Every `/api` request is still authorized by an access token: the session's, or an `Authorization: Bearer` header
  from a service or `curl` ([docs/auth.md](docs/auth.md)).
- **One origin.** Caddy in production, and nginx or Vite locally, put the SPA and the API on one origin. There is no
  CORS, and the frontend has no auth configuration.
- **A double-entry journal.** Entries and postings, debit positive and credit negative, every entry summing to zero
  per currency. Reports are computed from postings on every request; nothing derivable is stored
  ([ADR 0001](docs/adr/0001-double-entry-ledger.md)).
- **Exact money.** Amounts are `NUMERIC(19,4)` in the database and `BigDecimal` in Java, sent as decimal strings, and
  added in the browser with big.js, never with floating point.
- **Ledgers and membership.** Every ledger row has a `ledger_id`, and every query is scoped by a `LedgerScope` that
  only `LedgerAccess` makes, from an active membership: a user's personal ledger, or a family budget they belong to
  ([ADR 0003](docs/adr/0003-family-budget-membership-and-cross-ledger-posting.md), topics A to C). An architecture test
  keeps queries scoped.
- **Rules in the database too.** Triggers check at commit that each entry balances per currency, that a posting's
  account, category and counterparty belong to its ledger, that only equity postings carry a category, and that only
  the family budget's writer posts its entries.
- **Spring Data JDBC, not JPA.** Entities are records, the SQL is explicit, and Flyway migrations own the schema `app`.

## Tech stack

| Part     | Technology                                                                                                             |
| -------- | ---------------------------------------------------------------------------------------------------------------------- |
| Backend  | Java 21, Spring Boot 3.5 (Web, Security with OAuth2 client and resource server, Data JDBC, Validation, Actuator), Flyway, springdoc-openapi, Maven 3.9 (wrapper) |
| Frontend | React 19, React Router 7, TypeScript 7, Vite 8, big.js; charts in plain HTML and CSS; IBM Plex Sans and Mono             |
| Serving  | Production: nginx-unprivileged 1.30 (static files only) behind the auth server's Caddy. Locally: nginx 1.30 as a reverse proxy |
| Database | PostgreSQL 17: production `postgres:17.11` pinned by digest, local runs and tests `postgres:17-alpine`                  |
| Auth     | Keycloak 26: the shared auth server, realm `myapps`, client `finance-tracker`                                         |
| Tests    | JUnit 5, MockMvc, Testcontainers, ArchUnit, an in-process fake Keycloak; Vitest and Testing Library; Playwright end to end |
| Runtime  | Docker Compose; GitHub Actions for CI and an external monitor                                                         |

## Repository layout

```text
backend/                      Spring Boot app
  src/main/java/com/example/financetracker/
    api/                      what all endpoints share: problem details, JSON, OpenAPI
    security/                 login (BFF), token checks, role mapping, the current user
    ledger/                   records, repositories and services of the ledger
      access/                 LedgerScope and LedgerAccess: who reaches which ledger
      api/                    the REST controllers
      domain/                 the ledger's rules, in plain Java
      family/                 family budgets, their records and the posting into members' ledgers
      report/                 reports computed from postings
      rates/                  ECB and manual exchange rates
      importer/               the Excel import and the export
      demo/                   the demo ledger and family
  src/main/resources/db/migration/   Flyway migrations V1 to V17
frontend/                     React SPA; public/privacy.html is the privacy policy
e2e/                          Playwright end-to-end suite (e2e/README.md)
deploy/                       production: compose stack, Caddy site file, backup settings,
                              deploy.sh and rollback.sh with their tests, checks/ per stage, RUNBOOK.md
docs/                         ADRs, auth, database hosting, the family budget's decisions (docs/README.md)
scripts/                      check-forbidden-names.sh, rehearsal-db.sh
.github/workflows/            ci.yml (every push), ci-ubuntu-26.yml (by hand), monitor.yml (production)
change_log.mdx                every completed task: what changed, how it was verified, what is open
docker-compose*.yml           local stack: PostgreSQL, backend, nginx; dev overlay with Vite
dev.sh, stop.sh               start and stop the dev stack, auth server included
.env.example                  every local configuration variable, with comments
```

## Getting started

### Prerequisites

- **Docker with Compose v2.** The local overlay uses host networking. That works as is on Linux; Docker Desktop needs
  host networking turned on in its settings.
- **The auth server's repository** next to this one (`../auth_server`), whose dev stack runs Keycloak on
  `localhost:8080` with the `finance-tracker` client in realm `myapps`.
- **No database of your own.** `docker-compose.yml` runs PostgreSQL 17 in a container with the named volume
  `local-db` (`finance-tracker_local-db` in `docker volume ls`); Flyway creates the `app` schema on startup. Nothing
  in the local stack reaches a remote database.
- **Free host ports** 3000 (nginx), 8081 (backend), 5433 (PostgreSQL, on 127.0.0.1 only) and 5173 (Vite).

### Run the stack

```bash
cp .env.example .env    # the values already match the dev realm and the local database
./dev.sh                # the auth server's dev stack, then this stack with Vite's hot reload
```

Open <http://localhost:5173> and sign in as `testuser` / `test1234`. `./stop.sh` stops all of it. `./dev.sh` expects
the auth server in `../auth_server` (override with `AUTH_SERVER_DIR`); extra arguments go to `docker compose up`, as
in `./dev.sh -V` after changing frontend dependencies.

Without hot reload, `docker compose up --build` serves the built frontend through nginx at <http://localhost:3000>.
`COMPOSE_FILE` in `.env` adds `docker-compose.local.yml`: the backend and nginx join the host network, so the backend
reaches Keycloak at `localhost:8080`, the address the tokens name as their issuer. The local stack has family budgets
switched on.

The user needs the `finance-tracker` → `user` client role; without it the app shows "no access". See
[docs/auth.md](docs/auth.md#running-against-the-dev-stack) for the dev realm and a token for `curl`.

## Configuration

Every local variable is documented in [.env.example](.env.example). `.env` is git-ignored; keep real secrets out of
git.

| Variable                 | Purpose                                                                  | Local value                                   |
| ------------------------ | ------------------------------------------------------------------------ | --------------------------------------------- |
| `COMPOSE_FILE`           | Adds the local overlay                                                   | `docker-compose.yml:docker-compose.local.yml` |
| `LOCAL_DB_PORT`          | The local PostgreSQL's port on 127.0.0.1 (optional)                      | `5433`                                        |
| `LOCAL_DB_PASSWORD`      | The local PostgreSQL's password (optional)                               | `finance-local`                               |
| `KEYCLOAK_ISSUER_URL`    | Realm URL. Must equal the tokens' `iss` claim character for character.   | `http://localhost:8080/realms/myapps`         |
| `KEYCLOAK_CLIENT_ID`     | OIDC client                                                              | `finance-tracker`                             |
| `KEYCLOAK_CLIENT_SECRET` | OIDC client secret                                                       | `finance-tracker-secret` (dev realm only)     |
| `FRONTEND_PORT`          | nginx port on the host                                                   | `3000`                                        |
| `BACKEND_PORT`           | Backend port on the host, local setup only (8080 is Keycloak's)          | `8081`                                        |

The compose files also set `FAMILY_LEDGERS_ENABLED` (the family budget's switch, on locally, off unless set in
production) and `APP_PUBLIC_URL` (the address of the invite links). `ECB_RATES_ENABLED=false` stops the backend from
downloading the ECB's rates. Production's `deploy/app/.env` is written on the server by [the runbook's step 5](deploy/RUNBOOK.md#5-write-the-secrets).

## Development

### Backend tests

The tests need Docker, because Testcontainers starts PostgreSQL. They don't need Keycloak: `FakeKeycloak` serves
discovery, signing keys and a token endpoint, and signs real RS256 tokens. The test JVM runs in Pacific/Kiritimati
(UTC+14), so that a date shifted by a time zone conversion shows up.

```bash
cd backend
./mvnw test                   # all tests; the wrapper fetches Maven itself
./mvnw test -Dtest=ApiTests   # one class
./mvnw -B clean verify        # what CI runs (CI also shuffles the classes' order)
```

Without a local JDK, run Maven in Docker (Linux):

```bash
docker run --rm --network host \
  -u "$(id -u):$(id -g)" --group-add "$(stat -c %g /var/run/docker.sock)" \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v "$HOME/.m2:/var/maven/.m2" -e HOME=/var/maven \
  -e TESTCONTAINERS_HOST_OVERRIDE=localhost -e TESTCONTAINERS_RYUK_DISABLED=true \
  -v "$PWD/backend:/app" -w /app \
  maven:3.9-eclipse-temurin-21 mvn -B test -Dmaven.repo.local=/var/maven/.m2/repository
```

The tests are grouped by package under `backend/src/test/java/com/example/financetracker/`: `*ApiTests` drive the
API through MockMvc with signed tokens; `DataIsolationApiTests` checks that two users, and the members of different
family budgets, reach nothing of each other's through any endpoint; `ArchitectureTests` and `TestHygieneTests` are
ArchUnit rules; `LedgerSchemaTests` and the migration tests check the triggers and every migration on a real database;
the `*RandomTests` run seeded random histories of family budgets against their invariants.

### Frontend

```bash
cd frontend
npm ci
npm test         # Vitest with Testing Library in jsdom
npm run build    # type-check (tsc), then the production build
npm run dev      # Vite on :5173, proxying to the backend at localhost:8081
```

There is no linter. A change to `package.json` or the lock file is checked with `npm ci` from a clean copy in CI's
Node, as [CLAUDE.md](CLAUDE.md#testing) describes.

### End-to-end suite

`cd e2e && npm run e2e:local` runs the Playwright suite against `./dev.sh`'s stack, as the dev users `testuser` and
`testuser2`; `npm run test:guards` tests the suite's own guards. Production runs are the owner's only
([e2e/README.md](e2e/README.md)).

### Database migrations

Add a file `backend/src/main/resources/db/migration/V<n>__<description>.sql`. Flyway applies new migrations to the
`app` schema when the backend starts. Never edit a migration that has already been applied; add a new one instead.

### Change log

Each completed task gets an entry at the top of [change_log.mdx](change_log.mdx): what changed, how it was verified,
and what is still open, with the commit it landed in.

## API

Every endpoint under `/api` requires an access token with the `finance-tracker` → `user` role: the session cookie
from the browser, or `Authorization: Bearer <token>`. Writes from a browser session also need the CSRF token in the
`X-XSRF-TOKEN` header. Dates are ISO `YYYY-MM-DD`, and date ranges include both ends. Amounts are decimal strings such
as `"-12.50"`: debit positive, credit negative. The OpenAPI description, with every parameter and answer, is at
`GET /api/openapi`.

| Paths | What |
| --- | --- |
| `/api/me`, `/api/me/data`, `/api/me/family-memberships` | Who is signed in (name, email, time zone, today, date format, features, the account console's address); delete all my data; what that does to my family budgets |
| `/api/accounts`, `/api/categories`, `/api/counterparties` | Reference data: list, create, rename, archive, restore |
| `/api/entries` | Entries: list with filters and a summary, one entry, create from a command, replace, delete (`?version=`); similar entries; the family payees shown to me; a family payment's own fields |
| `/api/reports/…` | Balances, cash flow by month or by day, counterparty balances, net worth, the shared-account settlement, the integrity check; `currency=BASE` for the base currency |
| `/api/rates`, `/api/settings` | Rates (ECB and manual, CSV upload); base currency and shared account, time zone, date format |
| `/api/import`, `/api/export/…`, `/api/demo-data` | The Excel import (dry run by default), the export, the demo |
| `/api/family-ledgers/…`, `/api/invites/…` | Family budgets (only while switched on): members, split rule, categories, records and settlements, balances, journal, report, invites |

A user's first request gives them settings (base currency EUR), a personal ledger and a starter set of accounts and
categories. Errors are RFC 7807 problem details:

| Status | When                                                                                  |
| ------ | ------------------------------------------------------------------------------------- |
| 400    | A malformed request. `errors` lists each invalid field or parameter                   |
| 401    | No valid access token                                                                 |
| 403    | The token lacks the `finance-tracker` → `user` role, or a browser write has no CSRF token |
| 404    | The object doesn't exist, or belongs to a ledger the user isn't an active member of   |
| 409    | A stale `version`, a duplicate, a change the rules forbid (a system account, an account that still holds money, an owner's action by a member) |
| 422    | The entry or record breaks the ledger's rules. `violations` lists every broken rule   |
| 429    | Too many invite attempts; `Retry-After` says when to try again                         |

## Production

<https://app.finance-nl.com> runs on the Hetzner host of the auth server, signing in through
`https://auth.finance-nl.com/realms/myapps`. [deploy/RUNBOOK.md](deploy/RUNBOOK.md) is the procedure; its
"Production now" section says which commit runs.

- **The stack,** [deploy/app/docker-compose.yml](deploy/app/docker-compose.yml): `finance-tracker-postgres`
  (PostgreSQL 17, never shared with Keycloak's, on the stack's own network only), `finance-tracker-api` and
  `finance-tracker-web` (an unprivileged nginx serving the built frontend and `/privacy`, so both show while the
  backend is down), each with a memory limit and a read-only root for api and web. Secrets stay in `deploy/app/.env`
  on the server.
- **Routing:** the auth server's Caddy serves both sites over the shared Docker network `edge`.
  [deploy/finance.caddy](deploy/finance.caddy) sends `/api`, `/oauth2`, `/login/oauth2` and `/logout` to the api and
  everything else to web, with HSTS and a content security policy.
- **Deploys:** `deploy/deploy.sh run <commit> <stage>` checks CI, takes a backup and tests its restore, merges,
  builds, starts, checks health, pages and the database, and backs up again; `deploy/rollback.sh` goes one step back.
  Both are tested in `deploy/tests/` ([RUNBOOK: Deploying with deploy.sh](deploy/RUNBOOK.md#deploying-with-deploysh),
  [Roll back with rollback.sh](deploy/RUNBOOK.md#roll-back-with-rollbacksh)). After each deploy the owner runs the
  end-to-end suite against production.
- **Backups:** a nightly `pg_dump` with a restore test, kept 14 days on the host and pulled to the owner's laptop
  ([deploy/pg-backup/finance.conf](deploy/pg-backup/finance.conf), [RUNBOOK: Backups](deploy/RUNBOOK.md#9-backups)),
  and Hetzner's daily backups of the whole server, which are not database backups
  ([RUNBOOK](deploy/RUNBOOK.md#hetzners-backups-of-the-server)). Where the database runs and why:
  [docs/database-hosting.md](docs/database-hosting.md).
- **Monitoring and logs:** [.github/workflows/monitor.yml](.github/workflows/monitor.yml) checks the site, the api,
  the realm and both certificates from outside on a schedule, and every container logs to the host's journal for 14
  days ([RUNBOOK: Monitoring](deploy/RUNBOOK.md#monitoring), [Logs](deploy/RUNBOOK.md#logs)).

What production relies on:

- **HTTPS**, since the session and CSRF cookies are `Secure`. Caddy sends `X-Forwarded-Proto: https`, so the backend
  builds `https://` redirect URIs.
- **One backend instance.** Sessions live in the backend's memory. A restart signs everyone out, usually without a
  password prompt, since the Keycloak session survives.
- **Access for users:** anyone can sign up in the production realm, or sign in with Google or GitHub, and every user
  has the `finance-tracker` → `user` role ([docs/auth.md](docs/auth.md)).

## Status

The ledger, its reports and dashboard, the family budget (switched on in production since 2026-10-03), the import and
export, and the deploy, backup and monitoring around them are in production. Each stage is recorded in
[change_log.mdx](change_log.mdx), and every decision in
[docs/family-budget/requirements.md](docs/family-budget/requirements.md), whose section
[Later](docs/family-budget/requirements.md#later) is the one list of open work. Known limits of the sign-in setup are
in [docs/auth.md](docs/auth.md#known-limits).
