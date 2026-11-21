# Running the importer and the tests

Moved verbatim from the old CLAUDE.md (lines 327-358 and 369-405). Not loaded at session start: read it on demand, only when the task touches this area.

## How to run the importer

The importer reads a user's transactions file, as CSV with semicolons and D-235's 32 columns (D-61: each export in its own dated folder `data/private/export-YYYY-MM-DD/`; since D4b the accounts, categories and daily files are no input), and for a place without an account optionally the partner's transactions file, which it filters to his family rows as the import page does (D-140, D-260), for a family budget and the partner's seat in it (D-141). It imports them for one user. The production imports are in the browser (`/import`, the runbook's steps 10 to 12); the command line is for a throwaway database.

- `--up-to=yyyy-MM-dd` is the page's "Import up to" (D-165): rows of either file dated after it are neither imported nor missing, and the check stops at it.
- It is a dry run unless `--commit` is given. A dry run writes everything, reports, and rolls back. For a sub without a personal ledger, it creates that ledger before the import's transaction, so a dry run leaves an empty personal ledger behind; the runbook's "Delete a user" removes it.
- `--commit` saves everything, or nothing if any row has an error or the check against the file fails (D-336). Running it again recognises the rows by their IDs (D-139).
- The Markdown report goes next to the transactions file as `import-report.md`. `--report=<file>` writes it elsewhere.
- Exit codes: 0 means no row errors (and for a commit, saved), 1 means wrong arguments, an unreadable file or a refusal (a file that isn't a transactions file or whose accounts and categories don't fit together, D-229; a missing budget or seat; the partner's file refused), and 2 means row errors, or a commit whose check against the file fails (D-336): nothing saved.
- The family budget and the seat must exist (D-141): create them in the app, or with the services in a test.

Flyway migrates the target database on startup. Point the importer at a throwaway PostgreSQL. Production's database is reachable only inside its stack:

```bash
docker run -d --rm --name finance-tracker-import-db -e POSTGRES_PASSWORD=import -p 127.0.0.1:5433:5432 postgres:17-alpine

cd backend
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5433/postgres \
SPRING_DATASOURCE_USERNAME=postgres SPRING_DATASOURCE_PASSWORD=import \
./mvnw -q spring-boot:run -Dspring-boot.run.profiles=import -Dspring-boot.run.arguments="\
--user-sub=<Keycloak sub> --transactions=<export>/<transactions file>"
# add --family-ledger-id=<id> --seat-member-id=<id> for the family rows (and --partner-transactions=<file> for a place
# without an account), --up-to=yyyy-MM-dd (D-165), and --commit

docker stop finance-tracker-import-db   # --rm deletes it with its data
```

`--user-sub` is the owner's Keycloak user id, the `sub` claim that every ledger row is keyed by (rule 11). Import with the production sub. The dev Keycloak's users have different ids. To find it:

- **Admin console:** open realm `myapps`, go to Users, open the owner's user, and copy the **ID** field. For production, that is `https://auth.finance-nl.com`, reachable only from allowlisted IPs, with OTP.
- **The app's database,** once the owner has signed in to the app: `SELECT keycloak_id FROM app.users WHERE email = '<owner email>';`. Sign-in creates this row and stores the `sub` as `keycloak_id`.

## How to run tests

Backend tests need Docker, because Testcontainers starts PostgreSQL. They don't need Keycloak.

With JDK 21 installed:

```bash
cd backend
./mvnw test                   # all tests
./mvnw test -Dtest=ApiTests   # one test class
./mvnw -B verify              # what CI runs
```

Tests run from an IDE use its JVM's zone unless its VM arguments hold `-Duser.timezone=Pacific/Kiritimati`; Maven's runs and CI always do. When VS Code is open, run `./mvnw -B clean verify`. Its Java extension writes Eclipse-compiled classes into `target/classes`, and `ArchitectureTests` misreads their lambdas (`lambda$N`), so it fails on code that is fine. CI builds from scratch and is not affected.

Without a JDK (for example on a machine with only a JRE), run Maven in Docker from the repository root. Add `-Dtest=ApiTests` before `-Dmaven.repo.local` to run one class:

```bash
docker run --rm --network host \
  -u "$(id -u):$(id -g)" --group-add "$(stat -c %g /var/run/docker.sock)" \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v "$HOME/.m2:/var/maven/.m2" -e HOME=/var/maven \
  -e TESTCONTAINERS_HOST_OVERRIDE=localhost -e TESTCONTAINERS_RYUK_DISABLED=true \
  -v "$PWD/backend:/app" -w /app \
  maven:3.9-eclipse-temurin-21 mvn -B test -Dmaven.repo.local=/var/maven/.m2/repository
```

Frontend tests need no backend:

```bash
cd frontend
npm ci
npm test        # vitest run
npm run build   # tsc, then vite build
```

To try the screens against a local backend, run `./dev.sh`: the auth server's dev stack, then the local compose stack with its own PostgreSQL (data in the volume `finance-tracker_local-db`) and the family switch on, and the Vite dev server; `./stop.sh` stops it. Or run the backend on the host against a throwaway PostgreSQL, like the importer above, with `SERVER_PORT=8081`, the `KEYCLOAK_*` values of `.env`, and `FAMILY_LEDGERS_ENABLED=true` for the family endpoints. Then run `npx vite` in `frontend/` and sign in at http://localhost:5173 as testuser / test1234.
