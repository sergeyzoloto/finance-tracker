# Deploy

The deploy rules and the checklist template load by themselves from `.claude/rules/deploy.md` when you work under `deploy/`. The procedure is `deploy/RUNBOOK.md`. What production runs right now is in the runbook's "Deployed revisions" and "Production now", not here; older history is in `docs/claude/state-and-deploy-history.md` (grep it for a stage name).

## What is here

- `deploy/app/docker-compose.yml`, project `finance-tracker-prod`, in `/opt/finance-tracker/deploy/app` on the server: `postgres` (pinned by digest; changed only through `deploy.sh db-image`), `api`, `web`. `api` and `web` run with a read-only root, tmpfs `/tmp`, no capabilities, `no-new-privileges`. Secrets are in `deploy/app/.env` on the server, typed in with `read -rs`. Nothing of this project goes into `/opt/auth`; only containers Caddy proxies to join the network `edge`, named `finance-…`.
- `deploy/finance.caddy` and `deploy/finance-apex.caddy`: site blocks for the auth server's Caddy (`caddy-site install` / `remove`). `deploy/check-site.sh` validates them on the laptop. `deploy.sh run` refuses a changed `finance.caddy`.
- `deploy/pg-backup/finance.conf`: the backup instance for the auth server's `pg-backup` and `pg-restore-test`.
- `deploy/checks/*.sql` with `*.expected` per stage, and `numbers.sql` (a key per table, updated when a migration adds a table).
- The root `docker-compose.yml` is for local runs only: its own `postgres:17-alpine`, volume `finance-tracker_local-db`; it never reaches a remote database.

## The scripts

`deploy/deploy.sh`: `run <commit> <stage>`, `finish`, `verify <stage>`, `adopt`, `switch on|off` (the only way to change `FAMILY_LEDGERS_ENABLED` in production), `acceptance on|off` (the only way to change `FAMILY_ACCEPTANCE_ENABLED`, HIST-3), `prune-cache`, `db-image <commit>`. `deploy/rollback.sh <commit>` goes one step back. They never roll back by themselves and print the rollback command after a failure past the merge.

- Both source `deploy/common.sh`, are functions only with `main "$@"; exit $?` last (the merge replaces them while they run), and keep state in `/var/lib/finance-deploy` (data, parsed and never sourced). `DEPLOY_*` variables exist for tests only.
- They run SQL only as the read-only role `finance_checks` and from committed check files.
- A change to them must pass `deploy/tests/run.sh`, `deploy/tests/mutate.sh` and ShellCheck (`koalaman/shellcheck:v0.11.0`); a new refusal or step needs a test case and a mutation that is caught.
- Every question the scripts ask is answered at the terminal, never pasted. Server blocks start with `# On the server` and `cd /opt/finance-tracker &&`; laptop blocks with `# On the laptop` and `cd ~/dev/finance-tracker &&` (see the rules file).
