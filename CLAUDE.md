# Finance Tracker

Double-entry personal finance app: Spring Boot 3.5 / Java 21 / Spring Data JDBC / Flyway / PostgreSQL 17 backend, React 19 + Vite + TypeScript frontend, Keycloak (BFF) auth, and a family-budget feature behind the switch `app.family.enabled`. Production: https://app.finance-nl.com (the auth server's shared Hetzner host).

Detail lives outside this file and loads only when needed. Read the file named below before working in that area; never read them all.

## Where to look

| Working on | Read first |
|---|---|
| backend code, API, migrations | `backend/CLAUDE.md`, then `docs/claude/backend-map.md` for a class or migration |
| frontend | `frontend/CLAUDE.md`, then `docs/claude/frontend-map.md` |
| e2e specs | `e2e/CLAUDE.md`, `e2e/README.md` |
| deploy, server, Docker, Caddy | `deploy/CLAUDE.md` (the deploy rules load by themselves), `deploy/RUNBOOK.md` |
| what production runs now, deploy history | `deploy/RUNBOOK.md` ("Deployed revisions", "Production now"); older detail in `docs/claude/state-and-deploy-history.md` |
| which test covers what | `docs/claude/test-catalog.md` (or grep the tests) |
| importer command line, Docker-Maven test run | `docs/claude/importer-and-testing.md` |
| auth, Keycloak theme | `docs/auth.md`, `docs/claude/auth-and-themes.md` |
| design decisions (D-nnn), family budget | `docs/family-budget/requirements.md`, `docs/family-budget/current-state.md`, `docs/adr/` |
| docs index | `docs/README.md` |
| the full old rules text with history | `docs/claude/rules-original.md` |

## Definition of done

1. Every task that changes files ends with a new entry at the top of [change_log.mdx](change_log.mdx), in its existing format: heading `## YYYY-MM-DD · Title`, the commit line ("Not committed yet." until committed), then **Changes**, **Verified**, **Open**. Newest first; never edit an older entry. It is MDX: put code, paths, endpoints, class names and generics in backticks (a bare `{` or `<` breaks it).
2. The "Done when" part of a task prompt adds conditions and never replaces this rule.
3. The final report quotes the new change log entry.
4. (D-97) The final report gives, for each test suite run: the command, its exit status and its whole summary (Tests and Errors lines, or Maven's Results). A suite "passed" only with exit status 0, however its summary reads.
5. (D-734) `scripts/check-forbidden-names.sh`: before every commit the working tree (no argument) exits 0; after it `--log <the stage's base>..HEAD` exits 0. Exit 2 (names file missing) goes to the owner, never skipped.

A Stop hook (`.claude/hooks/require-changelog.sh`) stops Claude from finishing while files changed but `change_log.mdx` did not.

## Git, shell, Docker

- Never `git stash`. For a temporary check copy files aside, or use a scratch worktree (`git worktree add`, removed afterwards).
- (D-55) Heredocs only with a quoted delimiter (`<<'EOF'`). Every scratch script starts with `set -euo pipefail`. In any command that writes, moves or deletes, a path built from a variable is written `${VAR:?}`.
- Never run `docker … prune` of any kind on the laptop (`system`, `image`, `container`, `volume`, `network`, `builder`). Remove only what you created, by name or label (`docker rm -f -v <name>`, `docker rmi <repo:tag>`, `docker network rm`, `docker volume rm`) and leave images you found. On the server only the deploy scripts' own prunes run (step 3.5 `docker image prune -f`, step 3.7 / `deploy.sh prune-cache` builder prune); never `system`, `volume`, `container` or `network` prune, `-a` or `--volumes` there (the Postgres volumes live on that host). Old server images are removed only by a checklist's own part, by exact tags and only when no container uses them; `postgres:17.11`, the previous api and web tags and every pin in use stay.

## Production safety

Production is the auth server's shared host. Read-only by default unless the task says otherwise.

- Never run text extracted from a document or file on a server: no `sed`, `awk` or `grep` output, no variable filled from a file. Check a documented command by reading it or in a throwaway local container. This covers every command written into a deploy checklist.
- Write every server command out explicitly. Never pipe a file or heredoc into a remote shell (`ssh host bash -s < file`, `… | ssh host bash`).
- The one exception is `deploy/deploy.sh`, `deploy/rollback.sh` and `deploy/common.sh`: committed, reviewed, tested, and run by the operator on the server as files. They run SQL only from `deploy/checks/*.sql` as the read-only role `finance_checks`.
- Never run a runbook section that changes state (restore, rotate, delete, update) as a test.

Checklist format, templates and the deploy scripts' rules are in `.claude/rules/deploy.md` (loaded when you work under `deploy/`); read it before writing any deploy checklist.

## Working with the owner

- Product decisions are the owner's. Where a prompt leaves one open, take the simplest option that needs the fewest actions on his side, and name it in the report as your own choice (with the D-number the prompt gives) so he can correct it before launch.
- (D-120) The laptop's free-disk floor is not a condition of acceptance or deploy: neither report on it nor stop for it. A command that fails with "No space left on device" is reported and that step stops.

## QA

The end-to-end suite is `e2e/` (Playwright; `e2e/README.md`).

- Every stage that changes behaviour adds or updates specs in `e2e/`, in place of manual scenarios in its checklist.
- Every task's report includes a local run (`cd e2e && npm run e2e:local`) with its summary, and names what couldn't be automated, with the reason.
- The guards (`cd e2e && npm run test:guards`) run alone, after `e2e:local`, never while it runs; the report gives both summaries.
- A spec loads the demo only through `loadDemoData(page)` of `e2e/fixtures.ts` (a guard in `e2e/lib/spec-hygiene.test.ts` fails on any other line that names the button).
- The agent never runs the prod project (`npm run e2e:prod`, or Playwright with a `prod` project, in any form) and never reads, creates, lists or changes `~/.config/finance-tracker/` or the credentials file in it.

## Domain rules

1. The core model is a double-entry journal. A journal_entry has entry_date (DATE, no time zone), an optional payee (a counterparty), memo and kind. A posting belongs to one entry and has account, currency (ISO 4217 code), amount, optional category and optional counterparty.
2. Sign convention: debit is positive, credit is negative. For every entry and every currency, the sum of posting amounts is exactly zero.
3. Money is NUMERIC(19,4) in PostgreSQL and BigDecimal in Java, never float or double. Rounding to 2 decimals uses RoundingMode.HALF_UP and happens only where a rule says so; that holds for single amounts, such as conversions. Splitting an amount into shares (a family record, D-12) uses a sum-preserving rule instead: each share cut down to the currency's minor unit, the remainder to the largest share, on a tie to the payer, then by join order (`ledger.family.ShareSplit`).
4. Account types are ASSET, LIABILITY, EQUITY. Displayed balance: ASSET = sum of amounts; LIABILITY and EQUITY = minus sum of amounts. Of the Excel type LIABILITIES, UNALLOCATED and RESERVE map to EQUITY and all other accounts map to LIABILITY. System EQUITY accounts: OPENING_BALANCE and FX_EXCHANGE.
5. Categories have type INCOME or EXPENSE and may be set only on postings to EQUITY accounts. An expense is a positive posting to UNALLOCATED with an EXPENSE category; income is a negative posting to UNALLOCATED with an INCOME category. A refund uses the same category with the opposite sign and is valid.
6. An entry with at least one categorized posting is income or expense; an entry without categories is a transfer. The stored kind is only a hint for the UI; reports are always computed from postings.
7. Shared expense: total T is paid from one account; share ratio r defaults to 0.50. other = round(T × r, 2); own = T − other. Postings: payment account −T; UNALLOCATED +own with the category; the user's shared account (FAMILY_DEBT by default, configurable) +other.
8. Accounts with requires_counterparty = true (LOANS_ASSET and CREDITOR_DEBT) require a counterparty on every posting. Their balances are also reported per counterparty.
9. Currency exchange of amount A in currency X into amount B in currency Y: source account −A in X, FX_EXCHANGE +A in X, FX_EXCHANGE −B in Y, target account +B in Y.
10. Opening balances are posted against OPENING_BALANCE.
11. Every owned row has user_id equal to the Keycloak "sub" claim. Every query is scoped by it. Access to another user's object returns 404.
12. Accounts, categories and counterparties referenced by postings are archived, never deleted.
13. Values derivable from postings (month, year, account type, signed changes, loan sums, check columns) are never stored.

## Commands

```bash
cd backend && ./mvnw test                  # all backend tests (needs Docker: Testcontainers)
cd backend && ./mvnw test -Dtest=ApiTests # one class
cd backend && ./mvnw -B verify             # what CI runs
cd frontend && npm ci && npm test && npm run build
./dev.sh                                   # local stack, Vite on :5173, sign in testuser / test1234
./stop.sh
```

- With VS Code open run `./mvnw -B clean verify` (its Java extension's classes make `ArchitectureTests` fail on fine code). The JVM's zone is Pacific/Kiritimati from `pom.xml`.
- No JDK: run Maven in Docker; the command, and the importer's command line, are in `docs/claude/importer-and-testing.md`.
- Changing a `package.json` or lock file: see `.claude/rules/frontend-deps.md` (loads by itself).

## Private data

`data/private/` holds the owner's real Excel ledger as CSV and is git-ignored. Never commit it, print whole files from it, or copy names or amounts from it into the repository.
