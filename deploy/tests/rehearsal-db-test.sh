#!/usr/bin/env bash
# D-916 (REH-1): scripts/rehearsal-db.sh's choice of the family budget, on databases made for it. Unlike run.sh this one
# uses Docker (the pinned postgres image, no network after the pull): a source database with a minimal schema `app`
# (only the tables and columns the script reads or maps), dumped with pg_dump, and a stand-in for the dev Keycloak's
# database. On the laptop only; not part of CI's "Deploy scripts" job (D-916). Every container it starts is removed by name.
#   deploy/tests/rehearsal-db-test.sh        exit 0 when every case passes
# shellcheck disable=SC2329
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
IMAGE="$(grep -E '^\s+image: postgres:' "$ROOT/deploy/app/docker-compose.yml" | head -n 1 | sed -E 's/^\s+image: //')"
TMP="$(mktemp -d)"
SRC=rehearsal-test-src
KC=rehearsal-test-keycloak
export REHEARSAL_DB_PORT="${REHEARSAL_TEST_PORT:-5439}"
export REHEARSAL_TMPFS_SIZE=256m
export REHEARSAL_KEYCLOAK_DB="$KC"
fail=0
# shellcheck disable=SC2329
cleanup() {
  docker rm -f -v "$SRC" "$KC" >/dev/null 2>&1 || true
  "$ROOT/scripts/rehearsal-db.sh" --remove >/dev/null 2>&1 || true
  rm -rf "${TMP:?}"
}
trap cleanup EXIT

check() { # name, then a command whose success is the case passing
  local name="$1"
  shift
  if "$@"; then echo "ok   $name"; else echo "FAIL $name"; fail=1; fi
}
status_is() { [ "$(cat "$TMP/$1.status")" = "$2" ]; }
holds() { [ "$(stays | tr '\n' ' ')" = "$1" ]; }
has() { grep -q -- "$1" "$2"; }

start() { # name, user, database
  docker run -d --rm --name "$1" -e POSTGRES_PASSWORD=t -e POSTGRES_USER="$2" -e POSTGRES_DB="$3" "$IMAGE" -c fsync=off >/dev/null
  for _ in $(seq 1 60); do docker exec "$1" pg_isready -U "$2" -d "$3" -q && return 0; sleep 1; done
  echo "$1 didn't start" >&2; exit 1
}
sql() { docker exec -i "$SRC" psql -X -q -v ON_ERROR_STOP=1 -U postgres -d finance; }

docker rm -f -v "$SRC" "$KC" >/dev/null 2>&1 || true
start "$SRC" postgres finance
start "$KC" keycloak keycloak
docker exec -i "$KC" psql -X -q -v ON_ERROR_STOP=1 -U keycloak -d keycloak <<'EOF'
CREATE TABLE realm (id text PRIMARY KEY, name text);
CREATE TABLE user_entity (id text PRIMARY KEY, realm_id text, username text);
INSERT INTO realm VALUES ('r1', 'myapps');
INSERT INTO user_entity VALUES ('11111111-1111-1111-1111-111111111111', 'r1', 'testuser'),
                               ('22222222-2222-2222-2222-222222222222', 'r1', 'testuser2');
EOF

# The schema: the tables the script reads or counts, with the columns it reads; every owned table has a text user_id.
sql <<'EOF'
CREATE SCHEMA app;
CREATE TABLE app.ledger (id bigint PRIMARY KEY, type text, name text, created_at timestamptz);
CREATE TABLE app.ledger_member (id bigint PRIMARY KEY, ledger_id bigint, ledger_type text, user_sub text, role text, status text);
CREATE TABLE app.users (id bigint PRIMARY KEY, keycloak_id text);
CREATE TABLE app.account (id bigint PRIMARY KEY, user_id text);
CREATE TABLE app.category (id bigint PRIMARY KEY, user_id text);
CREATE TABLE app.counterparty (id bigint PRIMARY KEY, user_id text);
CREATE TABLE app.journal_entry (id bigint PRIMARY KEY, user_id text);
CREATE TABLE app.posting (id bigint PRIMARY KEY);
CREATE TABLE app.family_record (id bigint PRIMARY KEY);
CREATE TABLE app.family_share (id bigint PRIMARY KEY);
CREATE TABLE app.family_entry_link (id bigint PRIMARY KEY);
CREATE TABLE app.family_record_change (id bigint PRIMARY KEY);
CREATE TABLE app.ledger_invite (id bigint PRIMARY KEY);
-- Four real people: A and B own and share budget 10; C and D own and share budget 11 in the two-candidate dump.
INSERT INTO app.users VALUES (1, 'sub-a'), (2, 'sub-b'), (3, 'sub-c'), (4, 'sub-d'), (5, 'sub-demo');
INSERT INTO app.journal_entry VALUES (1, 'sub-a'), (2, 'sub-b'), (3, 'sub-c'), (4, 'sub-d'), (5, 'sub-demo');
INSERT INTO app.ledger VALUES (10, 'SHARED', 'Family Budget', '2026-10-13 21:05:23+00'),
                              (30, 'SHARED', 'Demo household', '2026-11-15 15:14:38+00');
INSERT INTO app.ledger_member VALUES
  (100, 10, 'SHARED', 'sub-a', 'OWNER', 'ACTIVE'), (101, 10, 'SHARED', 'sub-b', 'MEMBER', 'ACTIVE'),
  (300, 30, 'SHARED', 'sub-demo', 'OWNER', 'ACTIVE'), (301, 30, 'SHARED', NULL, 'MEMBER', 'ACTIVE');
EOF
dump() { docker exec "$SRC" pg_dump -U postgres -d finance -Fc >"$TMP/$1.dump"; }
dump two-budgets   # the real budget and a user's demo household with one member with an account
sql <<'EOF'
INSERT INTO app.ledger VALUES (11, 'SHARED', 'Second Budget', '2026-10-20 08:00:00+00');
INSERT INTO app.ledger_member VALUES (110, 11, 'SHARED', 'sub-c', 'OWNER', 'ACTIVE'), (111, 11, 'SHARED', 'sub-d', 'MEMBER', 'ACTIVE');
EOF
dump three-budgets   # two candidates and the demo
sql <<'EOF'
DELETE FROM app.ledger_member WHERE ledger_id IN (11, 30);
DELETE FROM app.ledger WHERE id IN (11, 30);
EOF
dump one-budget      # the real budget alone

run() { # case name, dump, arguments...: output in $TMP/<case>.out, exit status in $TMP/<case>.status
  local name="$1" dumpfile="$2"; shift 2
  set +e
  "$ROOT/scripts/rehearsal-db.sh" "$TMP/$dumpfile.dump" "$@" >"$TMP/$name.out" 2>&1
  echo $? >"$TMP/$name.status"
  set -e
}
stays() { # which subs the dump's database still holds after the last run (the container is still up)
  docker exec -i "$ROOT_NAME" psql -X -At -U postgres -d finance -c "SELECT user_id FROM app.journal_entry ORDER BY id"
}
ROOT_NAME=finance-tracker-rehearsal-db
DEV_A=11111111-1111-1111-1111-111111111111
DEV_B=22222222-2222-2222-2222-222222222222
remove() { "$ROOT/scripts/rehearsal-db.sh" --remove >/dev/null; }

# 1. The real budget is picked when the other budget is a demo household (its one member with an account).
run picked two-budgets
check "two budgets, one candidate: exit 0" status_is picked 0
check "two budgets, one candidate: Ready" has Ready "$TMP/picked.out"
check "the real budget's two members are mapped, the demo's user is not" holds \
  "$DEV_A $DEV_B sub-c sub-d sub-demo "
remove

# 2. Two candidates: refused, both named by id and creation time, nothing mapped.
run two three-budgets
check "two candidates: refused (exit 3)" status_is two 3
check "candidate 10 named by id and creation time" has "10 (created 2026-10-13 21:05:23)" "$TMP/two.out"
check "candidate 11 named by id and creation time" has "11 (created 2026-10-20 08:00:00)" "$TMP/two.out"
check "the refusal asks for --family" has "give --family <id>" "$TMP/two.out"
remove

# 3. --family with a candidate: that budget's members are mapped, the other candidate's are not.
run chosen three-budgets --family 11
check "--family with a candidate: exit 0" status_is chosen 0
check "only the chosen budget's members are mapped" holds "sub-a sub-b $DEV_A $DEV_B sub-demo "
remove

# 4. --family with a budget that isn't a candidate (the demo household), and one that doesn't exist.
run demo three-budgets --family 30
check "--family with a non-candidate: refused (exit 3)" status_is demo 3
check "--family with a non-candidate: says why" has "is not one whose active members" "$TMP/demo.out"
check "--family with a non-candidate: names the candidates" has "10 (created" "$TMP/demo.out"
remove
run missing three-budgets --family 999
check "--family with an unknown id: refused (exit 3)" status_is missing 3
remove

# 5. --family with a bad value: usage, exit 2, nothing started.
run bad one-budget --family x
check "--family with a bad value: exit 2" status_is bad 2
run bare one-budget --family
check "--family without a value: exit 2" status_is bare 2

# 6. A dump with one budget: picked with no argument, and --family with it works too.
run one one-budget
check "one budget: exit 0" status_is one 0
check "one budget: Ready" has Ready "$TMP/one.out"
remove
run one-family one-budget --family 10
check "one budget with --family 10: exit 0" status_is one-family 0
remove

if [ "$fail" -eq 0 ]; then echo "All cases passed."; else echo "Some cases FAILED."; fi
exit "$fail"
