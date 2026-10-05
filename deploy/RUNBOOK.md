# Production runbook

Finance Tracker runs at <https://app.finance-nl.com> on the Hetzner server that runs the auth
server (Keycloak at <https://auth.finance-nl.com>). This runbook sets it up once (steps 1–10),
then covers updates ([Deploying with deploy.sh](#deploying-with-deploysh)), rollback, restores and regular checks. Do the steps in order. Every step says
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
- A block that starts with a `read` prompt: paste the block once, then type the answer at the
  prompt and press Enter. Don't paste the block again as the answer: `read` would take the block's
  first line as the value, and the block would run with it.
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
the image production runs, and compares it with the live database. It always runs right after a
fresh backup, in the same block, so that the newest dump is the live database as it is:

```bash
# On the server
systemctl start pg-backup@finance.service && pg-restore-test finance </dev/null
```

You should see `Restored in …s`, then a table in which every line ends with `ok`: `tables` 19 and
`migration` 9 (since V9, F5; 18 and 8 since V8, 18 and 7 since V7, 14 and 5 since V5, 12 and 4
before), and the numbers of users, settings, ledgers, members, accounts, categories,
counterparties, entries and postings, the sum of all posted amounts, `unbalanced` 0, the manual
rates, the family records, shares, links and journal rows, and the invites (`family_invites`, since
V9). Then
`No test container left` and `PASS`. A `MISMATCH` right after a sign-in or a new entry means the
database changed between the backup and the test: run the block again.

**A restore test against an older dump fails** after any change in production since that dump: a
sign-in that provisions a user, a new entry, the demo, "Delete all my data". It compares the
restored copy with the live database, so it passes only against a dump taken just before it. On
2026-09-30 the checklist of F4c's deploy ran it against the previous evening's dump, after the test
account had loaded the demo, and it failed with mismatches; after a fresh backup it passed. Every
restore test in this runbook therefore comes right after `systemctl start pg-backup@finance.service`,
except step 1 of [Restore from a backup](#restore-from-a-backup), which checks a dump you chose and
expects the numbers to differ.

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
ledger (D-21 in [docs/family-budget/requirements.md](../docs/family-budget/requirements.md)). The
family budget's stages come first; F2a is deployed since 2026-09-29. The steps below stay for when
the import is due.

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
changed since: `git -C /opt/finance-tracker log -1 --oneline` on the server. Since OPS-1, `deploy/deploy.sh finish`
prints a row's text (no pipe character in it).

| Date | Commit the images were built from | What |
| --- | --- | --- |
| 2026-10-04 | `b6870f2`; nothing on the server | "CI on Ubuntu 26.04" (D-44), `workflow_dispatch` on `main` at `b6870f2`, started after the acceptance checks (the row below): run #1, `37227790313`, 19:18:49Z to 19:28:24Z, on Ubuntu 26.04.1 with Docker 29.4.2. Backend failed: 404 tests, 1 failure, `RateApiTests.aManualRateIsTheUsersOwn` (`RateApiTests.java:31`), expected "2026-09-01" but was "2026-09-25"; Maven finished at 19:28:20Z. Frontend (19:18:55Z to 19:19:23Z), Web image (19:18:53Z to 19:19:21Z) and Deploy scripts (19:18:53Z to 19:24:26Z) completed with success (the run's jobs, read from GitHub's public API on 2026-10-05). Its check runs belong to `b6870f2`, deployed and finished, so they hold up no deploy. OPS-2b looks for the cause. |
| 2026-10-04 | `b6870f2`; nothing changed | OPS-2's acceptance checks, each block alone. The read-only block before and after D-42's check printed the same: the previous file names `45100032b97b…`; `:previous` is api `sha256:6d6f35b8…` and web `sha256:bba7ff04…`; `deploy/app/.env` and its three copies are 600 root. D-42: `deploy/deploy.sh run b6870f2b6272… OPS-2` at 19:14:58Z (`2026-10-04T191458Z-b6870f2`), REFUSED at 1.3, "Nothing changed: no merge, no image tagged, built or started", with no CI line and no dump; its 1.2 printed "Image store: unknown, platform unknown" (defect 5) and "The running images are the last good deploy's content". Defect 1: `deploy/deploy.sh switch off` at 19:16:29Z and at 19:16:44Z, both answered `no`: each reached "2. Confirmation", each said "The latest run that isn't refused: 2026-10-04T191023Z-b6870f2, finished", and nothing changed; their 1.4 also printed "Image store: unknown, platform unknown". The switch stays on. |
| 2026-10-04 | `b6870f2`; read only | `deploy/deploy.sh verify OPS-2`: OK at 19:14:35Z. Both images "the recorded image", each with its content identity; but the line before them said "Image store: unknown, platform unknown", while `docker info` and `docker version` answer as in the pre-flight (defect 5). |
| 2026-10-04 | `b6870f2` (docs: OPS-2's deploy analysis and checklist); both images rebuilt from cache, the containers kept running | OPS-2: the deploy scripts' defects 1 to 4, D-42 to D-44, Flyway V10, the switch on. `deploy/deploy.sh run b6870f2b6272… OPS-2` at 19:10:23Z, run folder `/var/lib/finance-deploy/runs/2026-10-04T191023Z-b6870f2`, executed by `4510003`'s script (no content identity, no D-42 check, no preserved dump), after the one-time remedy (the row below). 1.2: running api `bb3bdef3…`, web `bba7ff04…`; the last good deploy `4510003` at 14:17:00Z, status good. 1.3: `c45d956`, `685deb3`, `789b8c6`, `f1c2f5c`, `bd77f05`, `83abd3e`, `b6870f2`; "Migrations added: none". 1.5: CI's 10 check runs, every one success, the new action versions on `ubuntu-24.04`. 1.8: dump `finance-2026-10-04T1910Z.dump`, 1446579 bytes, written 19:10:26Z; restore test PASS (19 tables). 3.2: "a failed run's. They take no tag", as expected with the remedy: the tags and the previous-commit file stayed on `4510003`. 3.3: both builds fully cached; api manifest `sha256:9315f0d9…`, config `sha256:03ecad3b6730…`, attestation `sha256:52e46962…`, a new manifest list `sha256:41e46a733329…`, now `:latest`; web manifest `sha256:2a6042327728…`, config `sha256:04c29e54bd46…`, attestation `sha256:0521a63c…`, a new list `sha256:827620f31177…`, now `:latest` (`change_log.mdx` and `docs/` are outside both build contexts). 3.4: both "Running", not recreated. 3.5: healthy; "Total reclaimed space: 0B". 3.6: the pages 5 of 5. 4.1: V10. 4.2: D-25 "on" at 2026-10-03T09:25:33Z. 4.3: the same numbers (accounts 44, categories 72, counterparties 107, entries 296, every `family_*` 0, import_batches 1, outside_their_ledger 0, personal_ledgers 3, personal_members 3, settings 3, users 3). 4.4: OPS-2's 5 lines as expected. 4.5: `finance.conf` not installed. 4.6: dump `finance-2026-10-04T1912Z.dump`, 1446579 bytes, written 19:12:03Z, PASS; a different minute, so defect 3 didn't occur. `finish`, OPS-2's script: the pages 5 of 5; the browser checks "yes" at 19:14:25Z and the smoke test "yes" at 19:14:27Z, about 2 minutes 20 seconds after the run's summary. **The smoke test was not performed in full**, like the finishes 35 and 80 seconds after `run` on 2026-10-03 and 2026-10-04; F7's two-account check will cover it. The family lines as before. `last-good` now names `b6870f2`, api `bb3bdef3…` (content `9315f0d9…`) and web `bba7ff04…` (content `2a604232…`), `finish=passed`. |
| 2026-10-04 | `4510003`; only `last-good` changed | The one-time remedy of OPS-2's checklist (its step 3), once the PM approved it, between 19:09:57Z and 19:10:23Z: `last-good`'s `api_image` from `sha256:bb3bdef3…` (gone from the store) to `sha256:6d6f35b8…` (the ID `:previous` and `finance-tracker-api:45100032b97b…` hold, the same content); `diff` printed `3c3` with those two lines. The copy `/var/lib/finance-deploy/last-good.before-ops2` is kept. |
| 2026-10-04 | `4510003`; read only | OPS-2's pre-flight, in the session opened at 17:33:27Z. Docker server 29.8.2, linux/amd64; Docker Compose v5.5.1; `docker info -f '{{json .DriverStatus}}'` printed exactly `[["driver-type","io.containerd.snapshotter.v1"]]`. The clone at `4510003`; `git status --short` listed three untracked files, `deploy/app/.env.20261002T212046Z`, `.env.20261002T213540Z` and `.env.20261003T092523Z` (`switch`'s copies, 600 root). `last-good`: `commit=45100032b97bc2809ab7b8ec9538735ebbd8426a`, `time=2026-10-04T14:17:00Z`, api `sha256:bb3bdef3d2a94a71dc4f76c3970ab0981a58baed9d688edc65280f48584ec6b4`, web `sha256:bba7ff04771a…`, `source=deploy`, `finish=passed`. The containers: api `bb3bdef3…`, content (`ImageManifestDescriptor`) `sha256:9315f0d932d0…`, started 2026-10-03T09:25:24Z; web `bba7ff04…`, content `sha256:2a6042327728…`, started 2026-10-03T09:03:08Z. `docker image inspect sha256:bb3bdef3…` answered "No such image", as OPS-2's analysis predicted; `bba7ff04…` is present. The tags are full commit hashes (`finance-tracker-api:45100032b97bc2809ab7b8ec9538735ebbd8426a`): the checklist inspected `:4510003`, which names no image. The tags then: api `:4510003…`, `:f0425c0…` and `:previous` `sha256:6d6f35b8…`, `:8ede02e…` `sha256:5bd35f99…`, `:latest` `sha256:a7f2f131…`; web `:4510003…` and `:previous` `bba7ff04…`, `:f0425c0…` `sha256:a01f9202…`, `:8ede02e…` `sha256:ddf2a902…`, `:latest` `sha256:e921513f…`. `docker image inspect --platform linux/amd64 -f '{{.Id}}'`: api `:previous` and `:latest` `9315f0d9…`, web `:previous` and `:latest` `2a604232…`. |
| 2026-10-04 | `4510003`; nothing changed | Before the pre-flight (the row below), its server block was pasted on the laptop by mistake: `cd /opt/finance-tracker` failed, the read-only lines read the laptop, and nothing changed anywhere. OPS-2b's block rule (CLAUDE.md, "Deploy checklists") makes a server block do nothing on the laptop. |
| 2026-10-04 | `4510003` (fix: no early-closed pipes in the checks; switch on checks the pages); the images unchanged | `deploy/deploy.sh switch on` at 14:17:41Z, run folder `/var/lib/finance-deploy/runs/2026-10-04T141741Z-switch-on`, run by mistake with the 2026-10-03 F7b checklist: past "1.4" ("the last good deploy (finished)"), then REFUSED at "1.5 Preflight: the current switch": "FAMILY_LEDGERS_ENABLED is already true: nothing to switch". Nothing changed: `.env` untouched, api not restarted. Its folder's status `refused` is the newest one, so with `4510003`'s scripts the next `switch off` is REFUSED at "1.4" (defect 1; see [Production now](#production-now)). |
| 2026-10-04 | `4510003`; both images rebuilt from cache, the containers kept running | An empty run of `4510003`, the 2026-10-03 F7b checklist re-run by mistake: `deploy/deploy.sh run 45100032b97b… F7b` at 14:14:49Z, run folder `/var/lib/finance-deploy/runs/2026-10-04T141449Z-4510003`. "Commits: none (4510003 to 4510003)", a second case of defect 2; CI's 10 check runs completed with success. 1.2: running images api `sha256:bb3bdef3d2a9…`, web `sha256:bba7ff04771a…`; the last good deploy `4510003` at 2026-10-03T09:24:29Z; the newest history line `switch-on`. 3.2: "The running images … are not the last good deploy's: a failed run's. They take no tag; the tags of the last good deploy 4510003 and :previous stay as they are", a false verdict caused by defect 4: they were the last good deploy's content (`last-good` held api `sha256:6d6f35b8…`, the container `sha256:bb3bdef3…`, the same content). 3.3 rebuilt both from cache: api config `sha256:03ecad3b6730…` (unchanged), a new manifest list `sha256:a7f2f131f9db…`; web config `sha256:04c29e54bd46…`, a new manifest list `sha256:e921513ff648…`; both tagged `:latest`. 3.4: neither container recreated ("Running"): they still run `bb3bdef3…` and `bba7ff04…`, while `:latest` names `a7f2f131…` and `e921513f…`. Before: dump `finance-2026-10-04T1414Z.dump`, written 14:14:51Z, 1446579 bytes, restore test PASS. After: dump `finance-2026-10-04T1415Z.dump`, written 14:15:33Z, 1446579 bytes, restore test PASS; the dumps fell in different minutes, so nothing was overwritten. 3.6 the pages 5 of 5; Flyway V10; D-25 "on" at 2026-10-03T09:25:33Z; the numbers unchanged; F7b's checks as expected. The summary recorded api `bb3bdef3…` and web `bba7ff04…` in `last-good`, which now matches the running containers. `finish`: pages 5 of 5; browser checks "yes" at 14:16:58Z and smoke test "yes" at 14:17:00Z, about 80 seconds after the run's summary; the family numbers as before. The browser checks of the old F7b checklist expected the switch off (no family switcher, `familyLedgers:false`), so they did not apply to this state. `deploy/deploy.sh verify F7b`: OK at 14:17:09Z and again at 14:17:57Z (pages 5 of 5, D-25 "on" since 2026-10-03T09:25:33Z, every `family_*` 0). Read-only proof: in the clone `privacy.html` is 600 root and `postgres-init.sh` 755 root; in the web image both files are `-rw-r--r--` root; `/privacy` answers 200. F7's production check (two test accounts) has not been run yet. |
| 2026-10-03 | `4510003`; the images unchanged, api recreated | `deploy/deploy.sh switch on` at 09:24:44Z, run folder `/var/lib/finance-deploy/runs/2026-10-03T092444Z-switch-on`: the pages 5 of 5; `.env` copied to `.env.20261003T092523Z`; `false` became `true`; api recreated, and since then it runs `sha256:bb3bdef3d2a9…` (the `:latest` of 09:23:15Z's run), not `last-good`'s `sha256:6d6f35b8…`, while the summary said "Images unchanged" (defect 4); the D-25 line "on" at 09:25:33Z; the numbers unchanged; the summary at 09:25:40Z. Family budgets are switched on in production since 2026-10-03T09:25:40Z. |
| 2026-10-03 | `4510003`; both images rebuilt, the containers kept running | An empty run of `4510003` at 09:23:15Z, run folder `/var/lib/finance-deploy/runs/2026-10-03T092315Z-4510003`, the workaround for defect 1 a second time. `finish` at 09:24:27Z and 09:24:29Z, after short checks. The before-dump was overwritten by the after-dump (defect 3): `finance-2026-10-03T0923Z.dump`, written at 09:23:18Z, was replaced by the one written at 09:23:38Z, since both fell in the same minute. |
| 2026-10-03 | `4510003`; nothing changed | `deploy/deploy.sh switch on` twice: at 09:20:28Z (`2026-10-03T092028Z-switch-on`) REFUSED at the confirmation, because the answer was the next pasted block; nothing changed. At 09:20:55Z (`2026-10-03T092055Z-switch-on`) REFUSED at step 1.4 because of the refusal before it (defect 1); nothing changed. |
| 2026-10-03 | `4510003`; both images rebuilt, the containers kept running | An empty run of `4510003` at 09:18:55Z, run folder `/var/lib/finance-deploy/runs/2026-10-03T091855Z-4510003`, the workaround for defect 1: "Commits: none"; `:previous` now names `4510003`; `7a60020`'s images removed. `finish` at 09:20:05Z and 09:20:07Z, 35 seconds after `run`, without the checks being performed. |
| 2026-10-03 | `4510003`; nothing changed | `deploy/deploy.sh switch on` at 09:05:56Z (`2026-10-03T090556Z-switch-on`): REFUSED at the confirmation, because the answer was the next pasted block; nothing changed. |
| 2026-10-03 | `4510003` (fix: no early-closed pipes in the checks; switch on checks the pages) | F7b: the web image readable whatever the checkout's modes, page checks in the deploy scripts, git under `umask 022`, `switch on` checking the pages; Flyway V10; switch off during the deploy. `deploy/deploy.sh run` at 09:02:22Z, run folder `/var/lib/finance-deploy/runs/2026-10-03T090222Z-4510003`, executed by `f0425c0`'s script, so it had no page step. The commits `7046892`, `a01d9e4`, `3a6da31` and `4510003` on top of `f0425c0`; CI's 10 check runs completed with success. Before: dump `finance-2026-10-03T0902Z.dump`, 1446570 bytes, restore test PASS. `f0425c0`'s images kept as `:f0425c0` and `:previous`; `46dedcd`'s images removed. api not recreated (the same content); web rebuilt as `sha256:bba7ff04771a…`. Flyway V10; the D-25 line "off"; the numbers unchanged; F7b's checks as expected. After: dump `finance-2026-10-03T0903Z.dump`, 1446570 bytes, restore test PASS. After the deploy: in the server's clone `privacy.html` is 600 root (left so on purpose) and `postgres-init.sh` 755 root; in the web image `privacy.html` and `default.conf` are `-rw-r--r--` root; `/privacy` answers 200. `finish`: the pages 5 of 5; browser checks passed at 09:05:20Z (the typo "yes'" was asked again); smoke test at 09:05:22Z. `deploy/deploy.sh verify F7b`: OK at 09:05:35Z. |
| 2026-10-02 | `f0425c0` (ops: deploy.sh switch); the images unchanged | `deploy/deploy.sh switch off`, decided by the PM (D-41): family budgets don't stay switched on while the published privacy policy is unreachable. At 2026-10-02T21:35:57Z, commit `f0425c0`: `FAMILY_LEDGERS_ENABLED=true` became `false`; images unchanged (api `sha256:6d6f35b82a50…`, web `sha256:a01f92023e31…`); the D-25 line "off" at 21:35:49Z; the numbers unchanged. Its summary itself (the script's "4. Summary" block) wasn't sent; these are its facts as the owner gave them on 2026-10-03. Production after it: `f0425c0`, Flyway V10, the switch off, `/privacy` and `/privacy.html` answering 403 until F7b's deploy. The server's `frontend/public/privacy.html` stays mode 600 on purpose, so that F7b's deploy proves its fix where F7's failed. F7's production check didn't run; it moves to F7b's checklist. |
| 2026-10-02 | `f0425c0` (ops: deploy.sh switch); the images unchanged | `deploy/deploy.sh switch on`, run folder `/var/lib/finance-deploy/runs/2026-10-02T212036Z-switch-on`: `.env` copied to `.env.20261002T212046Z` (mode 600); `FAMILY_LEDGERS_ENABLED` absent, so off, became `FAMILY_LEDGERS_ENABLED=true`; api alone recreated; "Family ledgers (D-25): on" at 21:20:56Z; the numbers the same before and after; the summary at 21:21:03Z; images unchanged. It followed a `finish` whose browser checks were recorded as passed by mistake (the row below), so the switch went on while the privacy policy answered 403; by D-41 it was switched off again (the row above). |
| 2026-10-02 | `f0425c0` (ops: deploy.sh switch) | F7: family budgets ready to switch on (E3's check by D-40, the privacy policy without "aren't available yet", CI's job "Web image"), `deploy.sh switch`, Flyway V10, switch off during the deploy. Deployed by F6c's script (the clone's `8ede02e`), run folder `/var/lib/finance-deploy/runs/2026-10-02T211023Z-f0425c0`. CI's 10 check runs completed with success (Backend, Dependency updates, Deploy scripts, Frontend, Web image). Before: a fresh backup, then the restore test: PASS (dump `finance-2026-10-02T2114Z.dump`, 1446570 bytes, written 21:14:28Z; 19 tables, migration 10, family counts 0). `8ede02e`'s images (api `sha256:5bd35f99…`, web `sha256:ddf2a902…`) kept as `:8ede02e…` and `:previous`; built and healthy; Flyway V10, "Schema "app" is up to date. No migration necessary." at 21:15:25Z; "Started FinanceTrackerApplication in 7.821 seconds" at 21:15:27Z; the D-25 line off at 21:15:27Z; the same numbers before and after; F7's stage checks as expected. After: a fresh backup, then the restore test: PASS (dump `finance-2026-10-02T2115Z.dump`, 1446579 bytes, written 21:15:36Z). Images api `sha256:6d6f35b8…`, web `sha256:a01f9202…`. **The browser checks failed**: nginx answered `GET /privacy` with 403 at 21:17:07Z, and `/privacy.html` the same, before `finish`; `finish` recorded the browser checks as passed at 21:20:03Z, answered yes by mistake, and the smoke test as passed at 21:20:06Z; the family numbers after the smoke test as before. `history` isn't corrected: it says `good f0425c0`, as written. `deploy/deploy.sh verify F7` at 21:20:27Z: OK. The cause, confirmed on the server: `deploy.sh` sets `umask 077` (`deploy/deploy.sh:970` at `f0425c0`; F6c's script, which ran this deploy, at its line 785), so step 3.1's `git merge --ff-only` rewrote `frontend/public/privacy.html`, which F7 changed, with mode 600 at 21:14:55Z, while the files of `frontend/public` it didn't touch (`favicon.ico`, `favicon.svg`, `privacy.css`, unchanged since 2026-09-28) stayed 644; the build carried the mode into the image (`/usr/share/nginx/html/privacy.html` `-rw------- root root`, 20328 bytes, while `index.html`, written inside the build container, is `-rw-r--r--`); nginx runs as uid 101: `open() "/usr/share/nginx/html/privacy.html" failed (13: Permission denied)`, so `/privacy` and `/privacy.html` answer 403, `/` and `/favicon.svg` 200. CI's and the laptop's checkouts give 644 or 664, so neither could see it. A fix by hand, `docker exec -u 0 finance-tracker-web chmod 644 /usr/share/nginx/html/privacy.html`, failed with "Read-only file system" (the container's root is read-only, and stays so); nothing changed. F7b fixes the image and the scripts. |
| 2026-10-02 | `8ede02e` (ops: answers typed, finish again, the rollback target) | F6c: the family report, the demo family, H4, the privacy policy, typed answers, Flyway V10, switch off. Two runs. The first, at 15:46:43Z, deployed by F6b's script: CI's 8 check runs completed with success; a fresh backup, then the restore test: PASS (dump 2026-10-02T1546Z, migration 10); `7a60020`'s images kept as `:7a60020…` and `:previous`; built and healthy; Flyway reported V10 up to date; the D-25 line off; the same numbers; F6c's 15 checks as expected; a fresh backup, then the restore test: PASS (dump 2026-10-02T1547Z). The owner's browser check reported the privacy page not opening, and `finish` at 15:51Z recorded "browser checks: no, smoke test: yes" as `finish-failed`, printing the rollback to `7a60020`. The rollback, with `rollback.sh`, 15:52Z to 15:53Z: the first confirmation, typed without "ROLLBACK", was refused with nothing changed; then `7a60020` came back with its images, healthy, the database and `finance.conf` untouched, the clone detached. The cause, found after F7's deploy failed the same way (the row above): F6b's script also sets `umask 077` (`deploy/tests/fixtures/f6b/deploy.sh:742`), so its `git merge --ff-only` rewrote `frontend/public/privacy.html`, which F6c changed, with mode 600, the build carried the mode into the image, and nginx, running as uid 101, couldn't read it: 403. It looked unreproducible because every check then used another image: production on `7a60020` (its own image, built from a 644 file) answered both `/privacy` and `/privacy.html` with 200 (10075 bytes), and `8ede02e`'s web image built on the laptop, whose checkout gives 664, answered both with 200 (20447 bytes). After the rollback, `git checkout main` in the interactive root shell (umask 0022) rewrote `privacy.html` as 644, and the second run's merge left it alone, so the second build read a 644 file. The second run, at 16:04:37Z, after `git checkout main`, deployed by F6c's script: step 1.2 named the last good deploy `7a60020`; step 1.3 listed the commits since it; CI's 8 check runs completed with success; a fresh backup, then the restore test: PASS (dump 2026-10-02T1604Z); built and healthy; Flyway reported V10 up to date; the D-25 line off; the same numbers; F6c's checks as expected; a fresh backup, then the restore test: PASS (dump 2026-10-02T1605Z, 1446577 bytes); images api `sha256:5bd35f99…`, web `sha256:ddf2a902…`. The browser checks, with the privacy page showing the new section this time, and the smoke test passed; `finish` at 16:06:50Z; `deploy/deploy.sh verify F6c` at 16:06:59Z: OK. |
| 2026-10-02 | `7a60020` (ops: a read-only role for checks, deploy.sh adopt) | F6b: D-35 to D-39, the read-only role for the deploy checks and `deploy.sh adopt`, Flyway V10, switch off. The first deploy with `deploy/deploy.sh`: OPS-1's script, as the clone at `46dedcd` held it. Once, before the run: the role `finance_checks` created with the command of [A read-only role for the deploy checks](#a-read-only-role-for-the-deploy-checks); the read-only proof: the INSERT answered "permission denied for table users", `COPY … TO PROGRAM` was refused, the role's own line read `finance_checks`, `f`, `t`, `on` (no superuser, a member of `pg_read_all_data`, read-only), and 0 proof rows. Three runs at 10:58Z to 10:59Z were refused at step 1.3, since the commit wasn't pushed yet; nothing changed. The run of 11:02:52Z, deploy.sh's first: CI waited about three minutes, then 8 check runs completed with success; 21 numbers before; a fresh backup, then the restore test: PASS (dump 2026-10-02T1105Z, 1444128 bytes, 19 tables, migration 9); the baseline `46dedcd` recorded, its images kept as `:46dedcd…` and `:previous`; built and healthy; Flyway applied V10 at 11:07:01Z in 0.019 s; started in 8.3 s; the D-25 line off; the same numbers after; F6b's 16 stage checks as expected; `finance.conf` not installed (unchanged); a fresh backup, then the restore test: PASS (dump 2026-10-02T1107Z, 1446365 bytes, migration 10); images api `sha256:4e96fa0c…`, web `sha256:5c11c27f…`. `finish` at 11:08Z ran before the browser checks and the smoke test, and the next pasted block was read as its two answers, so both were recorded as not passed; nothing was rolled back. Afterwards the browser checks with the switch off and the smoke test passed; `deploy/deploy.sh verify F6b` at 11:27:34Z: OK. `deploy.sh adopt` at 11:28Z refused with "nothing to adopt", since `last-good` already named `7a60020` with the running images. The state since: `last-good` names `7a60020` (source deploy); `history` ends with `baseline 46dedcd`, `good 7a60020` and `finish-failed 7a60020` (11:08:13Z); the latest run folder is the refused adopt's. |
| 2026-10-02 | `46dedcd` (docs: deploying with deploy.sh); the images stayed those built from `c26cff6` | OPS-1: the deploy scripts, no application change, Flyway V9, switch off. Deployed by hand (a pull, no build), as OPS-1's checklist said. CI: Frontend, Deploy scripts and Dependency updates (six runs) completed with success before the deploy; Backend's two runs were still in progress then and completed with success afterwards, with no backend change in OPS-1. Before: the numbers and the containers' image IDs and start times saved; a fresh backup, then the restore test: PASS (dump 2026-10-02T0847Z, 1444075 bytes, 19 tables, migration 9, `family_invites` 0). The pull from `c26cff6` to `46dedcd` brought exactly `7964a2b`, `f8e4c3a` and `46dedcd`; nothing under `backend`, `frontend`, `deploy/app`, `deploy/finance.caddy` or `deploy/pg-backup`, so no build, restart or install. After: the same numbers; the same containers, not restarted (api `sha256:842c2e4a…`, web `sha256:07b6eea4…`, started at 04:35Z); `deploy.sh` and `rollback.sh` executable; a fresh backup, then the restore test: PASS (dump 2026-10-02T0848Z, 1444075 bytes). Rehearsal `deploy/deploy.sh verify OPS-1` at 08:49:25Z: OK (health, Flyway V9, the D-25 line of 04:35:46Z, the stage's checks as expected, 21 numbers). |
| 2026-10-02 | `c26cff6` (feat(frontend): leaving, removal, return, owners) | F6a: leaving, removal, return, owners, Flyway V9 with no migration, switch off. Deployed with [Update the app](#update-the-app). CI: both runs for `c26cff6` completed with success before the deploy. Before: the numbers saved; a fresh backup, then the restore test: PASS (dump 2026-10-02T0434Z, 1449039 bytes, 19 tables, migration 9, `family_invites` 0). The pull from `e8f5ca0` to `c26cff6` brought exactly `12c0af3`, `dc99b2b`, `7dd43ea` and `c26cff6`; under `deploy/` only `RUNBOOK.md` changed, so `finance.conf` was not installed. The api healthy; Flyway "Schema app is up to date. No migration necessary." at 04:35:43Z; started in 7.8 s; "Family ledgers (D-25): off; the family endpoints answer 404" at 04:35:46Z. After: the same numbers as before the deploy; a fresh backup, then the restore test: PASS (dump 2026-10-02T0436Z, 1449047 bytes, 19 tables, migration 9, `family_invites` 0). The browser checks with the switch off passed, F5's and F6a's endpoints included. Smoke test with the test account, ending with Delete all my data; afterwards the family lines as before the deploy. For F7: the `edge` network's subnet is 172.19.0.0/16. |
| 2026-10-01 | `e8f5ca0` (feat(frontend): invites and taking a seat) | F5: invites and taking a seat, Flyway V9, switch off. Deployed with [Update the app](#update-the-app). CI green for `e8f5ca0`. Before, saved to a file by one function: the numbers; Flyway at version 8; a fresh backup, then the restore test: PASS (dump 2026-10-01T1655Z, 18 tables, migration 8). The pull from `31419bd` to `e8f5ca0` brought exactly `29af12b`, `0a1f990` and `e8f5ca0`; under `deploy/` only `pg-backup/finance.conf` changed. The api healthy; Flyway applied V9 ("9 - family invites") at 16:56:49Z in 0.028 s; the api logged "Family ledgers (D-25): off; the family endpoints answer 404". V9's checks: `ledger_invite` empty, its trigger `ledger_invite_check` present, `release_family_memberships` revoking invites. After: the same numbers as before the deploy. The new `finance.conf` installed; a fresh backup, then the restore test: PASS (dump 2026-10-01T1657Z, 19 tables, migration 9, `family_invites` 0). The browser checks with the switch off passed. Smoke test with the test account, ending with Delete all my data; afterwards the family lines as before the deploy. |
| 2026-10-01 | `31419bd` (feat(frontend): other currencies in the family pages) | F4e: the settlement lock and other currencies, switch off. Deployed with [Update the app](#update-the-app). Before, saved to a file: Flyway at version 7; 2 users, 2 settings, 2 ledgers, 2 members, 22 accounts, 33 categories, 10 counterparties, 138 entries, 0 import batches (the test account held the demo); every family count 0; nothing outside its ledger. A fresh backup, then the restore test: PASS with 18 tables and migration 7. Exactly the four F4e commits pulled; the deploy files unchanged. Flyway applied V8 ("8 - family record currencies") in 0.025 s; the api healthy, logging the switch as off. V8's checks: the three new columns, no record with a rate source, the posting guard knowing `FX_EXCHANGE`, the currency trigger present. After: the same numbers as before the deploy; a fresh backup's restore test PASS with migration 8; CI for `31419bd` green; the browser checks passed. Smoke test with the test account passed, and the family numbers afterwards equalled those before the deploy. |
| 2026-10-01 | `144ff6b` (feat(frontend): incomes, settlements and settling up) | F4d: settlements and family incomes, switch off. Deployed with [Update the app](#update-the-app). Before, saved to a file: Flyway at version 7; 2 users, 2 settings, 2 ledgers, 2 members, 22 accounts, 33 categories, 10 counterparties, 138 entries, 0 import batches (the test account held the demo); every family count 0; nothing outside its ledger. A fresh backup, then the restore test: PASS with 18 tables, migration 7 and the same counts. Exactly the four F4d commits pulled; the deploy files unchanged; both images rebuilt; the api logging the schema as up to date and the switch as off; the server at `main`, `144ff6b`. After: the same numbers as before the deploy. Browser: no switcher; no family option on a new expense or income; the family paths ended on the dashboard; the new endpoints answered 404. Smoke test with the test account: an ordinary expense and an ordinary income created, edited and deleted; Delete all my data, then the demo in ledger 13 (12 accounts, 18 categories, 10 counterparties, 138 entries); Delete all my data again, leaving ledger 14 with the starter rows only; the family numbers as before. |
| 2026-09-30 | `e7cdeb1` (feat(family): the payer's entry and family expenses from the personal editor) | F4c: the payer's side, switch off. Deployed with [Update the app](#update-the-app). Before: Flyway at version 7; 2 users, 2 settings, 2 ledgers, 2 members, 22 accounts, 33 categories, 10 counterparties, 138 entries, 0 import batches (the test account held the demo); every family count 0; no row outside its user's personal ledger. A restore test against the previous evening's dump failed with mismatches: it ran without a fresh backup, and the test account had loaded the demo since. After a fresh backup it passed with 18 tables, migration 7 and the same counts. The three F4c commits pulled; `finance.caddy` and `finance.conf` unchanged; both images rebuilt; the api healthy, logging the schema as up to date and the switch as off; the server at `main`, `e7cdeb1`. After: the same counts, every family count 0; `/api/me` held `"features":{"familyLedgers":false}`; no switcher in the header; New entry → Expense without a "Family expense" option; `/api/entries/1/family-payment` answered 404. Smoke test with the test account: an ordinary expense created, edited and deleted; Delete all my data; the demo loaded (12 accounts, 18 categories, 10 counterparties, 138 entries in its ledger); Delete all my data again, back to the starter rows. |
| 2026-09-30 | `4285d52` (feat(frontend): family expenses, balances and journal) | F4b: the family expenses interface, switch off. Deployed with [Update the app](#update-the-app). Before: Flyway at version 7; 2 users, 2 settings, 2 ledgers, 2 members, 20 accounts, 30 categories, 0 counterparties, 0 entries, 0 import batches; every family count 0; no row outside its user's personal ledger; restore test PASS with 18 tables, migration 7 and 0 for each of the four family counts. No migration: Flyway reported the schema up to date; the api logged the switch as off; both images new, with layers different from `:previous`; the server at `main`, `4285d52`. After: the same counts; every family count 0; `/api/me` held `"features":{"familyLedgers":false}`; no switcher in the header; `/family/7/expenses` ended on the dashboard; the personal pages as before; the smoke test with the test account passed. |
| 2026-09-30 | `5f5af66` (feat(frontend): personal pages with family rows) | F4a parts 5 and 6: family categories in personal ledgers, personal pages with family rows, switch off. Deployed with [Update the app](#update-the-app). Before: Flyway at version 7; 2 users, 2 settings, 2 ledgers, 2 members, 20 accounts, 30 categories, 0 counterparties, 0 entries, 0 import batches; restore test PASS with 18 tables and migration 7. No migration: Flyway reported the schema up to date; the api logged the switch as off; both images new, with layers different from `:previous`; the server at `main`, `5f5af66`. The new `finance.conf` installed after the deploy; restore test PASS with 18 tables, migration 7 and 0 for each of the four family counts. After: the same counts; every family count 0; no row outside its user's personal ledger; the browser checks passed; smoke test with the test account: 18 categories, each with Rename and Archive; reports without family lines; archiving Unallocated refused; Delete all my data re-provisioned the ledger. |
| 2026-09-30 | `71c3eb7` (feat(family): records, posting, balances and journal) | F4a parts 1 to 4: V7, family records and posting, switch off. Deployed with [Update the app](#update-the-app). Before: Flyway at version 6; 2 users, 2 settings, 2 ledgers, 2 members, 20 accounts, 30 categories, 0 counterparties, 0 entries, 0 import batches; restore test PASS with 14 tables and migration 6. Flyway applied V7 ("7 - family records and posting") in 0.077 s; the api healthy, logging the switch as off; the server at `main`, `71c3eb7`; the layers of both images different from `:previous`. After: the same counts, still 20 accounts; every family count 0 (family ledgers, members, categories, records, shares, links, journal rows, family accounts, start dates); no row outside its user's personal ledger; restore test on a new backup PASS with 18 tables and migration 7; the browser checks passed; the smoke test with the test account passed, the refusal to archive Unallocated included. |
| 2026-09-30 | `5a7e490` (feat(family): the family budget interface, behind the switch) | F3b: the family budget interface, switch off. Deployed with [Update the app](#update-the-app). Before: Flyway at version 6; 2 users, 2 settings, 2 ledgers, 2 members, 20 accounts, 30 categories, 0 counterparties, 0 entries, 0 import batches; no family ledger, member, category, split rule or share; no row outside its user's personal ledger; restore test PASS with 14 tables and migration 6. The api and web images both new, with layers different from `:previous`; Flyway reported the schema up to date; the api logged the switch as off; the server at `main`, `5a7e490`. After: the same counts; `/api/me` held `"features":{"familyLedgers":false}`; no switcher in the header; `/family/new` ended on the dashboard; the personal pages as before. Smoke test with the test account: the demo landed in its ledger 7 (12 accounts, 18 categories, 10 counterparties, 138 entries), and after Delete all my data it got a new ledger 8 with the starter rows only; the owner's rows unchanged each time. |
| 2026-09-29 | `9bbf427` (feat(family): family ledgers in the backend, behind a switch) | F3a: V6 and the family ledger backend, switch off. Deployed with [Update the app](#update-the-app). Before: Flyway at version 5; 2 users, 2 settings, 2 ledgers, 2 members, 20 accounts, 30 categories, 0 counterparties, 0 entries, 0 import batches; restore test PASS with 14 tables, migration 5, 2 ledgers and 2 members. The api healthy on the new image, the web container kept running, Flyway applied V6 ("6 - family ledgers"), and the api logged "Family ledgers (D-25): off; the family endpoints answer 404". After: the same counts; no family ledger, family member, family category, split rule or share; no row outside its user's personal ledger; `/api/family-ledgers` answered 404 and `/api/me` held `"features":{"familyLedgers":false}`; the owner's dashboard as before; the smoke test with the test account passed. A rollback to `ee9e498` ran by mistake at 21:06 UTC, and its image ran on V6 without problems, which confirms the rollback path; `9bbf427` was then deployed again (Flyway: schema up to date; the switch off), and the checks were repeated on it. |
| 2026-09-29 | `ee9e498` (test(architecture): enforce ledger scoping) | F2b: every query scoped by ledger_id; no migration. Deployed with [Update the app](#update-the-app). Before: Flyway at version 5; 2 users, 2 settings, 2 ledgers, 2 members, 20 accounts, 30 categories, 0 counterparties, 0 entries, 0 import batches; restore test PASS with 14 tables, migration 5, 2 ledgers and 2 members. The api healthy on the new image; the web container kept running on the same layers; Flyway reported the schema up to date. After: the same counts; no row without a ledger or outside its user's personal ledger; the owner's dashboard and accounts as before. Smoke test with the test account: the demo landed in its own ledger, delete-all re-provisioned a new ledger, and the owner's rows were unchanged. |
| 2026-09-29 | `1f1662f` (feat(db): V5 ledgers and membership) | F2a: migration V5 (ledger, ledger_member, ledger_id on five tables); no application code changed. Deployed with [Deploy a release whose only change is a migration](#deploy-a-release-whose-only-change-is-a-migration). Flyway at version 5, "ledgers and membership", success. After the migration: one PERSONAL ledger with one member, an active owner; no sub without a personal ledger; no row without a ledger or outside its user's personal ledger; counts unchanged (10 accounts, 15 categories, 0 counterparties, 0 entries, 0 import batches). The api healthy on the new image; the web image was rebuilt with the same layers, and its container kept running. Smoke test with a test account: after Load demo data, its 12 accounts, 18 categories, 10 counterparties and 138 entries all in its own ledger (3), the owner's rows unchanged, 2 ledgers in all and none without a member; after Delete all my data, re-provisioned into a new ledger (4) with only the starter rows (10 accounts, 15 categories), the owner's rows again unchanged. The new `finance.conf` installed after the migration; restore test PASS with 14 tables, migration 5, 2 ledgers and 2 members (the owner's and the test account's). |
| 2026-09-28 | `9287f0e` (Change log: D3a's commit) | D3a: one token refresh per session, and a late login callback lands in the app. Only the backend changed; Compose left `finance-tracker-web` running, as its rebuilt image has the same layers. |
| 2026-09-28 | `070fab2` (Change log: D2c's commit) | First deploy, steps 1 to 9 of this runbook (D3). Step 10, the import, is still to do. Certificate from Let's Encrypt (YE2), valid until 2026-12-27; Caddy renews it. |

### Production now

As of 2026-10-04T19:16:44Z (the second `switch off` of OPS-2's acceptance checks, answered `no`):

- `b6870f2` (OPS-2) on Flyway V10 since 2026-10-04T19:14:27Z (its `finish`), with family budgets switched on since
  2026-10-03T09:25:40Z. The containers run api `sha256:bb3bdef3d2a9…` (content `sha256:9315f0d932d0…`) and web
  `sha256:bba7ff04771a…` (content `sha256:2a6042327728…`), which `last-good` names with their contents
  (`finish=passed`). The manifest list `bb3bdef3…` is gone from the store while its container runs it; `:latest`
  names `sha256:41e46a733329…` and `sha256:827620f31177…`, the same contents.
- The rollback state: `/root/finance-tracker.previous` and `:previous` name `4510003` (api `sha256:6d6f35b8…`, web
  `sha256:bba7ff04…`), and `:f0425c0…` and `:8ede02e…` are kept. `rollback.sh`'s target is `4510003`, the commit
  OPS-2's run replaced, its api accepted by content. A rollback to `f0425c0` is pointless (its web serves `/privacy`
  with 403). The way back from the family budget is `deploy/deploy.sh switch off`, which works in this state (OPS-2's
  acceptance checks, the row of 19:16Z).
- Defect 5, open until OPS-2b is deployed: `run`'s step 1.2, `verify` and `switch`'s step 1.4 print "Image store:
  unknown, platform unknown", although `docker info` and `docker version` answer (the pre-flight of 2026-10-04). The
  content identities themselves are read, and the verdicts were right (`verify` named both images "the recorded
  image").
- A correction: OPS-2's report, and this section until OPS-2's deploy, gave an empty `run` of `4510003` as the way
  around defect 1 before OPS-2. With `last-good` naming api `bb3bdef3…`, which the pre-flight found gone from the
  store, that run would also have FAILED at step 3.2, after the merge (`docker tag` of a gone ID; the case
  `4510003_run_fails_on_a_gone_image`), unless the one-time remedy ran first. It was never needed.
- The clone holds three untracked copies of `.env`, `switch`'s (600 root).
- F7's production check (two test accounts) has not been run; it also covers OPS-2's smoke test, which wasn't
  performed in full.
- "CI on Ubuntu 26.04" on `b6870f2` failed in Backend (`RateApiTests`); OPS-2b looks for the cause.

## OPS-2's deploy checklist

**Commit:** OPS-2's last commit on `feature/family-budget`, "docs: OPS-2's deploy analysis and checklist", printed in
full by step 1; OPS-2's report names it in full, and the blocks of steps 4 and 8 take it in place of
`FULL_COMMIT_OF_STEP_1`. **Stage:** `OPS-2` (`deploy/checks/OPS-2.sql` and `OPS-2.expected`).
**The switch:** on (`FAMILY_LEDGERS_ENABLED=true` since 2026-10-03T09:25:40Z) before, during and after this deploy.
This checklist is for this deploy only; don't run it again for another.

Rules for every block: paste one block at a time, and the next only once the shell prompt `root@auth-1:…#` has
returned. A command that asks a question (`deploy.sh run`, `finish`, `switch`, `rollback.sh`) is alone in its block;
type its answer on the keyboard, after the question, never paste it.

What runs and why (OPS-2's analysis, from `4510003`'s scripts; `deploy/tests/run.sh` proves each, cases
`4510003_run_fails_on_a_gone_image`, `4510003_run_deploys_ops2` and `ops2_scripts_after_the_4510003_run`):

- `run` is `4510003`'s (bash reads the script before the merge): it has the page step (3.6), but no content
  identity, no D-42 refusal and no preserved dump. Its stage name `OPS-2` is accepted (any name of
  letters, digits, `.`, `_` and `-` that names the check files).
- Its step 1.2 only prints the images and never refuses for them. What can fail is step 3.2, after the merge: when the
  running images are the ones `last-good` names, it runs `docker tag <last-good's api_image>`, and in the containerd
  image store the manifest list `sha256:bb3bdef3…` was most likely deleted once 2026-10-04's run moved `:latest` away
  from it (a reproduction did so: OPS-2's change log, defect 4). Then `run` stops FAILED at 3.2, after the merge, with
  nothing built. The pre-flight below shows whether that ID is still in the store; if not, the one-time block after
  it makes `last-good` name the same content under the ID `finance-tracker-api:4510003` holds, and 3.2 then tags
  nothing ("a failed run's", harmless: `:4510003`, `:previous` and the previous-commit file stay as they are).
  A `switch` before this deploy recreates api from `:latest` and changes this again: don't switch until it's done.
- The switch on: `run` reads `FAMILY_LEDGERS_ENABLED=true` from the api container and expects "Family ledgers
  (D-25): on" in the api's log since its start. OPS-2 changes no application file, so the builds are the same
  content, Compose recreates neither container, and 4.1 and 4.2 find the lines of 2026-10-03T09:25:3xZ, as
  2026-10-04's run did (the log keeps 5 × 10 MB; the pre-flight shows they are still there).
- After it, OPS-2's `finish`, `verify`, `switch` and `rollback.sh` read what `4510003`'s `run` wrote; `finish`
  records the content identities. The rollback target is `4510003`, the commit the run replaced, with the images of
  its newest history line (api `bb3bdef3…`, web `bba7ff04…`); OPS-2's `rollback.sh` accepts `:4510003` by content.

**1. On the laptop: merge, push both branches, the full hash.**

```bash
# On the laptop
cd ~/dev/finance-tracker && git checkout main && git merge --ff-only feature/family-budget && git push origin main feature/family-budget && git log -1 --format='%H %s'
```

You should see the push and the commit named at the top, in full. CI starts with the push; `run` waits for it.

**2. On the server: the pre-flight, read only.** Send its whole output to the PM before going on.

```bash
# On the server (read only)
cd /opt/finance-tracker
docker version -f 'Docker server {{.Server.Version}}, {{.Server.Os}}/{{.Server.Arch}}'; docker compose version
docker info -f 'Image store: {{json .DriverStatus}}'
git log -1 --format='Clone: %H %s'; git status --short; echo "previous file: $(cat /root/finance-tracker.previous)"
cat /var/lib/finance-deploy/last-good
tail -n 12 /var/lib/finance-deploy/history
ls -1 /var/lib/finance-deploy/runs | tail -n 4
docker inspect -f '{{.Name}} image {{.Image}} content {{with .ImageManifestDescriptor}}{{.Digest}}{{end}} started {{.State.StartedAt}}' finance-tracker-api finance-tracker-web
docker image ls -a --no-trunc --format '{{.ID}} {{.Repository}}:{{.Tag}}' | grep finance-tracker-
docker image inspect --platform linux/amd64 -f '{{.Id}}' finance-tracker-api:4510003 finance-tracker-api:previous finance-tracker-api:latest finance-tracker-web:4510003 finance-tracker-web:previous finance-tracker-web:latest
docker logs --since "$(docker inspect -f '{{.State.StartedAt}}' finance-tracker-api)" finance-tracker-api 2>&1 | grep -E 'Schema "app" is up to date|Family ledgers \(D-25\)|Started FinanceTrackerApplication'
grep -c '^FAMILY_LEDGERS_ENABLED=true$' deploy/app/.env
```

You should see: the Docker version; the image store (`driver-type","io.containerd.snapshotter.v1` is the containerd
store; without it, the classic one); the clone at `4510003` with nothing changed; `last-good` naming `4510003`, api
`sha256:bb3bdef3…`, web `sha256:bba7ff04…`, `finish=passed …`; the history ending with `good 4510003 …`; the newest
run folders ending with `2026-10-04T141741Z-switch-on`; both containers' image IDs equal to `last-good`'s, with their
content identity (the platform manifest's digest; empty in the classic store); the listed images; the content
identities of the six tags (an error for `--platform` means an older daemon: send it); the three log lines of
2026-10-03T09:25:3xZ; and `1`. If the platform isn't `linux/amd64`, the `--platform` line says so: send it anyway.

**3. Only if the pre-flight shows it, and only once the PM approved it: the one-time remedy.** It is needed when
`last-good`'s `api_image` equals the running api's image ID but that ID is missing from the `docker image ls` lines
(the same for `web_image`), and the ID `finance-tracker-api:4510003` names has the running api's content (the
`--platform` line equals the container's content). The PM types the two IDs in full into its first line (and
`key=web_image` for web); it copies `last-good` first, changes that one line only if it holds exactly the old ID, and
prints the difference; otherwise it says REFUSED and changes nothing.

```bash
# On the server: only if the pre-flight shows last-good's image gone from the store, with the IDs the PM typed in full
key=api_image old=sha256:OLD_ID_IN_FULL new=sha256:NEW_ID_IN_FULL; cp -p /var/lib/finance-deploy/last-good /var/lib/finance-deploy/last-good.before-ops2; if [[ $key =~ ^(api|web)_image$ && $old =~ ^sha256:[0-9a-f]{64}$ && $new =~ ^sha256:[0-9a-f]{64}$ ]] && grep -qx "$key=$old" /var/lib/finance-deploy/last-good; then sed -i "s/^$key=$old\$/$key=$new/" /var/lib/finance-deploy/last-good; diff /var/lib/finance-deploy/last-good.before-ops2 /var/lib/finance-deploy/last-good; else echo "REFUSED: an ID not typed in full, or last-good's $key isn't $old; nothing changed"; fi
```

You should see two diff lines: `< api_image=sha256:bb3bdef3…` and `> api_image=sha256:6d6f35b8…` (the ID
`:4510003` holds).

**4. On the server: the deploy**, alone in its block, with the commit of step 1 in full; type its first 7 characters
at the confirmation.

```bash
# On the server
cd /opt/finance-tracker && deploy/deploy.sh run FULL_COMMIT_OF_STEP_1 OPS-2
```

It prints (`4510003`'s steps; "Deploying with deploy.sh" lists them): 1.2 the running images and `last-good`
`4510003`, its status `good`; 1.3 the commits since `4510003`, OPS-2's seven, "Migrations added: none", under
`deploy/` `RUNBOOK.md`, `checks/OPS-2.expected`, `checks/OPS-2.sql`, `common.sh`, `deploy.sh`, `rollback.sh` and
`tests/…`; 1.4 `deploy/finance.caddy and the postgres service unchanged`; 1.5 `CI: 10 check runs, every one completed
with success (Backend, Dependency updates, Deploy scripts, Frontend, Web image)`; 1.6 the OPS-2 files; 1.7 the 21
numbers; 1.8 a dump and `PASS`; 2 `Deploy … stage OPS-2, over 4510003`; 3.2 with the remedy "not the last good
deploy's: a failed run's … take no tag", without it "kept as :4510003… and :previous"; 3.3 both built (cached);
3.4 both "Running", not recreated; 3.5 healthy; 3.6 `Pages: 5 of 5`; 4.1 `Latest row: 10 claimed seats and family
deletion true … V10` with the line of 2026-10-03; 4.2 `Logged: "Family ledgers (D-25): on" at 2026-10-03T09:25:33Z`;
4.3 `The same numbers`; 4.4 the 5 lines of `OPS-2.expected`; 4.5 `finance.conf: not installed (unchanged)`; 4.6 a dump
and `PASS`; 5 the summary.

**5. The browser checks.** In a private window, at https://app.finance-nl.com: `/` (sign in; if a session ended, sign
in again: the api isn't expected to restart, 3.4 "Running"), `/privacy` and `/privacy.html` (the policy, with
"Family budgets"), `/favicon.svg` and `/favicon.ico` (the icon). Signed in: the header has the family budget switcher,
and `/api/me` holds `"familyLedgers":true`.

**6. The smoke test**, with the test account (never the owner's):

1. Sign in; the dashboard and the switcher load.
2. Switcher → "New family budget…": name "OPS-2 smoke", EUR, bring one personal expense category, add the member
   "Sam" without an account, the split rule equal; create it.
3. Activity → add an expense: 10.00 EUR, that category, paid by you from an account, split equally.
4. The expense shows your share 5.00 and Sam's 5.00; Balances says Sam owes you 5.00.
5. Personal → Entries: the family share and payment lines of that expense.
6. The family budget → Members → Leave: the confirmation says the budget and its records will be deleted ("Leave and
   delete the family budget"); confirm. The switcher no longer lists it.
7. Settings → "Delete all my data", typing `DELETE`: the empty dashboard.

**7. On the server: finish**, only after steps 5 and 6, alone in its block; type `yes` or `no` to each question.

```bash
# On the server: only after the browser checks and the smoke test
cd /opt/finance-tracker && deploy/deploy.sh finish
```

You should see the pages 5 of 5, the two answers, "Family numbers after the smoke test: the family lines as before
the deploy", `Images: api … (content …), web … (content …)` and "Done". Then, read only:

```bash
# On the server (read only)
cd /opt/finance-tracker && deploy/deploy.sh verify OPS-2
```

You should see "The images" with the store, each image's ID and content identity and `api: the recorded image …`
and `web: the recorded image …`, the pages 5 of 5, Flyway V10, the D-25 line "on", `OPS-2.expected`'s lines, the
numbers, and `verify OPS-2: OK`.

**8. Acceptance checks of OPS-2's fixes**, each changing nothing in production. Each block alone, in this order.

D-42, before: the rollback state.

```bash
# On the server (read only)
cat /root/finance-tracker.previous; docker image inspect -f '{{.Id}}' finance-tracker-api:previous finance-tracker-web:previous
```

D-42: a run of the same commit, alone in its block; it asks nothing.

```bash
# On the server
cd /opt/finance-tracker && deploy/deploy.sh run FULL_COMMIT_OF_STEP_1 OPS-2
```

You should see `REFUSED at "1.3 Preflight: git fetch, the commit and what it brings": … runs already, deployed and
finished cleanly (good, finish passed …): nothing to deploy (D-42)…`, before step 1.5 (no CI line, no dump), and
"Nothing changed". Then the block "D-42, before" again: the same three lines.

Defect 1: `switch off`, answered `no`, twice, each alone in its block.

```bash
# On the server: type no at the question
cd /opt/finance-tracker && deploy/deploy.sh switch off
```

You should see, before the question, "The latest run that isn't refused: …-<the commit's 7 characters>, finished",
then `REFUSED at "2. Confirmation": not confirmed` and "Nothing changed: .env untouched, api not restarted."

```bash
# On the server: type no at the question again
cd /opt/finance-tracker && deploy/deploy.sh switch off
```

You should see the same: it reaches "Type SWITCH OFF" again (`4510003`'s would have stopped at 1.4), and refuses at
the confirmation. The switch stays on.

Defect 4: `verify OPS-2` of step 7 shows each image's content identity. Defect 3 (the preserved before-dump) is
checked at the next deploy that makes dumps with OPS-2's `run`: its step 1.8 prints "The before-dump preserved as
…", and its run folder holds the copy.

**9. CI on Ubuntu 26.04 (D-44)**, only now, after step 8: on GitHub, Actions → "CI on Ubuntu 26.04" → "Run
workflow" on `main`. Its check runs belong to OPS-2's commit, which is deployed and finished, so they can't hold up a
deploy; never start it between a push and that commit's `run`. Send its result to the PM before 2026-10-19.

**10. Last, only for a failed deploy where the site is down: the rollback**, alone in its block; type `ROLLBACK
4510003` at its question.

```bash
# On the server: only for a failed deploy where the site is down
cd /opt/finance-tracker && deploy/rollback.sh 4510003
```

## Deploying with deploy.sh

Since OPS-1 (2026-10-02), a deploy is one command on the server, `deploy/deploy.sh run <commit> <stage>`. It does
what the F5 and F6a checklists did by hand, in their order and mostly with their commands, prints every step, keeps
everything it printed in a run folder, and stops at the first check that fails. It never rolls back by itself: after
a failure it prints the command of [Roll back with rollback.sh](#roll-back-with-rollbacksh), and you decide.
[Update the app](#update-the-app), by hand, stays as the fallback for when the script itself is at fault.

Each stage's deploy checklist (CLAUDE.md, "Deploy checklists") names the commit and the stage, and lists its
browser checks and smoke test. The stage's `deploy/checks/<stage>.sql` and `<stage>.expected` are in the commit.

**1. On the laptop: merge and push.**

```bash
# On the laptop
cd ~/dev/finance-tracker && git checkout main && git merge --ff-only feature/family-budget && git push origin main feature/family-budget
git log -1 --format='%H %s'
```

You should see the push, then the commit to deploy, in full, as the checklist names it. CI starts with the push;
`deploy.sh` waits for it, up to 20 minutes, so there's no need to wait here. If the merge says `Not possible to
fast-forward`, stop: `main` has commits the release doesn't.

**2. On the server: the deploy.** In a root shell on the server with a terminal (a plain `ssh root@2.28.108.199`):
the confirmation is read from the terminal. Paste this block alone, and type each answer after its question: since
F6c, lines that wait at the terminal before a question (a block pasted ahead) are discarded, never taken as answers;
the script of F6b and before took them. The block asks for the commit and the stage, as the checklist names them:

```bash
# On the server
cd /opt/finance-tracker && IFS= read -r -p 'Commit to deploy: ' commit && IFS= read -r -p 'Stage: ' stage && deploy/deploy.sh run "$commit" "$stage"
```

It prints each step under a `==` heading:

| Step | What it does | You should see |
| --- | --- | --- |
| 1.1 | The tools it needs, and the terminal | `Tools present: …` |
| 1.2 | The clone on `main` without changes, `.env` present, the running images, the last good deploy (from F6c with its status, its newest line in `history`), and (from F6b) the read-only role of the checks. Since OPS-2 (defect 4) the image store, the platform and each image's content identity, compared with the last good deploy's by content: the same ID, or `INFO` for another ID with the same content (a rebuild); a content that can't be read is REFUSED, with nothing changed | `HEAD: …`, `Running images: …`, `Image store: containerd, platform linux/amd64; content: …`, `Last good deploy: …`, `Its status, its newest line in the history: …`, `api: the recorded image, …` (or `INFO api: another image ID with the recorded content: …`), `The running images are the last good deploy's content`, `The checks run as finance_checks: superuser=false read_all_data=true writes=0 read_only=true` |
| 1.3 | `git fetch`; the commit must be `origin/main` and a fast-forward of `HEAD`; a commit the clone doesn't have says to push it from the laptop first. Since OPS-2 (D-42) the commit that already runs, after its clean finish, is REFUSED here, before CI, any backup and any image, naming `verify` and `switch`; after a stop or a finish that didn't pass it may run again, and step 3.2 then leaves `:previous` and `/root/finance-tracker.previous` as they are | the commits (`git log --oneline HEAD..<commit>`), the migrations added and the files changed under `deploy/`, as the checklist names them |
| 1.4 | `deploy/finance.caddy`, the postgres service and `postgres-init.sh` unchanged | `deploy/finance.caddy and the postgres service unchanged` |
| 1.5 | CI's check runs of the commit, through GitHub's API | one line per check run, then `CI: N check runs, every one completed with success (…)`; while CI runs, `CI still runs; checking again in 60 s` |
| 1.6 | The stage's check files in the commit | `deploy/checks/<stage>.sql and <stage>.expected are in …` |
| 1.7 | The numbers before (`deploy/checks/numbers.sql` of the running commit) | one `key=count` line per table, as before the last deploy unless users signed up or wrote since |
| 1.8 | A fresh backup, then the restore test; since OPS-2 (D-43) a copy of that dump in the run folder, mode 600, its name, size and SHA-256 in the run's `meta` | `Dump finance-….dump, … bytes, written at …, SHA-256 …`, the restore test's table, `PASS`, `The before-dump preserved as /var/lib/finance-deploy/runs/…/finance-….dump (mode 600, … bytes, SHA-256 …)` |
| 2 | The confirmation | the commit and its commits; type the commit's first 7 characters, or anything else to stop with nothing changed |
| 3.1 | `git merge --ff-only <commit>`, under `umask 022` since F7b, so the files it writes are 644 | `Fast-forward` and the files |
| 3.2 | The running images, if they are the last good deploy's content (since OPS-2: by content, from whichever image still holds it, since the containerd store deletes a manifest list no tag names), tagged with its commit and as `:previous`; tags older than the last three revisions removed | `kept as :<commit> and :previous` |
| 3.3, 3.4 | `docker compose build api`, `build web`, `up -d --no-deps api web` | `Image … Built`, the containers started (a container whose image is the same stays as it is) |
| 3.5 | Health, 60 × 5 s, as before | `api: healthy, web: healthy`, then `docker compose ps` and the prune |
| 3.6 | (F7b) The pages through the public address, as users reach them, Caddy included: `/` with `<div id="root">`, `/privacy` and `/privacy.html` with `<h1>Privacy policy</h1>`, both favicons, each 200 (the list is `PAGES` in `deploy/common.sh`, the host the site block's of `deploy/finance.caddy`) | one line per page, then `Pages: 5 of 5 as expected through https://app.finance-nl.com` |
| 4.1 | Flyway's latest row against the commit's highest migration, and its log line | `Latest row: 9 family invites true; the highest migration …: V9`, the line `Schema "app" is up to date…` (or `Migrating schema`, `Successfully applied`), and `Started: …` |
| 4.2 | The D-25 line against `FAMILY_LEDGERS_ENABLED` as production sets it (absent means off) | `Logged: "Family ledgers (D-25): off; the family endpoints answer 404" at …` |
| 4.3 | The numbers after, the same text as before | `The same numbers` |
| 4.4 | The stage's checks against `<stage>.expected` | the check's lines, `The stage's checks as expected` |
| 4.5 | `deploy/pg-backup/finance.conf`, installed if it changed | `finance.conf: not installed (unchanged)`, or `installed …` |
| 4.6 | A fresh backup, then the restore test; since OPS-2 (D-43) first a wait while a dump written now would get the before-dump's name (pg-backup names dumps by the UTC minute), then a check that the names differ and the preserved copy still has its SHA-256 | perhaps `Waiting for the next minute: …`, then `PASS` and `The dumps: before …, after …; the preserved copy as recorded (SHA-256 …)` |
| 5 | The summary | the text for [Deployed revisions](#deployed-revisions), then `Left for you: …` |

**3. The browser checks and the smoke test** of the stage's checklist. Finish them before step 4.

**4. On the server: finish**, only after the browser checks and the smoke test. Paste the block alone, then type the
two answers after their questions. Since F7b it checks the pages first, as step 3.6 does: if one fails, it records
the browser checks as not passed without asking (there is no way to answer yes), and asks only about the smoke test.

```bash
# On the server: only after the browser checks and the smoke test
cd /opt/finance-tracker && deploy/deploy.sh finish
```

It finishes the latest deployed run (refused runs, refused adopts included, don't count), asks whether the browser
checks and the smoke test passed (only `yes` or `no` counts; anything else asks again), compares the family numbers
with those before the deploy, and prints the final summary with your answers and their times: add it as the row of
[Deployed revisions](#deployed-revisions). It may run again for the same run (an answer was wrong, or the cause is
fixed): the latest answers count in the summary, in `last-good` (`finish=passed …` or `finish=not passed …`) and in
`history` (`good` or `finish-failed`). Not passed ends with `NOT PASSED: …`, exit status 3, and the rollback command.

**A page that fails is a `no`** (the lesson of F7's deploy, 2026-10-02). A 403, a 404 or wrong text on any page of
the checklist's browser checks means `no` to the browser checks in `finish`, however well everything else works; and
`deploy.sh switch on` runs only after a `finish` that passed cleanly. F7's `finish` recorded yes while `/privacy`
answered 403, and the switch went on with the published privacy policy unreachable (D-41).

**When it stops.**

- `REFUSED at "<step>": <why>`, then `Nothing changed`: preflight or the confirmation stopped it, before the merge.
  Only the fresh backup of 1.8 may have been taken. Fix the cause, then run the block of step 2 again:
  - `CI for … still runs after 20 minutes` or `GitHub has no check run`: wait for CI, or push. `did not succeed`:
    fix the commit; a cancelled run counts as not succeeded, so run it again on GitHub.
  - `is not origin/main` or `is not a fast-forward of HEAD`: the commit isn't the pushed `main`, or the server's clone
    has a commit of its own (`git log origin/main..HEAD`).
  - `runs already, deployed and finished cleanly` (D-42): there is nothing to deploy. Check what runs with
    `deploy/deploy.sh verify <stage>`; switch the family budget with `deploy/deploy.sh switch on|off`. Before OPS-2
    such a run went through as "Commits: none" and made the running commit its own rollback target.
  - `deploy/finance.caddy changes` or `the postgres service changes`: those stay manual; deploy with
    [Update the app](#update-the-app) and [Change the site file](#change-the-site-file).
  - `has no deploy/checks/<stage>.sql and <stage>.expected`: the commit lacks the stage's checks; add them on the
    laptop.
  - `the restore test did not PASS`: a `MISMATCH` right after someone signed in or wrote; run the block again.
  - `missing on this server: …`, `can't read /dev/tty` (ssh without a terminal), `another deploy.sh or rollback.sh
    holds …`, or `the clone isn't on main` (after a rollback: `git checkout main`): fix that, and run it again.
  - `the read-only role finance_checks is missing or cannot log in`, `finance_checks is a superuser`, `may insert,
    update or delete in …` or `is not as the runbook makes it`: see
    [A read-only role for the deploy checks](#a-read-only-role-for-the-deploy-checks), then run it again.
- `FAILED at "<step>": <why>`, after the merge: the deploy is half done, and the last lines are the exact rollback
  command. Nothing was rolled back. Since F7b it also says that the site may still work, but the deploy isn't good:
  don't run `finish`, and send the output (the run folder's `log` holds it). Judge first:
  - The pages (3.6): a page answers another status or lacks its text. Open it in a browser, and look at
    `cd /opt/finance-tracker/deploy/app && docker compose logs --tail 50 web`; a `403` with `Permission denied` is a
    file nginx can't read ([Troubleshooting](#troubleshooting)). The web container can't be fixed by hand: roll back,
    or deploy a fix.
  - Health, Flyway, the D-25 line: the new release doesn't run as it should. Look at
    `cd /opt/finance-tracker/deploy/app && docker compose logs --tail 100 api`, then roll back.
  - The numbers differ: the diff is printed and kept as `numbers.diff` in the run folder. Someone signing up or writing
    during the deploy shows up here too; if that explains every line, the release is fine: check it with
    `deploy/deploy.sh verify <stage>`, do the browser checks and the smoke test, then record it with
    [Adopt the running revision](#adopt-the-running-revision), and note it in the row. Otherwise roll back.
  - The stage's checks differ (`stage.diff`): roll back, unless `<stage>.expected` is what's wrong.
  - The restore test after the deploy: run `systemctl start pg-backup@finance.service && pg-restore-test finance
    </dev/null` again; a `MISMATCH` after activity passes the second time.
  - After fixing the cause, run the deploy again, with the same commit or a newer one. A failed or repeated run never
    replaces the last good deploy's images or its record: the summary then counts the commits from the last good
    deploy.
- `finish` with a `no` or different family numbers: `NOT PASSED`, and the rollback command; judge as above, and run
  `finish` again once the answers are right.

**Where things are.** `/var/lib/finance-deploy/` (mode 700, created by the first run):

- `runs/<UTC time>-<commit>/`, one folder per run: `log` (everything it printed), `meta`, `status` (`refused`,
  `deploying`, `failed`, `deployed`, `finished`, `finish-failed`), `numbers.sql` (the text it ran before and after),
  `numbers-before.txt`, `numbers-after.txt`, `numbers-finish.txt`, `numbers.diff`, `stage-<stage>.sql`,
  `stage-<stage>.expected`, `stage-<stage>.txt`, `stage.diff`, `restore-test-before.txt`, `restore-test-after.txt`,
  `summary.txt`, and since OPS-2 (D-43) the before-dump's copy, `finance-<UTC minute>.dump`, mode 600, whose name,
  size, SHA-256 and path `meta` holds (`before_dump`, `before_dump_size`, `before_dump_sha256`,
  `before_dump_copy`). A run refused at its confirmation keeps its copy too. A rollback's folder is `<UTC time>-rollback-<commit>/`, an adoption's `<UTC time>-adopt-<commit>/`
  (status `adopted`, or `refused`), and (F7) a switch's `<UTC time>-switch-on/` or
  `<UTC time>-switch-off/` (status `switch-on`, `switch-off`, `switching`, `failed` or `refused`; no
  `stage-*` or restore-test files, since it changes no migration and takes no backup).
- `last-good`: the commit, time and image IDs of the last good deploy, and how it became one (`source`: `baseline`,
  `deploy`, `rollback`, `adopt`), and since F6c `finish`, its finish's latest answer. `history`: one line per event
  (`baseline`, `good`, `finish-failed`, `rolled-back-from`, `rollback-to`, `adopted`, and since F7 `switch-on`,
  `switch-off`); a commit's status is its newest line (F6c), and a finish that passes adds `good`. Both are written by
  the scripts only; F6b's scripts read what OPS-1's wrote, F6c's what F6b's wrote.
- `contents` (OPS-2): one line per image ID the scripts have seen, `<image ID> <content identity>`, so that an ID's
  content is known after the image store deleted it; `last-good` also holds `api_content` and `web_content` since
  OPS-2 (a `last-good` without them, as `4510003`'s scripts wrote it, gets them worked out from the image, or from the
  container that runs it).
- The images: `finance-tracker-api:<commit>` and `finance-tracker-web:<commit>` for the last three revisions, and
  `:previous` for the last good deploy's, whose commit `/root/finance-tracker.previous` names, as
  [Update the app](#update-the-app) did. The lock: `/run/lock/finance-deploy.lock`.

**The preserved dumps** (D-43, decided by the PM after 2026-10-03, when the after-dump of 09:23:38Z replaced the
before-dump of 09:23:18Z: pg-backup names a dump by its UTC minute). Each run past step 1.8 keeps a copy of its
before-dump, about 1.5 MB today, which `rollback.sh` names when it refuses to go back below V7 (D-22): that copy is
the dump to restore. Nothing prunes them. Prune by hand, keeping at least the copies of the last three deploys and
of every deploy that added a migration. List them first, oldest first:

```bash
# On the server (read only)
ls -ltr --time-style=+%FT%TZ /var/lib/finance-deploy/runs/*/finance-*.dump; du -sh /var/lib/finance-deploy/runs
```

Then remove one copy at a time with `rm -i` and its full path, typed from that list, never a pattern; the run folder
and its other files stay.

**Image identity** (OPS-2, defect 4). In Docker's containerd image store, which production uses, an image's ID is the
digest of a manifest list, and BuildKit's attestations make a new one at every build, also of the same content
(api `6d6f35b8…`, `4517bb90…`, `a4e0c660…`, `bb3bdef3…`, all with the config `03ecad3b…`); the list that a tag no
longer names is deleted, even while a container runs it, and `docker tag` of its ID fails. Compose recreates a
container only when its manifest changed, so a rebuild of the same content leaves it running, while anything that
recreates it (a `switch`) starts it from the newer `:latest`. Since OPS-2 the scripts compare images by their content
identity: the platform manifest's digest in the containerd store (it names the config digest and every layer's), the
image ID, which is the config digest, in the classic store, read as `docker image inspect --platform <os/arch>` and a
container's `ImageManifestDescriptor`. Another ID with the same content is `INFO`; a content that can't be read is
never taken as the same.

**Checking at any time, read only:** `cd /opt/finance-tracker && deploy/deploy.sh verify <stage>` prints whether the
read-only role is as this runbook makes it (without it, a problem, and the database's checks below are skipped), the
images (since OPS-2: the store, each running image's ID and content identity against `last-good`'s; other content or
an unknown one is a problem, another ID with the same content `INFO`), the health of api and web, the pages of step 3.6 (since F7b; a page that fails is a problem), Flyway's latest row against the highest migration, the stage's checks against `<stage>.expected`, the
D-25 line (a warning if it is no longer in the retained log), and the numbers, then `verify <stage>: OK` or the
problems. It takes no backup and changes nothing.

**What the server needs:** `git`, `docker` with its Compose plugin, `flock`, `curl`, `python3`, `systemctl`,
`pg-restore-test` (the auth server's `deploy/backup/install.sh server`), `install`, `diff` and `paste`, which step 1.1
checks and names when one is missing; a terminal; HTTPS to `api.github.com` (unauthenticated: 60 requests an hour
from the server's address, one a minute while CI runs) and, since F7b, to the site itself (`https://app.finance-nl.com`,
through the server's public address, as the api reaches Keycloak); `/var/lib` for the state folder and `/run/lock`; and from F6b
the database role `finance_checks` ([A read-only role for the deploy checks](#a-read-only-role-for-the-deploy-checks)).
Every check, the numbers and Flyway's row are read as that role, through `docker compose exec -T postgres psql -U
finance_checks`, with read-only transactions asked for by `PGOPTIONS` too, as before F6b; `deploy.sh`, `finish`,
`verify` and `rollback.sh` stop without it (before F6b they read as `finance`).

**File modes** (F7b). The scripts run under `umask 077`, which keeps the state folder, the run folders and the copies
of `.env` to root; only the git commands that write the working tree (the merge, and `rollback.sh`'s checkout) run
under `umask 022`, so the files they write are 644, as an interactive root shell's checkout gives. Before F7b they wrote
the files a deploy changed as 600, and F7's web image held `privacy.html` that way (403). The web image no longer
depends on the clone's modes either (`frontend/Dockerfile`). A deploy runs the script of the commit the clone is at
when it starts (bash reads it before the merge): F7b's own deploy runs `f0425c0`'s, whose merge still writes F7b's
changed files as 600 and which has no page step; F7b's `finish`, `verify` and `rollback.sh` are F7b's own, and the
next deploy after F7b is the first whose merge writes 644.

**The first run** finds no `last-good`: it says so, and once you confirm, it records `HEAD` with the running images as
the last good deploy (`baseline` in `history`). For F6b that is OPS-1's commit with the images built from `c26cff6`,
the same application code.

**After a manual deploy** with [Update the app](#update-the-app), `deploy.sh`'s record is behind: its next run would
take the running images for a failed run's and tag nothing, and `rollback.sh` would offer the last deploy that
`deploy.sh` made. Once the manual deploy has passed its checks, record it with
[Adopt the running revision](#adopt-the-running-revision) (F6b).

**Decided after OPS-1's review** (2026-10-02, by the PM; built in F6b, as the sections below say):

- The exception in CLAUDE.md's "Production safety" for these scripts stays. Read-only becomes a property of a database
  role rather than of `PGOPTIONS`: the checks and the numbers run as the role `finance_checks` (no superuser, a member
  of `pg_read_all_data`, `default_transaction_read_only` set on the role, no password), created once by a command of
  this runbook; preflight and `verify` refuse without it.
- A cancelled CI run refuses the deploy, as built: it counts as not succeeded.
- A stop the owner judges harmless (for example numbers changed by users' activity during the deploy) and a manual
  deploy are both closed with `deploy.sh adopt`, which records the running revision as the last good deploy and
  changes nothing else.

## A read-only role for the deploy checks

Since F6b, `deploy.sh` and `rollback.sh` read the database as the role `finance_checks`: a login without a password
(the container's socket trusts local logins, as it does for `finance`), no superuser, a member of `pg_read_all_data`
(SELECT on every table, nothing else), allowed to connect to `finance`, and `default_transaction_read_only = on` set on
the role. A check can't write even if it turns that setting off, since the role has no privilege to write, and it
can't run a program (`COPY … TO PROGRAM`). Roles aren't part of `pg_dump`'s dump of the database, so the nightly
backup and `pg-restore-test` don't change. The app's own login `finance` owns the database and is no superuser
(`deploy/app/postgres-init.sh`); `postgres` is the container's superuser, for maintenance only.

**Once, before the first deploy that needs it (F6b):** create it, as the superuser. Then the read-only proof: the
first two commands must each answer `ERROR:  permission denied …`; the third
`finance_checks|f|t|on`; the fourth `0`, no proof row anywhere.

```bash
# On the server
cd /opt/finance-tracker/deploy/app && docker compose exec -T postgres psql -X -v ON_ERROR_STOP=1 -U postgres -d finance -c "CREATE ROLE finance_checks LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS; GRANT pg_read_all_data TO finance_checks; GRANT CONNECT ON DATABASE finance TO finance_checks; ALTER ROLE finance_checks SET default_transaction_read_only = on" </dev/null
```

```bash
# On the server (read only: every write here is refused, and the transaction rolls back anyway)
cd /opt/finance-tracker/deploy/app
docker compose exec -T postgres psql -X -U finance_checks -d finance -c "BEGIN READ WRITE; INSERT INTO app.users (keycloak_id, email, display_name) VALUES ('read-only-proof', 'proof@example.invalid', 'proof'); ROLLBACK" </dev/null
docker compose exec -T postgres psql -X -U finance_checks -d finance -c "COPY (SELECT 1) TO PROGRAM 'true'" </dev/null
docker compose exec -T postgres psql -X -At -U finance_checks -d finance -c "SELECT current_user, rolsuper, pg_has_role(oid, 'pg_read_all_data', 'MEMBER'), current_setting('default_transaction_read_only') FROM pg_roles WHERE rolname = current_user" </dev/null
docker compose exec -T postgres psql -X -At -U finance_checks -d finance -c "SELECT count(*) FROM app.users WHERE keycloak_id = 'read-only-proof'" </dev/null
```

**If `deploy.sh` says the role is wrong** (a superuser, a privilege to write, not read-only): drop it, then create it
again with the first block above.

```bash
# On the server: only if deploy.sh or verify said finance_checks is wrong
cd /opt/finance-tracker/deploy/app && docker compose exec -T postgres psql -X -v ON_ERROR_STOP=1 -U postgres -d finance -c "DROP OWNED BY finance_checks; DROP ROLE finance_checks" </dev/null
```

## Adopt the running revision

`deploy/deploy.sh adopt` (F6b) records the revision that runs, `HEAD` with the running images, as the last good
deploy: after a manual deploy with [Update the app](#update-the-app), or after a run that stopped for a reason you
judged harmless (numbers changed by users during the deploy, for example) and whose `verify`, browser checks and smoke
test passed. It shows the commit, the running images with their health, the last run's status and the last good
deploy, asks you to type `ADOPT` and the commit's first 7 characters, and then writes `last-good` and an `adopted` line
in `history`. It changes no git, image or container, and reads no database. Since F6c it goes by HEAD's status, its
newest line in `history`: it adopts HEAD after a `finish-failed`, and has nothing to adopt only when that line is
`good`, `adopted` or `baseline` and `last-good` names HEAD with the running images. The next `deploy.sh run` keeps its images under its commit's tag and `:previous`, and
`rollback.sh` can go back to it.

```bash
# On the server: only after a manual deploy, or a stopped run you judged harmless and checked
cd /opt/finance-tracker && deploy/deploy.sh adopt
```

## Switch the family budget on or off

`deploy/deploy.sh switch on|off` (F7) is the only way to change `FAMILY_LEDGERS_ENABLED` (D-25) in
`/opt/finance-tracker/deploy/app/.env`: never edit the file by hand. Preflight is `verify`'s (the
read-only role, health, the numbers before) plus HEAD must be the last good deploy and the latest
run that isn't refused must be finished, rolled back, adopted or a completed switch, never
mid-flight (since OPS-2, defect 1: a refused run, switch, adopt or rollback changed nothing and
never counts; on 2026-10-03 a switch refused at its confirmation blocked the next one, and on
2026-10-04 one refused as already on would have blocked `switch off`). Only real unfinished work
blocks `switch on`, and its refusal names the command that resolves it: a deploy without its
`finish` (`deploy/deploy.sh finish`), a deploy that stopped or failed its finish (judge it; roll
back, run it again, `finish` again or `adopt`), or a switch that changed `.env` or restarted api and
didn't complete (`deploy/deploy.sh switch off`). **`switch off` never refuses for any of these**,
nor for health, a HEAD that isn't the last good deploy, or images that aren't its content: it prints
each as a WARNING and goes on, since the way back must always work; it still needs a terminal, the
read-only role (for the numbers) and `.env`. "Nothing to switch" (REFUSED at 1.5) only when `.env`
and the running api both already have the value; after a switch that changed `.env` but stopped
before the restart, the same switch again restarts api. And (since F7b's
fourth commit, D-41) the pages of `PAGES` in `deploy/common.sh` through the public address, as
`verify` checks them: `switch on` refuses, changing nothing and before its question, while any page
fails ("REFUSED at "1.7 Preflight: the pages": a page isn't as expected (FAILED through
https://app.finance-nl.com: /privacy 403): family budgets aren't switched on while a page of the
list fails (D-41)"); `switch off` prints the same lines and a WARNING, and goes on, since the way
back must always work. Either way the summary has a line "Pages before the switch". It shows the
current line (never the rest of the file, so no secret of `.env` is shown), what it will become and
(since F7b) the numbers before, says the family endpoints will answer 200 to their members (or 404),
asks you to type `SWITCH ON` or `SWITCH OFF`, then: copies `.env` beside itself with a timestamp
(mode 600), changes or adds only that one line (never another, even on a bug: it refuses first),
restarts `api` alone with `docker compose up -d --no-deps api` (`web` keeps running), checks health
and the D-25 line against the new value, checks the numbers are the same before and after (nothing in
the database changes), prints api's and web's image ID and content identity before and after (since
OPS-2, defect 4: "unchanged" only for the same content; a container recreated with the same content
under another ID, as `:latest` rebuilt gives, makes `last-good` name the new ID; other content, or one
that can't be read, fails `switch on` after the change and is a WARNING for `switch off`), and writes
a `switch-on` or `switch-off` line in `history` and a summary for
[Deployed revisions](#deployed-revisions). It takes no backup and runs no restore test, since nothing
in the database changes. On a failure after the change it prints the exact way back
(since OPS-2 always `deploy/deploy.sh switch off`: back after a failed `switch on`, again after a
failed `switch off`) and stops: it never switches back by itself.

```bash
# On the server: only once the stage's deploy has finished (deploy.sh finish), never mid-deploy
cd /opt/finance-tracker && deploy/deploy.sh switch on
```

The way back, once real family records exist in production, is the same command the other way:

```bash
# On the server
cd /opt/finance-tracker && deploy/deploy.sh switch off
```

`switch off` leaves every family row in the database as it is; only the family endpoints answer 404
again (D-25), exactly as before F7.

Family budgets don't stay switched on while the published privacy policy (`/privacy`) is unreachable in production
(D-41, decided by the PM after F7's deploy): the way back is `switch off`, and switching on again waits for a deploy
whose `finish` shows the policy, with the browser checks passed. Extended after F7b's refused deploy (2026-10-03,
decided by the PM): they aren't switched on while any page of the list fails, which `switch on`'s preflight enforces;
`switch off` never refuses because of a page.

## Roll back with rollback.sh

> **Only for a deploy that failed its checks**, in a block of its own, never in one with a deploy. After a deploy that
> passed its checks, a rollback repairs nothing: it puts the previous commit and images back into production (as on
> 2026-09-29, after F3a). Before the block, make sure the deploy failed.

`deploy/rollback.sh <commit>` goes one step back: to the commit that HEAD's deploy replaced, as that run of `deploy.sh`
recorded it and printed it after the failure, and only while its images are still there under their commit's tag
(F6c, decided by the PM; OPS-1's rule skipped a commit whose `finish` failed: a deploy on top of a commit means you
accepted it). The block asks for the commit:

```bash
# On the server: only for a deploy that failed its checks
cd /opt/finance-tracker && IFS= read -r -p 'Commit to roll back to, as deploy.sh printed it: ' commit && deploy/rollback.sh "$commit"
```

It refuses, changing nothing, a commit the clone doesn't know, any commit but the one HEAD's deploy replaced (it names
that one), a commit whose images are missing or aren't the recorded ones (since OPS-2 by content: another ID with the
recorded content is `INFO`), without the read-only role (then it names
[Roll an update back](#roll-an-update-back), the manual way), and, by D-22, a commit below
V7 while production holds family records: the code before F4a can't read the entry kinds the family budget posts, so
that needs [Restore from a backup](#restore-from-a-backup) of the dump taken before the deploy. Otherwise every
migration is additive (D-22): the previous image runs on the newer schema, and Flyway in it ignores the migrations
it doesn't know.

It shows what it will do and asks you to type `ROLLBACK` and the commit's first 7 characters. Then it checks out the
commit (detached; under `umask 022` since F7b), tags its images as the ones to run, starts api and web
(`up -d --no-deps api web`) and checks their health, then (F7b) the pages of step 3.6, which it reports without
stopping: the commit gone back to may hold the fault, as F7's web image answers `/privacy` with 403. It leaves the
database and `/etc/pg-backup/finance.conf` as they are. `last-good` names that commit again,
`history` records the rollback, and the summary is in the run folder `<UTC time>-rollback-<commit>/`.

To go forward again: `cd /opt/finance-tracker && git checkout main`, then [Deploying with deploy.sh](#deploying-with-deploysh).

## Update the app

> **The manual procedure**, the fallback for when `deploy.sh` itself is at fault, and how OPS-1, which brought the
> script, was deployed. Normally use [Deploying with deploy.sh](#deploying-with-deploysh).

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
previous=$(cat /root/finance-tracker.previous); echo "previous: $(git log -1 --oneline "$previous")"
git log --oneline "$previous"..HEAD
git diff --stat "$previous" HEAD -- deploy/finance.caddy deploy/pg-backup
```

The second line prints the commit that ran before, as `/root/finance-tracker.previous` now holds it
for a rollback: the one in the first row of [Deployed revisions](#deployed-revisions). The next lists
the update's commits. The last command lists `deploy/finance.caddy` or
`deploy/pg-backup/finance.conf` if the update changed them; then also do the matching part below.

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
systemctl start pg-backup@finance.service && pg-restore-test finance </dev/null
```

You should see `PASS`. The new backup comes first: the dump taken before the update has the old
schema, which the new checks may not fit (V5's ledgers, for one; V7's family tables, for another).
Since F4a's parts 5 and 6 the checks also count the family records, shares, links and journal rows
(`family_records`, `family_shares`, `family_links`, `family_journal`), and since F5 the invites
(`family_invites`, V9's `ledger_invite`); in production they read 0 until the switch goes on (F7).
Install the version with `family_invites` only once the api that migrates to V9 runs.

## Deploy a release whose only change is a migration

Manual, from F2a's deploy; `deploy.sh` covers such a release too ([Deploying with deploy.sh](#deploying-with-deploysh)).

For a release that changes the database and nothing the app does, such as F2a's V5 (ADR 0003,
topic J): the steps of [Update the app](#update-the-app), written out here as step 5, with checks
before and after. Follow the section from top to bottom. The code is the same as before, so the
previous image runs on the new schema, and a rollback needs no restore.

**1. On the laptop:** merge the release into `main`, push it, and wait until CI is green
(<https://github.com/sergeyzoloto/finance-tracker/actions>). For the family budget the release is
on `feature/family-budget`:

```bash
# On the laptop
git fetch origin && git checkout main && git merge --ff-only origin/main && git merge --ff-only feature/family-budget && git push origin main
git log -1 --oneline
```

You should see the push, then the release's last commit. If a merge says `Not possible to
fast-forward`, stop: `main` has commits the release doesn't, and the release needs a rebase first.

**2. On the laptop:** check that the release changes no application code but the migration. Take
the running commit from [Deployed revisions](#deployed-revisions); for F2a it is `9287f0e`:

```bash
# On the laptop
git diff --stat 9287f0e origin/main -- backend/src/main frontend/src
```

You should see only the new `backend/src/main/resources/db/migration/V….sql`. It prints nothing
when step 1 was skipped, because `origin/main` is then still the running commit: do step 1 first.

**3. On the server:** the numbers before, read only, to compare afterwards:

```bash
# On the server (read only)
cd /opt/finance-tracker/deploy/app && docker compose exec -T postgres psql -X -A -U finance -d finance -c 'SET default_transaction_read_only = on' -c "SELECT version, description, success FROM app.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1" -c "SELECT (SELECT count(*) FROM app.users) AS users, (SELECT count(*) FROM app.user_settings) AS settings, (SELECT count(*) FROM app.account) AS accounts, (SELECT count(*) FROM app.category) AS categories, (SELECT count(*) FROM app.counterparty) AS counterparties, (SELECT count(*) FROM app.journal_entry) AS entries, (SELECT count(*) FROM app.import_batch) AS import_batches" </dev/null
```

Before F2a, on 2026-09-29: version `4`, and `1|1|10|15|0|0|0`.

**4. On the server:** a backup, and a restore test of it with the settings file still installed:

```bash
# On the server
systemctl start pg-backup@finance.service && pg-restore-test finance </dev/null
```

You should see `PASS`, with the migration before the release (`4` before F2a).

**5. On the server:** the update itself, the blocks of [Update the app](#update-the-app). Back up,
note the running commit for a rollback, and pull:

```bash
# On the server
systemctl start pg-backup@finance.service
cd /opt/finance-tracker && git rev-parse HEAD > /root/finance-tracker.previous && git pull --ff-only
previous=$(cat /root/finance-tracker.previous); echo "previous: $(git log -1 --oneline "$previous")"
git log --oneline "$previous"..HEAD
git diff --stat "$previous" HEAD -- deploy/finance.caddy deploy/pg-backup
```

You should see the commit that ran before (`previous: …`, as in [Deployed
revisions](#deployed-revisions)), the release's commits, and the last command may list
`deploy/pg-backup/finance.conf` (step 7 installs it). If it lists `deploy/finance.caddy`, the
release is more than a migration: stop, and follow [Update the app](#update-the-app) instead.

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

You should see `api: healthy` and all three containers `Up … (healthy)`. The frontend didn't
change, so `finance-tracker-web` keeps its earlier `Up` time: its rebuilt image has the same layers.

Then Flyway's lines:

```bash
# On the server
cd /opt/finance-tracker/deploy/app && docker compose logs api | grep -E 'Migrating schema|Successfully applied|Started FinanceTrackerApplication'
```

For F2a: `Migrating schema "app" to version "5 - ledgers and membership"`,
`Successfully applied 1 migration to schema "app", now at version v5`, and
`Started FinanceTrackerApplication`.

**6. On the server:** the migration's own checks, read only. For F2a:

```bash
# On the server (read only)
cd /opt/finance-tracker/deploy/app && docker compose exec -T postgres psql -X -A -U finance -d finance -c 'SET default_transaction_read_only = on' -c "SELECT version, description, success FROM app.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1" -c "SELECT l.type, count(DISTINCT l.id) AS ledgers, count(m.id) AS members, count(m.id) FILTER (WHERE m.role = 'OWNER' AND m.status = 'ACTIVE') AS active_owners FROM app.ledger l LEFT JOIN app.ledger_member m ON m.ledger_id = l.id GROUP BY l.type" -c "SELECT count(*) AS subs_without_ledger FROM (SELECT keycloak_id FROM app.users UNION SELECT user_id FROM app.user_settings UNION SELECT user_id FROM app.account UNION SELECT user_id FROM app.category UNION SELECT user_id FROM app.counterparty UNION SELECT user_id FROM app.journal_entry UNION SELECT user_id FROM app.import_batch UNION SELECT user_id FROM app.exchange_rate WHERE user_id IS NOT NULL) AS s (sub) WHERE NOT EXISTS (SELECT FROM app.ledger_member m WHERE m.user_sub = s.sub AND m.ledger_type = 'PERSONAL')" -c "SELECT (SELECT count(*) FROM app.account) AS accounts, (SELECT count(*) FROM app.category) AS categories, (SELECT count(*) FROM app.counterparty) AS counterparties, (SELECT count(*) FROM app.journal_entry) AS entries, (SELECT count(*) FROM app.import_batch) AS import_batches" -c "SELECT (SELECT count(*) FROM app.account WHERE ledger_id IS NULL) + (SELECT count(*) FROM app.category WHERE ledger_id IS NULL) + (SELECT count(*) FROM app.counterparty WHERE ledger_id IS NULL) + (SELECT count(*) FROM app.journal_entry WHERE ledger_id IS NULL) + (SELECT count(*) FROM app.import_batch WHERE ledger_id IS NULL) AS rows_without_ledger" -c "SELECT count(*) AS rows_outside_their_users_ledger FROM (SELECT ledger_id, user_id FROM app.account UNION ALL SELECT ledger_id, user_id FROM app.category UNION ALL SELECT ledger_id, user_id FROM app.counterparty UNION ALL SELECT ledger_id, user_id FROM app.journal_entry UNION ALL SELECT ledger_id, user_id FROM app.import_batch) AS r WHERE NOT EXISTS (SELECT FROM app.ledger_member m WHERE m.ledger_id = r.ledger_id AND m.user_sub = r.user_id AND m.ledger_type = 'PERSONAL')" </dev/null
```

You should see, for production as on 2026-09-29:

- `5|ledgers and membership|t`;
- `PERSONAL|1|1|1`: one ledger per sub, each with its one member, an active owner;
- `subs_without_ledger` 0;
- the counts of step 3 (`10|15|0|0|0`);
- `rows_without_ledger` 0 and `rows_outside_their_users_ledger` 0.

The queries were tried on 2026-09-29 against V1 to V5 in a throwaway local container, with the
owner's 10 accounts and 15 categories written before V5.

**7. On the server:** if the release changed `deploy/pg-backup/finance.conf`, as F2a does, install
it now, after the migration, and test it with a new backup:

```bash
# On the server
install -o root -g root -m 600 /opt/finance-tracker/deploy/pg-backup/finance.conf /etc/pg-backup/finance.conf
systemctl start pg-backup@finance.service && pg-restore-test finance </dev/null
```

You should see `PASS`, with `tables` 14 and `migration` 5 for F2a. `ledgers` and `members` each
equal the number of `users` in the same table at that moment, since every user has one personal
ledger with one member: 2 on 2026-09-29, the owner's and a test account's.

**8. A smoke test with a test account**, never your own: sign in with it in a private window, and on
its empty dashboard click **Load demo data**. Then, **on the server**, read only, with the test
account's email address:

```bash
# On the server (read only)
IFS= read -r -p 'Email address of the test account: ' email
cd /opt/finance-tracker/deploy/app && docker compose exec -T postgres psql -X -A -t -v ON_ERROR_STOP=1 -U finance -d finance -v email="$email" <<'SQL'
SET default_transaction_read_only = on;
SELECT keycloak_id AS sub FROM app.users WHERE email = :'email' \gset
SELECT 'its ledger ' || ledger_id || ', ' || role || ' ' || status FROM app.ledger_member WHERE user_sub = :'sub';
SELECT 'its rows: ' || string_agg(t || ' ' || n || ' (' || in_ledger || ' in its ledger)', ', ' ORDER BY t) FROM (SELECT t, count(*) AS n, count(*) FILTER (WHERE r.ledger_id = m.ledger_id) AS in_ledger FROM (SELECT 'accounts' AS t, ledger_id FROM app.account WHERE user_id = :'sub' UNION ALL SELECT 'categories', ledger_id FROM app.category WHERE user_id = :'sub' UNION ALL SELECT 'counterparties', ledger_id FROM app.counterparty WHERE user_id = :'sub' UNION ALL SELECT 'entries', ledger_id FROM app.journal_entry WHERE user_id = :'sub') AS r JOIN app.ledger_member m ON m.user_sub = :'sub' AND m.ledger_type = 'PERSONAL' GROUP BY t) AS counts;
SELECT 'everyone else: ledgers ' || (SELECT count(*) FROM app.ledger_member WHERE user_sub <> :'sub') || ', accounts ' || (SELECT count(*) FROM app.account WHERE user_id <> :'sub') || ', categories ' || (SELECT count(*) FROM app.category WHERE user_id <> :'sub') || ', counterparties ' || (SELECT count(*) FROM app.counterparty WHERE user_id <> :'sub') || ', entries ' || (SELECT count(*) FROM app.journal_entry WHERE user_id <> :'sub');
SELECT 'ledgers in all ' || count(*) || ', without a member ' || count(*) FILTER (WHERE NOT EXISTS (SELECT FROM app.ledger_member m WHERE m.ledger_id = l.id)) FROM app.ledger l;
SQL
```

You should see `its ledger N, OWNER ACTIVE`; each of its tables with all its rows in its ledger
(`accounts 12 (12 in its ledger)`, `categories 18 (18 …)`, `counterparties 10 (10 …)` and the
demo's 138 entries); `everyone else: ledgers 1, accounts 10, categories 15, counterparties 0,
entries 0`, which are your rows when nobody else has any; and `ledgers in all 2, without a member
0`. Note N.

Then, in the test account's window, **Settings → Delete all my data**. The empty dashboard it shows
provisions the account again. Paste the block again, and type the address at its prompt again. You
should see `its ledger M` with M greater than N: ledger N and its member are gone, and the new
ledger holds only the starter rows (`accounts 10`, `categories 15`; tables without rows are left
out). The `everyone else` line is unchanged, and `ledgers in all 2, without a member 0`. To remove
the test account too, [delete the user](#delete-a-user); afterwards `ledgers in all 1`.

**9.** Add the row to [Deployed revisions](#deployed-revisions), with the commit
`git log -1 --oneline` prints on the server.

**Rolling back** is [Roll an update back](#roll-an-update-back) without a restore: the previous
image runs on the new schema, and Flyway in it ignores the migration it doesn't know. For V5 the
whole test suite of the code before it passed on V5, which is the evidence. A restore of the dump
from step 4 is needed only if the migration damaged data, or left the database in a state that
neither image can work with; it loses everything written since the dump. A migration that fails
leaves nothing behind: Flyway runs it in one transaction, the api doesn't start, and the previous
image runs on the schema as it was.

## Roll an update back

The manual rollback of [Update the app](#update-the-app), the fallback for when `rollback.sh` itself is at fault.
After a deploy with `deploy.sh`, use [Roll back with rollback.sh](#roll-back-with-rollbacksh).

> **Only for an update that failed its checks.** After an update that passed them, this section
> repairs nothing: it undoes the release, putting the previous commit and images back into
> production. On 2026-09-29 it ran by mistake after F3a's successful deploy, and F3a had to be
> deployed again. Before pasting the block below, make sure the update failed.

First the commit that ran before the last update, as the update wrote it down, read only:

```bash
# On the server (read only)
cd /opt/finance-tracker && git log -1 --oneline "$(cat /root/finance-tracker.previous)"
```

It must be the commit that ran before the failed update: the newest row of [Deployed
revisions](#deployed-revisions) that isn't that update. If it isn't, stop: the file is from another
update. Then back to that commit and the images that ran with it:

```bash
# On the server
cd /opt/finance-tracker && previous=$(cat /root/finance-tracker.previous); echo "previous: $previous"
git checkout --detach "$previous" && git log -1 --oneline
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
- **Past V7, only while production holds no family record.** The images before F4a (`5a7e490` and
  older) can't read the entry kinds the family budget posts (`FAMILY_SHARE`, `FAMILY_PAYMENT` and
  the others): an entry list with one of them fails. While the switch is off in production (until
  F7) there are none, and the rollback needs no restore. Once the switch is on, going back past V7
  means restoring the dump taken before the update ([Restore from a backup](#restore-from-a-backup)).
  Check it before the block above; it must print `0`:

  ```bash
  # On the server (read only)
  cd /opt/finance-tracker/deploy/app && docker compose exec -T postgres psql -X -A -t -U finance -d finance -c "SELECT count(*) FROM app.family_record" </dev/null
  ```

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
# 1. Dump the current state first, and pause the nightly backup until step 6
systemctl start pg-backup@finance.service
systemctl stop pg-backup@finance.timer
# 2. Check that the chosen dump restores, without touching production
pg-restore-test finance "$dump" </dev/null
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

- Step 2 compares the restored copy of the dump you chose with the live database. It is the one
  restore test here that doesn't come right after its own dump: for an older dump, or after
  activity since the dump, the numbers differ, and what counts is `Restored in …s`.
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
2 and 4 to 6 here: new database passwords in `.env` are fine, because a dump holds the database
but not the logins, which `postgres-init.sh` creates. Copy the newest dump up:

```bash
# On the laptop
scp "$(ls -1 ~/backups/finance-nl-server/finance/finance-*.dump | tail -n 1)" root@2.28.108.199:/root/
```

Then restore it as above with `dump=$(ls -1 /root/finance-*.dump | tail -n 1)`; step 2's comparison
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
keeps for them. It can't be undone, except by restoring a backup.

**Keycloak's side needs the service client `automation-cli`**, which has been disabled since the
auth server's D4. Either enable it for this section: in the admin console, realm `myapps` →
**Clients** → `automation-cli` → **Enabled** on, and off again at the end. Or leave it disabled,
and do the Keycloak part in the admin console instead: the block "Without `automation-cli`" below
in place of the first one, and the user's deletion there at the end.

The first block only looks: it asks for the email address and prints the user's id, linked
providers, Keycloak sessions and the app's rows. It stops with a `STOP` line if kcadm can't sign in
or Keycloak has no user with that address.

```bash
# On the server
IFS= read -r -p 'Email address of the user: ' email
kc() { docker compose -f /opt/auth/docker-compose.yml exec -T keycloak /opt/keycloak/bin/kcadm.sh "$@" --config /tmp/kcadm.config </dev/null; }
sub=""
if ! kc config credentials --server http://localhost:8080 --realm myapps --client automation-cli --secret "$(cat /root/automation-cli.secret)"; then
  echo "STOP: kcadm could not sign in. Is automation-cli enabled?"
else
  sub=$(kc get users -r myapps -q email="$email" -q exact=true --fields id --format csv --noquotes)
  if [ -z "$sub" ]; then
    echo "STOP: Keycloak has no user with this email address."
  else
    echo "user: $sub"
    kc get users/$sub/federated-identity -r myapps --fields identityProvider
    echo "sessions: $(kc get users/$sub/sessions -r myapps --fields id --format csv --noquotes | grep -c .)"
    cd /opt/finance-tracker/deploy/app && docker compose exec -T postgres psql -X -A -t -U finance -d finance -v sub="$sub" <<'SQL'
SELECT 'users ' || count(*) FROM app.users WHERE keycloak_id = :'sub';
SELECT 'accounts ' || count(*) || ', categories ' || (SELECT count(*) FROM app.category WHERE user_id = :'sub') || ', entries ' || (SELECT count(*) FROM app.journal_entry WHERE user_id = :'sub') FROM app.account WHERE user_id = :'sub';
SELECT 'ledgers ' || count(*) FROM app.ledger_member WHERE user_sub = :'sub';
SELECT 'family ledgers ' || count(*) || ', owned ' || count(*) FILTER (WHERE role = 'OWNER') FROM app.ledger_member WHERE user_sub = :'sub' AND ledger_type = 'SHARED';
SQL
  fi
fi
```

You should see a line about logging in, the user's id (36 characters), then their linked providers
(`[ ]` for none), `sessions: 0`, and the numbers of rows. If `sessions` isn't 0, sign them out in
the admin console (realm `myapps` → **Users** → the user → **Sessions** → **Sign out**), wait 5
minutes, the lifetime of an access token, and run the block again.

**Without `automation-cli`**, look the user up in the admin console instead: realm `myapps` →
**Users**, search for the email address, open the user, and copy the **ID** field. Check their
**Sessions** tab as above. Then this block asks for the id and prints the app's rows:

```bash
# On the server
IFS= read -r -p 'ID of the user, from the admin console: ' sub
if [ -z "$sub" ]; then
  echo "STOP: no user id."
else
  cd /opt/finance-tracker/deploy/app && docker compose exec -T postgres psql -X -A -t -U finance -d finance -v sub="$sub" <<'SQL'
SELECT 'users ' || count(*) FROM app.users WHERE keycloak_id = :'sub';
SELECT 'accounts ' || count(*) || ', categories ' || (SELECT count(*) FROM app.category WHERE user_id = :'sub') || ', entries ' || (SELECT count(*) FROM app.journal_entry WHERE user_id = :'sub') FROM app.account WHERE user_id = :'sub';
SELECT 'ledgers ' || count(*) FROM app.ledger_member WHERE user_sub = :'sub';
SELECT 'family ledgers ' || count(*) || ', owned ' || count(*) FILTER (WHERE role = 'OWNER') FROM app.ledger_member WHERE user_sub = :'sub' AND ledger_type = 'SHARED';
SQL
fi
```

Then, in the same shell: a backup, and the app's rows in one transaction, as the app's own "Delete
all my data" deletes them (`UserDataService.deleteAll`). The block refuses to run without a user id:

```bash
# On the server, in the shell of the block above
if [ -z "$sub" ]; then
  echo "STOP: no user id. Run the block above again."
else
  systemctl start pg-backup@finance.service
  cd /opt/finance-tracker/deploy/app && docker compose exec -T postgres psql -X -1 -v ON_ERROR_STOP=1 -U finance -d finance -v sub="$sub" <<'SQL'
SELECT 'writer ' || set_config('app.writer', 'delete-all', true);
DELETE FROM app.user_settings WHERE user_id = :'sub';
DELETE FROM app.journal_entry WHERE user_id = :'sub';
DELETE FROM app.import_batch WHERE user_id = :'sub';
DELETE FROM app.account WHERE user_id = :'sub';
DELETE FROM app.category WHERE user_id = :'sub';
DELETE FROM app.counterparty WHERE user_id = :'sub';
DELETE FROM app.exchange_rate WHERE user_id = :'sub';
DELETE FROM app.transactions WHERE user_id IN (SELECT id FROM app.users WHERE keycloak_id = :'sub');
DELETE FROM app.categories WHERE user_id IN (SELECT id FROM app.users WHERE keycloak_id = :'sub');
SELECT 'family memberships released ' || app.release_family_memberships(:'sub');
DELETE FROM app.ledger WHERE id IN (SELECT ledger_id FROM app.ledger_member WHERE user_sub = :'sub' AND ledger_type = 'PERSONAL');
DELETE FROM app.users WHERE keycloak_id = :'sub';
SELECT 'ledgers left ' || count(*) FROM app.ledger_member WHERE user_sub = :'sub';
SQL
fi
```

You should see `writer delete-all`, one `DELETE n` per statement, with the numbers of the first
block, `family memberships released n`, with the `family ledgers` of the first block, `DELETE 1` for
the ledger, and `ledgers left 0`. The first statement lets the entries that a family budget posted
into the user's ledger go with the rest (V7's guard; `UserDataService.deleteAll` sets the same), for
this transaction only. The statement after the entries is the membership part of "Delete all my
data" (D-20), the database function `release_family_memberships` (V6, V7, V9) that
`UserDataService.deleteAll` calls too, after the user's entries, so that none of their postings uses
a family category any more: in each family ledger the user becomes a FORMER member without a sub,
named "Former member", their comments are erased from the records and the journal, their links
to records are detached, and their invites that are still pending are revoked (V9, F5); a family
ledger without another ACTIVE member with an account is deleted with its records, categories,
invites and members; otherwise its records and the others' balances stay, if
the user was its last owner, the ACTIVE member with an account who joined earliest becomes owner,
and a custom share of the user's above 0 turns the split rule into EQUAL, which the family's journal
shows. Deleting the personal ledger deletes its member with it (`ON
DELETE CASCADE`), and the statement finds the ledger by the member's sub, so it also removes the
ledger of a sub without a `users` row: the command-line importer (CLAUDE.md, "How to run the
importer") writes rows without one. A ledger that still holds a row of theirs makes the statement
fail, and the transaction with it. Deleting the `users` row would also delete the personal ledger,
by V5's trigger, but only for a sub that has that row.

Last, the Keycloak user. With `automation-cli`, in the shell of the first block:

```bash
# On the server, in the shell of the first block
if [ -z "$sub" ]; then
  echo "STOP: no user id. Run the first block again."
else
  kc delete users/$sub -r myapps && echo "deleted in Keycloak"
fi
docker compose -f /opt/auth/docker-compose.yml exec -T keycloak rm -f /tmp/kcadm.config </dev/null
```

You should see `deleted in Keycloak`; the last line deletes kcadm's session. Then disable
`automation-cli` again in the admin console. Without it: in the admin console, the user's page →
**Action** → **Delete**.

The dump taken first holds the user until it ages out, 14 days on the server and 60 on the laptop,
as the privacy policy says. On 2026-09-28 the test account of the first deploy was deleted with
these statements, as they were then, run in a `DO` block that also checked the total: 27 rows
(settings 1, accounts 10, categories 15, users 1), then the Keycloak user. Afterwards both blocks
were tried as they were written then, for an address and a `sub` nobody has: the first printed
`user: none`, and the second's `psql`, with a failing statement added at the end, ran its ten
`DELETE 0` and rolled back. `UserDataApiTests` runs the statements of the deleting block as they
are written here.

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
| `pg-restore-test finance` shows `MISMATCH` | The database changed after the dump: the test ran against an older dump, or something changed between the backup and the test. Run `systemctl start pg-backup@finance.service && pg-restore-test finance </dev/null` again. |
| `/privacy` (or another file of the web image) answers `403`, while `/` answers `200`; the web container logs `open() "/usr/share/nginx/html/…" failed (13: Permission denied)` | nginx, as uid 101, can't read that file: it is in the image with a mode only root may read, as after F7's deploy (`umask 077` in the deploy scripts' merge; see its row in [Deployed revisions](#deployed-revisions)). The web container can't be fixed by hand: its root filesystem is read-only, so `docker exec -u 0 finance-tracker-web chmod …` fails with "Read-only file system", and it stays read-only. Only a deploy fixes it (since F7b the image sets its own modes, whatever the clone's). If family budgets are on, switch them off first (D-41). |
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
