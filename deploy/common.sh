# shellcheck shell=bash
# Shared by deploy/deploy.sh and deploy/rollback.sh, which source it before they run anything, so a merge or checkout
# that replaces this file while they run changes nothing in them. Only functions and settings, nothing runs here.
#
# The state lives in DEPLOY_STATE_DIR (/var/lib/finance-deploy, mode 700):
#   runs/<UTC time>-<commit>/   one folder per run: log, meta, status, numbers, stage diff, restore tests, summary.txt
#   last-good                   the last good deploy: commit, time, image IDs, how it became good (key=value lines)
#   history                     one line per event: <UTC time> <event> <commit> <api image> <web image>; events are
#                               baseline, good, finish-failed, rolled-back-from, rollback-to
# Both files are written only by these scripts and parsed, never sourced or run.
# shellcheck disable=SC2034  # the settings are read by the scripts that source this file

common_settings() {
  REPO_DIR=${DEPLOY_REPO_DIR:-/opt/finance-tracker}
  STATE_DIR=${DEPLOY_STATE_DIR:-/var/lib/finance-deploy}
  LOCK_FILE=${DEPLOY_LOCK:-/run/lock/finance-deploy.lock}
  TTY=${DEPLOY_TTY:-/dev/tty}
  BACKUP_DIR=${DEPLOY_BACKUP_DIR:-/var/backups/pg/finance}
  PREVIOUS_FILE=${DEPLOY_PREVIOUS_FILE:-/root/finance-tracker.previous}
  CI_WAIT=${DEPLOY_CI_WAIT:-1200}
  CI_INTERVAL=${DEPLOY_CI_INTERVAL:-60}
  HEALTH_TRIES=${DEPLOY_HEALTH_TRIES:-60}
  HEALTH_INTERVAL=${DEPLOY_HEALTH_INTERVAL:-5}
  APP_DIR=$REPO_DIR/deploy/app
  CONF_TARGET=/etc/pg-backup/finance.conf
  GITHUB_API=https://api.github.com/repos/sergeyzoloto/finance-tracker
  MIGRATIONS=backend/src/main/resources/db/migration
  local v
  for v in CI_WAIT CI_INTERVAL HEALTH_TRIES HEALTH_INTERVAL; do
    [[ ${!v} =~ ^[0-9]+$ ]] || { echo "ERROR: DEPLOY_$v must be a whole number" >&2; exit 2; }
  done
  RUN_DIR=
  TEE_PID=
  STEP=
  REASON=
}

say() { printf '%s\n' "$*"; }
now() { date -u +%Y-%m-%dT%H:%M:%SZ; }

# heading TEXT: the step that runs now, named in a failure.
heading() {
  STEP=$1
  printf '\n== %s\n' "$1"
}

# fail REASON: stops the script. The EXIT trap of the script says what that means at this point.
fail() {
  REASON=$*
  say "ERROR: $*" >&2
  exit 1
}

print_settings() {
  say "Repository $REPO_DIR, state $STATE_DIR, lock $LOCK_FILE, terminal $TTY"
  say "Backups $BACKUP_DIR, previous commit file $PREVIOUS_FILE"
}

# take_lock exclusive|shared: one deploy.sh or rollback.sh at a time; verify takes it shared.
take_lock() {
  local mode=-x
  STEP="the lock"
  [ "$1" = shared ] && mode=-s
  exec {LOCK_FD}>>"$LOCK_FILE"
  flock -n "$mode" "$LOCK_FD" || fail "another deploy.sh or rollback.sh holds $LOCK_FILE; nothing was done"
}

# check_tools TOOL...: every tool present, and Docker's compose plugin.
check_tools() {
  local t missing=()
  for t in "$@"; do command -v "$t" >/dev/null 2>&1 || missing+=("$t"); done
  [ ${#missing[@]} -eq 0 ] || fail "missing on this server: ${missing[*]}"
  docker compose version >/dev/null 2>&1 || fail "docker compose (the Compose plugin) is missing"
  say "Tools present: $* and docker compose"
}

# start_log NAME: a run folder named by the UTC time and NAME, and from now on everything printed also goes to its log.
start_log() {
  local base n=1
  mkdir -p "$STATE_DIR/runs"
  chmod 700 "$STATE_DIR" "$STATE_DIR/runs"
  base=$STATE_DIR/runs/$(date -u +%Y-%m-%dT%H%M%SZ)-$1
  RUN_DIR=$base
  until mkdir "$RUN_DIR" 2>/dev/null; do n=$((n + 1)); RUN_DIR=$base-$n; done
  exec > >(tee -a "$RUN_DIR/log") 2>&1
  TEE_PID=$!
  say "Run folder: $RUN_DIR"
}

# stop_log: the last thing the EXIT trap does, so that the log is complete when the script ends.
stop_log() {
  [ -n "$TEE_PID" ] || return 0
  exec >&- 2>&-
  wait "$TEE_PID" 2>/dev/null || true
  TEE_PID=
}

set_status() { printf '%s\n' "$1" >"$RUN_DIR/status"; }

# ask PROMPT: one line typed at the terminal ($TTY), into ANSWER. Never from stdin, so a pasted block or a pipe can't
# answer it.
ask() {
  if [ -z "${TTY_FD:-}" ]; then
    exec {TTY_FD}<"$TTY" || fail "can't read $TTY: run this in a terminal on the server"
  fi
  printf '%s' "$1"
  ANSWER=
  IFS= read -r ANSWER <&"$TTY_FD" || true
  printf '\n(typed: %s)\n' "$ANSWER"
}

# psql_ro ARG...: psql as the app's login, every statement in a read-only transaction, as pg-restore-test reads
# production (its q_prod). The api's container environment is never printed.
psql_ro() {
  (cd "$APP_DIR" && docker compose exec -T -e PGOPTIONS='-c default_transaction_read_only=on' postgres \
    psql -X -q -v ON_ERROR_STOP=1 -At -U finance -d finance "$@")
}

sql_query() { psql_ro -c "$1" </dev/null; }

# check_sql_file FILE: a check file has no backslash, so psql runs no meta-command from it (no \! shell escape).
check_sql_file() {
  [ -s "$1" ] || fail "$1 is missing or empty"
  if grep -qF -- "\\" "$1"; then fail "$1 holds a backslash; check files may hold SQL only"; fi
}

# sql_file FILE: runs a committed check file, read only (see check_sql_file).
sql_file() {
  check_sql_file "$1"
  psql_ro -f - <"$1"
}

container_image() { docker inspect -f '{{.Image}}' "$1"; }

health_of() { docker inspect -f '{{.State.Health.Status}}' "$1" 2>/dev/null || echo missing; }

# wait_healthy: api and web healthy, HEALTH_TRIES checks HEALTH_INTERVAL seconds apart (the runbook's 60 x 5 s).
wait_healthy() {
  local i api=missing web=missing
  for ((i = 1; i <= HEALTH_TRIES; i++)); do
    api=$(health_of finance-tracker-api)
    web=$(health_of finance-tracker-web)
    [ "$api" = healthy ] && [ "$web" = healthy ] && break
    [ "$i" -lt "$HEALTH_TRIES" ] && sleep "$HEALTH_INTERVAL"
  done
  say "api: $api, web: $web"
  [ "$api" = healthy ] && [ "$web" = healthy ]
}

# highest_migration REV: the highest V<n>__*.sql in the commit.
highest_migration() {
  git ls-tree --name-only "$1" "$MIGRATIONS/" | sed -nE 's|.*/V([0-9]+)__[^/]*\.sql$|\1|p' | sort -n | tail -n 1
}

# flyway_row: the latest row of flyway_schema_history as "version description success".
flyway_row() {
  sql_query "SELECT version || ' ' || description || ' ' || success FROM app.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1"
}

valid_commit() { [[ $1 =~ ^[0-9a-f]{40}$ ]]; }
valid_image() { [[ $1 =~ ^sha256:[0-9a-f]{64}$ ]]; }

# read_last_good: LG_COMMIT, LG_TIME, LG_API, LG_WEB and LG_SOURCE from last-good; returns 1 when there is none.
read_last_good() {
  local line
  LG_COMMIT='' LG_TIME='' LG_API='' LG_WEB='' LG_SOURCE=''
  [ -f "$STATE_DIR/last-good" ] || return 1
  while IFS= read -r line; do
    case $line in
      commit=*) LG_COMMIT=${line#commit=} ;;
      time=*) LG_TIME=${line#time=} ;;
      api_image=*) LG_API=${line#api_image=} ;;
      web_image=*) LG_WEB=${line#web_image=} ;;
      source=*) LG_SOURCE=${line#source=} ;;
    esac
  done <"$STATE_DIR/last-good"
  if ! valid_commit "$LG_COMMIT" || ! valid_image "$LG_API" || ! valid_image "$LG_WEB"; then
    fail "$STATE_DIR/last-good is malformed; look at it before going on"
  fi
}

# write_last_good COMMIT API WEB SOURCE
write_last_good() {
  local tmp=$STATE_DIR/.last-good.tmp
  printf 'commit=%s\ntime=%s\napi_image=%s\nweb_image=%s\nsource=%s\n' "$1" "$(now)" "$2" "$3" "$4" >"$tmp"
  mv -f "$tmp" "$STATE_DIR/last-good"
}

# add_history EVENT COMMIT API WEB
add_history() { printf '%s %s %s %s %s\n' "$(now)" "$1" "$2" "$3" "$4" >>"$STATE_DIR/history"; }

# rollback_target HEAD: the last good deploy before HEAD, into RT_COMMIT, RT_API and RT_WEB; returns 1 if none.
# That is the newest commit of the history whose latest event is good, baseline or rollback-to (not finish-failed or
# rolled-back-from) and which is an ancestor of HEAD other than HEAD itself.
rollback_target() {
  local head=$1 i _time ev c a w
  local -a evs=() cs=() as=() ws=()
  local -A last=()
  RT_COMMIT='' RT_API='' RT_WEB=''
  [ -f "$STATE_DIR/history" ] || return 1
  while read -r _time ev c a w; do
    valid_commit "$c" || continue
    evs+=("$ev") cs+=("$c") as+=("$a") ws+=("$w")
    last[$c]=$ev
  done <"$STATE_DIR/history"
  for ((i = ${#cs[@]} - 1; i >= 0; i--)); do
    case ${evs[i]} in
      good | baseline | rollback-to) ;;
      *) continue ;;
    esac
    case ${last[${cs[i]}]} in
      good | baseline | rollback-to) ;;
      *) continue ;;
    esac
    [ "${cs[i]}" != "$head" ] || continue
    git merge-base --is-ancestor "${cs[i]}" "$head" 2>/dev/null || continue
    if ! valid_image "${as[i]}" || ! valid_image "${ws[i]}"; then continue; fi
    RT_COMMIT=${cs[i]} RT_API=${as[i]} RT_WEB=${ws[i]}
    return 0
  done
  return 1
}

# print_rollback_command HEAD: the exact command, for the operator to judge; nothing here runs it.
print_rollback_command() {
  if rollback_target "$1"; then
    say "Nothing was rolled back. If the deploy failed (deploy/RUNBOOK.md, \"Roll back with rollback.sh\"), roll back"
    say "to the last good deploy $(git log -1 --format='%h %s' "$RT_COMMIT") with, on the server, alone in its block:"
    say "  cd $REPO_DIR && deploy/rollback.sh $RT_COMMIT"
  else
    say "Nothing was rolled back, and no earlier good deploy is recorded in $STATE_DIR/history to roll back to:"
    say "follow deploy/RUNBOOK.md, \"Roll an update back\", by hand."
  fi
}
