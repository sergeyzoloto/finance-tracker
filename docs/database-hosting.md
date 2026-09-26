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

## State on 2026-09-23

- A Supabase project exists in `eu-west-1`.
  - Schema `keycloak`: from the auth server's production rehearsal.
  - Schema `app`: empty; Flyway v1 was applied on 2026-09-23.
  - The local dev `.env` points at this project. Its `postgres` password was shown in a working
    session on 2026-09-23; change it before this project, or its password, is used for anything
    real.
- Nothing implements this decision yet. The auth server's `PRODUCTION.md` and
  `docker-compose.prod.yml`, and this repository's README and `docker-compose.yml`, still describe
  Supabase.

## State on 2026-09-26

- finance-tracker's side is ready to deploy ([deploy/RUNBOOK.md](../deploy/RUNBOOK.md)):
  `postgres:17` in `deploy/app/docker-compose.yml`, database `finance` owned by the login
  `finance`, which is no superuser, and schema `app`. A nightly `pg_dump` is kept 14 days on the
  host and copied to a Hetzner Storage Box, and `deploy/backup/restore-test.sh` tests a restore.
- The auth server's production stack in `/opt/auth` already runs its own PostgreSQL, `postgres:16`
  (its `deploy/docker-compose.yml`), with no backup yet.
