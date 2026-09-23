# Finance Tracker

A personal finance web app: record income and expenses by category, and see your balance and where
the money goes this month. Spring Boot backend, React frontend, PostgreSQL on Supabase. Sign-in goes
through a shared Keycloak server.

> [!NOTE]
> **Status: early development.** The whole stack runs end to end locally against the auth server's
> dev Keycloak. It has not been deployed to production yet, and so far it has only run against a
> stand-in Postgres, not the real Supabase database. See [Status and roadmap](#status-and-roadmap).

## Features

- **Dashboard:** all-time balance, this month's income and expenses, and spending by category.
- **Transactions:** add, edit and delete. Filter by date range (the current month by default) and by
  category.
- **Categories:** each one is marked income or expense. A category that still has transactions
  can't be deleted.
- **Sign-in:** single sign-on through the shared Keycloak (realm `myapps`), for members only. The
  app has no passwords of its own, and no token ever reaches the browser.
- **Per-user data:** users only ever see their own categories and transactions.

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
    category/                 categories: record, repository, REST controller
    transaction/              transactions
    dashboard/                summary endpoint (SQL aggregates)
    security/                 login (BFF), token checks, role mapping, current user
  src/main/resources/
    application.yml
    db/migration/             Flyway migrations
  src/test/                   integration tests
frontend/                     React SPA
  src/api.ts                  fetch wrapper: CSRF header, errors, reload while the backend is down
  src/auth.ts                 login and logout redirects
  nginx.conf                  nginx config template: static files and the proxy
docs/auth.md                  authentication and authorization in depth
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

With JDK 21 and Maven installed:

```bash
cd backend
mvn test
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
npm run build    # type-check (tsc), then the production build
npm run dev      # Vite on :5173, proxying to the backend at localhost:8081
```

`npm run dev` needs the backend running on the host, as in the local stack. Set
`API_PROXY_TARGET` to proxy somewhere else. There are no frontend tests or linter yet.

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
`X-XSRF-TOKEN` header. Dates are ISO `YYYY-MM-DD`, and date ranges include both ends.

| Method   | Path                                           | Description                                                            |
| -------- | ---------------------------------------------- | ---------------------------------------------------------------------- |
| `GET`    | `/api/me`                                      | The signed-in user's display name                                      |
| `GET`    | `/api/categories`                              | The user's categories, by name                                         |
| `GET`    | `/api/categories/{id}`                         | One category                                                           |
| `POST`   | `/api/categories`                              | Create: `{"name", "type": "INCOME" \| "EXPENSE"}` → 201                |
| `PUT`    | `/api/categories/{id}`                         | Update                                                                 |
| `DELETE` | `/api/categories/{id}`                         | Delete → 204                                                           |
| `GET`    | `/api/transactions?from=&to=&categoryId=`      | The user's transactions; every filter is optional                      |
| `GET`    | `/api/transactions/{id}`                       | One transaction                                                        |
| `POST`   | `/api/transactions`                            | Create: `{"categoryId", "amount", "occurredOn", "note"?}` → 201        |
| `PUT`    | `/api/transactions/{id}`                       | Update                                                                 |
| `DELETE` | `/api/transactions/{id}`                       | Delete → 204                                                           |
| `GET`    | `/api/dashboard/summary?from=&to=`             | `balance` (all-time), plus `income`, `expense` and `spendByCategory` for the range |

Error responses:

| Status | When                                                                                  |
| ------ | ------------------------------------------------------------------------------------- |
| 400    | Validation failed (for example an amount ≤ 0 or with more than two decimals), or the category doesn't belong to the user |
| 401    | No valid access token                                                                 |
| 403    | The token lacks the `finance-tracker` → `user` role, or a browser write has no CSRF token |
| 404    | The row doesn't exist or belongs to another user                                      |
| 409    | Deleting a category that still has transactions                                       |

Validation and domain errors come back as problem details (RFC 9457).

## Production

Not deployed yet. The target is `https://app.finance-nl.com`, signing in through
`https://auth.finance-nl.com/realms/myapps`. It needs:

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

Done: the domain model and REST API, the three screens, sign-in through the shared Keycloak, and a
local stack that recovers from backend restarts and Keycloak outages. The history is in
[change_log.mdx](change_log.mdx).

Open:

- [ ] Run against the real Supabase database.
- [ ] Set up the client in the production realm and deploy.
- [ ] CI: build and test on every push. There is no pipeline at the moment.
- [ ] Frontend tests, and the scripted browser checks, in the repository.
- [ ] Pagination for the transaction list.
- [ ] Update the user's email and display name after the first sign-in; today they are captured
      once.
- [ ] Back-channel logout. For now, a sign-out in another `myapps` app is noticed here within 5
      minutes.
- [ ] Admin features. The `admin` role exists but grants nothing yet.

Known limits of the sign-in setup are listed in [docs/auth.md](docs/auth.md#known-limits).
