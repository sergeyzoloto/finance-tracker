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
  rm -rf "$C"   # a case may set up twice
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
  echo "$ROLE_OK" >"$s/fixtures/role"

  for name in docker systemctl pg-restore-test curl install; do ln -s "$HERE/stubs/$name" "$C/bin/$name"; done

  export STUB_STATE=$s PATH=$C/bin:$BASE_PATH
  export DEPLOY_REPO_DIR=$C/server DEPLOY_STATE_DIR=$C/state DEPLOY_LOCK=$C/lock DEPLOY_TTY=$C/tty
  export DEPLOY_BACKUP_DIR=$C/backups DEPLOY_PREVIOUS_FILE=$C/previous
  export DEPLOY_CI_WAIT=2 DEPLOY_CI_INTERVAL=1 DEPLOY_HEALTH_TRIES=3 DEPLOY_HEALTH_INTERVAL=0
  # The terminal (F6c): a FIFO that terminal_feeder writes into, as the operator would type and paste.
  mkfifo "$C/tty"
  : >"$C/typed"
  : >"$C/pasted"
  cp "$C/state/last-good" "$C/last-good.orig"
  cp "$C/state/history" "$C/history.orig"
}
BASE_PATH=$PATH
# The read-only role as the runbook makes it, as it reads itself (deploy/common.sh, ROLE_SQL).
ROLE_OK="superuser=false read_all_data=true writes=0 read_only=true"

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
        privacy) mkdir -p frontend/public && echo '<h1>Privacy policy</h1>' >frontend/public/privacy.html ;;
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

# run_script SCRIPT ARG...: runs a script in the server's clone, as the operator does, while terminal_feeder plays the
# terminal; its output in $C/out, its exit code in RC.
run_script() {
  local feeder
  rm -f "$C/done"
  : >"$C/out"
  terminal_feeder &
  feeder=$!
  (cd "$C/server" && "$@") >"$C/out" 2>&1 </dev/null
  RC=$?
  : >"$C/done"
  wait "$feeder" 2>/dev/null
  cat "$C/out" >>"$C/all-out"
}
run_deploy() { run_script ./deploy/deploy.sh "$@"; }
run_rollback() { run_script ./deploy/rollback.sh "$@"; }

# The terminal (F6c): the lines of type_at_terminal answer the questions one by one, each written only once its
# question is in the output, as an operator types; the lines of paste_ahead are written at once, before any question,
# as a block pasted while the script runs. After the last answer, the terminal closes at the next question.
type_at_terminal() { if [ $# -gt 0 ]; then printf '%s\n' "$@" >"$C/typed"; else : >"$C/typed"; fi; }
paste_ahead() { if [ $# -gt 0 ]; then printf '%s\n' "$@" >"$C/pasted"; else : >"$C/pasted"; fi; }
questions() { grep -oE 'Type (the first 7|yes or no|ADOPT|ROLLBACK|SWITCH)' "$C/out" 2>/dev/null | wc -l; }
wait_for_question() {
  local i
  for ((i = 0; i < 600; i++)); do
    [ "$(questions)" -ge "$1" ] && return 0
    [ -e "$C/done" ] && return 1
    sleep 0.05
  done
  return 1
}
terminal_feeder() {
  local answer n=0
  exec 3<>"$C/tty"
  if [ -s "$C/pasted" ]; then cat "$C/pasted" >&3; fi
  while IFS= read -r answer; do
    n=$((n + 1))
    wait_for_question "$n" || break
    printf '%s\n' "$answer" >&3
  done <"$C/typed"
  wait_for_question $((n + 1)) || true
  exec 3>&-
}
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
sha_of() { local s; s=$(sha256sum "$1"); printf '%s' "${s%% *}"; }
meta_of() { sed -n "s/^$2=//p" "$1/meta"; }
mode_is() { [ "$(stat -c %a "$1")" = "$2" ] || { echo "      $1 is $(stat -c %a "$1"), not $2"; return 1; }; }

# in_order REGEX...: the calls match in this order.
in_order() {
  local pattern n last=0
  for pattern in "$@"; do
    n=$(grep -nE -- "$pattern" "$STUB_STATE/calls" | awk -F: -v after="$last" '!n && $1 > after { n = $1 } END { if (n) print n }')
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
in_order_file() { local f=$1; shift; local p n last=0; for p in "$@"; do n=$(grep -nF -- "$p" "$f" | awk -F: -v a="$last" '!n && $1 > a { n = $1 } END { if (n) print n }'); [ -n "$n" ] || return 1; last=$n; done; }

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
  check "it waited" [ "$(count_calls '^curl .*/check-runs')" -ge 2 ]
}

case_ci_waits_then_succeeds() {
  setup_case
  new_target
  cp "$HERE/fixtures/check-runs-in-progress.json" "$STUB_STATE/fixtures/ci.1"
  deploy_e
  check "exit code 0" rc_is 0
  check "waited once" out_has "CI still runs; checking again"
  check "two reads of the check runs" [ "$(count_calls '^curl .*/check-runs')" -eq 2 ]
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
  type_at_terminal
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
  check "the failed images take no tag" out_has "are not the last good deploy's content: a failed run's"
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
  check "the third run's summary says the failed run's images took no tag" file_has "$(latest_run)/summary.txt" "were not the last good deploy's content and took no tag"
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
  check "history: good E as its newest line" bash -c "tail -n 1 '$C/state/history' | grep -q ' good $SHA_E '"
  check "last-good: finish passed" file_has "$C/state/last-good" "finish=passed 20"
  # F6c: finish may run again for that run, and the latest answers count, in the summary, last-good and history.
  type_at_terminal yes no
  run_deploy finish
  check "again: exit code 3" rc_is 3
  check "again: a clear not passed" out_has "NOT PASSED: the smoke test. Recorded as finish-failed"
  check "again: not an unexpected error" out_lacks "unexpected error"
  check "again: the summary has the new answer, once" bash -c "grep -c '^Smoke test: ' '$summary' | grep -qx 1 && grep -q '^Smoke test: NOT passed (answered \"no\")' '$summary' && grep -c '^Family numbers after' '$summary' | grep -qx 1"
  check "again: status finish-failed" status_is finish-failed
  check "again: history's newest line finish-failed E" bash -c "tail -n 1 '$C/state/history' | grep -q ' finish-failed $SHA_E '"
  check "again: last-good still E, finish not passed" bash -c "grep -qx 'commit=$SHA_E' '$C/state/last-good' && grep -q '^finish=not passed' '$C/state/last-good'"
  type_at_terminal yes yes
  run_deploy finish
  check "a third time: passed" rc_is 0
  check "a third time: good E newest" bash -c "tail -n 1 '$C/state/history' | grep -q ' good $SHA_E '"
  check "a third time: status finished" status_is finished
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
  check "its own exit code, 3" rc_is 3
  check "not an unexpected error" out_lacks "unexpected error"
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
  check "no state-changing call" calls_lack "$STATE_CHANGING|^systemctl|^pg-restore-test|^curl .*api\.github\.com"
  check "only reads: inspect, logs, read-only psql, version" calls_lack '^docker (compose (build|up|config|ps)|exec|tag|rmi)'
  check "the pages read (F7b), each once, by GET" [ "$(count_calls '^curl -sS --max-time 15 -o .* -w %\{http_code\} https://app\.finance-nl\.com/')" -eq 5 ]
  check "the pages reported OK" out_has "5 of 5 as expected through https://app.finance-nl.com"
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

# The read-only role (F6b): preflight refuses without it, with the runbook's one-time command, before CI or a backup;
# verify reports it and skips the database's checks.
case_refuse_role_missing() {
  setup_case
  new_target
  fixture role missing
  deploy_e
  refused "the read-only role finance_checks is missing or cannot log in"
  check "the one-time command printed" out_has "GRANT pg_read_all_data TO finance_checks"
  check "before CI and the backup" calls_lack '^curl |^systemctl|^pg-restore-test'
  check "no other query" [ "$(count_calls 'psql')" -eq 1 ]
  run_deploy verify OPS-1
  check "verify: exit code 1" rc_is 1
  check "verify names it" out_has "PROBLEM: the read-only role finance_checks is missing"
  check "verify prints the command" out_has "CREATE ROLE finance_checks LOGIN NOSUPERUSER"
  check "verify: one problem" out_has "verify OPS-1: 1 problem(s)"
  check "verify reads nothing else of the database" [ "$(count_calls 'psql')" -eq 2 ]
  type_at_terminal "ROLLBACK ${SHA_C:0:7}"
  run_rollback "${SHA_C:0:7}"
  check "rollback.sh refuses too" out_has "the read-only role finance_checks is missing"
  check "and names the manual rollback (F6c)" out_has 'without it, roll back by hand: deploy/RUNBOOK.md, "Roll an update back"'
  nothing_changed
}

case_refuse_role_superuser() {
  setup_case
  new_target
  fixture role "superuser=true read_all_data=true writes=19 read_only=false"
  deploy_e
  refused "finance_checks is a superuser"
  check "the one-time command printed" out_has "ALTER ROLE finance_checks SET default_transaction_read_only = on"
}

case_refuse_role_may_write() {
  setup_case
  new_target
  fixture role "superuser=false read_all_data=true writes=3 read_only=true"
  deploy_e
  refused "finance_checks may insert, update or delete in 3 tables of app"
  fixture role "superuser=false read_all_data=true writes=0 read_only=false"
  deploy_e
  refused "finance_checks is not as the runbook makes it"
}

# adopt (F6b): a run stopped by numbers that users' activity changed, judged harmless; a wrong confirmation changes
# nothing; the right one records E with its running images, and touches no git, image or container.
case_adopt_after_a_harmless_stop() {
  setup_case
  new_target
  numbers 0 | sed 's/^users=2$/users=3/' >"$STUB_STATE/fixtures/numbers.2"
  deploy_e
  check "the run stopped after the merge" out_has "the numbers differ from preflight's"
  cp "$C/state/last-good" "$C/last-good.orig"
  cp "$C/state/history" "$C/history.orig"
  : >"$STUB_STATE/calls"
  local running
  running=$(cat "$STUB_STATE/containers/finance-tracker-api/image")

  type_at_terminal "ADOPT ${SHA_D:0:7}"
  run_deploy adopt
  check "exit code not 0" rc_not
  check "says not confirmed" out_has 'REFUSED at "2. Confirmation": not confirmed'
  check "shows the commit" out_has "HEAD: ${SHA_E:0:7} revision E"
  check "shows the running images" out_has "Running images: api $running"
  check "shows the last run's status" out_has "-${SHA_E:0:7}, failed"
  check "last-good unchanged" same_file "$C/state/last-good" "$C/last-good.orig"
  check "history unchanged" same_file "$C/state/history" "$C/history.orig"

  type_at_terminal "ADOPT ${SHA_E:0:7}"
  run_deploy adopt
  check "exit code 0" rc_is 0
  check "last-good is E" last_good_is "$SHA_E"
  check "with E's running api image" file_has "$C/state/last-good" "api_image=$running"
  check "recorded as adopted" file_has "$C/state/last-good" "source=adopt"
  check "history: adopted E" file_has "$C/state/history" " adopted $SHA_E $running "
  check "HEAD still E" head_is "$SHA_E"
  check "no git, image or container change" calls_lack "$STATE_CHANGING|^docker compose (build|up)"
  check "no database query" calls_lack 'psql'
  check "its run folder says adopted" grep -qx adopted "$(find "$C/state/runs" -name "*-adopt-${SHA_E:0:7}*" | sort -V | tail -n 1)/status"
  run_deploy finish
  check "finish has nothing to finish after it" out_has "nothing to finish"
  # A rollback from E goes to D, the last good deploy before it.
  type_at_terminal no
  run_rollback "${SHA_D:0:7}"
  check "rollback.sh offers D" out_has "Target: ${SHA_D:0:7} revision D"
  # And the next deploy keeps E's images as the last good deploy's.
  (cd "$C/work" && echo F >README && git commit -qam "revision F" && git push -q origin main)
  local f
  f=$(git -C "$C/work" rev-parse HEAD)
  type_at_terminal "${f:0:7}"
  run_deploy run "$f" OPS-1
  check "the next run deployed" rc_is 0
  check "E's images kept under E's commit" image_is finance-tracker-api "$SHA_E" "$running"
}

case_adopt_nothing_to_adopt() {
  setup_case
  type_at_terminal "ADOPT ${SHA_D:0:7}"
  run_deploy adopt
  check "exit code not 0" rc_not
  check "says nothing to adopt" out_has "nothing to adopt: HEAD ${SHA_D:0:7} with the running images is the last good deploy already"
  check "not asked" out_lacks "Type ADOPT"
  check "last-good unchanged" same_file "$C/state/last-good" "$C/last-good.orig"
  check "history unchanged" same_file "$C/state/history" "$C/history.orig"
}

# ---------------------------------------------------------------------------------------------------------------------
# switch (F7)

# switch_ready: E deployed and finished, so HEAD is the last good deploy, healthy, with the latest run finished, as
# deploy/deploy.sh switch's preflight wants. deploy/app/.env has no FAMILY_LEDGERS_ENABLED line yet, as production's
# does today.
switch_ready() {
  setup_case
  new_target
  deploy_e
  [ "$RC" -eq 0 ] || { echo "    setup: the deploy of E failed"; cat "$C/out"; FAILS=$((FAILS + 1)); }
  type_at_terminal yes yes
  run_deploy finish
  [ "$RC" -eq 0 ] || { echo "    setup: finish failed"; cat "$C/out"; FAILS=$((FAILS + 1)); }
  cp "$C/server/deploy/app/.env" "$C/env.orig"
  : >"$STUB_STATE/calls"
  cp "$C/state/last-good" "$C/last-good.orig"
  cp "$C/state/history" "$C/history.orig"
}

case_switch_on_and_off() {
  switch_ready
  type_at_terminal "SWITCH ON"
  run_deploy switch on
  check "on: exit code 0" rc_is 0
  check "says switch on" out_has "Switch on on "
  check "current shown before, absent" out_has "Current: FAMILY_LEDGERS_ENABLED is absent, so off"
  check "will become shown" out_has "Will become: FAMILY_LEDGERS_ENABLED=true"
  check ".env now has it true" file_has "$C/server/deploy/app/.env" "FAMILY_LEDGERS_ENABLED=true"
  check "only that line added, nothing else in .env changed" bash -c \
    "diff <(grep -v '^FAMILY_LEDGERS_ENABLED=' '$C/env.orig') <(grep -v '^FAMILY_LEDGERS_ENABLED=' '$C/server/deploy/app/.env')"
  check "a backup copy was made, mode 600" calls_have '^install -m 600 .*/deploy/app/\.env .*/deploy/app/\.env\.[0-9]{8}T[0-9]{6}Z$'
  check "api alone restarted" calls_have '^docker compose up -d --no-deps api$'
  check "web not restarted too" calls_lack '^docker compose up -d --no-deps api web$'
  check "D-25 logged as on" out_has '"Family ledgers (D-25): on" at'
  check "the D-25 check compares against what was written" out_has "as production now sets it (FAMILY_LEDGERS_ENABLED=true, so on)"
  check "the numbers are read before and after" [ "$(count_calls 'psql.*-f -')" -eq 2 ]
  check "no backup or restore test (the database doesn't change)" calls_lack '^systemctl|^pg-restore-test'
  check "history: switch-on E" file_has "$C/state/history" " switch-on $SHA_E "
  check "last-good unchanged: same commit and images" same_file "$C/state/last-good" "$C/last-good.orig"
  local summary
  summary=$(latest_run)/summary.txt
  check "summary names the change" file_has "$summary" "FAMILY_LEDGERS_ENABLED: absent -> FAMILY_LEDGERS_ENABLED=true"
  check "summary without a pipe character" bash -c "! grep -q '|' '$summary'"
  check "status switch-on" status_is switch-on

  : >"$STUB_STATE/calls"
  # A second apart, so this run's folder (…-switch-off) sorts after the first's (…-switch-on) even within the same
  # wall-clock second: start_log names folders by whole seconds, and "off" < "on" byte for byte.
  sleep 1
  type_at_terminal "SWITCH OFF"
  run_deploy switch off
  check "off: exit code 0" rc_is 0
  check "current shown before, true" out_has "Current: FAMILY_LEDGERS_ENABLED is FAMILY_LEDGERS_ENABLED=true, so on"
  check ".env now false" file_has "$C/server/deploy/app/.env" "FAMILY_LEDGERS_ENABLED=false"
  check "D-25 logged as off" out_has '"Family ledgers (D-25): off; the family endpoints answer 404" at'
  check "history: switch-off E" file_has "$C/state/history" " switch-off $SHA_E "
  check "status switch-off" status_is switch-off

  type_at_terminal "SWITCH OFF"
  run_deploy switch off
  check "refuses: already off" rc_not
  check "says already off, nothing to switch" out_has "FAMILY_LEDGERS_ENABLED is already false: nothing to switch"
  check "still no restart" [ "$(count_calls '^docker compose up')" -eq 1 ]
}

case_switch_wrong_confirmation() {
  switch_ready
  type_at_terminal "switch on"
  run_deploy switch on
  check "exit code not 0" rc_not
  check "says REFUSED" out_has "REFUSED at"
  check "says not confirmed" out_has "not confirmed"
  check "says nothing changed" out_has "Nothing changed: .env untouched, api not restarted."
  check "the line never appears" bash -c "! grep -q '^FAMILY_LEDGERS_ENABLED=' '$C/server/deploy/app/.env'"
  check "no backup copy" calls_lack '^install '
  check "no restart" calls_lack '^docker compose up'
  check "last-good unchanged" same_file "$C/state/last-good" "$C/last-good.orig"
  check "history unchanged" same_file "$C/state/history" "$C/history.orig"
}

case_switch_secret_never_printed() {
  switch_ready
  type_at_terminal "SWITCH ON"
  run_deploy switch on
  check "exit code 0" rc_is 0
  check "the secret is still in .env" file_has "$C/server/deploy/app/.env" "$SECRET"
  check "the secret is in no output" bash -c "! grep -qF -- '$SECRET' '$C/all-out'"
  check "the secret is nowhere in the state folder" bash -c "! grep -rqF -- '$SECRET' '$C/state'"
}

case_switch_failure_prints_the_way_back() {
  switch_ready
  fixture health-api starting
  type_at_terminal "SWITCH ON"
  run_deploy switch on
  check "exit code not 0" rc_not
  check "says FAILED at health" out_has 'FAILED at "3.3 Health"'
  check ".env was already changed before the failure" file_has "$C/server/deploy/app/.env" "FAMILY_LEDGERS_ENABLED=true"
  check "names the way back, the opposite direction" out_has "deploy/deploy.sh switch off"
  check "never switches back on its own" out_has "Never switch back on its own"
  check "api restarted exactly once, not reverted" [ "$(count_calls '^docker compose up -d --no-deps api$')" -eq 1 ]
  check "status failed" status_is failed
}

# F6c's second run named HEAD in its confirmation while an older revision, the last good deploy after a rollback and a
# plain "git checkout main", was running (deploy/RUNBOOK.md's F6c deploy record): the confirmation must name that
# running revision (BASE), not HEAD.
case_run_names_the_running_revision() {
  setup_case
  new_target
  sed 's/^family_records 0$/family_records 1/' "$ROOT/deploy/checks/OPS-1.expected" >"$STUB_STATE/fixtures/stage"
  deploy_e
  check "first run failed after the merge" out_has 'FAILED at "4.4 Postflight: the stage'\''s checks (OPS-1)"'
  : >"$STUB_STATE/calls"
  type_at_terminal "ROLLBACK ${SHA_D:0:7}"
  run_rollback "${SHA_D:0:7}"
  check "rolled back to D" rc_is 0
  (cd "$C/server" && git checkout -q main)
  check "HEAD back on main, at E (local main was fast-forwarded by the first run's merge)" head_is "$SHA_E"
  type_at_terminal "${SHA_E:0:7}"
  run_deploy run "$SHA_E" OPS-1
  check "confirmation names D, the running revision" out_has "stage OPS-1, over ${SHA_D:0:7}."
  check "not HEAD" out_lacks "stage OPS-1, over ${SHA_E:0:7}."
}

# F6b's deploy runs OPS-1's deploy.sh, which bash has read before the merge, as the server has it: its first run,
# recording the baseline, as the app's login. Then F6b's finish and verify, from the merged clone, read the run folder,
# last-good and history that OPS-1's script wrote, and run their checks as the read-only role.
case_finish_on_an_ops1_run() {
  setup_case
  rm "$C/state/last-good" "$C/state/history"
  new_target
  type_at_terminal "${SHA_E:0:7}"
  export STUB_ALLOW_FINANCE=1
  run_script "$HERE/fixtures/ops-1/deploy.sh" run "$SHA_E" OPS-1
  unset STUB_ALLOW_FINANCE
  check "OPS-1's run deployed E" rc_is 0
  check "OPS-1's run said it was the first" out_has "No record of a last good deploy: deploy.sh's first run"
  check "OPS-1's run queried as finance" calls_have ' -U finance -d finance'
  check "OPS-1's run asked for no role" calls_lack 'pg_read_all_data'
  check "the clone has F6b's scripts now" file_has "$C/server/deploy/common.sh" "CHECKS_ROLE=finance_checks"
  check "history: OPS-1's baseline and good" in_order_file "$C/state/history" " baseline $SHA_D " " good $SHA_E "
  : >"$STUB_STATE/calls"

  type_at_terminal yes yes
  run_deploy finish
  check "F6b's finish: exit code 0" rc_is 0
  check "it checked the role" out_has "The checks run as finance_checks"
  check "finished OPS-1's run" status_is finished
  check "the family lines as before" file_has "$(latest_run)/summary.txt" "Family numbers after the smoke test: the family lines as before the deploy"
  check "OPS-1's summary lines kept" file_has "$(latest_run)/summary.txt" "Previous commit: ${SHA_D:0:7} (revision D), HEAD with its images at deploy.sh's first run"
  check "every query as finance_checks" calls_lack ' -U finance -d finance'
  run_deploy verify OPS-1
  check "F6b's verify: OK" out_has "verify OPS-1: OK"
  type_at_terminal no
  run_rollback "${SHA_D:0:7}"
  check "F6b's rollback.sh reads OPS-1's history: D before E" out_has "Target: ${SHA_D:0:7} revision D"
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
  rollback_refused "only one step back, to the commit HEAD's deploy replaced, ${SHA_D:0:7} revision D, not ${SHA_E:0:7}"
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
  check "no psql but the role's check and the read-only Flyway row" \
    [ "$(count_calls 'psql')" -eq "$(($(count_calls 'psql .*flyway_schema_history') + $(count_calls 'psql .*pg_read_all_data')))" ]
  check "last-good is D again" last_good_is "$SHA_D"
  check "history: rolled back from E to D" in_order_file "$C/state/history" " rolled-back-from $SHA_E " " rollback-to $SHA_D "
  check "says how to go forward" out_has "git checkout main, then deploy/deploy.sh run"
  check "the pages checked after the restart (F7b)" in_order '^docker compose up -d --no-deps api web$' '^curl .*https://app\.finance-nl\.com/privacy$'
  check "the pages in the summary" file_has "$(latest_run)/summary.txt" "Pages: 5 of 5 as expected through https://app.finance-nl.com"
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
  check "names the dump preserved before the deploy (D-43)" out_has "the dump taken before HEAD's deploy is preserved as $C/state/runs/"
  check "with its SHA-256" out_has "(SHA-256 $(meta_of "$(find "$C/state/runs" -mindepth 1 -maxdepth 1 -name "*-${SHA_E:0:7}" | sort | tail -n 1)" before_dump_sha256))"
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

# ---------------------------------------------------------------------------------------------------------------------
# F6c: answers typed after their question, finish again, a commit's status by its newest line, the rollback target one
# step back, and production's state after F6b's deploy.

# Lines pasted ahead are never answers: the confirmation and finish's questions discard them, and take what is typed.
case_pasted_ahead_is_discarded() {
  setup_case
  new_target
  paste_ahead "${SHA_E:0:7}"
  type_at_terminal
  run_deploy run "$SHA_E" OPS-1
  refused "not confirmed"
  check "says what it discarded" out_has "1 line(s) were waiting at the terminal before this question, pasted ahead: discarded"
  paste_ahead "${SHA_E:0:7}" "# On the server" "cd /opt/finance-tracker && deploy/deploy.sh finish"
  type_at_terminal "${SHA_E:0:7}"
  run_deploy run "$SHA_E" OPS-1
  check "typed after the question: deployed" rc_is 0
  check "three lines discarded" out_has "3 line(s) were waiting at the terminal"
  paste_ahead yes yes
  type_at_terminal no no
  run_deploy finish
  check "finish took the typed answers" rc_is 3
  check "browser checks: the typed no" file_has "$(latest_run)/summary.txt" 'Browser checks: NOT passed (answered "no")'
  check "smoke test: the typed no" file_has "$(latest_run)/summary.txt" 'Smoke test: NOT passed (answered "no")'
}

# A yes or no question asks again until yes or no is typed; a terminal that closes first records nothing.
case_yes_no_asks_again() {
  setup_case
  new_target
  deploy_e
  cp "$C/state/history" "$C/history.deployed"
  type_at_terminal y
  run_deploy finish
  check "no answer: stopped" rc_is 1
  check "says so" out_has "no answer of yes or no was typed"
  check "asked again first" out_has "Only yes or no counts as an answer; asking again."
  check "nothing recorded" status_is deployed
  check "history unchanged" same_file "$C/state/history" "$C/history.deployed"
  type_at_terminal y maybe yes YES yes
  run_deploy finish
  check "then passed" rc_is 0
  check "asked again three times" [ "$(grep -c '^Only yes or no counts as an answer; asking again\.$' "$C/out")" -eq 3 ]
  check "both recorded as passed" bash -c "grep -q '^Browser checks: passed' '$(latest_run)/summary.txt' && grep -q '^Smoke test: passed' '$(latest_run)/summary.txt'"
}

# finish works on the latest deployed run: refused runs and refused adopts after it don't count. F6b's finish didn't.
case_finish_skips_refused_runs() {
  setup_case
  new_target
  deploy_e
  local deployed
  deployed=$(latest_run)
  type_at_terminal wrong
  run_deploy run "$SHA_E" OPS-1
  check "a refused run after it" status_is refused
  run_deploy adopt
  check "a refused adopt after it" out_has "nothing to adopt"
  run_script "$HERE/fixtures/f6b/deploy.sh" finish
  check "F6b's finish stopped at the refused adopt" out_has "not deployed: nothing to finish"
  type_at_terminal yes yes
  run_deploy finish
  check "F6c's finish: exit code 0" rc_is 0
  check "it finished the deployed run" bash -c "grep -qx finished '$deployed/status'"
  check "said which" out_has "Finish of $deployed"
}

# A commit's status is its newest line in the history: adopt takes HEAD when that is finish-failed, and has nothing to
# adopt when it is good, adopted or baseline, whatever came before.
case_newest_line_decides_status() {
  setup_case
  printf '2026-10-01T17:00:00Z finish-failed %s %s %s\n' "$SHA_D" "$API_D" "$WEB_D" >>"$C/state/history"
  type_at_terminal "ADOPT ${SHA_D:0:7}"
  run_deploy adopt
  check "finish-failed newest: adopted" rc_is 0
  check "showed the status" out_has "HEAD's status, its newest line in the history: finish-failed"
  check "history: adopted newest" bash -c "tail -n 1 '$C/state/history' | grep -q ' adopted $SHA_D '"
  run_deploy adopt
  check "adopted newest: nothing to adopt" out_has "nothing to adopt: HEAD ${SHA_D:0:7} with the running images is the last good deploy already (adopted)"
  printf '2026-10-01T18:00:00Z finish-failed %s %s %s\n2026-10-01T19:00:00Z good %s %s %s\n' "$SHA_D" "$API_D" "$WEB_D" \
    "$SHA_D" "$API_D" "$WEB_D" >>"$C/state/history"
  run_deploy adopt
  check "good after finish-failed: nothing to adopt" out_has "last good deploy already (good)"
  new_target
  type_at_terminal no
  run_deploy run "$SHA_E" OPS-1
  check "step 1.2 shows the last good deploy's status" out_has "Its status, its newest line in the history: good"
}

# Step 1.3: a commit the clone doesn't have after git fetch is to be pushed from the laptop first.
case_step_1_3_says_push_first() {
  setup_case
  (cd "$C/work" && echo F >README && git commit -qam "revision F, not pushed")
  local unpushed
  unpushed=$(git -C "$C/work" rev-parse HEAD)
  type_at_terminal "${unpushed:0:7}"
  run_deploy run "$unpushed" OPS-1
  refused "is no commit of this clone after git fetch: push it from the laptop first"
}

PROD_C=46dedcdd519bedcb8c77bf82106d479881cde867
PROD_D=7a60020264a1b448fad865270bb602789a142054

# production_state: production's state after F6b's deploy (fixtures/production-2026-10-02), with 46dedcd as C and
# 7a60020 as D: its last-good, history and run folders; D's images running, as latest; C's under C's tag and
# :previous, as F6b's run kept them.
production_state() {
  local f dir
  rm -rf "$C/state"
  mkdir "$C/state"
  chmod 700 "$C/state"
  cp -a "$HERE/fixtures/production-2026-10-02/." "$C/state/"
  rm "$C/state/README"
  for dir in "$C"/state/runs/*7a60020*; do mv "$dir" "${dir//7a60020/${SHA_D:0:7}}"; done
  find "$C/state" -type f -exec sed -i "s/$PROD_C/$SHA_C/g; s/$PROD_D/$SHA_D/g" {} +
  read -r _ _ _ PAPI_C PWEB_C < <(grep ' baseline ' "$C/state/history")
  read -r _ _ _ PAPI_D PWEB_D < <(grep ' good ' "$C/state/history")
  for f in api web; do
    local c=$PAPI_C d=$PAPI_D
    [ $f = web ] && c=$PWEB_C d=$PWEB_D
    echo "$d" >"$STUB_STATE/containers/finance-tracker-$f/image"
    echo "$d" >"$STUB_STATE/images/finance-tracker-$f/latest"
    echo "$c" >"$STUB_STATE/images/finance-tracker-$f/$SHA_C"
    echo "$c" >"$STUB_STATE/images/finance-tracker-$f/previous"
  done
  cp "$C/state/last-good" "$C/last-good.orig"
  cp "$C/state/history" "$C/history.orig"
}

# F6c's run executes F6b's deploy.sh on production's state. What it says at step 1.2, whether step 3.2 keeps D's
# images under D's tag before the build, and which rollback command it prints after a failure: C's, which F6c's
# rollback.sh refuses, since the deploy replaced D; F6c's rollback.sh goes back to D, with D's images.
case_f6b_run_on_production_state() {
  setup_case
  production_state
  new_target
  sed 's/^family_records 0$/family_records 1/' "$ROOT/deploy/checks/OPS-1.expected" >"$STUB_STATE/fixtures/stage"
  type_at_terminal "${SHA_E:0:7}"
  run_script "$HERE/fixtures/f6b/deploy.sh" run "$SHA_E" OPS-1
  check "F6b's run failed after the merge" out_has "FAILED at \"4.4 Postflight: the stage's checks (OPS-1)\""
  check "step 1.2: the last good deploy D, by its record" out_has "Last good deploy: ${SHA_D:0:7} revision D at 2026-10-02T11:07:47Z (deploy)"
  check "step 3.2: D's images kept under D's tag" out_has "The running images, the last good deploy's, kept as :$SHA_D and :previous"
  check "D's api image under D's tag" image_is finance-tracker-api "$SHA_D" "$PAPI_D"
  check "D's web image under D's tag" image_is finance-tracker-web "$SHA_D" "$PWEB_D"
  check "D's images as :previous" image_is finance-tracker-api previous "$PAPI_D"
  check "kept before the build" in_order "^docker tag $PAPI_D finance-tracker-api:$SHA_D$" '^docker compose build api$'
  check "F6b's script prints the rollback to C" out_has "deploy/rollback.sh $SHA_C"
  check "and not to D" out_lacks "deploy/rollback.sh $SHA_D"
  cp "$C/out" "$C/out-f6b-run"
  cp "$C/state/history" "$C/history.orig"
  cp "$C/state/last-good" "$C/last-good.orig"
  : >"$STUB_STATE/calls"

  type_at_terminal "ROLLBACK ${SHA_C:0:7}"
  run_rollback "${SHA_C:0:7}"
  rollback_refused "only one step back, to the commit HEAD's deploy replaced, ${SHA_D:0:7} revision D, not ${SHA_C:0:7}"
  type_at_terminal "ROLLBACK ${SHA_D:0:7}"
  run_rollback "${SHA_D:0:7}"
  check "F6c's rollback.sh to D: exit code 0" rc_is 0
  check "its target D, the commit E's deploy replaced" out_has "Target: ${SHA_D:0:7} revision D, the commit HEAD's deploy replaced"
  check "D's status shown" out_has "its newest line in the history: finish-failed"
  check "HEAD detached at D" head_is "$SHA_D"
  check "D's images run" image_is finance-tracker-api latest "$PAPI_D"
}

# F6c's scripts on production's state as it is: adopt takes 7a60020, whose newest line is finish-failed; finish skips
# the refused adopt, finds F6b's run and may answer it again.
case_f6c_scripts_on_production_state() {
  setup_case
  production_state
  type_at_terminal "ADOPT ${SHA_D:0:7}"
  run_deploy adopt
  check "adopt takes D: exit code 0" rc_is 0
  check "after finish-failed" out_has "HEAD's status, its newest line in the history: finish-failed"
  check "history: adopted D newest" bash -c "tail -n 1 '$C/state/history' | grep -q ' adopted $SHA_D $PAPI_D $PWEB_D'"
  check "last-good D, adopted" bash -c "grep -qx 'commit=$SHA_D' '$C/state/last-good' && grep -qx 'source=adopt' '$C/state/last-good'"

  production_state
  type_at_terminal yes yes
  run_deploy finish
  check "finish answers F6b's run again: exit code 0" rc_is 0
  check "it is F6b's run" out_has "Finish of $C/state/runs/2026-10-02T110252Z-${SHA_D:0:7}"
  check "said its finish ran before" out_has "Its finish ran before (finish-failed)"
  check "the run finished" bash -c "grep -qx finished '$C/state/runs/2026-10-02T110252Z-${SHA_D:0:7}/status'"
  check "history: good D newest" bash -c "tail -n 1 '$C/state/history' | grep -q ' good $SHA_D '"
  check "its summary: passed, once" bash -c "grep -c '^Browser checks: ' '$C/state/runs/2026-10-02T110252Z-${SHA_D:0:7}/summary.txt' | grep -qx 1 && grep -q '^Browser checks: passed' '$C/state/runs/2026-10-02T110252Z-${SHA_D:0:7}/summary.txt'"
}

# F6c's run by F6b's deploy.sh, then F6c's finish: it finishes that run; the rollback target is D.
case_finish_after_the_f6c_run() {
  setup_case
  production_state
  new_target
  type_at_terminal "${SHA_E:0:7}"
  run_script "$HERE/fixtures/f6b/deploy.sh" run "$SHA_E" OPS-1
  check "F6b's run deployed E" rc_is 0
  type_at_terminal yes yes
  run_deploy finish
  check "F6c's finish: exit code 0" rc_is 0
  check "finished E's run" status_is finished
  check "history: good E newest" bash -c "tail -n 1 '$C/state/history' | grep -q ' good $SHA_E '"
  check "last-good E, finish passed" bash -c "grep -qx 'commit=$SHA_E' '$C/state/last-good' && grep -q '^finish=passed' '$C/state/last-good'"
  type_at_terminal no
  run_rollback "${SHA_D:0:7}"
  check "the rollback target is D" out_has "Target: ${SHA_D:0:7} revision D, the commit HEAD's deploy replaced"
  check "not confirmed: nothing rolled back" head_is "$SHA_E"
}

# ---------------------------------------------------------------------------------------------------------------------
# F7b: the pages through the public address, git under umask 022, switch's text.

# A page that answers 403 after the deploy's health: FAILED at the page step, with the guidance and the way back;
# verify is not OK. Only /privacy fails, as after F7's deploy.
case_run_fails_on_a_page() {
  setup_case
  new_target
  fixture pages "/privacy 403"
  deploy_e
  failed_after_merge "a page isn't as expected"
  check "at the page step, after health" out_has 'FAILED at "3.6 Deploy: the pages"'
  check "the pages read after the start" in_order '^docker compose up -d --no-deps api web$' '^curl .*https://app\.finance-nl\.com/$'
  check "names /privacy and its status" out_has "/privacy: 403, NOT 200"
  check "the others as expected" out_has "/privacy.html: 200, with <h1>Privacy policy</h1>"
  check "the guidance" out_has "The site may still work, but this deploy isn't good: don't run deploy.sh finish."
  check "the summary names the page" file_has "$(latest_run)/summary.txt" "Pages: FAILED through https://app.finance-nl.com: /privacy 403"
  run_deploy verify OPS-1
  check "verify: exit code 1" rc_is 1
  check "verify: a problem" out_has "PROBLEM: a page isn't as expected"
  check "verify: the images too, not the last good deploy's (OPS-2)" out_has "PROBLEM: api isn't the last good deploy's content"
  check "verify: three problems, api, web and the page" out_has "verify OPS-1: 3 problem(s)"
}

# finish checks the pages before its questions: a page that fails records the browser checks as failed, without
# asking, so there is no way to answer yes; once the page is fixed, finish again passes.
case_finish_records_failed_pages_without_asking() {
  setup_case
  new_target
  deploy_e
  check "deployed" rc_is 0
  fixture pages "/privacy 403"
  type_at_terminal yes yes
  run_deploy finish
  check "exit code 3" rc_is 3
  check "the pages before the questions" out_has "Pages: FAILED through https://app.finance-nl.com: /privacy 403"
  check "never asks about the browser checks" out_lacks "Did the browser checks pass?"
  check "says why" out_has "The page checks failed: the browser checks are recorded as NOT passed, without asking."
  check "asks the smoke test" out_has "Did the smoke test pass?"
  local summary
  summary=$(latest_run)/summary.txt
  check "recorded as not passed, without asking" grep -qE '^Browser checks: NOT passed \(the page checks failed: FAILED through https://app\.finance-nl\.com: /privacy 403\), recorded at 20[0-9-]{8}T[0-9:]{8}Z without asking$' "$summary"
  check "the smoke test's answer" grep -q '^Smoke test: passed' "$summary"
  check "the pages at finish" file_has "$summary" "Pages at finish: FAILED"
  check "not passed" out_has "NOT PASSED: the browser checks."
  check "status finish-failed" status_is finish-failed
  check "history: finish-failed E newest" bash -c "tail -n 1 '$C/state/history' | grep -q ' finish-failed $SHA_E '"
  rm "$STUB_STATE/fixtures/pages"
  type_at_terminal yes yes
  run_deploy finish
  check "fixed: passed" rc_is 0
  check "fixed: asked again" out_has "Did the browser checks pass?"
  check "fixed: one line each" bash -c "grep -c '^Browser checks: ' '$summary' | grep -qx 1 && grep -c '^Pages at finish: ' '$summary' | grep -qx 1"
  check "fixed: the pages at finish" file_has "$summary" "Pages at finish: 5 of 5 as expected"
  check "fixed: status finished" status_is finished
}

# The merge writes the files E changes as 644 and its new folders as 755, under umask 022, while the run folder stays
# under 077; the rollback's checkout too.
case_merged_files_land_644() {
  setup_case
  new_target privacy
  deploy_e
  check "deployed" rc_is 0
  check "README, changed by E: 644" mode_is "$C/server/README" 644
  check "a new file of E: 644" mode_is "$C/server/frontend/public/privacy.html" 644
  check "its new folders: 755" mode_is "$C/server/frontend/public" 755
  check "the run folder: 700" mode_is "$(latest_run)" 700
  check "the run's summary: 600" mode_is "$(latest_run)/summary.txt" 600
  check "last-good, written by the run: 600" mode_is "$C/state/last-good" 600
  type_at_terminal "ROLLBACK ${SHA_D:0:7}"
  run_rollback "${SHA_D:0:7}"
  check "rolled back" rc_is 0
  check "README, rewritten by the rollback's checkout: 644" mode_is "$C/server/README" 644
  check "the rollback's summary: 600" mode_is "$(latest_run)/summary.txt" 600
}

# The rollback reports a page that fails after its restart, and is done all the same: the commit gone back to may hold
# the fault (F7's web image).
case_rollback_reports_a_failing_page() {
  rollback_setup
  fixture pages "/privacy 403"
  type_at_terminal "ROLLBACK ${SHA_D:0:7}"
  run_rollback "${SHA_D:0:7}"
  check "rolled back: exit code 0" rc_is 0
  check "a warning" out_has "WARNING: a page isn't as expected after the rollback: FAILED through https://app.finance-nl.com: /privacy 403"
  check "the summary" file_has "$(latest_run)/summary.txt" "Pages: FAILED through https://app.finance-nl.com: /privacy 403"
  check "status rolled-back" status_is rolled-back
}

# switch's confirmation: 200 for on (F7's said "202"), 404 for off; step 1.6 prints the numbers, as run's 1.7 does.
case_switch_says_200_and_prints_the_numbers() {
  switch_ready
  type_at_terminal "SWITCH ON"
  run_deploy switch on
  check "on: exit code 0" rc_is 0
  check "on: 200" out_has "The family endpoints will answer 200 to their members once it is up."
  check "never 202" out_lacks "answer 202"
  check "the numbers printed before the confirmation" in_order_file "$C/out" "== 1.6 Preflight: the numbers before" \
    "accounts=22" "family_records=0" "users=2" "== 2. Confirmation"
  sleep 1
  type_at_terminal "SWITCH OFF"
  run_deploy switch off
  check "off: exit code 0" rc_is 0
  check "off: 404" out_has "The family endpoints will answer 404 once it is up."
  check "off: the numbers printed" in_order_file "$C/out" "== 1.6 Preflight: the numbers before" "users=2" "== 2. Confirmation"
}

# ---------------------------------------------------------------------------------------------------------------------
# F7b, its fourth commit: switch's preflight checks the pages (D-41, extended), and the pages are large.

# The pages the curl stub serves are over 1 MB, with the text near the start: page_checks finds every text in them.
case_large_pages_pass() {
  setup_case
  new_target
  deploy_e
  check "deployed" rc_is 0
  check "the stub's pages are over 1 MB" bash -c "[ \$(stat -c %s '$STUB_STATE/page-filler') -gt 1048576 ]"
  check "every page with its text" out_has "5 of 5 as expected through https://app.finance-nl.com"
  run_deploy verify OPS-1
  check "verify: the pages too" out_has "Pages: 5 of 5 as expected through https://app.finance-nl.com"
}

# switch on refuses while a page fails, before its question, with nothing changed (D-41).
case_switch_on_refuses_on_a_failed_page() {
  switch_ready
  fixture pages "/privacy 403"
  type_at_terminal "SWITCH ON"
  run_deploy switch on
  check "exit code not 0" rc_not
  check "REFUSED at the pages" out_has 'REFUSED at "1.7 Preflight: the pages"'
  check "names the page and D-41" out_has "a page isn't as expected (FAILED through https://app.finance-nl.com: /privacy 403): family budgets aren't switched on while a page of the list fails (D-41)"
  check "says nothing changed" out_has "Nothing changed: .env untouched, api not restarted."
  check "never asked" out_lacks "Type SWITCH ON"
  check ".env unchanged" same_file "$C/server/deploy/app/.env" "$C/env.orig"
  check "no backup copy" calls_lack '^install '
  check "no restart" calls_lack '^docker compose up'
  check "last-good unchanged" same_file "$C/state/last-good" "$C/last-good.orig"
  check "history unchanged" same_file "$C/state/history" "$C/history.orig"
  check "status refused" status_is refused
}

# switch off reports a failing page and goes on: the way back must always work.
case_switch_off_goes_on_despite_a_failed_page() {
  switch_ready
  type_at_terminal "SWITCH ON"
  run_deploy switch on
  check "on: exit code 0" rc_is 0
  check "on: the pages checked before the confirmation" in_order_file "$C/out" "== 1.7 Preflight: the pages" \
    "Pages: 5 of 5 as expected through https://app.finance-nl.com" "== 2. Confirmation"
  check "on: the pages in the summary" file_has "$(latest_run)/summary.txt" "Pages before the switch: 5 of 5 as expected"
  sleep 1
  fixture pages "/privacy 403"
  : >"$STUB_STATE/calls"
  type_at_terminal "SWITCH OFF"
  run_deploy switch off
  check "off: exit code 0" rc_is 0
  check "off: the page reported" out_has "/privacy: 403, NOT 200"
  check "off: a warning" out_has "WARNING: a page isn't as expected; switching off goes on all the same"
  check "off: asked" out_has "Type SWITCH OFF"
  check "off: .env false" file_has "$C/server/deploy/app/.env" "FAMILY_LEDGERS_ENABLED=false"
  check "off: api restarted" calls_have '^docker compose up -d --no-deps api$'
  check "off: the pages in the summary" file_has "$(latest_run)/summary.txt" "Pages before the switch: FAILED through https://app.finance-nl.com: /privacy 403"
  check "off: status switch-off" status_is switch-off
}

# ---------------------------------------------------------------------------------------------------------------------
# OPS-2, defect 3 (D-43): the before-dump preserved in the run folder; the after-dump never replaces it.

# pg-backup's names have minute precision: both dumps in the same minute. The run waits for the next minute before the
# after-dump, the names differ, and the copy in the run folder is the before-dump, as recorded.
case_dumps_in_the_same_minute() {
  setup_case
  new_target
  fixture dump-minute-names yes
  # Early in a minute, so that the run reaches its after-dump within it.
  while [ "$((10#$(date -u +%S)))" -gt 30 ]; do sleep 1; done
  deploy_e
  check "deployed" rc_is 0
  check "waited for the next minute" out_has "Waiting for the next minute: a dump written now would be named finance-"
  local run before copy sum
  run=$(latest_run)
  before=$(meta_of "$run" before_dump)
  copy=$(meta_of "$run" before_dump_copy)
  sum=$(meta_of "$run" before_dump_sha256)
  check "a name of minute precision" bash -c "[[ '$before' =~ ^finance-[0-9-]{10}T[0-9]{4}Z\.dump$ ]]"
  check "two dumps in the backups" [ "$(find "$C/backups" -name 'finance-*.dump' | wc -l)" -eq 2 ]
  check "the before-dump still there, as recorded" [ "$(sha_of "$C/backups/$before")" = "$sum" ]
  check "the copy in the run folder" [ "$copy" = "$run/$before" ]
  check "the copy as recorded" [ "$(sha_of "$copy")" = "$sum" ]
  check "the copy's size recorded" [ "$(meta_of "$run" before_dump_size)" = "$(stat -c %s "$copy")" ]
  check "the copy 600" mode_is "$copy" 600
  check "a copy, not a hard link" [ "$(stat -c %i "$copy")" != "$(stat -c %i "$C/backups/$before")" ]
  check "the summary: the before-dump and its copy" file_has "$run/summary.txt" "Before: dump $before at "
  check "the summary: its SHA-256 and restore test" file_has "$run/summary.txt" "SHA-256 $sum; restore test PASS"
  check "the summary: the copy's path" file_has "$run/summary.txt" "preserved as $copy (mode 600, "
  check "the summary: the after-dump with its SHA-256" grep -qE '^After: dump finance-.* bytes, SHA-256 [0-9a-f]{64}; restore test PASS' "$run/summary.txt"
}

# A backup that rewrites the before-dump's file in place: the deploy stops after the after-dump, and the copy is the
# before-dump still.
case_dump_overwritten_in_place() {
  setup_case
  new_target
  fixture dump-overwrite yes
  deploy_e
  failed_after_merge "the after-dump has the before-dump's name"
  local run before
  run=$(latest_run)
  before=$(meta_of "$run" before_dump)
  check "names the copy" out_has "the before-dump is preserved as $run/$before"
  check "the file in the backups was rewritten" [ "$(sha_of "$C/backups/$before")" != "$(meta_of "$run" before_dump_sha256)" ]
  check "the copy is the before-dump still" [ "$(sha_of "$run/$before")" = "$(meta_of "$run" before_dump_sha256)" ]
}

# The copy itself changed after it was made: the deploy stops, and says so.
case_preserved_dump_changed() {
  setup_case
  new_target
  cat >"$STUB_STATE/fixtures/on-restore-test.2" <<'EOF'
#!/usr/bin/env bash
for f in "$DEPLOY_STATE_DIR"/runs/*/finance-*.dump; do printf 'x' >>"$f"; done
EOF
  chmod +x "$STUB_STATE/fixtures/on-restore-test.2"
  deploy_e
  failed_after_merge "the preserved before-dump $(latest_run)/"
  check "says it changed" out_has "changed: SHA-256"
}

# ---------------------------------------------------------------------------------------------------------------------
# OPS-2, defect 4: images by content, which the image ID doesn't tell in the containerd image store.

# use_containerd: the containerd image store (deploy/tests/stubs/docker), with the content identities of D's images.
use_containerd() {
  echo containerd >"$STUB_STATE/store"
  mkdir -p "$STUB_STATE/content"
  id_of api-D-content >"$STUB_STATE/content/${API_D#sha256:}"
  id_of web-D-content >"$STUB_STATE/content/${WEB_D#sha256:}"
}

# rebuild_same_content SERVICE: :latest built again from the same content, under a new ID, as an empty run does; the ID
# it replaces is gone from the store unless another tag names it.
rebuild_same_content() {
  local f=$STUB_STATE/images/finance-tracker-$1/latest id
  id=$(id_of "$1-rebuilt-$RANDOM-$RANDOM")
  cp "$STUB_STATE/content/$(sed 's/^sha256://' "$f")" "$STUB_STATE/content/${id#sha256:}"
  echo "$id" >"$f"
  REBUILT=$id
}

# other_content SERVICE: the container runs other content than recorded, as after a failed build.
other_content() {
  local id
  id=$(id_of "$1-other-$RANDOM-$RANDOM")
  id_of "$1-other-content-$RANDOM" >"$STUB_STATE/content/${id#sha256:}"
  echo "$id" >"$STUB_STATE/images/finance-tracker-$1/latest"
  echo "$id" >"$STUB_STATE/containers/finance-tracker-$1/image"
}

# containerd_ready: as switch_ready, in the containerd store: E deployed and finished.
containerd_ready() {
  setup_case
  use_containerd
  new_target
  deploy_e
  [ "$RC" -eq 0 ] || { echo "    setup: the deploy of E failed"; cat "$C/out"; FAILS=$((FAILS + 1)); }
  type_at_terminal yes yes
  run_deploy finish
  [ "$RC" -eq 0 ] || { echo "    setup: finish failed"; cat "$C/out"; FAILS=$((FAILS + 1)); }
  E_API=$(cat "$STUB_STATE/containers/finance-tracker-api/image")
  E_API_CONTENT=$(cat "$STUB_STATE/content/${E_API#sha256:}")
  cp "$C/state/last-good" "$C/last-good.orig"
  cp "$C/state/history" "$C/history.orig"
  : >"$STUB_STATE/calls"
}

# deploy_f: a commit F on top of E, deployed, typing its first 7 characters.
deploy_f() {
  (cd "$C/work" && echo F >README && git commit -qam "revision F" && git push -q origin main)
  SHA_F=$(git -C "$C/work" rev-parse HEAD)
  type_at_terminal "${SHA_F:0:7}"
  run_deploy run "$SHA_F" OPS-1
}

# As on 2026-10-03: :latest rebuilt with the same content under a new ID (the ID before it gone), then switch on
# recreates api from it. The switch says so, not "unchanged", and last-good names the new ID, so that verify and the
# next run's step 1.2 match.
case_same_content_new_id() {
  containerd_ready
  check "the finish recorded the content" file_has "$C/state/last-good" "api_content=$E_API_CONTENT"
  rebuild_same_content api
  type_at_terminal "SWITCH ON"
  run_deploy switch on
  check "switch on: exit code 0" rc_is 0
  check "api recreated from the new ID" [ "$(cat "$STUB_STATE/containers/finance-tracker-api/image")" = "$REBUILT" ]
  check "prints api before and after" out_has "api recreated with the same content: $E_API -> $REBUILT, content $E_API_CONTENT"
  check "web unchanged, by content" out_has "web unchanged, image "
  check "never 'api unchanged'" out_lacks "api unchanged"
  check "last-good names the new ID" file_has "$C/state/last-good" "api_image=$REBUILT"
  check "with the same content" file_has "$C/state/last-good" "api_content=$E_API_CONTENT"
  check "and its finish kept" file_has "$C/state/last-good" "finish=passed"
  check "the summary says so" file_has "$(latest_run)/summary.txt" "(last-good updated to the new IDs)"
  run_deploy verify OPS-1
  check "verify: OK" out_has "verify OPS-1: OK"
  check "verify: api is the recorded image" out_has "api: the recorded image, image $REBUILT (content $E_API_CONTENT)"
  sleep 1
  sed -i 's/Family ledgers (D-25): off; the family endpoints answer 404/Family ledgers (D-25): on/' "$STUB_STATE/fixtures/start-log"
  deploy_f
  check "the next run deployed" rc_is 0
  check "its 1.2 matches" out_has "The running images are the last good deploy's content"
  check "E's api kept under E's tag" image_is finance-tracker-api "$SHA_E" "$REBUILT"
  check "the previous file names E" file_has "$C/previous" "$SHA_E"
}

# As before 2026-10-04's run: last-good names one ID, the container runs another with the same content, and the
# recorded ID is gone. Step 1.2 says INFO, step 3.2 keeps the last good deploy's content under E's tag and :previous
# (4510003's script took them for a failed run's); a rollback to E goes by content.
case_last_good_id_differs_same_content() {
  containerd_ready
  rebuild_same_content api
  echo "$REBUILT" >"$STUB_STATE/containers/finance-tracker-api/image"
  deploy_f
  check "deployed" rc_is 0
  check "1.2: INFO, not a mismatch" out_has "INFO api: another image ID with the recorded content: running $REBUILT, recorded $E_API, content $E_API_CONTENT"
  check "1.2: the last good deploy's content" out_has "The running images are the last good deploy's content"
  check "no false verdict" out_lacks "a failed run's"
  check "E's api kept under E's tag, by content" image_is finance-tracker-api "$SHA_E" "$REBUILT"
  check "and as :previous" image_is finance-tracker-api previous "$REBUILT"
  check "the previous file names E" file_has "$C/previous" "$SHA_E"
  check "no warning about E's tag" out_lacks "finance-tracker-api:$SHA_E is missing or not the recorded content"
  : >"$STUB_STATE/calls"
  type_at_terminal "ROLLBACK ${SHA_E:0:7}"
  run_rollback "${SHA_E:0:7}"
  check "rollback to E: exit code 0" rc_is 0
  check "by content, with INFO" out_has "INFO finance-tracker-api:$SHA_E is $REBUILT, another ID with the recorded content $E_API_CONTENT (recorded $E_API)"
  check "E's api runs" image_is finance-tracker-api latest "$REBUILT"
}

# Other content than recorded: step 1.2 says so and 3.2 tags nothing; switch on refuses before changing anything,
# switch off warns and goes on; verify reports a problem.
case_different_content() {
  containerd_ready
  other_content api
  local running
  running=$(cat "$STUB_STATE/containers/finance-tracker-api/image")
  run_deploy verify OPS-1
  check "verify: a problem" out_has "PROBLEM: api isn't the last good deploy's content, or its content is unknown"
  type_at_terminal "SWITCH OFF"
  echo 'FAMILY_LEDGERS_ENABLED=true' >>"$C/server/deploy/app/.env"
  run_deploy switch off
  check "switch off goes on" rc_is 0
  check "with a warning" out_has "WARNING: the running images are not the last good deploy's content, or it is unknown; switching off goes on all the same"
  sleep 1
  cp "$C/server/deploy/app/.env" "$C/env.orig"
  : >"$STUB_STATE/calls"
  type_at_terminal "SWITCH ON"
  run_deploy switch on
  check "switch on refused" out_has 'REFUSED at "1.4 Preflight: the clone, the last good deploy, no unfinished run": the running images are not the last good deploy'"'"'s content'
  check "names api" out_has "api: NOT the recorded content: running image $running"
  check ".env unchanged" same_file "$C/server/deploy/app/.env" "$C/env.orig"
  check "no restart" calls_lack '^docker compose up'
  sleep 1
  deploy_f
  check "the run: 1.2 says so" out_has "The running images are not the last good deploy's content"
  check "the run: 3.2 tags none" out_has "They take no tag"
  check "the run: no tag of E" no_image finance-tracker-api "$SHA_E"
}

# A content identity that can't be read: run refuses at 1.2 with nothing changed; switch on refuses; switch off goes
# on and never says "unchanged"; verify reports it.
case_identity_unavailable() {
  setup_case
  echo broken >"$STUB_STATE/store"
  new_target
  deploy_e
  refused "the running containers' content identity can't be read (image store unknown"
  check "before CI and the backup" calls_lack '^curl .*check-runs|^systemctl|^pg-restore-test'
  run_deploy verify OPS-1
  check "verify: a problem" out_has "PROBLEM: api isn't the last good deploy's content, or its content is unknown"

  containerd_ready
  touch "$STUB_STATE/containers/finance-tracker-api/no-descriptor"
  echo 'FAMILY_LEDGERS_ENABLED=true' >>"$C/server/deploy/app/.env"
  type_at_terminal "SWITCH OFF"
  run_deploy switch off
  check "switch off goes on" rc_is 0
  check "says unknown after the restart" file_has "$(latest_run)/summary.txt" "content unknown"
  check "never 'api unchanged'" bash -c "! grep -q 'api unchanged' '$(latest_run)/summary.txt'"
  sleep 1
  type_at_terminal "SWITCH ON"
  run_deploy switch on
  check "switch on refused" out_has "REFUSED at"
  check "api's content unknown" out_has "api: the content identity is unknown"
  check "never asked" out_lacks "Type SWITCH ON"
}

# last-good as 4510003's scripts write it, without content identities: worked out from the image while it exists, or
# from the container that runs it; verify writes nothing; once neither holds it, it is unknown and 3.2 tags nothing.
case_old_format_state() {
  setup_case
  use_containerd
  check "the case's last-good has no content line" bash -c "! grep -q _content '$C/state/last-good'"
  local before after
  before=$(cd "$C" && find state -exec stat -c '%n %s %Y' {} + | sort)
  run_deploy verify OPS-1
  after=$(cd "$C" && find state -exec stat -c '%n %s %Y' {} + | sort)
  check "verify: from the image" out_has "api: the recorded image, image $API_D (content $(id_of api-D-content))"
  check "verify: OK" out_has "verify OPS-1: OK"
  check "verify writes nothing" [ "$before" = "$after" ]
  rebuild_same_content api
  new_target
  deploy_e
  check "deployed" rc_is 0
  check "1.2: from the container, the image gone" out_has "api: the recorded image, image $API_D (content $(id_of api-D-content))"
  check "the contents file has it" file_has "$C/state/contents" "$API_D $(id_of api-D-content)"
  check "D's api kept under D's tag, from :latest" image_is finance-tracker-api "$SHA_D" "$REBUILT"
  check "last-good now has content lines" file_has "$C/state/last-good" "api_content="

  setup_case
  use_containerd
  rebuild_same_content api
  echo "$REBUILT" >"$STUB_STATE/containers/finance-tracker-api/image"
  new_target
  deploy_e
  check "neither image nor container holds it: unknown" out_has "api: the content identity is unknown (running image $REBUILT"
  check "3.2 tags none" out_has "They take no tag"
}

# ---------------------------------------------------------------------------------------------------------------------
# OPS-2, defect 1: a refused switch never blocks what comes next; only real unfinished work does.

# Refused at the confirmation twice in a row (on 2026-10-03 the answer was the next pasted block): each next switch
# reaches the confirmation again, and the third goes through.
case_switch_refused_at_the_confirmation() {
  switch_ready
  type_at_terminal "no"
  run_deploy switch on
  check "first: refused at the confirmation" out_has 'REFUSED at "2. Confirmation": not confirmed'
  check "first: status refused" status_is refused
  sleep 1
  type_at_terminal "# On the server"
  run_deploy switch on
  check "second: reached the confirmation again" out_has "Type SWITCH ON"
  check "second: refused there, not at 1.4" out_has 'REFUSED at "2. Confirmation": not confirmed'
  check "second: says the refused run doesn't count" out_lacks "is refused, not finished"
  sleep 1
  type_at_terminal "SWITCH ON"
  run_deploy switch on
  check "third: switched on" rc_is 0
  check ".env true" file_has "$C/server/deploy/app/.env" "FAMILY_LEDGERS_ENABLED=true"
}

# Production's state on 2026-10-04 (J): a switch on refused at its preflight, already on. switch off works, and so do
# finish, verify and run.
case_switch_refused_as_already_on() {
  switch_ready
  type_at_terminal "SWITCH ON"
  run_deploy switch on
  check "on" rc_is 0
  sleep 1
  run_deploy switch on
  check "J: refused at 1.5" out_has 'REFUSED at "1.5 Preflight: the current switch": FAMILY_LEDGERS_ENABLED is already true: nothing to switch'
  check "J: status refused" status_is refused
  sleep 1
  type_at_terminal "SWITCH OFF"
  run_deploy switch off
  check "switch off after it: exit code 0" rc_is 0
  check "switch off: no warning about the refused run" out_lacks "WARNING: the switch"
  check ".env false" file_has "$C/server/deploy/app/.env" "FAMILY_LEDGERS_ENABLED=false"
  run_deploy verify OPS-1
  check "verify: OK" out_has "verify OPS-1: OK"
}

# Refused at a pre-check (a page): the next switch, once the page is fixed, reaches the confirmation and goes through.
case_switch_refused_at_a_precheck() {
  switch_ready
  fixture pages "/privacy 403"
  run_deploy switch on
  check "refused at the pages" out_has 'REFUSED at "1.7 Preflight: the pages"'
  rm "$STUB_STATE/fixtures/pages"
  sleep 1
  type_at_terminal "SWITCH ON"
  run_deploy switch on
  check "then switched on" rc_is 0
}

# A switch on that changed .env and failed at health: switch on refuses and names switch off; switch off goes on.
case_switch_interrupted_after_the_change() {
  switch_ready
  fixture health-api starting
  type_at_terminal "SWITCH ON"
  run_deploy switch on
  check "failed after the change" out_has 'FAILED at "3.3 Health"'
  check "names switch off" out_has "deploy/deploy.sh switch off"
  sleep 1
  run_deploy switch on
  check "switch on: refused" out_has "REFUSED at \"1.3 Preflight: health\""
  fixture health-api healthy
  echo healthy >"$STUB_STATE/containers/finance-tracker-api/health"
  sleep 1
  run_deploy switch on
  check "switch on, healthy: refused at 1.4" out_has 'REFUSED at "1.4 Preflight: the clone, the last good deploy, no unfinished run"'
  check "names the interrupted switch and switch off" out_has "changed .env or restarted api and did not complete (failed): run deploy/deploy.sh switch off"
  check "never asked" out_lacks "Type SWITCH ON"
  echo starting >"$STUB_STATE/containers/finance-tracker-api/health"
  sleep 1
  type_at_terminal "SWITCH OFF"
  run_deploy switch off
  check "switch off goes on" rc_is 0
  check "warns about health" out_has "WARNING: api and web not both healthy; switching off goes on all the same"
  check "warns about the interrupted switch" out_has "WARNING: the switch"
  check ".env false" file_has "$C/server/deploy/app/.env" "FAMILY_LEDGERS_ENABLED=false"
  check "status switch-off" status_is switch-off
}

# A switch off that changed .env but whose restart failed: .env says false, the api still runs with true. switch off
# again restarts it, rather than "nothing to switch", and the way back it prints is switch off.
case_switch_off_after_a_failed_restart() {
  switch_ready
  type_at_terminal "SWITCH ON"
  run_deploy switch on
  check "on" rc_is 0
  sleep 1
  fixture up-fails yes
  type_at_terminal "SWITCH OFF"
  run_deploy switch off
  check "failed at the restart" out_has 'FAILED at "3.2 Restart api"'
  check "the way back is switch off again" out_has "run it again (no stop refuses it):"
  check "not switch on" out_lacks "deploy/deploy.sh switch on"
  check ".env already false" file_has "$C/server/deploy/app/.env" "FAMILY_LEDGERS_ENABLED=false"
  rm "$STUB_STATE/fixtures/up-fails"
  sleep 1
  type_at_terminal "SWITCH OFF"
  run_deploy switch off
  check "switch off again: exit code 0" rc_is 0
  check "restarts the api that still runs with true" out_has ".env already says FAMILY_LEDGERS_ENABLED=false, but the api runs with FAMILY_LEDGERS_ENABLED=true, so on: this restarts it"
  check "D-25 off" out_has '"Family ledgers (D-25): off; the family endpoints answer 404" at'
}

# switch on after a deploy without its finish: refused, naming finish; switch off goes on with a warning.
case_switch_after_an_unfinished_run() {
  setup_case
  new_target
  deploy_e
  check "deployed" rc_is 0
  run_deploy switch on
  check "switch on refused" out_has 'REFUSED at "1.4 Preflight: the clone, the last good deploy, no unfinished run"'
  check "names finish" out_has "has no finish yet: do its browser checks and smoke test, then run deploy/deploy.sh finish"
  sleep 1
  echo 'FAMILY_LEDGERS_ENABLED=true' >>"$C/server/deploy/app/.env"
  type_at_terminal "SWITCH OFF"
  run_deploy switch off
  check "switch off goes on" rc_is 0
  check "with a warning naming finish" out_has "WARNING: the deploy"
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
  refuse_role_missing refuse_role_superuser refuse_role_may_write
  adopt_after_a_harmless_stop adopt_nothing_to_adopt
  switch_on_and_off switch_wrong_confirmation switch_secret_never_printed switch_failure_prints_the_way_back
  run_names_the_running_revision
  finish_on_an_ops1_run
  rollback_refusals rollback_wrong_confirmation rollback_rolls_back rollback_below_v7
  old_tags_removed
  pasted_ahead_is_discarded yes_no_asks_again finish_skips_refused_runs newest_line_decides_status
  step_1_3_says_push_first
  f6b_run_on_production_state f6c_scripts_on_production_state finish_after_the_f6c_run
  run_fails_on_a_page finish_records_failed_pages_without_asking merged_files_land_644
  rollback_reports_a_failing_page switch_says_200_and_prints_the_numbers
  large_pages_pass switch_on_refuses_on_a_failed_page switch_off_goes_on_despite_a_failed_page
  dumps_in_the_same_minute dump_overwritten_in_place preserved_dump_changed
  same_content_new_id last_good_id_differs_same_content different_content identity_unavailable old_format_state
  switch_refused_at_the_confirmation switch_refused_as_already_on switch_refused_at_a_precheck
  switch_interrupted_after_the_change switch_off_after_a_failed_restart switch_after_an_unfinished_run
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
