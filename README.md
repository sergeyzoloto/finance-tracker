# Finance Tracker

A personal finance web app: record income and expenses by category, and see your balance and where
the money goes this month. Spring Boot backend, React frontend, PostgreSQL on Supabase. Sign-in goes
through a shared Keycloak server.

> [!NOTE]
> **Status: early development.** The whole stack runs end to end locally against the auth server's
> dev Keycloak. It has not been deployed to production yet, and so far it has only run against a
> stand-in Postgres, not the real Supabase database. See [Status and roadmap](#status-and-roadmap).
> The backend's API and the frontend serve the double-entry ledger
> (docs/adr/0001-double-entry-ledger.md).

## Features

- **Dashboard:** net worth per currency, this month's income and expenses, and spending by category.
- **Entries:** newest first, 50 per page, filtered by period, account, category, payee or person,
  and text. Each row reads like "Current account → Groceries".
- **Entry form:** tabs for expense (optionally split with the family, showing your own share
  live), income, transfer, loan given or repaid, currency exchange, and Advanced for raw postings,
  which saves only when every currency adds up to zero. Nobody needs to know debit and credit.
- **Accounts:** by type, with balances per currency, and per person for loans; rename, archive and
  restore.
- **Categories:** income or expense, fixed once created; add, rename, archive and restore.
- **Import:** upload the Excel ledger's CSV export, check it in a dry run, then commit it if no
  row has an error.
- **Sign-in:** single sign-on through the shared Keycloak (realm `myapps`), for members only. The
  app has no passwords of its own, and no token ever reaches the browser.
- **Per-user data:** users only ever see their own ledger.

## Architecture

```
Browser ──▶ nginx (frontend container)
              ├─ /                                   React SPA, static build
              └─ /api, /oauth2, /login/oauth2, /logout
                    ──▶ Spring Boot backend ──▶ PostgreSQL on Supabase (schema "app")
                              │
                              └──▶ shared Keycloak, realm "myapps" (auth server repository)
```

Key decisions:

- **Backend-for-frontend login.** The backend is a confidential OpenID Connect client. It runs the
  authorization code flow with PKCE and keeps the tokens in its server-side session. The browser
  only gets an `HttpOnly` session cookie. Every `/api` request is still authorized by an access
  token: the session's token, or an `Authorization: Bearer` header from a service or `curl`. The
  full picture is in [docs/auth.md](docs/auth.md).
- **One origin.** nginx, or the Vite dev server, serves the SPA and proxies the API and the login
  endpoints to the backend. There is no CORS, and the frontend has no auth configuration.
- **Spring Data JDBC, not JPA.** Entities are records, the SQL is explicit, and Flyway migrations
  own the schema.
- **Its own schema, `app`.** Supabase publishes `public` through its Data API, so the app's tables
  stay out of it.
- **Exact money.** Amounts are `NUMERIC(12,2)` in the database and `BigDecimal` in Java. They must be
  positive, and more than two decimals is rejected rather than rounded.
- **Isolation in the database too.** A composite foreign key lets a transaction reference only a
  category of the same user.

## Tech stack

| Part     | Technology                                                                                           |
| -------- | ---------------------------------------------------------------------------------------------------- |
| Backend  | Java 21, Spring Boot 3.5 (Web, Security with OAuth2 client and resource server, Data JDBC, Validation), Flyway, Maven |
| Frontend | React 19, React Router 7, TypeScript 7, Vite 8                                                       |
| Serving  | nginx 1.30: static files and a reverse proxy to the backend                                          |
| Database | PostgreSQL on Supabase, through the Session Pooler. Tests use PostgreSQL 17.                         |
| Auth     | Keycloak 26: the shared auth server, realm `myapps`, client `finance-tracker`                       |
| Tests    | JUnit 5, MockMvc, Testcontainers, an in-process fake Keycloak                                        |
| Runtime  | Docker Compose                                                                                       |

## Repository layout

```
backend/                      Spring Boot app
  src/main/java/com/example/financetracker/
    api/                      what all endpoints share: problem details, JSON, OpenAPI
    ledger/                   the double-entry ledger: records, repositories, services
      api/                    its REST controllers
      domain/                 its rules, in plain Java
      report/                 reports computed from postings
      importer/               the Excel ledger importer
    security/                 login (BFF), token checks, role mapping, current user
  src/main/resources/
    application.yml
    db/migration/             Flyway migrations
    seed/starter-ledger.json  a new user's accounts and categories
  src/test/                   integration tests
frontend/                     React SPA
  src/api.ts                  fetch wrapper: CSRF header, errors, reload while the backend is down
  src/auth.ts                 login and logout redirects
  nginx.conf                  nginx config template: static files and the proxy
docs/auth.md                  authentication and authorization in depth
docs/database-hosting.md      where the database runs at launch and later, and why
change_log.mdx                every completed task: what changed, how it was verified, what is open
docker-compose.yml            production-like stack: backend and nginx
docker-compose.local.yml      local overlay: host network, next to the dev Keycloak
docker-compose.dev.yml        Vite dev server with hot reload
dev.sh                        starts the dev stack, auth server included
stop.sh                       stops it
.env.example                  every configuration variable, with comments
```

## Getting started

### Prerequisites

- **Docker with Compose v2.** The local overlay uses host networking. That works as is on Linux;
  Docker Desktop needs host networking turned on in its settings.
- **The auth server's dev stack**, from the auth server repository: Keycloak on `localhost:8080`,
  with the `finance-tracker` client in realm `myapps`.
- **A PostgreSQL database.** Production uses Supabase. For local work any PostgreSQL the backend
  can reach will do; Flyway creates the `app` schema and its tables on startup.
- **Free host ports** 3000 (nginx) and 8081 (backend), and 5173 for hot reload.

### Run the stack

1. Start the auth server: `docker compose up -d` in the auth server repository. If its realm
   predates the `finance-tracker` client, add the client as described in
   [docs/auth.md](docs/auth.md#running-against-the-dev-stack).
2. Create the config:
   ```bash
   cp .env.example .env
   ```
   Fill in `SUPABASE_JDBC_URL`, `SUPABASE_DB_USER` and `SUPABASE_DB_PASSWORD`. The Keycloak values
   already match the dev realm.
3. Start the stack:
   ```bash
   docker compose up --build
   ```
   `COMPOSE_FILE` in `.env` adds `docker-compose.local.yml`: the backend and nginx join the host
   network, so the backend reaches Keycloak at `localhost:8080`, the same address the tokens name
   as their issuer.
4. Open <http://localhost:3000> and sign in as `testuser` / `test1234`. That user needs the
   `finance-tracker` → `user` client role; without it the app shows "no access".

### Frontend with hot reload

```bash
docker compose -f docker-compose.yml -f docker-compose.local.yml -f docker-compose.dev.yml up --build
```

Then open <http://localhost:5173>. The port is fixed because it is a registered redirect URI in
Keycloak. After changing frontend dependencies, add `-V` so the container gets a fresh
`node_modules`.

`./dev.sh` does both steps in one go: it starts the auth server's dev stack, expected in
`../auth_server` (override with `AUTH_SERVER_DIR`), then runs this command in the background. Extra
arguments go to `docker compose up`, as in `./dev.sh -V`. `./stop.sh` stops all of it again.

### Calling the API from the command line

The dev realm allows the password grant for this client, so you can get a token with `curl`. See
[docs/auth.md](docs/auth.md#a-dev-token-for-curl).

## Configuration

Every variable is documented in [.env.example](.env.example). `.env` is git-ignored; keep real
secrets out of git.

| Variable                 | Purpose                                                                  | Local value                                   |
| ------------------------ | ------------------------------------------------------------------------ | --------------------------------------------- |
| `COMPOSE_FILE`           | Adds the local overlay                                                   | `docker-compose.yml:docker-compose.local.yml` |
| `SUPABASE_JDBC_URL`      | JDBC URL of the Supabase **Session Pooler** (port 5432, `sslmode=require`) | your project's                              |
| `SUPABASE_DB_USER`       | Pooler user, `postgres.<project-ref>`                                    | your project's                                |
| `SUPABASE_DB_PASSWORD`   | Database password                                                        | your project's                                |
| `KEYCLOAK_ISSUER_URL`    | Realm URL. Must equal the tokens' `iss` claim character for character.   | `http://localhost:8080/realms/myapps`         |
| `KEYCLOAK_CLIENT_ID`     | OIDC client                                                              | `finance-tracker`                             |
| `KEYCLOAK_CLIENT_SECRET` | OIDC client secret                                                       | `finance-tracker-secret` (dev realm only)     |
| `FRONTEND_PORT`          | nginx port on the host                                                   | `3000`                                        |
| `BACKEND_PORT`           | Backend port on the host, local setup only (8080 is Keycloak's)          | `8081`                                        |

About the database connection:

- Use the Session Pooler. The Transaction Pooler (port 6543) breaks the JDBC driver's server-side
  prepared statements, and the direct connection is IPv6-only, which Docker networks lack.
- The backend's connection pool is capped at 5, because the Session Pooler limits clients per user
  to the project's pool size.

## Development

### Backend tests

The tests need Docker, because Testcontainers starts PostgreSQL 17. They don't need Keycloak:
`FakeKeycloak` serves discovery, signing keys and a token endpoint, and signs real RS256 tokens. The
test JVM runs in UTC+14, so that a date shifted by a time zone conversion shows up.

With JDK 21 installed — the Maven wrapper fetches Maven 3.9.16 itself, so no local Maven is needed:

```bash
cd backend
./mvnw test
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

| Test class                   | Covers                                                             |
| ---------------------------- | ------------------------------------------------------------------ |
| `ApiTests`                   | CRUD, filters, user provisioning, users isolated from each other   |
| `DashboardSummaryTests`      | Dashboard figures: date range edges, exact cents, per-user totals |
| `AccessTokenTests`           | 401 and 403 rules for bearer tokens                                |
| `BrowserLoginTests`          | The code flow, token refresh, CSRF, logout                        |
| `KeycloakOutageTests`        | Startup and recovery while Keycloak is down                       |
| `security/RoleMappingTests`  | Which roles become authorities                                     |

### Frontend

```bash
cd frontend
npm ci
npm test         # Vitest: money, the entry form's logic, and the form components in jsdom
npm run build    # type-check (tsc), then the production build
npm run dev      # Vite on :5173, proxying to the backend at localhost:8081
```

`npm run dev` needs the backend running on the host, as in the local stack. Set
`API_PROXY_TARGET` to proxy somewhere else. There is no linter yet.

### Database migrations

Add a file `backend/src/main/resources/db/migration/V<n>__<description>.sql`. Flyway applies new
migrations to the `app` schema when the backend starts. Never edit a migration that has already
been applied; add a new one instead.

### Change log

Each completed task gets an entry at the top of [change_log.mdx](change_log.mdx): what changed,
how it was verified, and what is still open, with the commit it landed in.

## API

Every endpoint under `/api` requires a member's access token: the session cookie from the browser,
or `Authorization: Bearer <token>`. Writes from a browser session also need the CSRF token in the
`X-XSRF-TOKEN` header. Dates are ISO `YYYY-MM-DD`, and date ranges include both ends. Amounts are
decimal strings such as `"-12.50"`: debit positive, credit negative. The OpenAPI description is at
`GET /api/openapi`.

A user's first request for data gives them settings (base currency EUR) and a starter set of
accounts and categories.

| Method   | Path                                  | Description                                                                 |
| -------- | ------------------------------------- | --------------------------------------------------------------------------- |
| `GET`    | `/api/me`                             | The signed-in user's display name                                           |
| `GET`    | `/api/accounts`                       | The user's accounts, archived ones included, by code                        |
| `POST`   | `/api/accounts`                       | Create: `{"code", "name", "type", "defaultCurrency"?, "requiresCounterparty"?}` → 201 |
| `PATCH`  | `/api/accounts/{id}`                  | Rename, archive or restore, set or clear the default currency. Not for system accounts |
| `GET`    | `/api/categories`                     | The user's categories, by name                                              |
| `POST`   | `/api/categories`                     | Create: `{"code", "name", "type": "INCOME" \| "EXPENSE"}` → 201             |
| `PATCH`  | `/api/categories/{id}`                | Rename, archive or restore. The type can't change                           |
| `GET`    | `/api/counterparties`                 | The user's counterparties, each with `lastCategoryId`                       |
| `POST`   | `/api/counterparties`                 | Create: `{"name", "kind"?}` → 201                                           |
| `PATCH`  | `/api/counterparties/{id}`            | Rename, classify, archive or restore                                        |
| `GET`    | `/api/entries`                        | Entries, newest first; filters `from`, `to`, `accountId`, `categoryId`, `counterpartyId`, `q`; `page`, `size` |
| `GET`    | `/api/entries/{id}`                   | One entry with its postings                                                 |
| `POST`   | `/api/entries`                        | Create from a command whose `kind` is `EXPENSE`, `INCOME`, `TRANSFER`, `SHARED_EXPENSE`, `LOAN_GIVEN`, `LOAN_REPAID`, `CURRENCY_EXCHANGE`, `OPENING_BALANCE` or `MANUAL` → 201 |
| `PUT`    | `/api/entries/{id}?version=`          | Replace with a new command, if the entry is still at `version`              |
| `DELETE` | `/api/entries/{id}?version=`          | Delete, if the entry is still at `version` → 204                            |
| `GET`    | `/api/reports/balances?asOf=`         | Balance per account and currency                                            |
| `GET`    | `/api/reports/cash-flow?from=&to=`    | Income and expenses per month, category and currency                        |
| `GET`    | `/api/reports/counterparty-balances?accountCode=&asOf=` | Balance per counterparty of an account such as `LOANS_ASSET` |
| `GET`    | `/api/reports/net-worth?asOf=`        | Assets minus liabilities per currency                                       |
| `GET`    | `/api/reports/shared-settlement?asOf=` | What is open with the shared budget                                        |
| `GET`    | `/api/reports/integrity`              | Currencies in which the ledger doesn't add up; empty when sound             |
| `POST`   | `/api/import`                         | Multipart `accounts`, `categories`, `transactions`, `openingBalances`?; `dryRun` (default true). Returns the import report |
| `GET`    | `/api/settings`                       | Base currency, shared account, default share ratio                          |
| `PUT`    | `/api/settings`                       | Replace the settings                                                        |

`asOf` defaults to today in the server's time zone.

Errors are problem details (RFC 9457):

| Status | When                                                                                  |
| ------ | ------------------------------------------------------------------------------------- |
| 400    | A malformed request. `errors` lists each invalid field or parameter                   |
| 401    | No valid access token                                                                 |
| 403    | The token lacks the `finance-tracker` → `user` role, or a browser write has no CSRF token |
| 404    | The object doesn't exist or belongs to another user                                   |
| 409    | A stale `version`, a duplicate code or name, renaming or archiving a system account, or changing a category's type |
| 422    | The entry or settings break the ledger's rules. `violations` lists every broken rule |

## Production

Not deployed yet. The target is `https://app.finance-nl.com`, signing in through
`https://auth.finance-nl.com/realms/myapps`. At launch the database runs next to the app on the
Hetzner host, not on Supabase. The move to Supabase comes later; see
[docs/database-hosting.md](docs/database-hosting.md). It needs:

- **HTTPS**, since the session and CSRF cookies are `Secure`. The TLS proxy in front of nginx must
  send `X-Forwarded-Proto: https`, so that the backend builds `https://` redirect URIs.
- **A production `.env`:** delete the `COMPOSE_FILE` line, set the production issuer, and take the
  client secret from Keycloak (*Clients → finance-tracker → Credentials*). Keep secrets in a
  secret store, not in git.
- **One backend instance.** Sessions live in the backend's memory. A restart signs everyone out,
  usually without a password prompt, since the Keycloak session survives. More than one instance
  would need sticky sessions or a shared session store.
- **Access for users:** an administrator assigns the `finance-tracker` → `user` client role to
  each user in the production realm.

## Status and roadmap

Done: the double-entry ledger with its REST API and screens, sign-in through the shared Keycloak, and a
local stack that recovers from backend restarts and Keycloak outages. The history is in
[change_log.mdx](change_log.mdx).

Open:

- [ ] Run against the real Supabase database.
- [ ] Set up the client in the production realm and deploy.
- [x] CI: build and test on every push, in [.github/workflows/ci.yml](.github/workflows/ci.yml).
- [x] Frontend unit and component tests (Vitest).
- [ ] The scripted browser checks in the repository.
- [x] Pagination for the entry list.
- [ ] Screens to create accounts and to change the settings (shared account, default share).
- [ ] Update the user's email and display name after the first sign-in; today they are captured
      once.
- [ ] Back-channel logout. For now, a sign-out in another `myapps` app is noticed here within 5
      minutes.
- [ ] Admin features. The `admin` role exists but grants nothing yet.

Known limits of the sign-in setup are listed in [docs/auth.md](docs/auth.md#known-limits).
