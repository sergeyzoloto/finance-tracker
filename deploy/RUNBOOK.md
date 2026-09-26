# Production runbook

Finance Tracker runs at https://app.finance-nl.com on the Hetzner server that already runs the auth
server (Keycloak at https://auth.finance-nl.com). This runbook sets it up once (steps 1–12), then
covers updates, restores and regular checks. Do the steps in order. Every step says what you should
see; if you see something else, stop and look it up under [Troubleshooting](#troubleshooting).

## How to read this

- Every code block starts with a comment saying where it runs: `# On the laptop` or
  `# On the server`. Paste one block at a time, and wait for it to finish.

- "On the server" means a root shell on the server. To open one, run this on the laptop:

  ```bash
  # On the laptop
  ssh root@2.28.108.199
  ```

  `exit` closes it. If you sign in as another user, run `sudo -i` first.

- `2.28.108.199` is the server: the address of auth.finance-nl.com. `dig +short auth.finance-nl.com`
  on the laptop prints it.

- Nothing has to be made up. Where a value must be looked up, the block prints it or asks for it.

## What runs where

```text
Internet ──:443──▶ caddy                                         stack /opt/auth (the auth server)
                    ├─ auth.finance-nl.com ──▶ keycloak ──▶ postgres (Keycloak's own)
                    └─ app.finance-nl.com
                         ├─ /api/*, /oauth2/*, /login/oauth2/*, /logout
                         │     ──network finance-tracker-proxy──▶ backend ──▶ postgres (the app's own)
                         │                                         stack /opt/finance-tracker/deploy/app
                         └─ every other path: files in the volume finance-tracker-www
```

| What | Where |
| --- | --- |
| The auth server's stack: Keycloak, its PostgreSQL, Caddy | `/opt/auth` on the server; `~/dev/auth_server/deploy` on the laptop |
| Finance Tracker's code | `/opt/finance-tracker` on the server, a clone of https://github.com/sergeyzoloto/finance-tracker |
| Its stack, compose project `finance-tracker-prod` | `/opt/finance-tracker/deploy/app`: `postgres`, `backend`, `frontend` |
| Its secrets | `/opt/finance-tracker/deploy/app/.env`, on the server only |
| The built frontend | Docker volume `finance-tracker-www`, which Caddy serves |
| The link between Caddy and the backend | Docker network `finance-tracker-proxy` |
| The database | container `finance-tracker-prod-postgres-1`, database `finance`, login `finance` |
| Backups | `/var/backups/finance-tracker` on the server (14 days), and a Hetzner Storage Box (all) |

**Why Caddy stays in `/opt/auth`.** Caddy there already owns ports 80 and 443 and the certificates,
so Finance Tracker only adds a site block to its Caddyfile and an override file that attaches it to a
shared network, and `/opt/auth/docker-compose.yml` itself stays unchanged. Moving Caddy into a stack
of its own would mean rewriting the auth server's compose file and moving its certificate volume,
for nothing that two sites need.

**Why the Keycloak client is `finance-tracker`, not a new public client.** The app signs in as a
backend-for-frontend ([docs/auth.md](../docs/auth.md)). The backend runs the authorization code flow
with PKCE S256 as the confidential client `finance-tracker`, and keeps the tokens in its session;
the browser never holds a token. A public client such as `finance-web` is for a single-page app
that holds tokens in the browser, and nothing in this app would use one. Step 3 sets up the
existing client for production instead. The backend already requires `finance-tracker` in every
token's `aud` claim (`spring.security.oauth2.resourceserver.jwt.audiences` in
`backend/src/main/resources/application.yml`).

## Memory budget

Every container on the server gets a memory limit. The auth stack's limits come with this setup
(step 8); until then its containers have none. Keycloak without a limit sizes its heap at 70% of
the **whole server's** memory.

| Container | Stack | Limit | Used in the local rehearsal |
| --- | --- | ---: | ---: |
| keycloak | /opt/auth | 1280 MiB (heap up to 70% of it) | 684 MiB |
| postgres (Keycloak's) | /opt/auth | 384 MiB | 46 MiB |
| caddy | /opt/auth | 128 MiB | 39 MiB |
| backend | finance-tracker-prod | 768 MiB (heap up to 60% of it) | 285–354 MiB |
| postgres (the app's) | finance-tracker-prod | 384 MiB | 75 MiB |
| **Total, always running** | | **2944 MiB** | **about 1.2 GiB** |
| frontend | finance-tracker-prod | 64 MiB, for seconds per deploy | |
| restore test | temporary | 256 MiB, for seconds per test | |

A 4 GB server shows about 3.7 GiB in `free -h`. After the limits, about 0.8 GiB is left for the
system, Docker and image builds; the Maven build is capped at 512 MiB of heap. Step 1 adds 2 GiB
of swap as a safety net for builds.

---

## 1. Check the server

```bash
# On the server
free -h
swapon --show
df -h /
docker --version
docker compose version
git --version
cd /opt/auth && docker compose ps
```

You should see:

- `free -h`: `total` in the `Mem:` row is at least `3.5Gi` (a 4 GB server shows about `3.7Gi`).
  If it is less, stop, and give the server more memory first (Hetzner Console → the server →
  Rescale).

- `df -h /`: at least `10G` under `Avail`.
- Docker 27 or newer, and Docker Compose v2 or newer.
- `docker compose ps`: `postgres`, `keycloak` and `caddy`, all `Up`.

If `swapon --show` printed nothing, the server has no swap. Add 2 GiB:

```bash
# On the server
fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
echo '/swapfile none swap sw 0 0' >> /etc/fstab
swapon --show
```

You should see a line for `/swapfile` with size `2G`.

## 2. DNS: point app.finance-nl.com at the server

```bash
# On the laptop
dig +short auth.finance-nl.com
dig +short app.finance-nl.com
```

You should see `2.28.108.199`, then nothing.

In the browser, sign in to Squarespace and open **Domains → finance-nl.com → DNS → DNS Settings**.
Under **Custom records**, add a record:

| Host | Type | TTL | Data |
| --- | --- | --- | --- |
| `app` | `A` | leave the default | `2.28.108.199` |

Save. Leave every other record as it is. Only an A record is needed: auth.finance-nl.com has no
AAAA record either (`dig +short AAAA auth.finance-nl.com` prints nothing).

```bash
# On the laptop
dig +short app.finance-nl.com
dig +short app.finance-nl.com @1.1.1.1
```

Both should print `2.28.108.199`. That usually takes minutes, sometimes a few hours. Steps 3 to 7
don't need it; step 8 does.

## 3. Keycloak: set up the client finance-tracker

In the browser, open https://auth.finance-nl.com/admin/ and sign in as your admin (realm
`master`). In the realm list at the top left, choose **myapps**.

**3.1** Open **Clients**. If `finance-tracker` isn't in the list, create it: **Create client**,
Client type `OpenID Connect`, Client ID `finance-tracker`, **Next**; **Client authentication**
On, only **Standard flow** ticked, **Next**, **Save**. Then, on its **Roles** tab, **Create role**
with Role name `user`, and **Save**.

**3.2** Open **finance-tracker**, tab **Settings**. Set these, then **Save** at the bottom:

| Field | Value |
| --- | --- |
| Valid redirect URIs | `https://app.finance-nl.com/login/oauth2/code/keycloak`, the only entry. Delete any `http://localhost:…` entries: they are development values. |
| Valid post logout redirect URIs | `https://app.finance-nl.com/`, the only entry |
| Web origins | empty (the browser never calls Keycloak from the app's pages) |
| Client authentication | On |
| Authentication flow | only **Standard flow** ticked. **Direct access grants** off: nobody types a password into anything but Keycloak's own page. |
| Require PKCE | On |
| PKCE Method | `S256` |

**3.3** Tab **Client scopes** → **finance-tracker-dedicated** → **Mappers**. If a mapper named
`finance-tracker audience` of type Audience is listed, open it and check it against the table.
If not, **Add mapper → By configuration → Audience**, and fill it in:

| Field | Value |
| --- | --- |
| Name | `finance-tracker audience` |
| Included Client Audience | `finance-tracker` |
| Included Custom Audience | empty |
| Add to ID token | Off |
| Add to access token | On |
| Add to lightweight access token | Off |
| Add to token introspection | On |

**Save**. Without this mapper, Keycloak leaves `finance-tracker` out of the `aud` of the tokens it
issues to `finance-tracker` itself, and the backend rejects them.

**3.4** Open **Users**, search for your own email address, and open your user.

- If it isn't there: **Add user**, fill in email, first and last name, turn **Email verified** on,
  **Create**. Then tab **Credentials → Set password**: at least 12 characters, **Temporary** off.

- Tab **Role mapping → Assign role**, switch the filter to **Filter by clients**, tick
  `finance-tracker` `user`, **Assign**.

**3.5** Open **Users → testuser**, tab **Role mapping**. Tick `finance-tracker` `user` and click
**Unassign**. The test user's password is stored in the auth server's files on the laptop, so it
must not open your ledger. It keeps its other roles, so the auth server's smoke test still works.

**3.6** Leave the admin console open: step 6 needs **Clients → finance-tracker → Credentials →
Client Secret**.

## 4. Put the code on the server

First push the commit that contains `deploy/` to GitHub (`git push` in `~/dev/finance-tracker` on
the laptop). Then:

```bash
# On the server
git clone https://github.com/sergeyzoloto/finance-tracker.git /opt/finance-tracker
git -C /opt/finance-tracker log -1 --oneline
ls /opt/finance-tracker/deploy
```

The commit should be the same as `git log -1 --oneline` prints on the laptop, and `deploy` should
list `app  backup  caddy  RUNBOOK.md`.

## 5. Create the shared network and volume

Both stacks use them and neither owns them, so a `docker compose down` in either stack leaves them
alone.

```bash
# On the server
docker network create --internal finance-tracker-proxy
docker volume create finance-tracker-www
```

You should see a long ID, then `finance-tracker-www`. `--internal` means the network only connects
Caddy and the backend; the backend reaches the internet through its own stack's network.

## 6. Write the secrets

```bash
# On the server
cd /opt/finance-tracker/deploy/app
if [ -e .env ]; then echo "STOP: .env exists already. Keep it and go on with step 7."; else install -m 600 /dev/null .env && printf 'POSTGRES_PASSWORD=%s\nAPP_DB_PASSWORD=%s\n' "$(openssl rand -hex 24)" "$(openssl rand -hex 24)" >> .env && echo "Database passwords written."; fi
```

In the admin console, open **Clients → finance-tracker → Credentials** and click the copy icon
next to **Client Secret**. Then run this, paste the secret when asked (it isn't shown), and press
Enter:

```bash
# On the server
cd /opt/finance-tracker/deploy/app && sed -i '/^KEYCLOAK_CLIENT_SECRET=/d' .env && read -rsp 'Paste the client secret, then press Enter: ' secret && echo && printf 'KEYCLOAK_CLIENT_SECRET=%s\n' "$secret" >> .env; unset secret
```

Check it without printing the secrets:

```bash
# On the server
cd /opt/finance-tracker/deploy/app
ls -l .env
awk -F= '{ print $1, length($2), "characters" }' .env
```

You should see `-rw------- 1 root root`, then `POSTGRES_PASSWORD 48`, `APP_DB_PASSWORD 48` and
`KEYCLOAK_CLIENT_SECRET 32`. Nothing in `.env` has to be kept anywhere else: a restore creates the
database login anew, and Keycloak can regenerate the client secret.

## 7. Build and start the app

```bash
# On the server
cd /opt/finance-tracker/deploy/app
docker compose build
```

The first build takes 5 to 15 minutes. It should end with `Image finance-tracker-backend Built`
and `Image finance-tracker-frontend-static Built`.

```bash
# On the server
cd /opt/finance-tracker/deploy/app
docker compose up -d
for i in $(seq 60); do s=$(docker inspect -f '{{.State.Health.Status}}' finance-tracker-prod-backend-1); [ "$s" = healthy ] && break; sleep 5; done; echo "backend: $s"
docker compose ps -a
docker compose logs frontend
docker compose logs backend | grep -E 'Migrating|Started|ECB'
```

You should see:

- `backend: healthy`.
- `docker compose ps -a`: `postgres` and `backend` `Up … (healthy)`, `frontend` `Exited (0)`. The
  frontend container copies the built files into the volume and stops; that is its job.

- `Published 4 assets and index.html` (the number can differ).
- On the first start, `Migrating schema "app" to version …` lines; then `Started FinanceTrackerApplication` and
  `Loaded … ECB rates`.

The app isn't reachable from outside yet; step 8 connects it.

## 8. Connect Caddy

Wait for DNS (step 2):

```bash
# On the server
getent hosts app.finance-nl.com
```

This should print `2.28.108.199     app.finance-nl.com`. If it prints nothing, wait and try again:
Let's Encrypt can't issue the certificate before the name points at the server.

```bash
# On the server
cd /opt/auth
ls docker-compose.override.yml
```

This should say `No such file or directory`. If the file exists, stop: this step would replace it.

Add the site and the override, then check both before anything restarts. A Caddyfile that Caddy
can't read would take auth.finance-nl.com down too.

```bash
# On the server
cd /opt/auth
cp -p Caddyfile Caddyfile.before-finance-tracker
cp /opt/finance-tracker/deploy/caddy/docker-compose.override.yml docker-compose.override.yml
grep -q '# BEGIN finance-tracker' Caddyfile || cat /opt/finance-tracker/deploy/caddy/app.finance-nl.com.caddy >> Caddyfile
grep -c '^app.finance-nl.com {' Caddyfile
docker run --rm -e KC_DOMAIN=auth.finance-nl.com -v /opt/auth/Caddyfile:/etc/caddy/Caddyfile:ro caddy:2 caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile 2>&1 | tail -n 1
docker compose config --quiet && echo "compose files OK"
```

You should see `1`, `Valid configuration` and `compose files OK`. If you see anything else, don't
go on: [undo this step](#undo-step-8).

Apply it. Compose recreates all three containers of the auth stack: Caddy to join the network and
mount the volume, Keycloak and its PostgreSQL for their memory limits. Logins are unavailable for
about a minute while Keycloak restarts.

```bash
# On the server
cd /opt/auth
docker compose up -d
sleep 60
docker compose ps
docker compose logs caddy 2>&1 | grep 'app.finance-nl.com' | grep -iE 'certificate obtained|error' | tail -n 3
docker exec finance-tracker-prod-backend-1 curl -s -o /dev/null -w '%{http_code} %{remote_ip}\n' https://auth.finance-nl.com/realms/myapps
```

You should see:

- All three containers `Up`.
- `certificate obtained successfully` for `app.finance-nl.com`. If there's no line yet, run the
  `logs` line again a minute later.

- `200` and an address starting with `172.` or `192.168.`, not `2.28.108.199`: the backend reaches
  Keycloak through Caddy on the shared network.

### Undo step 8

```bash
# On the server
cd /opt/auth
rm docker-compose.override.yml
cat Caddyfile.before-finance-tracker > Caddyfile
docker compose up -d
```

## 9. Check it from outside

```bash
# On the laptop
curl -sI https://app.finance-nl.com/ | head -n 1
curl -s -o /dev/null -w '%{http_code}\n' https://app.finance-nl.com/api/me
curl -s -o /dev/null -w '%{redirect_url}\n' https://app.finance-nl.com/oauth2/authorization/keycloak | grep -o 'redirect_uri=[^&]*'
cd ~/dev/auth_server/deploy && ./smoke-test.sh
```

You should see `HTTP/2 200`, `401`, `redirect_uri=https://app.finance-nl.com/login/oauth2/code/keycloak`,
and the auth server's smoke test with only ✅ lines.

In a private browser window, open https://app.finance-nl.com. It sends you to Keycloak's login
page; sign in with your own user (step 3.4). You land on the dashboard of an empty ledger; the
**Accounts** page lists the starter accounts (Cash, Current account, …).

## 10. Set up backups

Every night at about 03:30 server time, `deploy/backup/backup.sh` dumps the database into
`/var/backups/finance-tracker`, deletes dumps older than 14 days there, and copies new dumps to a
Hetzner Storage Box. The copies on the Storage Box are never deleted by the script.

**10.1 Order the Storage Box.** First print where the server is:

```bash
# On the server
curl -s http://169.254.169.254/hetzner/v1/metadata/availability-zone; echo
```

This prints something like `fsn1-dc14`; the letters before the dash are the location. In the
Hetzner Console, open **Storage Boxes → Create Storage Box**. The smallest size is plenty. Pick a
**different location** from the server's, so one data center's outage can't take both. Once it
exists, in its settings:

- turn **SSH support** on;
- reset its password and keep the new one at hand for 10.2 (it is needed once);
- turn on **automatic snapshots**, daily, keeping 30. They protect the copies even from someone
  who gets hold of the server's key.

Its page shows the username: `u` followed by digits.

**10.2 Connect the server to it.**

```bash
# On the server
apt-get install -y rsync
install -d -m 700 /etc/finance-tracker /root/.ssh
read -rp 'Storage Box username (u followed by digits): ' u && printf 'OFFSITE=%s@%s.your-storagebox.de\n' "$u" "$u" > /etc/finance-tracker/backup.env; cat /etc/finance-tracker/backup.env
```

You should see `OFFSITE=` followed by your username, `@`, and your username again with
`.your-storagebox.de`.

```bash
# On the server
ssh-keygen -t ed25519 -N '' -C finance-tracker-backup -f /root/.ssh/finance-tracker-backup
. /etc/finance-tracker/backup.env && ssh -p 23 "$OFFSITE" install-ssh-key < /root/.ssh/finance-tracker-backup.pub
```

It asks you to confirm the Storage Box's host key (type `yes`) and for the Storage Box password
(paste it). Then check that the key works without a password:

```bash
# On the server
. /etc/finance-tracker/backup.env && ssh -p 23 -i /root/.ssh/finance-tracker-backup -o BatchMode=yes "$OFFSITE" ls && echo "key works"
```

You should see `key works`, without a password prompt.

**10.3 Install the timer and run the first backup.**

```bash
# On the server
install -m 644 /opt/finance-tracker/deploy/backup/finance-tracker-backup.service /opt/finance-tracker/deploy/backup/finance-tracker-backup.timer /etc/systemd/system/
systemctl daemon-reload
systemctl start finance-tracker-backup.service
journalctl -u finance-tracker-backup.service -n 5 --no-pager
```

You should see `Dumped … to /var/backups/finance-tracker/finance-….dump` and
`Copied to u…:finance-tracker/`.

```bash
# On the server
systemctl enable --now finance-tracker-backup.timer
systemctl list-timers finance-tracker-backup.timer
date
```

You should see the next run tonight, between 03:30 and 03:45 in the time zone `date` shows.

## 11. Test a restore

`deploy/backup/restore-test.sh` restores a dump into a temporary PostgreSQL container without a
network, compares it with the live database, checks that every entry still balances, and removes
the container. Production isn't touched.

```bash
# On the server
/opt/finance-tracker/deploy/backup/restore-test.sh
```

You should see `Restored without errors.`, a table where the restored and live numbers match, and
`PASSED`.

Then test the copy on the Storage Box the same way:

```bash
# On the server
. /etc/finance-tracker/backup.env && rm -rf /root/offsite-check && rsync -a -e 'ssh -p 23 -i /root/.ssh/finance-tracker-backup' "$OFFSITE:finance-tracker/" /root/offsite-check/
/opt/finance-tracker/deploy/backup/restore-test.sh "$(ls /root/offsite-check/finance-*.dump | tail -n 1)"
rm -r /root/offsite-check
```

You should see `PASSED` again.

## 12. Import the Excel ledger

Do this only once steps 10 and 11 have passed. The import runs in the browser, as the signed-in
user, so it lands in your ledger. Nothing is copied onto the server's disk.

**12.1 Export the files.** Export the Excel workbook's sheets as CSV into
`~/dev/finance-tracker/data/private/` on the laptop, the same way as for the local importer
(CLAUDE.md, "How to run the importer"): `BalanceSheetItems.csv`, `CashFlowItems.csv`, and
`Transactions.csv` with **all** transactions (`Transactions_example.csv` is only a sample). Add an
opening balances file (columns `line,currency,amount,date`) if you use one. `data/private/` is
git-ignored; never commit it.

**12.2 Dry run.** Open https://app.finance-nl.com/import, choose the files, and click
**Check (dry run)**. A large ledger takes a minute. The dry run imports everything, reports, and
undoes it. Read the report:

- The top line must say **No errors**. If it lists errors, fix those rows in Excel, export again,
  and check again.

- Read **Warnings** and **Exchange gains imported as income, to review**.
- **Balances after the import**: compare every account and currency with the balance sheet in
  Excel, as of the last transaction's date. They must match to the cent. If one doesn't, don't
  commit: find the cause in Excel, export again, check again.

**12.3 Back up first.**

```bash
# On the server
systemctl start finance-tracker-backup.service && journalctl -u finance-tracker-backup.service -n 2 --no-pager
```

You should see `Dumped …` and `Copied to …`.

**12.4 Commit.** Back in the browser, with the same files still chosen, click **Commit import** and
confirm. You should see **Imported N entries**, with the same N as the dry run.

**12.5 Check.**

- **Accounts**: the balances match Excel again.
- Open https://app.finance-nl.com/api/reports/integrity: it should show `[]`.
- **Dashboard** and **Entries** show the ledger.

**12.6 Back up again.**

```bash
# On the server
systemctl start finance-tracker-backup.service && journalctl -u finance-tracker-backup.service -n 2 --no-pager
```

If something turns out wrong later, restore the dump from 12.3
([Restore production from a backup](#restore-production-from-a-backup)). Running the import again
is safe: rows imported before are skipped.

---

## Update the app

On the laptop, push to GitHub and wait until CI is green
(https://github.com/sergeyzoloto/finance-tracker/actions). Then:

```bash
# On the server
systemctl start finance-tracker-backup.service
cd /opt/finance-tracker && before=$(git rev-parse HEAD) && git pull --ff-only && git log -1 --oneline && git diff --stat "$before" HEAD -- deploy/caddy deploy/backup
```

The backup comes first because database migrations only go forward. The last command lists changed
files in `deploy/caddy` or `deploy/backup`; if it lists any, also do the matching part below.

```bash
# On the server
cd /opt/finance-tracker/deploy/app
docker compose up -d --build
for i in $(seq 60); do s=$(docker inspect -f '{{.State.Health.Status}}' finance-tracker-prod-backend-1); [ "$s" = healthy ] && break; sleep 5; done; echo "backend: $s"
docker compose logs --tail 1 frontend
docker image prune -f
```

You should see `backend: healthy` and `Published …`. Everyone signs in again after an update:
sessions live in the backend's memory.

**If `deploy/caddy` changed**, replace the site block and the override. `caddy reload` keeps the
running configuration if the new one is broken, and prints why.

```bash
# On the server
cd /opt/auth
cp -p Caddyfile Caddyfile.before-update
cp /opt/finance-tracker/deploy/caddy/docker-compose.override.yml docker-compose.override.yml
sed '/# BEGIN finance-tracker/,/# END finance-tracker/d' Caddyfile > /tmp/Caddyfile.new && cat /opt/finance-tracker/deploy/caddy/app.finance-nl.com.caddy >> /tmp/Caddyfile.new && cat /tmp/Caddyfile.new > Caddyfile && rm /tmp/Caddyfile.new
grep -c '^app.finance-nl.com {' Caddyfile
docker compose exec caddy caddy reload --config /etc/caddy/Caddyfile
docker compose up -d
```

You should see `1`, and no error from `caddy reload`. The file is rewritten in place on purpose:
Caddy's container sees the file it was started with, and a new file (from `sed -i` or an editor
that saves a copy) would stay invisible to it.

**If `deploy/backup` changed:**

```bash
# On the server
install -m 644 /opt/finance-tracker/deploy/backup/finance-tracker-backup.service /opt/finance-tracker/deploy/backup/finance-tracker-backup.timer /etc/systemd/system/
systemctl daemon-reload
```

### Roll an update back

```bash
# On the server
cd /opt/finance-tracker && git log --oneline -5
git checkout --detach HEAD~1
cd deploy/app && docker compose up -d --build
```

The older code starts even if the update migrated the database, since Flyway ignores migrations it
doesn't know. If the older code can't work with the newer tables, restore the backup taken before
the update (next section). To return to the newest version:

```bash
# On the server
cd /opt/finance-tracker && git checkout main && git pull --ff-only
cd deploy/app && docker compose up -d --build
```

## Restore production from a backup

This replaces the live database with a dump. Choose the dump first:

```bash
# On the server
ls -lh /var/backups/finance-tracker/
dump=$(ls /var/backups/finance-tracker/finance-*.dump | tail -n 1); echo "$dump"
```

That picks the newest. To restore an older one, set it from the list, for example
`dump=/var/backups/finance-tracker/finance-2026-10-01T0130Z.dump`. The names are UTC times.

Then keep a dump of the current state, in case, and restore:

```bash
# On the server
systemctl start finance-tracker-backup.service
cd /opt/finance-tracker/deploy/app && docker compose stop backend
docker exec finance-tracker-prod-postgres-1 psql -q -U postgres -c 'DROP DATABASE finance WITH (FORCE)' -c 'CREATE DATABASE finance OWNER finance' -c 'REVOKE ALL ON DATABASE finance FROM PUBLIC'
docker exec -i finance-tracker-prod-postgres-1 pg_restore -U finance -d finance --exit-on-error < "$dump" && echo restored
docker compose start backend
for i in $(seq 60); do s=$(docker inspect -f '{{.State.Health.Status}}' finance-tracker-prod-backend-1); [ "$s" = healthy ] && break; sleep 5; done; echo "backend: $s"
```

You should see `restored` and `backend: healthy`. Sign in and check the **Accounts** page.

**The whole server is gone.** On a new server, set up the auth server first (its data isn't
covered here, see [Not covered](#not-covered)), then do steps 1 and 4 to 7 of this runbook. Before
step 7, fetch the dumps from the Storage Box: do step 10.2 with a new key, then

```bash
# On the server
. /etc/finance-tracker/backup.env && install -d -m 700 /var/backups/finance-tracker && rsync -a -e 'ssh -p 23 -i /root/.ssh/finance-tracker-backup' "$OFFSITE:finance-tracker/" /var/backups/finance-tracker/
```

and after step 7, restore the newest dump as above. Then steps 8, 10.3 and 11.

## Regular checks

Weekly:

```bash
# On the server
systemctl list-timers finance-tracker-backup.timer
journalctl -u finance-tracker-backup.service --since '-2 days' --no-pager | grep -E 'Dumped|Copied|rror'
df -h /
docker stats --no-stream --format 'table {{.Name}}\t{{.MemUsage}}'
```

You should see a next run tonight, two `Dumped` and two `Copied` lines, free disk space, and every
container below its limit.

Monthly: system updates, fresh base images, and a restore test.

```bash
# On the server
apt-get update && apt-get upgrade -y
systemctl start finance-tracker-backup.service
cd /opt/finance-tracker/deploy/app && docker compose pull postgres && docker compose build --pull && docker compose up -d
/opt/finance-tracker/deploy/backup/restore-test.sh
[ -f /var/run/reboot-required ] && echo "Reboot needed: run  reboot  (both stacks start again by themselves)"
```

## Troubleshooting

| What you see | Cause and fix |
| --- | --- |
| The browser warns about the certificate of app.finance-nl.com | Caddy has no certificate yet. `cd /opt/auth && docker compose logs caddy \| grep app.finance-nl.com` on the server says why; nearly always DNS (step 2). Caddy keeps retrying by itself. |
| `502 Bad Gateway` | The backend is down. `cd /opt/finance-tracker/deploy/app && docker compose ps -a && docker compose logs --tail 50 backend` on the server. |
| Keycloak says `Invalid parameter: redirect_uri` | Step 3.2: the redirect URI is missing or mistyped. |
| The app says "Your account has no access to Finance Tracker" | Step 3.4: your user lacks the client role `finance-tracker` `user`. Sign out and in again after assigning it. |
| After signing in, the app says the login failed | `docker compose logs backend \| grep 'Login failed'` in `/opt/finance-tracker/deploy/app`. `401 Unauthorized` or `invalid_client`: the client secret in `.env` is wrong; repeat the second block of step 6, then `docker compose up -d backend`. `UnknownHost` or `Connection refused`: the check at the end of step 8 fails; see that Caddy is `Up` in `/opt/auth`. |
| `backend` is `unhealthy`, and its log says `password authentication failed for user "finance"` | `APP_DB_PASSWORD` in `.env` changed after the database was created. In `/opt/finance-tracker/deploy/app`: `docker exec finance-tracker-prod-postgres-1 psql -U postgres -c "ALTER ROLE finance PASSWORD '$(grep '^APP_DB_PASSWORD=' .env \| cut -d= -f2)'"`, then `docker compose up -d backend`. |
| `frontend` is `Exited (1)` with `Cannot write to /www` | `docker run --rm -v finance-tracker-www:/www busybox chown -R 65534:65534 /www`, then `docker compose up -d frontend` in `/opt/finance-tracker/deploy/app`. |
| The import fails with `413` | A file is over 20 MB; that is the limit in Caddy and in the backend. |
| A container restarts again and again | `docker inspect -f '{{.State.OOMKilled}}' finance-tracker-prod-backend-1` (or another container's name) prints `true` when it ran out of memory. Compare with [Memory budget](#memory-budget). |
| The backup failed | `journalctl -u finance-tracker-backup.service -n 30 --no-pager`. `OFFSITE isn't set`: step 10.2. `Permission denied (publickey)` or `Host key verification failed`: repeat step 10.2's `install-ssh-key` line. |

## Not covered

- **Keycloak's database isn't backed up here.** Every ledger row is keyed by your Keycloak user ID
  (the token's `sub`). If Keycloak's data is lost and your user is created again, the new user
  gets a new ID and can't see the old ledger. Back up `/opt/auth`'s PostgreSQL too: the same
  script and timer work with its container and database names.

- **No alerts.** A failed backup shows up only in `journalctl` and in the weekly check.
- **One server.** If it's lost, everything after the last Storage Box copy is lost with it.
