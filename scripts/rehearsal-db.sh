#!/usr/bin/env bash
# D-214, D-220: a throwaway database restored from a production dump, for step 12's local rehearsal (the runbook's
# "12. Monthly import, two participants"). On the laptop only; it never reaches the server. The dump is one the daily
# pull already copied (~/backups/finance-nl-server/finance/).
#
#   scripts/rehearsal-db.sh <dump> [owner's dev user] [partner's dev user]   restore, map the subs, print the numbers
#   scripts/rehearsal-db.sh --remove                                         remove the container and its data
#
# What it does:
# 1. Starts PostgreSQL from production's image (deploy/app/docker-compose.yml's postgres line, pinned by digest) as the
#    container finance-tracker-rehearsal-db, its data on tmpfs (memory, nothing on the laptop's disk; the laptop has
#    little free), published on 127.0.0.1:${REHEARSAL_DB_PORT:-5434}; database finance, user postgres, password
#    rehearsal.
# 2. Restores the dump into it (pg_restore --no-owner --no-acl --exit-on-error, read from stdin).
# 3. Maps the two participants of the dump's one family budget, its members with an account (OWNER and MEMBER), onto
#    two users of the dev Keycloak (realm myapps; testuser and testuser2 by default): every text column of schema app
#    that holds a participant's sub gets the dev user's id, in one transaction, triggers off; nothing else changes.
#    Then no column holds a participant's sub any more, which it checks. No sub is printed.
# 4. Prints the numbers of pg-restore-test's table (users, ledgers, ... family_invites), to compare with the dump's.
#
# Then run the backend on the host against it and Vite (the runbook's step 12, "The local rehearsal"), and sign in as
# the two dev users. Exit status: 0 done, 1 a refusal or a failure, 2 usage.
set -euo pipefail

NAME=finance-tracker-rehearsal-db
PORT="${REHEARSAL_DB_PORT:-5434}"
TMPFS_SIZE="${REHEARSAL_TMPFS_SIZE:-2g}"
KEYCLOAK_DB="${REHEARSAL_KEYCLOAK_DB:-keycloak-db}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

usage() {
  sed -n '2,8p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' >&2
  exit 2
}

psql_in() {
  docker exec -i "$NAME" psql -X -v ON_ERROR_STOP=1 -U postgres -d finance "$@"
}

if [ "${1:-}" = "--remove" ]; then
  [ "$#" -eq 1 ] || usage
  docker rm -f -v "$NAME" >/dev/null 2>&1 && echo "Removed $NAME." || echo "No $NAME to remove."
  exit 0
fi
[ "$#" -ge 1 ] && [ "$#" -le 3 ] || usage
DUMP="$1"
OWNER_USER="${2:-testuser}"
PARTNER_USER="${3:-testuser2}"
[ -f "$DUMP" ] && [ -r "$DUMP" ] || { echo "Can't read the dump $DUMP." >&2; exit 1; }

if docker container inspect "$NAME" >/dev/null 2>&1; then
  echo "$NAME exists already: scripts/rehearsal-db.sh --remove first." >&2
  exit 1
fi
if ss -ltnH "sport = :$PORT" | grep -q .; then
  echo "Port $PORT is taken; set REHEARSAL_DB_PORT to a free one." >&2
  exit 1
fi

# The dev users' ids, read from the dev Keycloak's database (read only).
dev_id() {
  docker exec -i "$KEYCLOAK_DB" psql -X -At -U keycloak -d keycloak -v ON_ERROR_STOP=1 -v name="$1" <<'EOF'
SELECT u.id FROM user_entity u JOIN realm r ON r.id = u.realm_id WHERE r.name = 'myapps' AND u.username = :'name'
EOF
}
OWNER_ID="$(dev_id "$OWNER_USER")"
PARTNER_ID="$(dev_id "$PARTNER_USER")"
for pair in "$OWNER_USER:$OWNER_ID" "$PARTNER_USER:$PARTNER_ID"; do
  [[ "${pair#*:}" =~ ^[0-9a-f-]{36}$ ]] || {
    echo "The dev Keycloak ($KEYCLOAK_DB, realm myapps) has no user ${pair%%:*}: start it (../auth_server)." >&2
    exit 1
  }
done
[ "$OWNER_ID" != "$PARTNER_ID" ] || { echo "The two dev users must differ." >&2; exit 1; }

IMAGE="$(grep -E '^\s+image: postgres:' "$ROOT/deploy/app/docker-compose.yml" | head -n 1 | sed -E 's/^\s+image: //')"
[[ "$IMAGE" =~ ^postgres:[0-9.]+@sha256:[0-9a-f]{64}$ ]] || { echo "No pinned postgres image found." >&2; exit 1; }

echo "Starting $NAME ($IMAGE, data on tmpfs $TMPFS_SIZE, 127.0.0.1:$PORT)"
docker run -d --name "$NAME" --label finance-tracker=rehearsal \
  --tmpfs "/var/lib/postgresql/data:rw,size=$TMPFS_SIZE" \
  -e POSTGRES_PASSWORD=rehearsal -e POSTGRES_DB=finance -p "127.0.0.1:$PORT:5432" \
  "$IMAGE" -c fsync=off -c full_page_writes=off >/dev/null
for _ in $(seq 1 60); do
  docker exec "$NAME" pg_isready -U postgres -d finance -q && break
  sleep 1
done
docker exec "$NAME" pg_isready -U postgres -d finance -q || { echo "PostgreSQL didn't start." >&2; exit 1; }

echo "Restoring $(basename "$DUMP") ($(stat -c %s "$DUMP") bytes, SHA-256 $(sha256sum "$DUMP" | cut -c1-12)…)"
docker exec -i "$NAME" pg_restore -U postgres -d finance --no-owner --no-acl --exit-on-error <"$DUMP"

echo "Mapping the participants onto $OWNER_USER (the owner) and $PARTNER_USER (the partner)"
psql_in -q -v owner="$OWNER_ID" -v partner="$PARTNER_ID" <<'EOF'
BEGIN;
CREATE TEMP TABLE participant AS
  SELECT m.user_sub AS sub, CASE m.role WHEN 'OWNER' THEN :'owner' ELSE :'partner' END AS dev
  FROM app.ledger_member m
  WHERE m.ledger_type = 'SHARED' AND m.user_sub IS NOT NULL AND m.status = 'ACTIVE';
DO $$
DECLARE c record; n bigint;
BEGIN
  IF (SELECT count(*) FROM app.ledger WHERE type = 'SHARED') <> 1 THEN
    RAISE EXCEPTION 'the dump has % family budgets, not 1', (SELECT count(*) FROM app.ledger WHERE type = 'SHARED');
  END IF;
  IF (SELECT count(*) FROM participant) <> 2 OR (SELECT count(DISTINCT dev) FROM participant) <> 2 THEN
    RAISE EXCEPTION 'the family budget has no owner and one other member with an account';
  END IF;
  FOR c IN SELECT table_name, column_name FROM information_schema.columns
           WHERE table_schema = 'app' AND data_type IN ('text', 'character varying') LOOP
    EXECUTE format('SELECT count(*) FROM app.%I WHERE %I IN (SELECT dev FROM participant)', c.table_name,
                   c.column_name) INTO n;
    IF n > 0 THEN
      RAISE EXCEPTION 'a dev user''s id is in app.%.% already', c.table_name, c.column_name;
    END IF;
  END LOOP;
END $$;
SET LOCAL session_replication_role = replica;
DO $$
DECLARE c record; n bigint;
BEGIN
  FOR c IN SELECT table_name, column_name FROM information_schema.columns
           WHERE table_schema = 'app' AND data_type IN ('text', 'character varying') LOOP
    EXECUTE format('UPDATE app.%1$I t SET %2$I = p.dev FROM participant p WHERE t.%2$I = p.sub', c.table_name,
                   c.column_name);
    GET DIAGNOSTICS n = ROW_COUNT;
    IF n > 0 THEN
      RAISE NOTICE 'mapped app.%.%: % rows', c.table_name, c.column_name, n;
    END IF;
  END LOOP;
  FOR c IN SELECT table_name, column_name FROM information_schema.columns
           WHERE table_schema = 'app' AND data_type IN ('text', 'character varying') LOOP
    EXECUTE format('SELECT count(*) FROM app.%I WHERE %I IN (SELECT sub FROM participant)', c.table_name,
                   c.column_name) INTO n;
    IF n > 0 THEN
      RAISE EXCEPTION 'app.%.% still holds a participant''s sub', c.table_name, c.column_name;
    END IF;
  END LOOP;
END $$;
COMMIT;
EOF

echo "The restored database, as pg-restore-test counts it:"
psql_in -At <<'EOF'
SELECT 'users ' || (SELECT count(*) FROM app.users)
  || ', ledgers ' || (SELECT count(*) FROM app.ledger)
  || ', members ' || (SELECT count(*) FROM app.ledger_member)
  || ', accounts ' || (SELECT count(*) FROM app.account)
  || ', categories ' || (SELECT count(*) FROM app.category)
  || ', counterparties ' || (SELECT count(*) FROM app.counterparty)
  || ', entries ' || (SELECT count(*) FROM app.journal_entry)
  || ', postings ' || (SELECT count(*) FROM app.posting)
  || ', family_records ' || (SELECT count(*) FROM app.family_record)
  || ', family_shares ' || (SELECT count(*) FROM app.family_share)
  || ', family_links ' || (SELECT count(*) FROM app.family_entry_link)
  || ', family_journal ' || (SELECT count(*) FROM app.family_record_change)
  || ', family_invites ' || (SELECT count(*) FROM app.ledger_invite);
EOF
cat <<EOF
Ready: jdbc:postgresql://localhost:$PORT/finance (postgres / rehearsal). Sign in as $OWNER_USER (the owner) and
$PARTNER_USER (the partner). Remove it with: scripts/rehearsal-db.sh --remove
EOF
