# End-to-end suite

Playwright, Chromium only, Node 24 (QA-1; decisions D-51 and D-52 in
[docs/family-budget/requirements.md](../docs/family-budget/requirements.md)). It runs in two places:

- **Locally**, `npm run e2e:local`, against the dev stack of `./dev.sh`: Vite at http://localhost:5173, the dev
  Keycloak at http://localhost:8080, the dev users `testuser` (A) and `testuser2` (B). It refuses to run unless every
  host it names is this machine. The agent runs it for every task.
- **In production**, `npm run e2e:prod`, against https://app.finance-nl.com with the accounts `e2e-a` (A) and `e2e-b`
  (B), made only for it. Only the owner runs it, from the laptop. It replaces the browser checks and the smoke test of
  the deploy checklists.

`e2e/` is outside both images' build contexts (`backend/` and `frontend/`), so a change here rebuilds neither api nor
web.

## What it checks

| Spec | Accounts | What |
| --- | --- | --- |
| pages | none | `/` (the landing page), `/privacy` and `/privacy.html` (the policy with its section "Family budgets"), `/favicon.svg` and `/favicon.ico` (status and content type), at 1280 and 375 px, with full-page screenshots |
| smoke | A | sign in; `/api/me`'s `familyLedgers` as `E2E_FAMILY` says; an expense created, changed and deleted; the demo, with "Demo household" and Sam when the family budget is on, its balance and report, then leaving it (which deletes it); the integrity check |
| family F7 | A and B | F7's check with two accounts: a family budget in EUR, an invite, expenses in EUR and in dollars, an income, the balances and the report, a settlement, the settlement lock (D-28), both settled, both leaving (D-36) |

Every spec starts and ends with "Delete all my data" for each account it uses, and fails on any response of 400 or
more from the app that it doesn't name with its reason, and on any console or page error there. "Integrity" means
`GET /api/reports/integrity` answers `[]` for that account.

The run, in order: pages; then one sign-in per account, with tracing off, whose `/api/me` must name that account
before any other request reaches the API; smoke; family F7; then the cleanup, which deletes both accounts' data
through the API (so nothing provisions them again) and signs them out, even after a failure. It ends with a summary
to paste into the chat.

## One-time setup (the owner)

### 1. Two Keycloak accounts for production

In Keycloak's admin console, https://auth.finance-nl.com/admin/ (only from your IP, with OTP), realm **myapps**,
**Users → Add user**, once for each:

| | e2e-a | e2e-b |
| --- | --- | --- |
| Email | `e2e-a@finance-nl.com` | `e2e-b@finance-nl.com` |
| Email verified | On | On |
| First name | `E2E` | `E2E` |
| Last name | `Account A` | `Account B` |
| Required user actions | none | none |
| Groups | none | none |

- There is no username field: the realm has "Email as username" on, so the email is the username, and the suite
  signs in with it.
- The first and last name matter: `/api/me` reports the full name, and the suite stops unless it is exactly
  `E2E Account A` or `E2E Account B` (D-52, guard 2). The realm's user profile requires both names; without them
  Keycloak would ask for them at the first sign-in, and the suite would stop there.
- **Credentials → Set password**: a long random password of its own for each (for example the output of
  `openssl rand -base64 30`), **Temporary off**. No OTP: add no other credential and no required action.
- **Role mapping**: assign nothing. The app needs the client role `finance-tracker` → `user`, which every user of the
  realm gets from the default role `default-roles-myapps` (`docs/auth.md`, "Roles and access"); with "Hide inherited
  roles" off it shows as inherited. Without it the app answers 403 and the sign-in step stops.
- Leave the realm's settings as they are, brute-force detection included (10 failures lock the account for a while):
  the suite never retries a failed sign-in.

### 2. The credentials file

Outside the repository, readable only by you:

```bash
# On the laptop
install -d -m 700 ~/.config/finance-tracker
install -m 600 /dev/null ~/.config/finance-tracker/e2e-prod.env
```

Then open `~/.config/finance-tracker/e2e-prod.env` in an editor and write exactly these four lines, with the two
passwords of step 1:

```
E2E_A_USERNAME=e2e-a@finance-nl.com
E2E_A_PASSWORD=<e2e-a's password>
E2E_B_USERNAME=e2e-b@finance-nl.com
E2E_B_PASSWORD=<e2e-b's password>
```

`KEY=value`, no spaces around `=`; a value may be in quotes; blank lines and `#` comments are fine. The runner refuses,
naming the rule and never a value, unless the file is a regular file of yours with mode 600 in a folder of mode 700,
holding exactly these keys, with exactly these two emails.

### 3. Node 24 and Chromium

```bash
# On the laptop
cd ~/dev/finance-tracker/e2e && nvm install && nvm use   # Node 24, from .nvmrc
npm ci
npx playwright install chromium   # the browser of the pinned Playwright, into ~/.cache/ms-playwright
```

## Running it locally

```bash
# On the laptop
cd ~/dev/finance-tracker && ./dev.sh   # wait until http://localhost:5173 answers
cd e2e && nvm use && npm run e2e:local
```

`E2E_FAMILY` defaults to `on`, as the dev stack has it. The run deletes all data of `testuser` and `testuser2` in the
local database. Its artifacts (a trace and a screenshot of a failure, and the screenshots the specs take) are in
`e2e/test-results/`, which git ignores and each local run empties first. `E2E_TRACE=on npm run e2e:local` keeps every
spec's trace, not only a failure's (the sign-in records none either way).

The invite limit (D-29: 10 a minute and 50 an hour per user, in the api's memory) counts B's invite requests, about 3
per family F7 and so about 6 per local run, which has F7 at 1280 and at 375 px: past about eight local runs in an hour,
family F7 stops at the invite with "B's invite requests hit D-29's limit". Wait, or restart the local backend, which
forgets the counts:

```bash
# On the laptop
cd ~/dev/finance-tracker && docker compose -f docker-compose.yml -f docker-compose.local.yml -f docker-compose.dev.yml restart backend
``` `npm run typecheck` and
`npm run test:guards` (the guards' tests, with temporary files and a fake app only) need no stack.

## Running it in production

From a clean checkout of the commit the checklist names (the deployed one), on the laptop:

```bash
# On the laptop
cd ~/dev/finance-tracker && git status --short && git log -1 --format='%H %s'
cd e2e && nvm use && npm ci && E2E_FAMILY=on npm run e2e:prod
```

`E2E_FAMILY` is `on` or `off`, as the family budget is switched in production now; the run stops if `/api/me` says
otherwise. Before anything else it prints the target, both accounts, `E2E_FAMILY` and the suite's commit (and whether
the tree is clean), then asks you to type `E2E PROD`; anything else stops it with nothing done. It takes about two
minutes.

The summary at the end, between `===== E2E summary =====` and `=======================`, is what you paste into the
chat, with the line after it (the password search):

- the start and end in UTC, the target, the commit and `E2E_FAMILY`;
- each spec, `passed` or `failed` (or `not run` after a failed sign-in, `skipped` for the family spec with
  `E2E_FAMILY=off`), with its duration;
- each account's sign-in and cleanup: `deleted …, signed out`, or why not, and then what to do by hand;
- `ABORTED: …` if the run stopped early (a sign-in that failed, or `/api/me` naming someone else);
- the artifacts' folder, and `Result: PASSED` or `FAILED`. The exit status is 0 only for PASSED.

The artifacts are in `~/.cache/finance-tracker-e2e/prod-<UTC time>/` (mode 700): for a failed test its trace
(`npx playwright show-trace <folder>/…/trace.zip`), a screenshot and `error-context.md`; and the screenshots of the
pages spec. A trace holds the test accounts' session cookies, which the cleanup's sign-out has made void; no
password is in any of them (the run searches them and says so). Delete a run's folder when it has served:

```bash
# On the laptop
rm -rf ~/.cache/finance-tracker-e2e/prod-<UTC time>
```

## What a run leaves in production

A new user's first request for data (any `/api` endpoint but `/api/me`) provisions them (`CurrentUserResolver`,
`UserDataService.provision`, `StarterLedger.seedIfNew`): one `users` row, one `user_settings` row (base currency EUR),
one personal ledger with its one member, the starter ledger's 10 accounts and 15 categories
(`backend/src/main/resources/seed/starter-ledger.json`), no counterparty and no entry. "Delete all my data" removes all
of it, and the next request provisions it again.

The sign-in step sends only `/api/me`, which provisions nothing. The specs provision both accounts, fill and empty
them; the cleanup deletes them last through the API, after which nothing provisions them again. So after a run,
finished or not, production holds no row of `e2e-a` or `e2e-b`: `deploy/deploy.sh verify` prints the same numbers as
before the run (users, settings, ledgers and members unchanged, every `family_*` as before), unless the summary says a
cleanup failed. Keycloak keeps the two accounts, and its events log their sign-ins.

## Layout

- `playwright.config.ts`: the projects per target: `<target>-pages`, `<target>-setup` (sign-in) with its teardown
  `<target>-cleanup`, `<target>-specs` (smoke, family F7). It refuses to load without a runner.
- `scripts/local.ts`, `scripts/prod.ts`: the runners; `scripts/common.ts`: the run, the summary, the password search.
- `lib/`: the allowlist (`accounts.ts`), targets, the credentials file's checks, the identity check, the session
  folder, the summary and its reporter; the guards' tests (`*.test.ts`) and their fake app.
- `fixtures.ts`: the signed-in contexts, the watch on responses and errors, "Delete all my data", the integrity check.
- `specs/`: the specs in their order, the sign-in, the cleanup.
