# Production runbook

Finance Tracker runs at <https://app.finance-nl.com> on the Hetzner server that runs the auth
server (Keycloak at <https://auth.finance-nl.com>). This runbook sets it up once (steps 1–10),
then covers updates, rollback, restores and regular checks. Do the steps in order. Every step says
what you should see; if you see something else, stop and look it up under
[Troubleshooting](#troubleshooting).

The auth server's rules for projects behind its Caddy apply throughout (its `PRODUCTION.md`,
"Hosting another project behind this Caddy"):

- Nothing of this project goes into `/opt/auth`: no site block in its Caddyfile, no compose file,
  no volume.
- The site file goes live only with `caddy-site install`, and leaves only with `caddy-site remove`.
- Only the containers Caddy proxies to join the Docker network `edge`, under names that start with
  `finance-`. The database never joins it.

## How to read this

- Every code block starts with a comment saying where it runs: `# On the laptop` or
  `# On the server`. Paste one block at a time, and wait for it to finish.
- "On the server" means a root shell on the server. To open one, **on the laptop**:

  ```bash
  # On the laptop
  ssh root@2.28.108.199
  ```

- "On the laptop" means a shell in `~/dev/finance-tracker`, unless the block changes directory.
- No block needs editing. Where a value must be looked up, the block prints it or asks for it.
- `docker compose exec -T` passes its input on to the container, so without `</dev/null` it can
  swallow the commands that follow it in the same block, and they never run. That happened in
  step 6 on 2026-09-28, with the block fed to `bash` through SSH. Every such line here whose
  input isn't a file or a pipe ends with `</dev/null`, and so does `pg-restore-test` wherever
  another command follows it, since it runs `docker compose exec -T` itself.
- **Secrets never appear on screen, in the shell history or in a chat.** You paste them at a
  `read -rs` prompt, which shows nothing, and `printf`, a shell builtin, writes them to the file, so
  they don't show up in the process list either. Paste a secret only at such a prompt.

## What runs where

```text
Internet ──:443──▶ caddy                                    stack /opt/auth (the auth server)
                    ├─ auth.finance-nl.com ──▶ auth-keycloak ──▶ postgres (Keycloak's own)
                    └─ app.finance-nl.com                   /opt/caddy-sites/finance.caddy
                         ├─ /api, /api/*, /oauth2/*, /login/oauth2/*, /logout
                         │     ──edge──▶ finance-tracker-api ──▶ finance-tracker-postgres
                         └─ every other path
                               ──edge──▶ finance-tracker-web (nginx, the built frontend)

finance-tracker-api ──▶ https://auth.finance-nl.com (through the server's public address)
                    ──▶ https://www.ecb.europa.eu (exchange rates)
```

| What | Where |
| --- | --- |
| The auth server: Keycloak, its PostgreSQL, Caddy, `caddy-site`, `pg-backup` | `/opt/auth` and `/usr/local/sbin` on the server; `~/dev/auth_server` on the laptop |
| Finance Tracker's code | `/opt/finance-tracker` on the server, a clone of <https://github.com/sergeyzoloto/finance-tracker> |
| Its stack, compose project `finance-tracker-prod` | `/opt/finance-tracker/deploy/app`: services `postgres`, `api` and `web`, containers `finance-tracker-postgres`, `finance-tracker-api` and `finance-tracker-web` |
| Its secrets | `/opt/finance-tracker/deploy/app/.env` (root, mode 600), on the server only |
| The database | Docker volume `finance-tracker-prod_postgres-data`: database `finance`, owned by the login `finance`, which is no superuser |
| The site file | `/opt/caddy-sites/finance.caddy`, installed from `/root/finance.caddy`, a copy of `deploy/finance.caddy` |
| The network between Caddy and the app | `edge`: created by the auth stack, owned by no stack. On it: Caddy, `finance-tracker-api` and `finance-tracker-web` |
| Backups | `/var/backups/pg/finance/` on the server (14 days), pulled daily to `~/backups/finance-nl-server/finance/` on the laptop (60 days) |
| Backup settings | `/etc/pg-backup/finance.conf`, from `deploy/pg-backup/finance.conf`; timer `pg-backup@finance.timer` |

Docker Compose also gives each container its service name on every network it joins, so `api` and
`web` are names on `edge` too. The site file uses only the `finance-` names, as the auth server's
rules require.

## Routing

[`deploy/finance.caddy`](finance.caddy), checked on the laptop with `deploy/check-site.sh`:

| Path | Goes to | What it is |
| --- | --- | --- |
| `/api`, `/api/*` | `finance-tracker-api:8080` | The REST API, with `/api/me` and `/api/openapi`. Uploads (POST `/api/import`) up to 20 MiB. |
| `/oauth2/*` | `finance-tracker-api:8080` | The login's start: `/oauth2/authorization/keycloak` redirects to Keycloak, with PKCE S256. |
| `/login/oauth2/*` | `finance-tracker-api:8080` | Keycloak's callback, `/login/oauth2/code/keycloak`. |
| `/logout` | `finance-tracker-api:8080` | The logout form's POST; the backend then redirects to Keycloak's end-session endpoint. |
| `/assets/*` | `finance-tracker-web:8080` | The build's hashed files: `Cache-Control: public, max-age=31536000, immutable`. A missing file is a 404. |
| every other path | `finance-tracker-web:8080` | The single-page app: the file if it exists, else `index.html` (deep links such as `/entries/42`), with `Cache-Control: no-cache`. |

- Every response gets the app's security headers from Caddy: `Strict-Transport-Security`,
  `Content-Security-Policy`, `X-Content-Type-Options` and `Referrer-Policy`. `Server` and `Via` are
  removed. Caddy compresses with zstd or gzip.
- The backend's `/actuator/health` is for the container's health check only. From outside,
  `/actuator/health` is just another path of the app and gets `index.html`.

## Memory budget

The server has 3.7 GiB of RAM (`free -h`) and 2 GB of swap. Only the app's containers get a limit
from this repository; Keycloak's limit, and whether the auth stack's other containers get one,
belong to the auth repository.

| Container | Stack | Limit | Measured on the server, 2026-09-28 |
| --- | --- | ---: | ---: |
| `keycloak` (`auth-keycloak`) | `/opt/auth` | 1280 MiB, set in the auth repo; heap up to 70%, 896 MiB | 559 MiB |
| `postgres` (Keycloak's) | `/opt/auth` | none | 59 MiB |
| `caddy` | `/opt/auth` | none | 16 MiB |
| `finance-tracker-api` | `finance-tracker-prod` | 768 MiB; heap up to 60%, 460 MiB | 351 MiB |
| `finance-tracker-postgres` | `finance-tracker-prod` | 384 MiB | 77 MiB |
| `finance-tracker-web` | `finance-tracker-prod` | 64 MiB | 5 MiB |
| **Always running** | | **2496 MiB of limits, plus the two without one** | **1066 MiB** |

The measurements are `docker stats` about an hour after the first deploy, after the ECB's history
was loaded, two users signed in and the demo was loaded and deleted once.

- **With every limit reached**, the containers take about 2.5 GiB, and about 1.1 GiB is left for
  the system, Docker and the page cache. After the first deploy, `free -h` showed 1.7 GiB used and
  2.0 GiB available, and 64 KiB of swap in use.
- **Building the images on the server** needs more for a while: Maven's heap is at most 512 MiB
  (`backend/Dockerfile`), and Vite's build is smaller. On 2026-09-28 the used memory rose from
  1155 MiB to at most 1515 MiB (sampled every 5 seconds), and no swap was used. The runbook builds
  one image after the other, and the swap covers a peak.
- **The restore test** starts a throwaway PostgreSQL container without a limit for a minute, and
  the nightly `pg_dump` runs inside `finance-tracker-postgres`, within its 384 MiB.
- Tmpfs files count toward a container's limit: the api's `/tmp` (at most 64 MiB, for uploads) and
  the web container's (8 MiB).

## Before you start

- On the laptop, all changes are committed. Step 4 pushes them.
- The laptop's address is on the auth server's admin allowlist, and you have its OTP at hand, for
  the admin console in step 5.
- You can sign in to Squarespace, where finance-nl.com's DNS is, for step 2.

---

## 1. Check the server

```bash
# On the server
free -h
df -h /
docker --version && docker compose version
cd /opt/auth && docker compose ps --format 'table {{.Service}}\t{{.Status}}'
docker network inspect edge -f 'edge: {{range .Containers}}{{.Name}} {{end}}'
ls -ld /opt/caddy-sites && ls /opt/caddy-sites
command -v caddy-site pg-backup pg-restore-test
systemctl list-timers --no-pager 'pg-backup@*'
ls /opt/auth | grep -E 'compose' ; test -e /opt/finance-tracker && echo "STOP: /opt/finance-tracker exists"
```

You should see:

- `free -h`: about `3.7Gi` in the `Mem:` row and `2.0Gi` in the `Swap:` row.
- `df -h /`: at least `10G` under `Avail`.
- Docker 27 or newer, and Docker Compose v2 or newer.
- `postgres`, `keycloak` and `caddy`, each `Up … (healthy)` (Caddy has no health check: `Up`).
- `edge: auth-caddy-1`, and nothing else from other projects.
- `/opt/caddy-sites` owned by root with `drwxr-xr-x`, and no `finance.caddy` in it.
- `/usr/local/sbin/caddy-site`, `/usr/local/sbin/pg-backup` and `/usr/local/sbin/pg-restore-test`.
- `pg-backup@auth.timer` with its next run.
- Only `docker-compose.yml` in `/opt/auth`, and no `STOP` line.

If `caddy-site` or `edge` is missing, the auth server's D1.1 deploy hasn't reached the server:
**on the laptop**, `cd ~/dev/auth_server && deploy/sync.sh`. If `pg-backup` is missing: **on the
laptop**, `cd ~/dev/auth_server && deploy/backup/install.sh server`.

## 2. DNS: point app.finance-nl.com at the server

```bash
# On the laptop
dig +short auth.finance-nl.com
dig +short app.finance-nl.com
```

You should see `2.28.108.199`, then nothing.

In the browser, sign in to Squarespace and open
**Domains → finance-nl.com → DNS → DNS Settings**. Under **Custom records**, add a record:

| Host | Type | TTL | Data |
| --- | --- | --- | --- |
| `app` | `A` | leave the default | `2.28.108.199` |

Save. Leave every other record as it is. No AAAA record: auth.finance-nl.com has none either, and
the auth server's Caddy network is IPv4-only.

```bash
# On the laptop
dig +short app.finance-nl.com @1.1.1.1
dig +short app.finance-nl.com @8.8.8.8
```

Both should print `2.28.108.199`. That usually takes minutes, sometimes a few hours. Steps 3 to 6
don't need it; step 7 does, because Caddy asks Let's Encrypt for the certificate as soon as the
site is installed.

## 3. Keycloak: check the client finance-tracker

The client `finance-tracker`, its role `user` and its audience mapper exist since the auth
server's stage 5 (2026-09-27). This step only reads them; it changes nothing. The reference for
every value is the auth server's `PRODUCTION.md`, "Client `finance-tracker`"; if something
differs, fix it there, with the auth repository, not here.

Sign in with the auth server's admin CLI (its `PRODUCTION.md`, "Admin CLI"). The commands never read
the client's secret: every read passes `--fields`.

```bash
# On the server
kc() { docker compose -f /opt/auth/docker-compose.yml exec -T keycloak /opt/keycloak/bin/kcadm.sh "$@" --config /tmp/kcadm.config </dev/null; }
kc config credentials --server http://localhost:8080 --realm myapps --client automation-cli --secret "$(cat /root/automation-cli.secret)"
cid=$(kc get clients -r myapps -q clientId=finance-tracker --fields id --format csv --noquotes); echo "$cid"
```

You should see a line about logging in, then the client's id, 36 characters with dashes. Keep this
shell open for 3.1 to 3.3: they use `kc` and `cid`.

**3.1 The client's settings.**

```bash
# On the server, in the shell of step 3
kc get clients/$cid -r myapps --fields 'clientId,enabled,publicClient,clientAuthenticatorType,standardFlowEnabled,implicitFlowEnabled,directAccessGrantsEnabled,serviceAccountsEnabled,consentRequired,fullScopeAllowed,frontchannelLogout,baseUrl,redirectUris,webOrigins,attributes(*)'
```

You should see:

| Field | Value |
| --- | --- |
| `enabled` | `true` |
| `publicClient` | `false`, with `clientAuthenticatorType` `client-secret` |
| `standardFlowEnabled` | `true` |
| `implicitFlowEnabled`, `directAccessGrantsEnabled`, `serviceAccountsEnabled`, `consentRequired` | `false` |
| `fullScopeAllowed`, `frontchannelLogout` | `false` |
| `baseUrl` | `https://app.finance-nl.com/` |
| `redirectUris` | only `https://app.finance-nl.com/login/oauth2/code/keycloak` |
| `webOrigins` | empty |
| `attributes` | `pkce.code.challenge.method` `S256`, `post.logout.redirect.uris` `https://app.finance-nl.com/` |

Then check what Keycloak's authorization endpoint accepts. The challenge is the S256 example of
RFC 7636:

```bash
# On the laptop
auth=https://auth.finance-nl.com/realms/myapps/protocol/openid-connect/auth
q='client_id=finance-tracker&response_type=code&scope=openid&state=test&nonce=test'
ok='redirect_uri=https%3A%2F%2Fapp.finance-nl.com%2Flogin%2Foauth2%2Fcode%2Fkeycloak'
bad='redirect_uri=https%3A%2F%2Fevil.example%2Flogin%2Foauth2%2Fcode%2Fkeycloak'
pkce='code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM&code_challenge_method=S256'
curl -s -o /dev/null -w '%{http_code}\n' "$auth?$q&$ok&$pkce"
curl -s -o /dev/null -w '%{http_code}\n' "$auth?$q&$bad&$pkce"
```

You should see `200` (the login page) and `400` (`Invalid parameter: redirect_uri`). The second
request leaves a `LOGIN_ERROR` event in the realm; that is expected.

**3.2 The role `user`, a default role.** Every user gets it, so nobody assigns it by hand.

```bash
# On the server, in the shell of step 3
kc get clients/$cid/roles -r myapps --fields name
kc get roles/default-roles-myapps/composites/clients/$cid -r myapps --fields name
```

You should see `"name" : "user"` twice: the client has the role, and the realm's default role
`default-roles-myapps` includes it.

**3.3 The audience mapper, and a token as the app will get it.**

```bash
# On the server, in the shell of step 3
kc get clients/$cid/protocol-mappers/models -r myapps --fields 'name,protocolMapper,config(*)'
kc get clients/$cid/evaluate-scopes/generate-example-access-token -r myapps -q 'scope=openid profile email' -q userId=7df2283a-b1c7-4959-a9ac-e88f71859ba6 | python3 -c 'import json, sys; t = json.load(sys.stdin); print(json.dumps({k: t.get(k) for k in ("iss", "aud", "azp", "resource_access")}, indent=1)); print("sub:", "present" if t.get("sub") else "MISSING")'
docker compose -f /opt/auth/docker-compose.yml exec -T keycloak rm -f /tmp/kcadm.config </dev/null
```

The second command prints only the claims the backend checks, not the user's name or email.
You should see:

- the mapper `finance-tracker audience`, type `oidc-audience-mapper`, with
  `included.client.audience` `finance-tracker`, `access.token.claim` `true` and
  `id.token.claim` `false`;
- `"iss": "https://auth.finance-nl.com/realms/myapps"`, `"aud": "finance-tracker"`,
  `"azp": "finance-tracker"`, `"resource_access": {"finance-tracker": {"roles": ["user"]}}`, and
  `sub: present`.

The last line deletes kcadm's session. Close the shell, or run `unset -f kc; unset cid`.

## 4. Put the code on the server

Push the commit that contains this runbook, and wait until CI is green
(<https://github.com/sergeyzoloto/finance-tracker/actions>):

```bash
# On the laptop
git push
git log -1 --oneline
```

```bash
# On the server
git clone https://github.com/sergeyzoloto/finance-tracker.git /opt/finance-tracker
git -C /opt/finance-tracker log -1 --oneline
ls /opt/finance-tracker/deploy
```

The commit should be the one the laptop printed, and `deploy` should list
`app  check-site.sh  finance.caddy  pg-backup  RUNBOOK.md`.

## 5. Write the secrets

Two database passwords, generated on the server and never shown:

```bash
# On the server
cd /opt/finance-tracker/deploy/app
test -e .env && echo "STOP: .env exists already. Keep it and go on with the next block." || (umask 077 && printf 'POSTGRES_PASSWORD=%s\nAPP_DB_PASSWORD=%s\n' "$(openssl rand -hex 24)" "$(openssl rand -hex 24)" > .env && echo "Database passwords written.")
```

You should see `Database passwords written.`

The client secret comes from the admin console. In the browser, open
<https://auth.finance-nl.com/admin/> and sign in as your admin (realm `master`, with OTP). Choose
the realm **myapps**, then **Clients → finance-tracker → Credentials**, and click the copy icon
next to **Client Secret**. Don't paste it anywhere but at the prompt below. Run this, paste the
secret when it asks (nothing is shown), and press Enter:

```bash
# On the server
cd /opt/finance-tracker/deploy/app
IFS= read -rs -p 'Client secret of finance-tracker: ' secret; echo
sed -i '/^KEYCLOAK_CLIENT_SECRET=/d' .env && printf 'KEYCLOAK_CLIENT_SECRET=%s\n' "$secret" >> .env; unset secret
```

Check it without printing the secrets:

```bash
# On the server
cd /opt/finance-tracker/deploy/app
ls -l .env
awk -F= '{ print $1, length($2), "characters" }' .env
```

You should see `-rw------- 1 root root`, then `POSTGRES_PASSWORD 48 characters`,
`APP_DB_PASSWORD 48 characters` and `KEYCLOAK_CLIENT_SECRET 86 characters`: Keycloak 26 generates
client secrets of 86 letters and digits, 512 bits. Afterwards, copy something harmless on the
laptop, so the secret leaves its clipboard.

Then let Keycloak check the secret. The block asks its token endpoint for a service-account token,
once with the secret from `.env` and once with a wrong one. The secret reaches curl through a pipe,
so it shows up neither on the screen nor in the process list, and only the error is printed:

```bash
# On the server
cd /opt/finance-tracker/deploy/app
token=https://auth.finance-nl.com/realms/myapps/protocol/openid-connect/token
check() { curl -s -w '\n%{http_code}' "$token" -d grant_type=client_credentials -d client_id=finance-tracker --data-urlencode client_secret@- | python3 -c 'import json, sys; *body, code = sys.stdin.read().split("\n"); r = json.loads("\n".join(body) or "{}"); print(code, r.get("error"), "-", r.get("error_description"))'; }
sed -n 's/^KEYCLOAK_CLIENT_SECRET=//p' .env | tr -d '\n' | check
printf '%s' wrong-secret | check
unset -f check; unset token
```

You should see:

- `401 unauthorized_client - Client not enabled to retrieve service account`: Keycloak accepted the
  secret, then refused the grant, since the client has no service account.
- `401 unauthorized_client - Invalid client or Invalid client credentials`: what a wrong secret
  gets.

Keycloak 26 answers both with 401 and the same `error`; only the description tells them apart. If
the first line reads like the second, the secret in `.env` is wrong: run the `read -rs` block
above again. Each check leaves a `CLIENT_LOGIN_ERROR` in Keycloak's log, which is expected: with
`error="invalid_client"` and the reason `Client not enabled to retrieve service account` for the
right secret, and with `error="invalid_client_credentials"` for the wrong one.

Nothing in `.env` has to be kept anywhere else. A restore creates the database logins anew from
`.env`, and the client secret can be copied from the admin console again, or regenerated there.

## 6. Build and start the app

```bash
# On the server
cd /opt/finance-tracker/deploy/app
docker compose build api && docker compose build web
```

Maven and npm download everything once. On 2026-09-28 the first build took 51 seconds for both
images, Maven's `package` 25 of them; allow a few minutes on a slower day. One image after the
other, so the two builds never need memory at the same time. It should end with
`Image finance-tracker-api Built`, then `Image finance-tracker-web Built`.

```bash
# On the server
cd /opt/finance-tracker/deploy/app
docker compose up -d
for i in $(seq 60); do s=$(docker inspect -f '{{.State.Health.Status}}' finance-tracker-api); [ "$s" = healthy ] && break; sleep 5; done; echo "api: $s"
docker compose ps --format 'table {{.Name}}\t{{.Status}}'
docker compose logs api | grep -E 'Successfully applied|Started FinanceTrackerApplication|ECB rates'
```

You should see:

- `api: healthy`, within about a minute.
- `finance-tracker-postgres`, `finance-tracker-api` and `finance-tracker-web`, each
  `Up … (healthy)`.
- `Successfully applied 4 migrations to schema "app", now at version v4`,
  `Started FinanceTrackerApplication`, and
  `Loaded … ECB rates for … day(s) … from the history`.

Check the networks and the way to Keycloak:

```bash
# On the server
docker network inspect edge -f 'edge: {{range .Containers}}{{.Name}} {{end}}'
cd /opt/auth && for n in finance-tracker-api finance-tracker-web finance-tracker-postgres; do echo "$n: $(docker compose exec -T caddy getent ahostsv4 $n </dev/null | awk '{print $1}' | sort -u | tr '\n' ' ')"; done
docker exec finance-tracker-api curl -s -o /dev/null -w '%{http_code} %{remote_ip}\n' https://auth.finance-nl.com/realms/myapps
```

You should see:

- `edge:` with `auth-caddy-1`, `finance-tracker-api` and `finance-tracker-web`, in any order, and
  not `finance-tracker-postgres`.
- One address after `finance-tracker-api:` and one after `finance-tracker-web:`; nothing after
  `finance-tracker-postgres:`, since the database is not on `edge`. Two addresses for a name mean
  another container uses it: stop, and find it with the `docker network inspect` line.
- `200 2.28.108.199`: the api reaches Keycloak through the server's public address.

The app isn't reachable from outside yet; step 7 connects it.

## 7. Put the site live

Wait for DNS (step 2):

```bash
# On the server
getent ahostsv4 app.finance-nl.com | head -n 1
```

This should print `2.28.108.199    STREAM app.finance-nl.com`. If it prints nothing, wait and try
again.

Copy the site file to `/root` under the site's name, and install it:

```bash
# On the server
cp /opt/finance-tracker/deploy/finance.caddy /root/finance.caddy
caddy-site install /root/finance.caddy
```

You should see `== Validating /opt/auth/Caddyfile with finance.caddy …`, `Valid.`,
`== Reloading Caddy` and `✅ /opt/caddy-sites/finance.caddy installed and live`. If it says
`❌ … is not valid: nothing was changed`, Caddy's error above it names the line; nothing changed.
Fix `deploy/finance.caddy` on the laptop (check it there with `deploy/check-site.sh`), push, and
follow [Update the app](#update-the-app).

```bash
# On the server
sleep 30
cd /opt/auth && docker compose logs --since 10m caddy | grep -v '"http.log.access' | grep -E 'error|app.finance-nl.com' | tail -n 5
```

You should see `certificate obtained successfully` for `app.finance-nl.com`, and no line with
`error`. If there is no line yet, run the block again a minute later.

## 8. Check it from outside

```bash
# On the laptop
curl -sI https://app.finance-nl.com/ | grep -iE '^(HTTP/|(cache-control|content-security-policy|strict-transport-security|server|via):)'
curl -s -o /dev/null -w '%{http_code} %{content_type}\n' https://app.finance-nl.com/entries/new
curl -s https://app.finance-nl.com/privacy | grep -o '<h1>.*</h1>\|Last updated: [^<]*'
curl -sI https://app.finance-nl.com/favicon.ico | grep -iE '^(HTTP/|(content-type|cache-control):)'
curl -s -o /dev/null -w '%{http_code}\n' https://app.finance-nl.com/assets/missing.js
curl -s -o /dev/null -w '%{http_code}\n' https://app.finance-nl.com/api/me
curl -s https://app.finance-nl.com/actuator/health | head -c 15; echo
curl -s -o /dev/null -w '%{redirect_url}\n' https://app.finance-nl.com/oauth2/authorization/keycloak | tr '?&' '\n\n' | grep -E '^https|^client_id|^redirect_uri|^code_challenge_method'
cd ~/dev/auth_server && deploy/smoke-test.sh | tail -n 1
```

You should see:

- `HTTP/2 200`, `cache-control: no-cache`, the `content-security-policy` and
  `strict-transport-security` lines, and no `server` or `via` line.
- `200 text/html`: a deep link of the single-page app gets `index.html`.
- `<h1>Privacy policy</h1>` and its `Last updated` date: the static policy, which needs neither
  JavaScript nor the backend.
- `HTTP/2 200`, `content-type: image/x-icon` and `cache-control: public, max-age=86400`: the
  icon, which browsers keep for a day.
- `404`: a missing build file is not the app.
- `401`: the API answers, and wants a login.
- `<!doctype html>`: `/actuator/health` is the app's page, not the backend's status.
- `https://auth.finance-nl.com/realms/myapps/protocol/openid-connect/auth`,
  `client_id=finance-tracker`, `redirect_uri=https://app.finance-nl.com/login/oauth2/code/keycloak`
  and `code_challenge_method=S256`.
- `== Summary: 11 ok, 0 fail ==`: the auth server is as before.

In a private browser window, open <https://app.finance-nl.com>. It shows the landing page, whose
**Sign in or create an account** leads to Keycloak's login page, which offers **Register**,
**Forgot password?**, **Google** and **GitHub**. Sign in as yourself. You land on the dashboard of
an empty ledger, which offers **Load demo data**, and **Accounts** lists the starter accounts, such
as Cash and Current account. In the admin console, realm `myapps` → **Events → User events** shows
`LOGIN` and `CODE_TO_TOKEN` with client `finance-tracker`. Don't load the demo into the ledger you
will import your own data into: the import would add to the demo's entries. To try it, load it,
then **Settings → Delete all my data**.

- **A new account** registers with its email address only. The password is set after the link in
  the verification email, in the tab the link opens. The tab of the registration then carries on
  by itself and lands in the app too: `authorization_request_not_found` under
  [Troubleshooting](#troubleshooting).
- **Delete all my data** leaves the user signed in, and the app's next request, from the empty
  dashboard it shows, gives them a new starter ledger, with their email address and name in
  `users`. To remove a test account completely, [delete the user](#delete-a-user).

Google's OAuth consent screen needs a public home page and privacy policy before Google login can
leave test mode: they are <https://app.finance-nl.com/> and <https://app.finance-nl.com/privacy>.

## 9. Backups

The auth server's `pg-backup` and `pg-restore-test` already run for Keycloak's database. The
finance database needs only its settings file and its timer: every night at 02:30 UTC (plus up to
10 minutes) `pg-backup@finance.timer` writes a `pg_dump` into `/var/backups/pg/finance/`, checks
it, and keeps 14 days, always the newest 3. The laptop already pulls everything under
`/var/backups/pg/`.

```bash
# On the server
install -o root -g root -m 600 /opt/finance-tracker/deploy/pg-backup/finance.conf /etc/pg-backup/finance.conf
systemctl enable --now pg-backup@finance.timer
systemctl list-timers --no-pager 'pg-backup@*'
```

You should see two timers, `pg-backup@auth.timer` and `pg-backup@finance.timer`, both next due at
02:30 UTC plus a few minutes, in the server's time zone.

The auth server's `deploy/backup/install.sh server` installs only that repository's `*.conf`; it
neither removes nor overwrites `/etc/pg-backup/finance.conf`.

**The first backup:**

```bash
# On the server
systemctl start pg-backup@finance.service
journalctl -u pg-backup@finance.service -n 5 --no-pager
cat /var/backups/pg/finance/last-success
ls -la /var/backups/pg/finance/
```

You should see `Wrote /var/backups/pg/finance/finance-….dump: … bytes, … archive entries` and
`OK: 1 dumps in /var/backups/pg/finance`. The directory is `drwx------` and the dump `-rw-------`.

**The restore test** restores the newest dump into a throwaway container without a network, from
the image production runs, and compares it with the live database:

```bash
# On the server
pg-restore-test finance
```

You should see `Restored in …s`, then a table in which every line ends with `ok`: `tables` 12,
`migration` 4, and the numbers of users, ledgers, accounts, categories, counterparties, entries
and postings, the sum of all posted amounts, `unbalanced` 0 and the manual rates. Then
`No test container left` and `PASS`. A `MISMATCH` right after a sign-in or a new entry means the
database changed after the dump: run both blocks again.

**The laptop's copy:**

```bash
# On the laptop
systemctl --user start finance-nl-backup-pull.service
ls -la ~/backups/finance-nl-server/finance/
cat ~/backups/finance-nl-server/finance/last-success
```

You should see the same dump file and the same `last-success`, and no desktop notification. From
now on the daily pull also warns when the finance database's newest dump is older than 36 hours.

## 10. Import the Excel ledger

**Not yet.** The import waits for family posting: its Family rows will go straight into a family
ledger (D-21 in [docs/family-budget/requirements.md](../docs/family-budget/requirements.md)). The next
step is stage F2a of the family budget. The steps below stay for when the import is due.

Do this only once step 9 has passed. The import runs in the browser, as the signed-in user, so it
lands in your ledger. Nothing is copied onto the server's disk.

**10.1 Export the files.** Export the Excel workbook's sheets as CSV into
`~/dev/finance-tracker/data/private/` on the laptop, the same way as for the local importer
(CLAUDE.md, "How to run the importer"): `BalanceSheetItems.csv`, `CashFlowItems.csv`, and
`Transactions.csv` with **all** transactions (`Transactions_example.csv` is only a sample). Add an
opening balances file (columns `line,currency,amount,date`) if you use one. `data/private/` is
git-ignored; never commit it.

**10.2 Dry run.** Open <https://app.finance-nl.com/import>, choose the files, and click
**Check (dry run)**. A large ledger takes a minute. The dry run imports everything, reports, and
undoes it. Read the report:

- The top line must say **No errors**. If it lists errors, fix those rows in Excel, export again,
  and check again.
- Read **Warnings** and **Exchange gains imported as income, to review**.
- **Balances after the import**: compare every account and currency with the balance sheet in
  Excel, as of the last transaction's date. They must match to the cent. If one doesn't, don't
  commit: find the cause in Excel, export again, check again.

**10.3 Back up first.**

```bash
# On the server
systemctl start pg-backup@finance.service && journalctl -u pg-backup@finance.service -n 2 --no-pager
```

You should see `Wrote …` and `OK: …`.

**10.4 Commit.** Back in the browser, with the same files still chosen, click **Commit import** and
confirm. You should see **Imported N entries**, with the same N as the dry run.

**10.5 Check.**

- **Accounts**: the balances match Excel again.
- Open <https://app.finance-nl.com/api/reports/integrity>: it should show `[]`.
- **Dashboard** and **Entries** show the ledger.

**10.6 Back up again**, with the same block as 10.3.

If something turns out wrong later, restore the dump from 10.3
([Restore from a backup](#restore-from-a-backup)). Running the import again is safe: rows imported
before are skipped.

---

## Deployed revisions

What runs on the server, newest first. The server's clone may be newer when only documentation
changed since: `git -C /opt/finance-tracker log -1 --oneline` on the server.

| Date | Commit the images were built from | What |
| --- | --- | --- |
| 2026-09-28 | `9287f0e` (Change log: D3a's commit) | D3a: one token refresh per session, and a late login callback lands in the app. Only the backend changed; Compose left `finance-tracker-web` running, as its rebuilt image has the same layers. |
| 2026-09-28 | `070fab2` (Change log: D2c's commit) | First deploy, steps 1 to 9 of this runbook (D3). Step 10, the import, is still to do. Certificate from Let's Encrypt (YE2), valid until 2026-12-27; Caddy renews it. |

## Update the app

Push, and wait until CI is green (<https://github.com/sergeyzoloto/finance-tracker/actions>):

```bash
# On the laptop
git push
```

Back up first, since database migrations only go forward, and note the running commit for a
rollback:

```bash
# On the server
systemctl start pg-backup@finance.service
cd /opt/finance-tracker && git rev-parse HEAD > /root/finance-tracker.previous && git pull --ff-only
git log --oneline "$(cat /root/finance-tracker.previous)"..HEAD
git diff --stat "$(cat /root/finance-tracker.previous)" HEAD -- deploy/finance.caddy deploy/pg-backup
```

The last command lists `deploy/finance.caddy` or `deploy/pg-backup/finance.conf` if the update
changed them; then also do the matching part below.

Keep the running images as `:previous`, then build and restart:

```bash
# On the server
cd /opt/finance-tracker/deploy/app
docker tag finance-tracker-api finance-tracker-api:previous && docker tag finance-tracker-web finance-tracker-web:previous
docker compose build api && docker compose build web && docker compose up -d
for i in $(seq 60); do s=$(docker inspect -f '{{.State.Health.Status}}' finance-tracker-api); [ "$s" = healthy ] && break; sleep 5; done; echo "api: $s"
docker compose ps --format 'table {{.Name}}\t{{.Status}}'
docker image prune -f
```

You should see `api: healthy` and all three containers `Up … (healthy)`. If the update didn't change
the frontend, `finance-tracker-web` keeps its earlier `Up` time: its rebuilt image has the same
layers under a new ID, and Compose leaves the container as it is. Everyone signs in again
after an update, since sessions live in the api's memory; while the Keycloak session lasts, that
is a redirect without a password prompt. A tab that was open during the update and loads a page it
hasn't loaded yet may need a reload: the old build's files are gone.

Add a row to [Deployed revisions](#deployed-revisions), with the commit `git log -1 --oneline`
printed on the server.

**If `deploy/finance.caddy` changed:**

```bash
# On the server
cp /opt/finance-tracker/deploy/finance.caddy /root/finance.caddy
caddy-site install /root/finance.caddy
```

You should see `✅ /opt/caddy-sites/finance.caddy replaced and live. The previous version is kept
as /root/caddy-sites-removed/…/finance.caddy`. Then do the checks of step 8.

**If `deploy/pg-backup/finance.conf` changed:**

```bash
# On the server
install -o root -g root -m 600 /opt/finance-tracker/deploy/pg-backup/finance.conf /etc/pg-backup/finance.conf
pg-restore-test finance
```

You should see `PASS`.

## Roll an update back

To the commit and the images that ran before the last update:

```bash
# On the server
cd /opt/finance-tracker && git checkout --detach "$(cat /root/finance-tracker.previous)" && git log -1 --oneline
cd /opt/finance-tracker/deploy/app
docker tag finance-tracker-api:previous finance-tracker-api && docker tag finance-tracker-web:previous finance-tracker-web
docker compose up -d
for i in $(seq 60); do s=$(docker inspect -f '{{.State.Health.Status}}' finance-tracker-api); [ "$s" = healthy ] && break; sleep 5; done; echo "api: $s"
```

- `docker compose up -d` builds nothing: the images exist, so it starts the `:previous` ones.
- The older code starts even if the update migrated the database: Flyway ignores migrations it
  doesn't know. If it can't work with the newer tables, restore the dump taken before the update
  ([Restore from a backup](#restore-from-a-backup)).
- If the update changed `deploy/finance.caddy`, install the old one too: the block "If
  `deploy/finance.caddy` changed" above, which now copies the older file.

To go forward again, leave the detached checkout first, **on the server**:
`cd /opt/finance-tracker && git checkout main`, then [Update the app](#update-the-app).

## Restore from a backup

This replaces the live database with a dump. Choose the dump:

```bash
# On the server
ls -l /var/backups/pg/finance/
dump=$(ls -1 /var/backups/pg/finance/finance-*.dump | tail -n 1); echo "$dump"
```

That picks the newest. For an older one, set it from the list, for example
`dump=/var/backups/pg/finance/finance-2026-10-01T0232Z.dump`. The names are UTC times.

Then, **on the server** in the same shell:

```bash
# On the server, in the same shell
# 1. Check that the dump restores, without touching production
pg-restore-test finance "$dump" </dev/null
# 2. Dump the current state too, and pause the nightly backup until step 6
systemctl start pg-backup@finance.service
systemctl stop pg-backup@finance.timer
# 3. Stop the api (the site answers 502 meanwhile)
cd /opt/finance-tracker/deploy/app && docker compose stop api
# 4. Keep the current database under another name, and create an empty one
docker compose exec -T postgres psql -X -v ON_ERROR_STOP=1 -U postgres -d postgres -c 'ALTER DATABASE finance RENAME TO finance_before_restore' -c 'CREATE DATABASE finance OWNER finance' -c 'REVOKE ALL ON DATABASE finance FROM PUBLIC' </dev/null
# 5. Restore in one transaction, as the app's own login, stopping at the first error
docker compose exec -T postgres pg_restore -U finance -d finance --exit-on-error --single-transaction < "$dump" && echo restored
# 6. Start the api, and resume the nightly backup
docker compose start api
for i in $(seq 60); do s=$(docker inspect -f '{{.State.Health.Status}}' finance-tracker-api); [ "$s" = healthy ] && break; sleep 5; done; echo "api: $s"
systemctl start pg-backup@finance.timer
```

- Step 1 compares the restored copy with the live database. For an older dump, or after activity
  since the dump, the numbers differ; what counts there is `Restored in …s`.
- You should see `restored` and `api: healthy`. Sign in and check **Accounts**, and that
  <https://app.finance-nl.com/api/reports/integrity> shows `[]`.
- Steps 4 and 5 were tried on 2026-09-27 in a throwaway container of the production image, with
  the same logins as production: the restore ran as `finance`, which is no superuser, and the
  ledger's triggers worked afterwards.

Step 4 fails if `finance_before_restore` is left from an earlier restore; drop it first (below).
If step 5 or 6 fails, go back to the previous database:

```bash
# On the server
cd /opt/finance-tracker/deploy/app && docker compose stop api
docker compose exec -T postgres psql -X -v ON_ERROR_STOP=1 -U postgres -d postgres -c 'DROP DATABASE IF EXISTS finance' -c 'ALTER DATABASE finance_before_restore RENAME TO finance' </dev/null
docker compose start api
systemctl start pg-backup@finance.timer
```

Once the restored state has worked for a few days:

```bash
# On the server
cd /opt/finance-tracker/deploy/app && docker compose exec -T postgres psql -X -U postgres -d postgres -c 'DROP DATABASE finance_before_restore' </dev/null
```

**If the server or the volume is lost**, the dump comes from the laptop. Set up the auth server
first (its `PRODUCTION.md`); Keycloak's own database comes back from its own backup, and every
ledger row is keyed by a Keycloak user's `sub`, so the realm must be the restored one. Then do steps
1 and 4 to 6 here: new database passwords in `.env` are fine, because a dump holds the database
but not the logins, which `postgres-init.sh` creates. Copy the newest dump up:

```bash
# On the laptop
scp "$(ls -1 ~/backups/finance-nl-server/finance/finance-*.dump | tail -n 1)" root@2.28.108.199:/root/
```

Then restore it as above with `dump=$(ls -1 /root/finance-*.dump | tail -n 1)`; step 1's comparison
fails there, because the live database is new and empty. Then steps 7 and 9.

## Change the site file

1. **On the laptop**: edit `deploy/finance.caddy`, then check it the way `caddy-site` will, with
   the auth server's Caddyfile and the Caddy image it pins, without a network:

   ```bash
   # On the laptop
   deploy/check-site.sh
   ```

   You should see `Valid configuration`, `Formatted as caddy fmt formats it.` and
   `✅ deploy/finance.caddy is valid`.
2. Commit, then [Update the app](#update-the-app). Its diff lists `deploy/finance.caddy`, and the
   block "If `deploy/finance.caddy` changed" installs it.

To take the site offline, **on the server**: `caddy-site remove finance`. It keeps the file in
`/root/caddy-sites-removed/`; the auth server's `PRODUCTION.md`, "Remove a site", has the rest.

## Delete a user

For someone who asks for their account to be deleted (the privacy policy: "your login account is
deleted on request"), or a test account. This deletes the user in Keycloak and every row the app
keeps for them. It can't be undone, except by restoring a backup. The first block only looks: it
asks for the email address and prints the user's id, linked providers, Keycloak sessions and the
app's rows.

```bash
# On the server
kc() { docker compose -f /opt/auth/docker-compose.yml exec -T keycloak /opt/keycloak/bin/kcadm.sh "$@" --config /tmp/kcadm.config </dev/null; }
kc config credentials --server http://localhost:8080 --realm myapps --client automation-cli --secret "$(cat /root/automation-cli.secret)"
IFS= read -r -p 'Email address of the user: ' email
sub=$(kc get users -r myapps -q email="$email" -q exact=true --fields id --format csv --noquotes); echo "user: ${sub:-none}"
[ -n "$sub" ] && kc get users/$sub/federated-identity -r myapps --fields identityProvider && echo "sessions: $(kc get users/$sub/sessions -r myapps --fields id --format csv --noquotes | grep -c .)"
cd /opt/finance-tracker/deploy/app && docker compose exec -T postgres psql -X -A -t -U finance -d finance -v sub="$sub" <<'SQL'
SELECT 'users ' || count(*) FROM app.users WHERE keycloak_id = :'sub';
SELECT 'accounts ' || count(*) || ', categories ' || (SELECT count(*) FROM app.category WHERE user_id = :'sub') || ', entries ' || (SELECT count(*) FROM app.journal_entry WHERE user_id = :'sub') FROM app.account WHERE user_id = :'sub';
SQL
```

You should see the user's id (36 characters), then their linked providers (`[ ]` for none),
`sessions: 0`, and the numbers of rows. If `sessions` isn't 0, sign them out in the admin console
(realm `myapps` → **Users** → the user → **Sessions** → **Sign out**), wait 5 minutes, the
lifetime of an access token, and run the block again.

Then, in the same shell: a backup, the app's rows in one transaction, as the app's own "Delete all
my data" deletes them (`UserDataService.deleteAll`), and the Keycloak user:

```bash
# On the server, in the shell of the block above
systemctl start pg-backup@finance.service
cd /opt/finance-tracker/deploy/app && docker compose exec -T postgres psql -X -1 -v ON_ERROR_STOP=1 -U finance -d finance -v sub="$sub" <<'SQL'
DELETE FROM app.user_settings WHERE user_id = :'sub';
DELETE FROM app.journal_entry WHERE user_id = :'sub';
DELETE FROM app.import_batch WHERE user_id = :'sub';
DELETE FROM app.account WHERE user_id = :'sub';
DELETE FROM app.category WHERE user_id = :'sub';
DELETE FROM app.counterparty WHERE user_id = :'sub';
DELETE FROM app.exchange_rate WHERE user_id = :'sub';
DELETE FROM app.transactions WHERE user_id IN (SELECT id FROM app.users WHERE keycloak_id = :'sub');
DELETE FROM app.categories WHERE user_id IN (SELECT id FROM app.users WHERE keycloak_id = :'sub');
DELETE FROM app.users WHERE keycloak_id = :'sub';
SQL
kc delete users/$sub -r myapps && echo "deleted in Keycloak"
docker compose -f /opt/auth/docker-compose.yml exec -T keycloak rm -f /tmp/kcadm.config </dev/null
```

You should see one `DELETE n` per table, with the numbers of the first block, and `deleted in
Keycloak`. The dump taken first holds the user until it ages out, 14 days on the server and 60 on
the laptop, as the privacy policy says. On 2026-09-28 the test account of the first deploy was
deleted with these statements, run in a `DO` block that also checked the total: 27 rows (settings
1, accounts 10, categories 15, users 1), then the Keycloak user. Afterwards both blocks were tried
as they are written here, for an address and a `sub` nobody has: the first printed `user: none`,
and the second's `psql`, with a failing statement added at the end, ran its ten `DELETE 0` and
rolled back.

## Regular checks

Weekly:

```bash
# On the server
systemctl list-timers --no-pager 'pg-backup@*'
cat /var/backups/pg/finance/last-success
journalctl -u pg-backup@finance.service -p err --since -8d --no-pager
df -h /
docker stats --no-stream --format 'table {{.Name}}\t{{.MemUsage}}'
```

You should see a next run tonight, a `last_success` from last night, no error lines, free disk
space, and every container below its limit. The laptop's daily pull raises a desktop notification
by itself when a dump is older than 36 hours.

Monthly, with the auth server's monthly routine (its `PRODUCTION.md`), which updates the Docker
packages:

```bash
# On the server
systemctl start pg-backup@finance.service && pg-restore-test finance </dev/null
cd /opt/finance-tracker/deploy/app && docker compose build --pull api && docker compose build web && docker compose up -d
```

You should see `PASS`, then the api rebuilt on the newest Java 21 runtime and `api` and `web`
restarted.

`postgres` and the nginx of `web` are pinned to an exact version and build
(`deploy/app/docker-compose.yml`, `frontend/Dockerfile`), so `--pull` doesn't change them. For a
new patch release, **on the laptop**, `docker pull postgres:17.12` or
`docker pull nginxinc/nginx-unprivileged:1.30.6-alpine-slim` prints the new
`Digest: sha256:…`; put the version and digest into the file, commit, and
[update](#update-the-app). A new PostgreSQL
major version needs a dump and a restore, which this runbook doesn't cover.

## Troubleshooting

| What you see | Cause and fix |
| --- | --- |
| The browser warns about the certificate of app.finance-nl.com | Caddy has no certificate yet. On the server, `cd /opt/auth && docker compose logs caddy \| grep app.finance-nl.com` says why; nearly always DNS (step 2). Caddy keeps retrying by itself. |
| `502 Bad Gateway` | The api (for `/api` and the login) or the web container (for pages) is down, or not on `edge`. On the server: `cd /opt/finance-tracker/deploy/app && docker compose ps -a && docker compose logs --tail 50 api`, and the network checks of step 6. |
| Keycloak says `Invalid parameter: redirect_uri` | Step 3.1: the client's redirect URI. |
| The app says "Your account has no access to Finance Tracker" | The user lacks `finance-tracker` → `user`: step 3.2, the default role. Sign out and in again after fixing it in the auth server. |
| After signing in, the app says the login failed | On the server, `cd /opt/finance-tracker/deploy/app && docker compose logs api \| grep 'Login failed'`. `401 Unauthorized`, `invalid_client` or `Invalid client or Invalid client credentials` (Keycloak 26 sends that one as `unauthorized_client`): the client secret in `.env` is wrong. Check it with step 5's token check, write it again with the `read -rs` block of step 5, then `docker compose up -d api`. `Connect timed out` or `UnknownHost`: the api can't reach Keycloak; see the last check of step 6. `authorization_request_not_found`: the next row. |
| The api logs `A late callback of a finished sign-in` or `Login failed: [authorization_request_not_found]`, and Keycloak, in the same second, `RESTART_AUTHENTICATION_ERROR` with `already_logged_in` | A second tab of the same sign-in came back after the first had finished it: typically the tab of a registration, after the verification link signed the user in, in a new tab. Harmless; seen on 2026-09-28. Since D3a (the change log, 2026-09-28), that tab lands in the app when the browser's session is signed in (the first message). The login-failed notice (the second) remains for a session that isn't, as when the link was opened in another browser. |
| Keycloak logs `IDENTITY_PROVIDER_LOGIN_ERROR` with `cookie_not_found` seconds after a successful Google or GitHub sign-in | A second callback of the same sign-in reached Keycloak after the first had finished it, for example after a double click in the provider's account chooser. Harmless; seen on 2026-09-28. |
| Users are sent through Keycloak again while they work, and Keycloak logs `REFRESH_TOKEN_ERROR` with `Maximum allowed refresh token reuse exceeded` | A bug of the app found in D3 and fixed in D3a (the change log, 2026-09-28). Parallel requests each refreshed an expiring access token with the same refresh token; the realm accepts each refresh token once, so the second refresh failed, and the api then ended its session. Now one request of a session refreshes at a time, and the others use its new token ([docs/auth.md](../docs/auth.md), "How a browser session works"). If it shows up again, check that exactly one api instance runs, since the lock is in its memory. To see it: `cd /opt/auth && docker compose logs --since 24h keycloak \| grep REFRESH_TOKEN_ERROR`, and the api's side: `cd /opt/finance-tracker/deploy/app && docker compose logs --since 24h api \| grep 'Keycloak refused'`. The realm doesn't store `REFRESH_TOKEN` events; the api's refreshes and code exchanges are the token endpoint's `POST`s with a `Java/…` user agent in Caddy's access log (`docker compose logs caddy` in `/opt/auth`), and Keycloak's stored `CODE_TO_TOKEN` events tell the code exchanges apart. |
| Someone signed in with Google or GitHub and sees an empty ledger | They used an address that differs from their account's: Keycloak made a separate user. [docs/auth.md](../docs/auth.md), "One person, several users". |
| `finance-tracker-api` is `unhealthy`, and its log says `password authentication failed for user "finance"` | `APP_DB_PASSWORD` in `.env` changed after the database was created. On the server: `cd /opt/finance-tracker/deploy/app && printf "ALTER ROLE finance PASSWORD '%s';\n" "$(sed -n 's/^APP_DB_PASSWORD=//p' .env)" \| docker compose exec -T postgres psql -X -q -U postgres -d postgres && docker compose up -d api`. `printf` and the pipe keep the password out of the process list. |
| `caddy-site install` says `is not valid: nothing was changed` | Caddy's error above it names the line. Fix it on the laptop with `deploy/check-site.sh` ([Change the site file](#change-the-site-file)). |
| `caddy-site` says the running Caddy has an older Caddyfile | On the server, `cd /opt/auth && docker compose restart caddy` (logins pause for seconds), then run the install again. |
| A container restarts again and again | On the server, `docker inspect -f '{{.State.OOMKilled}}' finance-tracker-api` (or another container's name) prints `true` when it ran out of memory. Compare with the [memory budget](#memory-budget). |
| `pg-backup@finance.service` failed | On the server, `journalctl -u pg-backup@finance.service -n 30 --no-pager`. `COMPOSE_DIR … is not a directory`: step 4. `required variable … is missing a value`: `.env` is missing (step 5). `database not ready`: the stack is down (step 6). |
| `pg-restore-test finance` shows `MISMATCH` | The database changed after the dump. Back up again and repeat the test. |
| The import fails with `413` | A file is over 20 MiB, the limit in Caddy and in the backend. |

## Not covered

- **No copy beyond the laptop.** As for the auth server: if the server is lost, everything since
  the laptop's last pull is lost too.
- **No alerts** but the laptop pull's notification. A failed backup shows up as a failed unit and in
  the journal.
- **One server and one api instance.** Sessions live in the api's memory, so a restart signs
  everyone out.
- **No access log for app.finance-nl.com in Caddy**: the auth server's log block applies to its own
  site only. `docker compose logs web` shows the page requests nginx served, with Caddy's address
  and the client's in `X-Forwarded-For`.
- **Open registration.** Anyone can sign up, and a new user's first request gives them a starter
  ledger. Nothing removes the ledgers of users who never come back, and there is no rate limit or
  captcha (the auth server's `PRODUCTION.md`, "Spam registrations").
- **PostgreSQL major upgrades.**
