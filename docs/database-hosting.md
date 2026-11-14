# Database hosting

**Decided 2026-09-23.** At launch every stack keeps its data in its own PostgreSQL on the Hetzner
host. The move to Supabase comes later.

This covers both stacks on the host: finance-tracker (this repository) and the shared auth server
(Keycloak, realm `myapps`).

## Decision

- **At launch:** each stack runs its own PostgreSQL container, next to the application, in its own
  compose project.
  - The auth server: Keycloak's database, schema `keycloak`.
  - finance-tracker: schema `app`.

  Neither database is published on the host; each is reachable only inside its compose network.
- **Later:** both move to Supabase, on a paid plan and in the region closest to the host:
  Frankfurt (`eu-central-1`) for a host in Germany.

## Context

- The applications will run on Hetzner, in Germany.
- The existing Supabase project is in `eu-west-1` (Ireland). Hetzner has no data center there. A
  round trip from Germany to Ireland is roughly 20–30 ms (an estimate, not measured).
  - Keycloak goes to the database several times for every login and token refresh.
  - finance-tracker runs several queries for every API call.
- A Supabase project can't change its region. Moving means a new project and copying the data.
- Nobody uses either application yet, so the choice is cheap to make now.

## Options considered

| Option                                  | Latency to the database | Backups and upgrades | Cost                    |
| --------------------------------------- | ----------------------- | -------------------- | ----------------------- |
| Supabase, existing project in Ireland   | ~20–30 ms per query     | Supabase             | paid plan               |
| Supabase Pro in Frankfurt               | a few ms                | Supabase             | about $25 per month     |
| **Own PostgreSQL on the host (chosen)** | under 1 ms              | ours                 | the host's RAM and disk |

The Ireland project was ruled out because of latency. Supabase in Frankfurt is the better option
for the long run, since it takes backups and upgrades off our hands. It's deferred, not rejected.

## Why own databases at launch

- **Latency:** the database runs on the same host as the application.
- **Cost:** no database subscription while the applications have no users.
- **Self-contained stacks:** each repository starts its application and its database with one
  `docker compose up`, with no external service to depend on.
- **Isolation:** Keycloak's data (password hashes, client secrets, signing keys) sits in a
  different PostgreSQL instance from finance-tracker's. A fault or a breach in one application
  doesn't expose the other's data.

## What we take on

These must be in place before launch, not after:

- **Backups, kept off the host.** Use WAL-G or pgBackRest to Hetzner Object Storage, or at least a
  nightly `pg_dump` copied off the host. Test a restore before launch. A Hetzner server snapshot
  isn't a database backup, because it isn't consistent at the PostgreSQL level.
- **One point of failure.** If the host is lost, everything written after the last off-host
  backup is lost with it.
- **Maintenance.** PostgreSQL security updates and major-version upgrades, disk space, monitoring.
- **Memory.** Two PostgreSQL instances share the host with Keycloak (capped at 2 GB) and the
  backend. Cap the memory of each database container.

## Keeping the later move simple

- **Same major version as Supabase:** PostgreSQL 17 (`postgres:17`). Then `pg_dump` and
  `pg_restore` move the data without conversion.
- **Same schemas as on Supabase:** `app` for finance-tracker, `keycloak` for Keycloak. Both are
  already configured (`application.yml`, `KC_DB_SCHEMA`), so the move changes only the connection
  settings.
- **A dedicated database user per application**, not `postgres`, from the start. Recreate the same
  users on Supabase.
- **Same Keycloak version on both sides** during the move. Keycloak migrates its schema on start
  and can't migrate back.

## Moving to Supabase later

1. Create a Supabase project on a paid plan, in the host's region. The Free plan has no automatic
   backups and pauses idle projects.
2. Create the schemas and the per-application users.
3. Pick the connection:
   - Preferred: the direct connection over IPv6. It needs a dual-stack Docker network, as the auth
     server's production stack already has.
   - Otherwise: the Session pooler (port 5432).
   - Never the Transaction pooler (port 6543).
4. Harden the connection:
   - Use `sslmode=verify-full` with Supabase's CA certificate.
   - Allow only the host's IP addresses in Supabase's Network Restrictions.
5. Stop Keycloak and the backend, copy each schema with `pg_dump`/`pg_restore`, switch the
   connection settings, and start them again.

## Current state (DOC-1, 2026-11-14)

The decision above stands: every stack has its own PostgreSQL on the host, and the move to Supabase is deferred,
with no date (D-720 of [the requirements](family-budget/requirements.md)).

**finance-tracker in production** ([deploy/app/docker-compose.yml](../deploy/app/docker-compose.yml),
[deploy/RUNBOOK.md](../deploy/RUNBOOK.md)):

- `postgres:17.11`, pinned by digest, as the container `finance-tracker-postgres`, `mem_limit: 384m`, on the stack's
  own network only: neither published on the host nor on the shared network `edge`.
- The database `finance`, owned by the login `finance`, which is no superuser
  ([postgres-init.sh](../deploy/app/postgres-init.sh)); Flyway creates the schema `app` in it (V1 to V17).
  `postgres` is the container's superuser, for maintenance only.
- The read-only role `finance_checks` (F6b): no password, no superuser, a member of `pg_read_all_data`, with
  `default_transaction_read_only = on`. The deploy scripts read every check as this role
  ([RUNBOOK: A read-only role for the deploy checks](../deploy/RUNBOOK.md#a-read-only-role-for-the-deploy-checks)).

**Backups:**

- **Nightly dump.** The auth server's `pg-backup` writes a `pg_dump` every night at 02:30 UTC (plus up to 10 minutes)
  into `/var/backups/pg/finance/`, checks it, and keeps 14 days, never fewer than the newest 3
  ([deploy/pg-backup/finance.conf](../deploy/pg-backup/finance.conf); the scripts are in the auth server repository,
  `deploy/backup/`). `pg-restore-test finance` restores a dump into a throwaway container of the same image and
  compares it with production: the number of tables and every `CHECK_*` query of `finance.conf`. The owner's laptop pulls `/var/backups/pg/` once a day, read only
  ([RUNBOOK: Backups](../deploy/RUNBOOK.md#9-backups)).
- **Around each deploy.** `deploy/deploy.sh run` takes a fresh dump and tests its restore before it changes anything,
  and again afterwards; it keeps a copy of the dump from before in the run's folder
  ([RUNBOOK: Deploying with deploy.sh](../deploy/RUNBOOK.md#deploying-with-deploysh)).
- **Hetzner's backups of the server** (since OPS-3, D-275): daily copies of the whole disk, the last 7 kept. They are
  not database backups: taken from a running system, a database in them is at best as after a power cut
  ([RUNBOOK](../deploy/RUNBOOK.md#hetzners-backups-of-the-server)). Restores come from the dumps
  ([RUNBOOK: Restore from a backup](../deploy/RUNBOOK.md#restore-from-a-backup)).

**Rehearsals from production's data.** [scripts/rehearsal-db.sh](../scripts/rehearsal-db.sh) (D3e, D-214, D-225)
restores a production dump into a throwaway database on tmpfs on the laptop (production's pinned image,
`127.0.0.1:5434`) and maps the two participants' users onto the dev users, so that a monthly import can be tried
locally first; `--remove` removes it.

**Local runs and tests.** The root `docker-compose.yml` runs `postgres:17-alpine` with the named volume `local-db`,
published on `127.0.0.1:5433` only by the local overlay; it never reaches a remote database. The backend's tests start
`postgres:17-alpine` through Testcontainers.

**The auth server** runs its own PostgreSQL, `postgres:16.15` pinned by digest (its `deploy/docker-compose.yml`, read
on 2026-11-14), backed up by the same `pg-backup` since its stage 3. It is one major version below this stack's and
below "Same major version as Supabase" above; that matters only for the deferred move.

**Supabase.** The project in `eu-west-1` from before the launch is deleted; the owner confirmed it on 2026-11-14,
and the date of the deletion isn't recorded (D-738). Its schemas were `keycloak` (the auth server's production
rehearsal) and `app` (Flyway V1 only, 2026-09-23). Nothing in this repository connects to Supabase, and no
configuration holds a connection to it, the local `.env` included. The decision above still stands; a move would start
from a new project, as "Moving to Supabase later" says.

## History

- 2026-09-23: the decision above; a Supabase project existed in `eu-west-1`, and the local `.env` pointed at it.
- 2026-09-27: this stack's production database ready to deploy, with `pg-backup` and its restore test.
- 2026-09-28: production live on its own PostgreSQL (D3; [change_log.mdx](../change_log.mdx)).
- 2026-10-02: the read-only role `finance_checks` (F6b).
- 2026-10-27: Hetzner's server backups and the external monitor (OPS-3).
- Date not recorded: the `eu-west-1` Supabase project deleted (confirmed by the owner on 2026-11-14, D-738).
