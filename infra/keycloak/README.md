# Keycloak realm

`realm-export.json` is imported by Keycloak on startup (`--import-realm`). It contains no secrets:
the `${VAR}` placeholders are filled from the Keycloak container's environment, which
`docker-compose.yml` passes through from `.env`.

- **`finance-frontend`**: public client, Authorization Code flow with PKCE (S256), no secret.
  Redirects are allowed to `FRONTEND_URL` (compose) and `FRONTEND_DEV_URL` (Vite on the host).
- **Backend**: deliberately not a client. It only validates access tokens against the realm's
  JWK Set URI, so it needs no secret.
- **`google`, `github`**: identity providers, credentials from `GOOGLE_*` / `GITHUB_*`.

The import only runs while the realm doesn't exist. After the first start, edit the realm in the
admin console (e.g. *Identity providers → google* for credentials), or drop and recreate the
`keycloak` schema to re-import, which deletes all realm users.

## Manual steps: OAuth credentials

Both providers redirect back to **Keycloak**, not to the frontend. The callback URL is
`<KEYCLOAK_URL>/realms/<realm>/broker/<alias>/endpoint`. With the `.env.example` defaults:

- Google: `http://localhost:8180/realms/finance-tracker/broker/google/endpoint`
- GitHub: `http://localhost:8180/realms/finance-tracker/broker/github/endpoint`

### Google ([console.cloud.google.com](https://console.cloud.google.com))

1. Select or create a project.
2. Open *APIs & Services → OAuth consent screen* (Google Auth Platform) and set it up: app name,
   support email, audience **External**, contact email.
3. Under *Audience*, add your Google account as a test user. While the app is in *Testing*, only
   test users can sign in; use *Publish app* when real users need access.
4. *Clients → Create client*, type **Web application**. Add the Google callback URL above under
   *Authorized redirect URIs*. JavaScript origins aren't needed, because Keycloak does the code
   exchange server-side.
5. Copy the client ID and secret into `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET`. Copy the secret
   straight away, as Google may not show it again.

### GitHub ([github.com/settings/developers](https://github.com/settings/developers))

1. *OAuth Apps → New OAuth App* (a GitHub App is a different thing). For an org-owned app, use
   *Organization settings → Developer settings* instead.
2. Homepage URL: your `FRONTEND_URL`. Authorization callback URL: the GitHub callback URL above.
3. *Register application*, copy the client ID, then *Generate a new client secret* and copy it
   (it is shown only once).
4. Put them in `GITHUB_CLIENT_ID` / `GITHUB_CLIENT_SECRET`.

An OAuth App has a single callback URL, so each environment (local, production) needs its own app.
A Google client can list several redirect URIs, but separate clients per environment are cleaner.

## Reusing in another project

Rename `realm` and the client's `clientId` (also in `frontend/src/auth.ts`), update the realm name in `docker-compose.yml` (JWK Set
URI) and `JWT_ISSUER_URL`, then set `FRONTEND_URL` / `FRONTEND_DEV_URL` and register new callback
URLs with the providers.
