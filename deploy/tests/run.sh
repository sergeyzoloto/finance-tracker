#!/usr/bin/env bash
# The tests of deploy/deploy.sh and deploy/rollback.sh. Each case runs the scripts in a scratch git repository with a
# fake origin, with stubs of docker, systemctl, pg-restore-test, curl and install (deploy/tests/stubs) first on PATH.
# The stubs record every call and answer from fixtures. No real container, no network, and nothing outside one temp
# folder, which is removed afterwards: the scripts' paths come from the DEPLOY_* variables (deploy/common.sh).
#
#   deploy/tests/run.sh               every case
#   deploy/tests/run.sh NAME...       the cases named (deploy/tests/run.sh --list names them)
#   DEPLOY_TEST_SCRIPTS=<dir>         takes deploy.sh, rollback.sh and common.sh from <dir> (deploy/tests/mutate.sh)
#   DEPLOY_TEST_KEEP=1                keeps the temp folder and prints where it is
set -uo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
SCRIPTS=${DEPLOY_TEST_SCRIPTS:-$ROOT/deploy}
WORK=$(mktemp -d "${TMPDIR:-/tmp}/deploy-tests.XXXXXX")
if [ "${DEPLOY_TEST_KEEP:-}" = 1 ]; then
  trap 'echo "Kept $WORK"' EXIT
else
  trap 'rm -rf "$WORK"' EXIT
fi

export HOME=$WORK/home GIT_CONFIG_NOSYSTEM=1
export GIT_AUTHOR_NAME=Test GIT_AUTHOR_EMAIL=test@example.test GIT_COMMITTER_NAME=Test GIT_COMMITTER_EMAIL=test@example.test
mkdir -p "$HOME"
git config --global init.defaultBranch main
git config --global advice.detachedHead false

SECRET=FAKE-SECRET-$(head -c 12 /dev/urandom | od -An -tx1 | tr -d ' \n')

id_of() { printf 'sha256:%s' "$(printf '%s' "$1" | sha256sum | cut -c1-64)"; }

# ---------------------------------------------------------------------------------------------------------------------
# The scratch repository: commits A, B, C and D (the running revision) on origin's main. With MAXV=6, D has the
# migrations up to V6 only.

make_template() {
  local t=$1 maxv=$2 f name v
  git init -q --bare "$t/origin.git"
  git clone -q "$t/origin.git" "$t/work" 2>/dev/null
  (
    cd "$t/work" || exit 1
    mkdir -p deploy/checks deploy/app deploy/pg-backup backend/src/main/resources/db/migration
    cp "$SCRIPTS/deploy.sh" "$SCRIPTS/rollback.sh" "$SCRIPTS/common.sh" deploy/
    cp "$ROOT/deploy/checks/numbers.sql" "$ROOT/deploy/checks/OPS-1.sql" "$ROOT/deploy/checks/OPS-1.expected" deploy/checks/
    cp "$ROOT/deploy/app/docker-compose.yml" "$ROOT/deploy/app/postgres-init.sh" deploy/app/
    cp "$ROOT/deploy/finance.caddy" deploy/
    cp "$ROOT/deploy/pg-backup/finance.conf" deploy/pg-backup/
    for f in "$ROOT"/backend/src/main/resources/db/migration/V*__*.sql; do
      name=$(basename "$f")
      v=${name#V}; v=${v%%__*}
      [ "$v" -le "$maxv" ] && echo "-- placeholder of $name" >"backend/src/main/resources/db/migration/$name"
    done
    printf 'deploy/app/.env\n' >.gitignore
    for c in A B C D; do
      echo "revision $c" >README
      git add -A
      git commit -qm "revision $c"
    done
    git push -q origin main
  )
}

make_template "$WORK/template9" 9
make_template "$WORK/template6" 6

# ---------------------------------------------------------------------------------------------------------------------
# A case: its own copy of the repositories, the server's clone at D, the state of a good deploy of D, and the stubs.

setup_case() {
  local variant=${1:-9} name
  C=$WORK/cases/$CASE
  mkdir -p "$C"
  cp -a "$WORK/template$variant/origin.git" "$C/origin.git"
  cp -a "$WORK/template$variant/work" "$C/work"
  git -C "$C/work" remote set-url origin "$C/origin.git"
  git clone -q "$C/origin.git" "$C/server"
  printf 'POSTGRES_PASSWORD=%s\nAPP_DB_PASSWORD=%s\nKEYCLOAK_CLIENT_SECRET=%s\n' "$SECRET" "$SECRET" "$SECRET" \
    >"$C/server/deploy/app/.env"
  SHA_A=$(git -C "$C/server" rev-parse HEAD~3)
  SHA_B=$(git -C "$C/server" rev-parse HEAD~2)
  SHA_C=$(git -C "$C/server" rev-parse HEAD~1)
  SHA_D=$(git -C "$C/server" rev-parse HEAD)
  API_D=$(id_of api-D)
  WEB_D=$(id_of web-D)

  mkdir -p "$C/state" "$C/backups" "$C/bin" "$C/stub/fixtures" "$C/stub/images/finance-tracker-api" \
    "$C/stub/images/finance-tracker-web" "$C/stub/containers/finance-tracker-api" "$C/stub/containers/finance-tracker-web"
  chmod 700 "$C/state"
  printf 'commit=%s\ntime=2026-10-01T16:57:00Z\napi_image=%s\nweb_image=%s\nsource=deploy\n' "$SHA_D" "$API_D" "$WEB_D" \
    >"$C/state/last-good"
  printf '2026-10-01T16:57:00Z good %s %s %s\n' "$SHA_D" "$API_D" "$WEB_D" >"$C/state/history"

  local s=$C/stub
  echo "$API_D" >"$s/images/finance-tracker-api/latest"
  echo "$WEB_D" >"$s/images/finance-tracker-web/latest"
  for name in api web; do
    local cdir=$s/containers/finance-tracker-$name
    id=$API_D
    [ "$name" = web ] && id=$WEB_D
    echo "$id" >"$cdir/image"
    echo 2026-10-01T16:56:40.000000000Z >"$cdir/started"
    echo healthy >"$cdir/health"
    printf 'SPRING_DATASOURCE_PASSWORD=%s\nKEYCLOAK_CLIENT_SECRET=%s\nKEYCLOAK_CLIENT_ID=finance-tracker\nPATH=/usr/bin\n' \
      "$SECRET" "$SECRET" >"$cdir/env"
  done
  # The api's log: an older start that logged the switch on, then the current start.
  cat >"$s/log-api" <<'EOF'
2026-09-20T10:00:00.000000000Z Family ledgers (D-25): on
2026-10-01T16:56:43.000000000Z 2026-10-01T16:56:43.000Z  INFO 1 --- [main] o.f.core.internal.command.DbMigrate : Schema "app" is up to date. No migration necessary.
2026-10-01T16:56:46.000000000Z 2026-10-01T16:56:46.000Z  INFO 1 --- [main] c.e.f.ledger.family.FamilySwitch : Family ledgers (D-25): off; the family endpoints answer 404
EOF
  cat >"$s/fixtures/start-log" <<'EOF'
2026-10-02T04:35:43.000Z  INFO 1 --- [main] o.f.core.internal.command.DbMigrate : Schema "app" is up to date. No migration necessary.
2026-10-02T04:35:46.000Z  INFO 1 --- [main] c.e.f.ledger.family.FamilySwitch : Family ledgers (D-25): off; the family endpoints answer 404
2026-10-02T04:35:46.500Z  INFO 1 --- [main] c.e.f.FinanceTrackerApplication : Started FinanceTrackerApplication in 7.8 seconds
EOF
  printf 'starting\nhealthy\n' >"$s/fixtures/health-api"
  echo healthy >"$s/fixtures/health-web"
  echo "9 family invites true" >"$s/fixtures/flyway"
  echo 0 >"$s/fixtures/family_records"
  cp "$ROOT/deploy/checks/OPS-1.expected" "$s/fixtures/stage"
  numbers 0 >"$s/fixtures/numbers"
  cp "$HERE/fixtures/check-runs-success.json" "$s/fixtures/ci"

  for name in docker systemctl pg-restore-test curl install; do ln -s "$HERE/stubs/$name" "$C/bin/$name"; done

  export STUB_STATE=$s PATH=$C/bin:$BASE_PATH
  export DEPLOY_REPO_DIR=$C/server DEPLOY_STATE_DIR=$C/state DEPLOY_LOCK=$C/lock DEPLOY_TTY=$C/tty
  export DEPLOY_BACKUP_DIR=$C/backups DEPLOY_PREVIOUS_FILE=$C/previous
  export DEPLOY_CI_WAIT=2 DEPLOY_CI_INTERVAL=1 DEPLOY_HEALTH_TRIES=3 DEPLOY_HEALTH_INTERVAL=0
  : >"$C/tty"
  cp "$C/state/last-good" "$C/last-good.orig"
  cp "$C/state/history" "$C/history.orig"
}
BASE_PATH=$PATH

# numbers FAMILY_RECORDS: numbers.sql's answer, as the stub's psql gives it.
numbers() {
  printf '%s\n' accounts=22 categories=33 counterparties=10 entries=138 family_accounts=0 family_invites=0 \
    family_journal=0 family_ledgers=0 family_links=0 family_members=0 family_members_active=0 \
    family_members_former=0 family_members_left=0 "family_records=$1" family_shares=0 import_batches=0 \
    outside_their_ledger=0 personal_ledgers=2 personal_members=2 settings=2 users=2
}

# new_target [CHANGE...]: a commit E on origin's main with the changes named; prints nothing, sets SHA_E.
new_target() {
  local change
  (
    cd "$C/work" || exit 1
    echo "revision E" >README
    for change in "$@"; do
      case $change in
        caddy) echo "# changed" >>deploy/finance.caddy ;;
        postgres) sed -i 's/mem_limit: 384m/mem_limit: 512m/' deploy/app/docker-compose.yml ;;
        postgres-init) echo "# changed" >>deploy/app/postgres-init.sh ;;
        api) sed -i 's/mem_limit: 768m/mem_limit: 769m/' deploy/app/docker-compose.yml ;;
        conf) echo "# changed" >>deploy/pg-backup/finance.conf ;;
        migrations)
          for v in 7 8 9; do
            for f in "$ROOT"/backend/src/main/resources/db/migration/V"${v}"__*.sql; do
              echo "-- placeholder" >"backend/src/main/resources/db/migration/$(basename "$f")"
            done
          done
          ;;
        scripts)
          printf '#!/usr/bin/env bash\necho "NEW CODE RAN"\nexit 42\n' >deploy/deploy.sh
          printf 'echo "NEW COMMON RAN"\n' >deploy/common.sh
          ;;
        *) echo "unknown change $change" >&2; exit 1 ;;
      esac
    done
    git add -A
    git commit -qm "revision E"
    git push -q origin main
  )
  SHA_E=$(git -C "$C/work" rev-parse HEAD)
}

run_deploy() {
  (cd "$C/server" && ./deploy/deploy.sh "$@") >"$C/out" 2>&1 </dev/null
  RC=$?
  cat "$C/out" >>"$C/all-out"
}
run_rollback() {
  (cd "$C/server" && ./deploy/rollback.sh "$@") >"$C/out" 2>&1 </dev/null
  RC=$?
  cat "$C/out" >>"$C/all-out"
}
type_at_terminal() { printf '%s\n' "$@" >"$C/tty"; }
fixture() { printf '%s\n' "$2" >"$STUB_STATE/fixtures/$1"; }

# deploy_e: deploys E, typing its first 7 characters.
deploy_e() {
  type_at_terminal "${SHA_E:0:7}"
  run_deploy run "$SHA_E" OPS-1
}

# ---------------------------------------------------------------------------------------------------------------------
# Checks: each failed one is printed and counted.

FAILS=0
check() {
  local what=$1
  shift
  if ! "$@"; then
    echo "    not as expected: $what"
    FAILS=$((FAILS + 1))
  fi
}
rc_is() { [ "$RC" -eq "$1" ]; }
rc_not() { [ "$RC" -ne 0 ]; }
out_has() { grep -qF -- "$1" "$C/out"; }
out_lacks() { ! grep -qF -- "$1" "$C/out"; }
calls_have() { grep -qE -- "$1" "$STUB_STATE/calls"; }
calls_lack() { ! grep -qE -- "$1" "$STUB_STATE/calls" 2>/dev/null; }
count_calls() { grep -cE -- "$1" "$STUB_STATE/calls" 2>/dev/null || true; }
head_is() { [ "$(git -C "$C/server" rev-parse HEAD)" = "$1" ]; }
image_is() { [ "$(cat "$STUB_STATE/images/$1/$2" 2>/dev/null)" = "$3" ]; }
no_image() { [ ! -e "$STUB_STATE/images/$1/$2" ]; }
last_good_commit() { sed -n 's/^commit=//p' "$C/state/last-good"; }
last_good_is() { [ "$(last_good_commit)" = "$1" ]; }
same_file() { cmp -s "$1" "$2"; }
latest_run() { find "$C/state/runs" -mindepth 1 -maxdepth 1 -type d | sort | tail -n 1; }
status_is() { [ "$(cat "$(latest_run)/status")" = "$1" ]; }
file_has() { grep -qF -- "$2" "$1"; }

# in_order REGEX...: the calls match in this order.
in_order() {
  local pattern n last=0
  for pattern in "$@"; do
    n=$(grep -nE -- "$pattern" "$STUB_STATE/calls" | awk -F: -v after="$last" '$1 > after { print $1; exit }')
    if [ -z "$n" ]; then echo "      no call matching $pattern after line $last"; return 1; fi
    last=$n
  done
}

STATE_CHANGING='^docker (tag|rmi|compose build|compose up|image prune)|^install '

nothing_changed() {
  check "HEAD still D" head_is "$SHA_D"
  check "no tag, build, start, prune or install" calls_lack "$STATE_CHANGING"
  check "last-good unchanged" same_file "$C/state/last-good" "$C/last-good.orig"
  check "history unchanged" same_file "$C/state/history" "$C/history.orig"
}

refused() {
  check "exit code not 0" rc_not
  check "says REFUSED" out_has "REFUSED at"
  check "says nothing changed" out_has "Nothing changed"
  check "names why: $1" out_has "$1"
  nothing_changed
}

# failed_after_merge WHY: stopped with the rollback command printed, and nothing rolled back.
failed_after_merge() {
  check "exit code not 0" rc_not
  check "says FAILED" out_has "FAILED at"
  check "names why: $1" out_has "$1"
  check "prints the exact rollback command" out_has "deploy/rollback.sh $SHA_D"
  check "HEAD stays at E: no checkout back" head_is "$SHA_E"
  check "the last good images aren't retagged to run" calls_lack "^docker tag finance-tracker-(api|web):$SHA_D finance-tracker-(api|web)$"
  check "api and web started once, by the deploy" [ "$(count_calls '^docker compose up')" -le 1 ]
  check "last-good unchanged" same_file "$C/state/last-good" "$C/last-good.orig"
  check "no rollback event" bash -c "! grep -qE 'rolled-back-from|rollback-to' '$C/state/history'"
  check "run status failed" status_is failed
}

# ---------------------------------------------------------------------------------------------------------------------
# The cases

case_happy_path() {
  setup_case
  new_target api
  deploy_e
  check "exit code 0" rc_is 0
  check "says deployed" out_has "Deploy of ${SHA_E:0:7} (stage OPS-1): deployed"
  check "the checklist's order" in_order \
    '^curl .*/commits/'"$SHA_E"'/check-runs' \
    '^docker compose exec -T -e PGOPTIONS=-c default_transaction_read_only=on postgres psql .* -f -$' \
    '^systemctl start pg-backup@finance.service$' \
    '^pg-restore-test finance$' \
    "^docker tag $API_D finance-tracker-api:$SHA_D$" \
    "^docker tag $API_D finance-tracker-api:previous$" \
    "^docker tag $WEB_D finance-tracker-web:$SHA_D$" \
    "^docker tag $WEB_D finance-tracker-web:previous$" \
    '^docker compose build api$' \
    '^docker compose build web$' \
    '^docker compose up -d --no-deps api web$' \
    '^docker inspect -f \{\{\.State\.Health\.Status\}\} finance-tracker-api$' \
    '^docker image prune -f$' \
    'psql .* -c SELECT version .*app.flyway_schema_history' \
    '^docker compose logs --no-log-prefix --timestamps --since .* api$' \
    'psql .* -f -$' \
    'psql .* -f -$' \
    '^systemctl start pg-backup@finance.service$' \
    '^pg-restore-test finance$'
  check "HEAD at E" head_is "$SHA_E"
  check "last-good is E" last_good_is "$SHA_E"
  check "last-good has E's new api image" file_has "$C/state/last-good" "api_image=$(cat "$STUB_STATE/containers/finance-tracker-api/image")"
  check "history: good E" file_has "$C/state/history" " good $SHA_E "
  check "D's images kept under D's commit" image_is finance-tracker-api "$SHA_D" "$API_D"
  check "D's web image kept under D's commit" image_is finance-tracker-web "$SHA_D" "$WEB_D"
  check "D's images as :previous" image_is finance-tracker-api previous "$API_D"
  check "the previous file names D" file_has "$C/previous" "$SHA_D"
  check "finance.conf not installed" calls_lack '^install '
  check "two restore tests" [ "$(count_calls '^pg-restore-test')" -eq 2 ]
  local summary
  summary=$(latest_run)/summary.txt
  for line in "Date: " "Previous commit: ${SHA_D:0:7} (revision D), the last good deploy" "New commit: ${SHA_E:0:7} (revision E)" \
    "Commits: ${SHA_E:0:7} (${SHA_D:0:7} to ${SHA_E:0:7})" "Migrations added: none; under deploy/ changed: deploy/app/docker-compose.yml" \
    "CI: 6 check runs, every one completed with success (Backend, Dependency updates, Frontend)" \
    "Before: dump finance-" "restore test PASS (tables 19, migration 9, family_records 0, family_shares 0, family_links 0, family_journal 0, family_invites 0)" \
    "Flyway: version 9 (9 family invites true); \"Schema \"app\" is up to date. No migration necessary.\" at " \
    "Started: \"Started FinanceTrackerApplication in 7.8 seconds\" at " \
    "D-25: \"Family ledgers (D-25): off; the family endpoints answer 404\" at " "as production sets it (FAMILY_LEDGERS_ENABLED absent, so off)" \
    "Numbers: the same before and after" "Stage checks (OPS-1): the same as deploy/checks/OPS-1.expected (11 lines)" \
    "finance.conf: not installed (unchanged)" "After: dump finance-" "Images: api sha256:" "Browser checks: not yet"; do
    check "summary has: $line" file_has "$summary" "$line"
  done
  check "the D-25 line is the current start's, not the older 'on'" bash -c "! grep -q 'D-25): on' '$summary'"
  check "summary without a pipe character" bash -c "! grep -q '|' '$summary'"
  check "the run folder holds the numbers, the stage diff and the restore tests" bash -c \
    "cd '$(latest_run)' && test -s numbers-before.txt && test -s numbers-after.txt && test -f stage.diff && test ! -s stage.diff && test -s restore-test-before.txt && test -s restore-test-after.txt && test -s log"
  check "status deployed" status_is deployed
  check "the state folder is mode 700" [ "$(stat -c %a "$C/state")" = 700 ]
  check "says what's left" out_has "then: deploy/deploy.sh finish"
}

case_first_run() {
  setup_case
  rm "$C/state/last-good" "$C/state/history"
  new_target
  deploy_e
  check "exit code 0" rc_is 0
  check "says it is the first run" out_has "No record of a last good deploy: deploy.sh's first run"
  check "the summary says so" file_has "$(latest_run)/summary.txt" "HEAD with its images at deploy.sh's first run (no record before)"
  check "history: D as the baseline, then E" in_order_file "$C/state/history" " baseline $SHA_D $API_D $WEB_D" " good $SHA_E "
  check "D's images kept" image_is finance-tracker-api "$SHA_D" "$API_D"
  check "last-good is E" last_good_is "$SHA_E"
}
in_order_file() { local f=$1; shift; local p n last=0; for p in "$@"; do n=$(grep -nF -- "$p" "$f" | awk -F: -v a="$last" '$1 > a { print $1; exit }'); [ -n "$n" ] || return 1; last=$n; done; }

case_refuse_ci_failed() {
  setup_case
  new_target
  cp "$HERE/fixtures/check-runs-failure.json" "$STUB_STATE/fixtures/ci"
  deploy_e
  refused "did not succeed"
  check "the check runs printed" out_has "Backend: completed, failure"
}

case_refuse_ci_still_running() {
  setup_case
  new_target
  cp "$HERE/fixtures/check-runs-in-progress.json" "$STUB_STATE/fixtures/ci"
  deploy_e
  refused "still runs after"
  check "it waited" [ "$(count_calls '^curl ')" -ge 2 ]
}

case_ci_waits_then_succeeds() {
  setup_case
  new_target
  cp "$HERE/fixtures/check-runs-in-progress.json" "$STUB_STATE/fixtures/ci.1"
  deploy_e
  check "exit code 0" rc_is 0
  check "waited once" out_has "CI still runs; checking again"
  check "two reads of the check runs" [ "$(count_calls '^curl ')" -eq 2 ]
}

case_refuse_ci_none() {
  setup_case
  new_target
  cp "$HERE/fixtures/check-runs-none.json" "$STUB_STATE/fixtures/ci"
  deploy_e
  refused "GitHub has no check run"
}

case_refuse_not_origin_main() {
  setup_case
  new_target
  local e=$SHA_E
  (cd "$C/work" && echo F >README && git commit -qam "revision F" && git push -q origin main)
  SHA_E=$e
  deploy_e
  refused "is not origin/main"
}

case_refuse_not_fast_forward() {
  setup_case
  (cd "$C/server" && echo local >LOCAL && git add LOCAL && git commit -qm "a local commit")
  local local_head
  local_head=$(git -C "$C/server" rev-parse HEAD)
  new_target
  deploy_e
  check "exit code not 0" rc_not
  check "says why" out_has "is not a fast-forward of HEAD"
  check "HEAD unchanged" head_is "$local_head"
  check "no tag, build, start or install" calls_lack "$STATE_CHANGING"
  check "last-good unchanged" same_file "$C/state/last-good" "$C/last-good.orig"
}

case_refuse_caddy_changed() {
  setup_case
  new_target caddy
  deploy_e
  refused "deploy/finance.caddy changes"
}

case_refuse_postgres_changed() {
  setup_case
  new_target postgres
  deploy_e
  refused "the postgres service changes"
}

case_refuse_postgres_init_changed() {
  setup_case
  new_target postgres-init
  deploy_e
  refused "the postgres service changes"
}

case_refuse_stage_files_missing() {
  setup_case
  new_target
  type_at_terminal "${SHA_E:0:7}"
  run_deploy run "$SHA_E" F6b
  refused "has no deploy/checks/F6b.sql and F6b.expected"
}

case_refuse_restore_test_fails() {
  setup_case
  new_target
  fixture restore.1 FAIL
  deploy_e
  refused "the restore test did not PASS"
}

case_refuse_without_terminal() {
  setup_case
  new_target
  export DEPLOY_TTY=$C/no-such-terminal
  deploy_e
  refused "can't read $C/no-such-terminal"
  check "no backup spent" calls_lack '^systemctl|^pg-restore-test'
}

case_refuse_tool_missing() {
  setup_case
  rm "$C/bin/pg-restore-test"
  new_target
  deploy_e
  refused "missing on this server: pg-restore-test"
}

case_wrong_confirmation() {
  setup_case
  new_target
  type_at_terminal "${SHA_D:0:7}"
  run_deploy run "$SHA_E" OPS-1
  refused "not confirmed"
  check "no run is deploying" status_is refused
  : >"$C/tty"
  run_deploy run "$SHA_E" OPS-1
  refused "not confirmed"
}

case_fail_never_healthy() {
  setup_case
  new_target
  fixture health-api starting
  deploy_e
  failed_after_merge "api and web not both healthy"
}

case_fail_flyway_below() {
  setup_case
  new_target
  fixture flyway "8 family record currencies true"
  deploy_e
  failed_after_merge "Flyway is at version 8, but ${SHA_E:0:7}'s highest migration is V9"
}

case_fail_switch_line_missing() {
  setup_case
  new_target
  grep -v 'D-25' "$STUB_STATE/fixtures/start-log" >"$STUB_STATE/fixtures/start-log.new"
  mv "$STUB_STATE/fixtures/start-log.new" "$STUB_STATE/fixtures/start-log"
  deploy_e
  failed_after_merge "the api's log since its start has no D-25 line"
}

case_fail_switch_line_wrong() {
  setup_case
  new_target
  sed -i 's/Family ledgers (D-25): off; the family endpoints answer 404/Family ledgers (D-25): on/' "$STUB_STATE/fixtures/start-log"
  deploy_e
  failed_after_merge "the D-25 line isn't \"Family ledgers (D-25): off; the family endpoints answer 404\""
}

case_fail_numbers_differ() {
  setup_case
  new_target
  numbers 0 | sed 's/^users=2$/users=3/' >"$STUB_STATE/fixtures/numbers.2"
  deploy_e
  failed_after_merge "the numbers differ from preflight's"
  check "the diff printed" out_has "> users=3"
}

case_fail_stage_diff() {
  setup_case
  new_target
  sed 's/^family_records 0$/family_records 1/' "$ROOT/deploy/checks/OPS-1.expected" >"$STUB_STATE/fixtures/stage"
  deploy_e
  failed_after_merge "the stage's checks differ from deploy/checks/OPS-1.expected"
  check "the diff printed" out_has "> family_records 1"
}

case_fail_second_restore_test() {
  setup_case
  new_target
  fixture restore.2 FAIL
  deploy_e
  failed_after_merge "the restore test did not PASS"
}

case_repeat_after_failure() {
  setup_case
  new_target
  fixture health-api starting
  deploy_e
  check "first run failed" rc_not
  local failed_api
  failed_api=$(cat "$STUB_STATE/containers/finance-tracker-api/image")
  deploy_e
  check "second run failed too" rc_not
  check "the failed images take no tag" out_has "are not the last good deploy's: a failed run's"
  check ":previous still D's api" image_is finance-tracker-api previous "$API_D"
  check ":previous still D's web" image_is finance-tracker-web previous "$WEB_D"
  check "D's tag still D's api" image_is finance-tracker-api "$SHA_D" "$API_D"
  check "no tag holds the failed image" bash -c "! grep -rqx '$failed_api' '$STUB_STATE/images/finance-tracker-api' --exclude=latest"
  check "last-good unchanged" same_file "$C/state/last-good" "$C/last-good.orig"
  check "the rollback command still names D" out_has "deploy/rollback.sh $SHA_D"
  fixture health-api healthy
  deploy_e
  check "third run deployed" rc_is 0
  check ":previous still D's api after success" image_is finance-tracker-api previous "$API_D"
  check "last-good is E now" last_good_is "$SHA_E"
  check "the third run's summary says the failed run's images took no tag" file_has "$(latest_run)/summary.txt" "were a failed run's and took no tag"
  check "the summary counts from the last good deploy" file_has "$(latest_run)/summary.txt" "Previous commit: ${SHA_D:0:7} (revision D), the last good deploy"
}

case_conf_changed() {
  setup_case
  new_target conf
  deploy_e
  check "exit code 0" rc_is 0
  check "installed, then a fresh backup and the restore test" in_order \
    "^install -o root -g root -m 600 $C/server/deploy/pg-backup/finance.conf /etc/pg-backup/finance.conf$" \
    '^systemctl start pg-backup@finance.service$' '^pg-restore-test finance$'
  check "the summary says installed" file_has "$(latest_run)/summary.txt" "finance.conf: installed as /etc/pg-backup/finance.conf (it changed)"
}

case_conf_unchanged() {
  setup_case
  new_target
  deploy_e
  check "exit code 0" rc_is 0
  check "not installed" calls_lack '^install '
  check "the restore test still run after the deploy" in_order '^docker compose up' '^systemctl start pg-backup' '^pg-restore-test finance$'
}

case_lock_held() {
  setup_case
  new_target
  exec 7>>"$C/lock"
  flock -n 7
  deploy_e
  check "exit code not 0" rc_not
  check "says the lock is held" out_has "another deploy.sh or rollback.sh holds"
  check "no run folder" [ ! -d "$C/state/runs" ]
  nothing_changed
  check "no other call either" [ ! -s "$STUB_STATE/calls" ]
  run_rollback "${SHA_C:0:7}"
  check "rollback.sh refused too" out_has "another deploy.sh or rollback.sh holds"
  exec 7>&-
}

case_script_replaced_mid_run() {
  setup_case
  new_target scripts
  # At the build, after the merge replaced deploy.sh and common.sh, rewrite the file that bash is reading, in place
  # (the same inode, through /proc), with lines that would run if bash read any more of it.
  cat >"$STUB_STATE/fixtures/on-build" <<'EOF'
#!/usr/bin/env bash
pid=$PPID
while [ "$pid" -gt 1 ]; do
  if readlink "/proc/$pid/fd/255" 2>/dev/null | grep -q '/deploy/deploy.sh'; then
    # Lines of 4000 spaces and then a command: wherever bash goes on reading, it reads spaces up to a whole command.
    for i in $(seq 40); do printf '%4000s%s\n' '' 'echo ran >>$STUB_STATE/rewritten-ran; echo REWRITTEN-IN-PLACE'; done >"/proc/$pid/fd/255"
    echo "$pid" >"$STUB_STATE/rewrote"
    exit 0
  fi
  pid=$(awk '{ print $4 }' "/proc/$pid/stat")
done
EOF
  chmod +x "$STUB_STATE/fixtures/on-build"
  deploy_e
  check "exit code 0" rc_is 0
  check "the merge brought the new script" file_has "$C/server/deploy/common.sh" "NEW COMMON RAN"
  check "the running file was rewritten in place" [ -s "$STUB_STATE/rewrote" ]
  check "no new code ran" out_lacks "NEW CODE RAN"
  check "no new common code ran" out_lacks "NEW COMMON RAN"
  check "nothing of the rewritten file ran" out_lacks "REWRITTEN-IN-PLACE"
  check "nothing of the rewritten file ran, even after the log closed" [ ! -e "$STUB_STATE/rewritten-ran" ]
  check "deployed to the end" out_has "then: deploy/deploy.sh finish"
}

case_secret_never_printed() {
  setup_case
  new_target
  deploy_e
  check "deployed" rc_is 0
  type_at_terminal yes yes
  run_deploy finish
  run_deploy verify OPS-1
  check "the secret is in the container's environment" file_has "$STUB_STATE/containers/finance-tracker-api/env" "$SECRET"
  check "the secret is nowhere in the state folder" bash -c "! grep -rqF -- '$SECRET' '$C/state'"
  check "the secret is in no output" bash -c "! grep -qF -- '$SECRET' '$C/all-out'"
}

case_finish_records_answers() {
  setup_case
  new_target
  deploy_e
  type_at_terminal yes yes
  run_deploy finish
  check "exit code 0" rc_is 0
  local summary
  summary=$(latest_run)/summary.txt
  check "browser checks recorded with the time" grep -qE '^Browser checks: passed, answered at 20[0-9-]{8}T[0-9:]{8}Z$' "$summary"
  check "smoke test recorded with the time" grep -qE '^Smoke test: passed, answered at 20[0-9-]{8}T[0-9:]{8}Z$' "$summary"
  check "family numbers as before" file_has "$summary" "Family numbers after the smoke test: the family lines as before the deploy"
  check "no 'not yet' left" bash -c "! grep -q 'not yet' '$summary'"
  check "status finished" status_is finished
  run_deploy finish
  check "a second finish refused" out_has "not deployed: nothing to finish"
}

case_finish_reports_family_difference() {
  setup_case
  new_target
  deploy_e
  numbers 1 >"$STUB_STATE/fixtures/numbers.3"
  type_at_terminal yes no
  run_deploy finish
  check "exit code not 0" rc_not
  check "the family difference reported" out_has "DIFFERENT from before the deploy"
  check "the diff printed" out_has "> family_records=1"
  check "the smoke test's no recorded" file_has "$(latest_run)/summary.txt" 'Smoke test: NOT passed (answered "no")'
  check "the rollback command printed" out_has "deploy/rollback.sh $SHA_D"
  check "nothing rolled back" head_is "$SHA_E"
  check "history: finish-failed" file_has "$C/state/history" " finish-failed $SHA_E "
}

case_verify_read_only() {
  setup_case
  local before after
  before=$(cd "$C" && find state -exec stat -c '%n %s %Y' {} + | sort)
  run_deploy verify OPS-1
  after=$(cd "$C" && find state -exec stat -c '%n %s %Y' {} + | sort)
  check "exit code 0" rc_is 0
  check "says OK" out_has "verify OPS-1: OK"
  check "the D-25 line of the current start" out_has '"Family ledgers (D-25): off; the family endpoints answer 404" at 2026-10-01T16:56:46Z'
  check "no state-changing call" calls_lack "$STATE_CHANGING|^systemctl|^pg-restore-test|^curl"
  check "only reads: inspect, logs, read-only psql, version" calls_lack '^docker (compose (build|up|config|ps)|exec|tag|rmi)'
  check "the state folder unchanged" [ "$before" = "$after" ]
  check "HEAD unchanged" head_is "$SHA_D"
}

case_verify_warns_without_switch_line() {
  setup_case
  grep -v 'D-25' "$STUB_STATE/log-api" >"$STUB_STATE/log-api.new"
  mv "$STUB_STATE/log-api.new" "$STUB_STATE/log-api"
  run_deploy verify OPS-1
  check "exit code 0" rc_is 0
  check "a warning" out_has "WARNING: no D-25 line left in the api's retained log"
}

case_verify_reports_stage_diff() {
  setup_case
  sed 's/^family_invites 0$/family_invites 2/' "$ROOT/deploy/checks/OPS-1.expected" >"$STUB_STATE/fixtures/stage"
  run_deploy verify OPS-1
  check "exit code 1" rc_is 1
  check "a problem" out_has "PROBLEM: the stage's checks differ"
  check "no state-changing call" calls_lack "$STATE_CHANGING|^systemctl|^pg-restore-test"
}

# rollback_setup: E deployed after D, so D is the last good deploy before the current one.
rollback_setup() {
  setup_case "$@"
  new_target "${TARGET_CHANGES[@]}"
  deploy_e
  [ "$RC" -eq 0 ] || { echo "    setup: the deploy of E failed"; cat "$C/out"; FAILS=$((FAILS + 1)); }
  : >"$STUB_STATE/calls"
  cp "$C/state/last-good" "$C/last-good.orig"
  cp "$C/state/history" "$C/history.orig"
}
TARGET_CHANGES=()

rollback_refused() {
  check "exit code not 0" rc_not
  check "says REFUSED" out_has "REFUSED at"
  check "names why: $1" out_has "$1"
  check "HEAD still E" head_is "$SHA_E"
  check "no tag, start or install" calls_lack "$STATE_CHANGING"
  check "last-good unchanged" same_file "$C/state/last-good" "$C/last-good.orig"
  check "history unchanged" same_file "$C/state/history" "$C/history.orig"
}

case_rollback_refusals() {
  rollback_setup
  type_at_terminal "ROLLBACK ${SHA_D:0:7}"
  run_rollback deadbee
  rollback_refused "deadbee is no commit of this clone"
  run_rollback "${SHA_E:0:7}"
  rollback_refused "only to the last good deploy before the current one, ${SHA_D:0:7} revision D, not ${SHA_E:0:7}"
  run_rollback "${SHA_C:0:7}"
  rollback_refused "not ${SHA_C:0:7}"
  rm "$STUB_STATE/images/finance-tracker-web/$SHA_D"
  run_rollback "${SHA_D:0:7}"
  rollback_refused "no image finance-tracker-web:$SHA_D"
}

case_rollback_wrong_confirmation() {
  rollback_setup
  type_at_terminal "ROLLBACK ${SHA_C:0:7}"
  run_rollback "${SHA_D:0:7}"
  rollback_refused "not confirmed"
  type_at_terminal "${SHA_D:0:7}"
  run_rollback "${SHA_D:0:7}"
  rollback_refused "not confirmed"
}

case_rollback_rolls_back() {
  rollback_setup
  type_at_terminal "ROLLBACK ${SHA_D:0:7}"
  run_rollback "${SHA_D:0:7}"
  check "exit code 0" rc_is 0
  check "HEAD detached at D" bash -c "[ \"\$(git -C '$C/server' rev-parse HEAD)\" = '$SHA_D' ] && ! git -C '$C/server' symbolic-ref -q HEAD"
  check "retagged, started, health checked" in_order \
    "^docker tag finance-tracker-api:$SHA_D finance-tracker-api$" "^docker tag finance-tracker-web:$SHA_D finance-tracker-web$" \
    '^docker compose up -d --no-deps api web$' '^docker inspect -f \{\{\.State\.Health\.Status\}\} finance-tracker-api$'
  check "D's images run" image_is finance-tracker-api latest "$API_D"
  check "the database left alone: only read-only queries, no backup or restore" calls_lack '^systemctl|^pg-restore-test|^install '
  check "no psql but the read-only Flyway row" [ "$(count_calls 'psql')" -eq "$(count_calls 'psql .*flyway_schema_history')" ]
  check "last-good is D again" last_good_is "$SHA_D"
  check "history: rolled back from E to D" in_order_file "$C/state/history" " rolled-back-from $SHA_E " " rollback-to $SHA_D "
  check "says how to go forward" out_has "git checkout main, then deploy/deploy.sh run"
  type_at_terminal "ROLLBACK ${SHA_C:0:7}"
  run_rollback "${SHA_C:0:7}"
  check "no second rollback past D: nothing recorded before D" out_has "no good deploy before"
}

case_rollback_below_v7() {
  TARGET_CHANGES=(migrations)
  rollback_setup 6
  TARGET_CHANGES=()
  fixture family_records 3
  type_at_terminal "ROLLBACK ${SHA_D:0:7}"
  run_rollback "${SHA_D:0:7}"
  rollback_refused "production holds 3 family records, and ${SHA_D:0:7} is below V7"
  fixture family_records 0
  type_at_terminal "no"
  run_rollback "${SHA_D:0:7}"
  check "without family records it gets to the confirmation" out_has "No family record: the code before V7 can run on this database."
  rollback_refused "not confirmed"
}

case_old_tags_removed() {
  setup_case
  local repo c
  local -A sha=([A]=$SHA_A [B]=$SHA_B [C]=$SHA_C)
  for repo in finance-tracker-api finance-tracker-web; do
    for c in A B C; do id_of "$repo-$c" >"$STUB_STATE/images/$repo/${sha[$c]}"; done
  done
  new_target
  deploy_e
  check "exit code 0" rc_is 0
  for repo in finance-tracker-api finance-tracker-web; do
    check "$repo: A's tag removed" no_image "$repo" "$SHA_A"
    check "$repo: B's tag kept" image_is "$repo" "$SHA_B" "$(id_of "$repo-B")"
    check "$repo: C's tag kept" image_is "$repo" "$SHA_C" "$(id_of "$repo-C")"
    check "$repo: D's tag kept" [ -e "$STUB_STATE/images/$repo/$SHA_D" ]
    check "$repo: :previous kept" [ -e "$STUB_STATE/images/$repo/previous" ]
  done
  check "said so" out_has "Removed finance-tracker-api:$SHA_A, older than the last three revisions"
}

CASES=(
  happy_path first_run
  refuse_ci_failed refuse_ci_still_running ci_waits_then_succeeds refuse_ci_none refuse_not_origin_main
  refuse_not_fast_forward refuse_caddy_changed refuse_postgres_changed refuse_postgres_init_changed
  refuse_stage_files_missing refuse_restore_test_fails refuse_tool_missing refuse_without_terminal
  wrong_confirmation
  fail_never_healthy fail_flyway_below fail_switch_line_missing fail_switch_line_wrong fail_numbers_differ
  fail_stage_diff fail_second_restore_test
  repeat_after_failure
  conf_changed conf_unchanged
  lock_held
  script_replaced_mid_run
  secret_never_printed
  finish_records_answers finish_reports_family_difference
  verify_read_only verify_warns_without_switch_line verify_reports_stage_diff
  rollback_refusals rollback_wrong_confirmation rollback_rolls_back rollback_below_v7
  old_tags_removed
)

if [ "${1:-}" = --list ]; then printf '%s\n' "${CASES[@]}"; exit 0; fi
selected=("$@")
[ ${#selected[@]} -gt 0 ] || selected=("${CASES[@]}")

passed=0
failed=()
for CASE in "${selected[@]}"; do
  if ! declare -F "case_$CASE" >/dev/null; then echo "unknown case: $CASE" >&2; exit 2; fi
  if (FAILS=0; "case_$CASE"; [ "$FAILS" -eq 0 ]); then
    echo "ok   $CASE"
    passed=$((passed + 1))
  else
    echo "FAIL $CASE (output: $WORK/cases/$CASE/out)"
    [ "${DEPLOY_TEST_KEEP:-}" = 1 ] || sed 's/^/      | /' "$WORK/cases/$CASE/out" | tail -n 25
    failed+=("$CASE")
  fi
done
echo
echo "$passed passed, ${#failed[@]} failed, of ${#selected[@]} cases"
[ ${#failed[@]} -eq 0 ]
