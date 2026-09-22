# Reusing the Keycloak setup in another project

Login in this repo is built to be lifted out. Keycloak owns the whole login flow (its own form plus
Google and GitHub), the apps never handle a password or a client secret, and the realm is one JSON
file in git with no secrets in it. Moving it to a new project means renaming two values and pointing
it at new URLs and a database.

| Piece | Where | Project-specific parts |
|---|---|---|
| Keycloak service: production mode, imports the realm on start, health check | `keycloak:` in `docker-compose.yml` | database settings |
| Realm: a public SPA client with PKCE, Google and GitHub sign-in | `infra/keycloak/realm-export.json` | realm name, client ID |
| Getting Google and GitHub credentials | `infra/keycloak/README.md` | callback URLs |
| SPA login: keycloak-js, token in memory, 401 → refresh → retry | `frontend/src/auth.ts`, `frontend/src/api.ts` | realm name, client ID |
| API: validates tokens, is not a Keycloak client, holds no secret | `backend/.../security/`, two env vars | realm name |

The examples below use a new project called `acme`, with an SPA client called `acme-web`.

## 1. Copy the files

- `infra/keycloak/realm-export.json` and `infra/keycloak/README.md`, to the same paths
- the `keycloak:` service from `docker-compose.yml`

## 2. Rename the realm and the client

In `realm-export.json`, change exactly two values:

```jsonc
"realm": "acme",              // was "finance-tracker"
"clientId": "acme-web",       // was "finance-frontend"
```

Nothing else in that file is project-specific. Redirect URIs and provider credentials are
`${VAR}` placeholders that Keycloak fills from its environment when it imports the file.

The realm name also appears in URLs elsewhere. Keep these in sync:

| Where | New value |
|---|---|
| `.env`: `JWT_ISSUER_URL` | `${KEYCLOAK_URL}/realms/acme` |
| API's JWK Set URI (`docker-compose.yml`) | `http://keycloak:8080/realms/acme/protocol/openid-connect/certs` |
| SPA config (`frontend/src/auth.ts`) | `realm: 'acme', clientId: 'acme-web'` |
| Callback URLs at Google and GitHub | `${KEYCLOAK_URL}/realms/acme/broker/google/endpoint` (and `…/github/…`) |

## 3. Give Keycloak a database

Production mode (`start`) needs a real database. In the copied service, `KC_DB_URL`,
`KC_DB_USERNAME` and `KC_DB_PASSWORD` read this repo's `SUPABASE_*` variables. Rename those to
whatever the new project uses, then pick one of these two layouts:

- **A schema in a shared Postgres** (as in this repo): keep `KC_DB_SCHEMA: keycloak`, and create the
  schema **once, before the first start**. Keycloak won't create it; it exits with
  `schema "keycloak" does not exist`.
  ```sql
  CREATE SCHEMA IF NOT EXISTS keycloak;
  ```
- **A database of its own:** delete `KC_DB_SCHEMA`, and point `KC_DB_URL` at that database, for
  example `jdbc:postgresql://keycloak-db:5432/keycloak`.

If Postgres runs in the same compose file, give it a health check and add
`depends_on: { db: { condition: service_healthy } }` to `keycloak`. Otherwise Keycloak can start
before the database is ready and exit.

If the database is Supabase, use the **Session Pooler** (port 5432). The direct connection is
IPv6-only, and the Transaction Pooler breaks JDBC prepared statements. Keep `KC_DB_POOL_MAX_SIZE`
small, because Keycloak and your app share the project's session pool size.

## 4. Set the environment

| Variable | What it is |
|---|---|
| `KEYCLOAK_PORT` | Host port for Keycloak |
| `KEYCLOAK_URL` | The URL browsers use for Keycloak. It becomes `KC_HOSTNAME`, and so the host part of every token's issuer |
| `KEYCLOAK_ADMIN`, `KEYCLOAK_ADMIN_PASSWORD` | Startup admin account for the `master` realm (admin console) |
| Database URL, user and password | From step 3 |
| `FRONTEND_URL`, `FRONTEND_DEV_URL` | Origins the SPA client may redirect to: the deployed frontend and a local dev server. Where there is no dev server, set both to the same URL |
| `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `GITHUB_CLIENT_ID`, `GITHUB_CLIENT_SECRET` | From step 5 |

For production, set `KEYCLOAK_URL=https://auth.example.com` and terminate TLS at a reverse proxy
that forwards to Keycloak's port 8080 (`KC_HTTP_ENABLED` is already `true`). Also add
`KC_PROXY_HEADERS: xforwarded` so Keycloak trusts the proxy's `X-Forwarded-*` headers. This repo has
only run on localhost, so that production setup is untested here.

## 5. Register the Google and GitHub apps

Follow `infra/keycloak/README.md`, using the new callback URLs from step 2. A GitHub OAuth App
accepts only one callback URL, so you need one app per environment.

## 6. Start Keycloak and check it

```sh
docker compose up -d keycloak
curl -s "$KEYCLOAK_URL/realms/acme/.well-known/openid-configuration" | grep -o '"issuer":"[^"]*"'
```

The `issuer` must equal `JWT_ISSUER_URL` character for character, including the scheme and the
port. The admin console is at `$KEYCLOAK_URL/admin`.

The first start builds Keycloak, creates its schema and imports the realm. That takes about 30 s
against a local database, but minutes against a remote one: about 3.5 min at 30 ms round-trip time.
That's why the health check's `start_period` is `10m`; keep it when you copy the service. Later
starts take about 30 s.

## 7. Connect the apps

**SPA** (public client, no secret), with keycloak-js:

```ts
const keycloak = new Keycloak({ url: KEYCLOAK_URL, realm: 'acme', clientId: 'acme-web' })
await keycloak.init({ onLoad: 'login-required', pkceMethod: 'S256', checkLoginIframe: false })
```

Before each API call, run `await keycloak.updateToken(30)`, then send
`Authorization: Bearer ${keycloak.token}`. On a 401, run `updateToken(-1)` and retry once.
`frontend/src/api.ts` has the full version. Tokens stay in memory; after a page reload,
keycloak-js restores them through the Keycloak session without asking for the password. The
frontend here receives `KEYCLOAK_URL` at build time as the `VITE_KEYCLOAK_URL` build argument.

**API** (a resource server, not registered in Keycloak): it checks the token's issuer against the
*public* URL, and fetches signing keys over the *internal* network.

```yaml
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI: ${JWT_ISSUER_URL}
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI: http://keycloak:8080/realms/acme/protocol/openid-connect/certs
```

It needs both URLs because every token carries the issuer browsers see (`localhost:8180`), which
the API container can't reach. The API reaches Keycloak as `keycloak:8080`. Other languages follow
the same rule: check `iss` against `JWT_ISSUER_URL`, and fetch keys from the internal JWK Set URL.
Key local data on the token's `sub`, which is Keycloak's stable user id. `CurrentUserConverter`
creates the local user row the first time a new `sub` appears.

## 8. Changing the realm later

`--import-realm` only imports a realm that doesn't exist yet. Every later start logs
`Realm 'acme' already exists. Import skipped`. So after the first start:

- **Any environment:** make the change in the admin console, and make the same change in
  `realm-export.json` so new environments get it too. This includes new Google or GitHub secrets;
  editing `.env` doesn't reach an existing realm.
- **Development only:** drop and recreate Keycloak's schema (or database) and restart, and the file
  is imported fresh. **This deletes every user in the realm.**

## Behaviour to expect

- **Same email on Google and GitHub:** the second sign-in stops at "Account already exists… Add to
  existing account". Linking the accounts makes them one user. Different emails give two separate
  users, each with their own data.
- **A provider that sends no name** (common for GitHub users without a public name): the realm
  requires first name, last name and email, so Keycloak asks for them once, on first sign-in.
- **Tokens rejected with an invalid-issuer error:** `JWT_ISSUER_URL` doesn't exactly match the
  discovery `issuer` from step 6.
- **Keycloak unhealthy on first start against a remote database:** the health check timed out
  before the schema setup finished. Raise `start_period`.
