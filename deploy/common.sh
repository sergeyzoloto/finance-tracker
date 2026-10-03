# shellcheck shell=bash
# Shared by deploy/deploy.sh and deploy/rollback.sh, which source it before they run anything, so a merge or checkout
# that replaces this file while they run changes nothing in them. Only functions and settings, nothing runs here.
#
# The state lives in DEPLOY_STATE_DIR (/var/lib/finance-deploy, mode 700):
#   runs/<UTC time>-<commit>/   one folder per run: log, meta, status, numbers, stage diff, restore tests, summary.txt
#   last-good                   the last good deploy: commit, time, image IDs, how it became good (key=value lines)
#   history                     one line per event: <UTC time> <event> <commit> <api image> <web image>; events are
#                               baseline, good, finish-failed, rolled-back-from, rollback-to, adopted (F6b). A
#                               commit's status is its newest line (F6c); a finish that passed adds good again
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
  PAGES_LINE=
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

# ask PROMPT: one line typed at the terminal ($TTY) after the question, into ANSWER; ANSWER_EOF=1 when the terminal
# closed instead. Never from stdin, so a pipe can't answer it, and (F6c) never from lines that were waiting at the
# terminal before the question: those are discarded first, so that a block pasted ahead (F6b's finish took the next
# block's lines as its answers) is never taken for an answer.
ask() {
  if [ -z "${TTY_FD:-}" ]; then
    exec {TTY_FD}<"$TTY" || fail "can't read $TTY: run this in a terminal on the server"
  fi
  discard_waiting
  printf '%s' "$1"
  ANSWER='' ANSWER_EOF=0
  IFS= read -r ANSWER <&"$TTY_FD" || ANSWER_EOF=1
  printf '\n(typed: %s)\n' "$ANSWER"
}

# discard_waiting: reads and drops every line already waiting at the terminal, and says how many there were.
discard_waiting() {
  local line n=0
  while [ "$n" -lt 10000 ] && IFS= read -r -t 0.2 line <&"$TTY_FD"; do n=$((n + 1)); done
  if [ "$n" -gt 0 ]; then
    say "($n line(s) were waiting at the terminal before this question, pasted ahead: discarded. Type the answer"
    say "after the question.)"
  fi
}

# ask_yes_no PROMPT: asks until the answer typed is exactly yes or no (F6c), into ANSWER; stops when the terminal
# closes without one.
ask_yes_no() {
  while :; do
    ask "$1"
    case $ANSWER in
      yes | no) return 0 ;;
    esac
    [ "$ANSWER_EOF" = 0 ] || fail "no answer of yes or no was typed at $TTY"
    say "Only yes or no counts as an answer; asking again."
  done
}

# psql_ro ARG...: psql as the read-only role finance_checks (F6b; deploy/RUNBOOK.md, "A read-only role for the deploy
# checks"): no superuser, a member of pg_read_all_data and nothing else, with default_transaction_read_only on, so a
# check can't write even if it turns that off. PGOPTIONS asks for read-only transactions too, as before F6b, when the
# checks ran as the app's own login "finance". The api's container environment is never printed.
psql_ro() {
  (cd "$APP_DIR" && docker compose exec -T -e PGOPTIONS='-c default_transaction_read_only=on' postgres \
    psql -X -q -v ON_ERROR_STOP=1 -At -U "$CHECKS_ROLE" -d finance "$@")
}

CHECKS_ROLE=finance_checks

# What the role is, as it reads itself: superuser, member of pg_read_all_data, how many tables of app it may insert
# into, update or delete from (directly or through a role it belongs to), and default_transaction_read_only on it.
ROLE_SQL="SELECT 'superuser=' || r.rolsuper || ' read_all_data=' || pg_has_role(r.oid, 'pg_read_all_data', 'MEMBER')
  || ' writes=' || (SELECT count(*) FROM pg_tables t WHERE t.schemaname = 'app'
                    AND (has_table_privilege(r.oid, format('%I.%I', t.schemaname, t.tablename), 'INSERT')
                         OR has_table_privilege(r.oid, format('%I.%I', t.schemaname, t.tablename), 'UPDATE')
                         OR has_table_privilege(r.oid, format('%I.%I', t.schemaname, t.tablename), 'DELETE')))
  || ' read_only=' || coalesce((SELECT bool_or(s = 'default_transaction_read_only=on')
                                FROM pg_db_role_setting d CROSS JOIN unnest(d.setconfig) AS s
                                WHERE d.setrole = r.oid), false)
FROM pg_roles r WHERE r.rolname = current_user"
ROLE_OK="superuser=false read_all_data=true writes=0 read_only=true"

# The runbook's one-time command, printed for the operator when the role is missing or wrong; nothing here runs it.
role_command() {
  say "The checks run as the read-only role $CHECKS_ROLE, which deploy/RUNBOOK.md, \"A read-only role for the deploy"
  say "checks\", creates once, on the server, as the database's superuser:"
  say "  cd $APP_DIR && docker compose exec -T postgres psql -X -v ON_ERROR_STOP=1 -U postgres -d finance -c \"CREATE ROLE $CHECKS_ROLE LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS; GRANT pg_read_all_data TO $CHECKS_ROLE; GRANT CONNECT ON DATABASE finance TO $CHECKS_ROLE; ALTER ROLE $CHECKS_ROLE SET default_transaction_read_only = on\" </dev/null"
  say "If it exists but is wrong, the runbook says how to drop it first."
}

# role_problem: why the read-only role can't run the checks, or nothing when it can (ROLE_LINE says what it is).
role_problem() {
  local writes
  ROLE_LINE=$(sql_query "$ROLE_SQL" 2>&1) || { printf 'the read-only role %s is missing or cannot log in (%s)' \
    "$CHECKS_ROLE" "$(printf '%s' "$ROLE_LINE" | tail -n 1)"; return 0; }
  [ "$ROLE_LINE" != "$ROLE_OK" ] || return 0
  case $ROLE_LINE in
    superuser=true*) printf '%s is a superuser' "$CHECKS_ROLE" ;;
    *' writes=0 '*) printf '%s is not as the runbook makes it (%s)' "$CHECKS_ROLE" "$ROLE_LINE" ;;
    *' writes='*)
      writes=${ROLE_LINE#* writes=}
      printf '%s may insert, update or delete in %s tables of app (%s)' "$CHECKS_ROLE" "${writes%% *}" "$ROLE_LINE"
      ;;
    *) printf '%s answered something else than its description: %s' "$CHECKS_ROLE" "$ROLE_LINE" ;;
  esac
}

# require_checks_role: stops (fail) unless the read-only role is as the runbook makes it.
require_checks_role() {
  local problem
  problem=$(role_problem)
  if [ -n "$problem" ]; then
    role_command
    fail "$problem"
  fi
  say "The checks run as $CHECKS_ROLE: $ROLE_OK"
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

# git_tree ARG...: a git command that writes the working tree (merge, checkout), under umask 022 in a subshell (F7b):
# what it writes is 644 (folders 755), as an interactive root shell's checkout gives. Under the scripts' umask 077,
# F6b's and F7's merges wrote the changed frontend/public/privacy.html as 600, the web image kept the mode, and nginx
# answered /privacy with 403 (deploy/RUNBOOK.md, "Deployed revisions"). Everything else stays under 077, which keeps the
# state folder, the run folders and the copies of .env to root.
git_tree() { (umask 022 && git "$@"); }

# The pages every deploy checks through the public address, as users reach them, Caddy included (F7b): the path, the
# status, and a text the body must hold (none for the icons). The one list; run, verify, finish and rollback.sh read it.
PAGES=(
  '/|200|<div id="root">'
  '/privacy|200|<h1>Privacy policy</h1>'
  '/privacy.html|200|<h1>Privacy policy</h1>'
  '/favicon.svg|200|'
  '/favicon.ico|200|'
)

# site_host: the site's host, from the first line of deploy/finance.caddy that opens a site block ("app.finance-nl.com
# {"), so that it is written in one place. Data, checked against a host name's shape, only ever an argument of curl.
# sed itself stops at the first one: no pipe into a reader that stops early (head), whose writer would die of SIGPIPE.
site_host() {
  local host
  host=$(sed -nE '/^([a-z0-9][a-z0-9.-]*) \{$/{s//\1/p;q}' "$REPO_DIR/deploy/finance.caddy" 2>/dev/null)
  [[ $host =~ ^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$ ]] || return 1
  printf '%s' "$host"
}

# page_checks: every page of PAGES through https://<site host>/, read only (GET); one line per page, PAGES_LINE for the
# summary; returns 1 when a page isn't as expected (a status, a missing text, no answer). curl writes the whole body to
# a file and grep reads the file: never "curl | grep -q", where grep stops at the text and curl, still writing a large
# page, fails on the closed pipe (exit 23), so that a good page would fail now and then (F7b, CI #46's exit 141).
page_checks() {
  local host entry path status text code body n=0 bad=()
  host=$(site_host) || { PAGES_LINE="not checked: no site host in deploy/finance.caddy"; say "$PAGES_LINE"; return 1; }
  body=$(mktemp)
  say "Through https://$host:"
  for entry in "${PAGES[@]}"; do
    IFS='|' read -r path status text <<<"$entry"
    n=$((n + 1))
    code=$(curl -sS --max-time 15 -o "$body" -w '%{http_code}' "https://$host$path" 2>/dev/null) || true
    code=${code:-000}
    if [ "$code" != "$status" ]; then
      say "  $path: $code, NOT $status"
      bad+=("$path $code")
    elif [ -n "$text" ] && ! grep -qF -- "$text" "$body"; then
      say "  $path: $code, but WITHOUT $text"
      bad+=("$path without its text")
    else
      say "  $path: $code${text:+, with $text}"
    fi
  done
  rm -f "$body"
  if [ ${#bad[@]} -eq 0 ]; then
    PAGES_LINE="$n of $n as expected through https://$host"
    say "Pages: $PAGES_LINE"
    return 0
  fi
  PAGES_LINE="FAILED through https://$host: $(printf '%s, ' "${bad[@]}" | sed 's/, $//')"
  say "Pages: $PAGES_LINE"
  return 1
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

# write_last_good COMMIT API WEB SOURCE [FINISH]: FINISH, "passed <time>" or "not passed <time>", is the latest answer
# of finish for that deploy (F6c); read_last_good ignores it.
write_last_good() {
  local tmp=$STATE_DIR/.last-good.tmp
  printf 'commit=%s\ntime=%s\napi_image=%s\nweb_image=%s\nsource=%s\n' "$1" "$(now)" "$2" "$3" "$4" >"$tmp"
  [ -z "${5:-}" ] || printf 'finish=%s\n' "$5" >>"$tmp"
  mv -f "$tmp" "$STATE_DIR/last-good"
}

# add_history EVENT COMMIT API WEB
add_history() { printf '%s %s %s %s %s\n' "$(now)" "$1" "$2" "$3" "$4" >>"$STATE_DIR/history"; }

# commit_status COMMIT: the commit's status, its newest line in the history (F6c), or nothing when it has none.
commit_status() {
  local _time ev c _rest status=''
  [ -f "$STATE_DIR/history" ] || return 0
  while read -r _time ev c _rest; do
    [ "$c" = "$1" ] && status=$ev
  done <"$STATE_DIR/history"
  printf '%s' "$status"
}

# deploy_run_of COMMIT: the newest run folder of a deploy of COMMIT that went past preflight (status not refused) and
# replaced another commit, into DR_DIR and DR_BASE; returns 1 if none. A repeated run of the same commit records the
# commit that the first one replaced as its base, as deploy.sh's preflight works it out.
deploy_run_of() {
  local dir status commit base
  DR_DIR='' DR_BASE=''
  [ -d "$STATE_DIR/runs" ] || return 1
  while IFS= read -r dir; do
    [ -f "$dir/meta" ] || continue
    status=$(cat "$dir/status" 2>/dev/null || true)
    [ "$status" != refused ] || continue
    [ "$(sed -n 's/^kind=//p' "$dir/meta")" = deploy ] || continue
    commit=$(sed -n 's/^commit=//p' "$dir/meta")
    base=$(sed -n 's/^base=//p' "$dir/meta")
    if [ "$commit" = "$1" ] && valid_commit "$base" && [ "$base" != "$1" ]; then
      DR_DIR=$dir DR_BASE=$base
      return 0
    fi
  done < <(find "$STATE_DIR/runs" -mindepth 1 -maxdepth 1 -type d | sort -r)
  return 1
}

# rollback_target HEAD: one step back (F6c, decided by the PM; replaces OPS-1's rule, which skipped a commit whose
# finish failed): the commit that HEAD's deploy replaced, as that run recorded it, with the images its newest line of
# the history names (not a rolled-back-from line, which names the images rolled back from), into RT_COMMIT, RT_API and
# RT_WEB; returns 1 if there is none. A deploy on top of a commit means the owner accepted it, whatever its finish said.
rollback_target() {
  local head=$1 _time ev c a w
  RT_COMMIT='' RT_API='' RT_WEB=''
  deploy_run_of "$head" || return 1
  git merge-base --is-ancestor "$DR_BASE" "$head" 2>/dev/null || return 1
  [ -f "$STATE_DIR/history" ] || return 1
  while read -r _time ev c a w; do
    if [ "$c" = "$DR_BASE" ] && [ "$ev" != rolled-back-from ] && valid_image "$a" && valid_image "$w"; then
      RT_API=$a RT_WEB=$w
    fi
  done <"$STATE_DIR/history"
  [ -n "$RT_API" ] || return 1
  RT_COMMIT=$DR_BASE
}

# print_rollback_command HEAD: the exact command, for the operator to judge; nothing here runs it.
print_rollback_command() {
  if rollback_target "$1"; then
    say "Nothing was rolled back. If the deploy failed (deploy/RUNBOOK.md, \"Roll back with rollback.sh\"), roll back"
    say "to $(git log -1 --format='%h %s' "$RT_COMMIT"), the commit this deploy replaced, with, on the server, alone in"
    say "its block:"
    say "  cd $REPO_DIR && deploy/rollback.sh $RT_COMMIT"
  else
    say "Nothing was rolled back, and no deploy of $(git rev-parse --short "$1") recorded in $STATE_DIR names a commit"
    say "it replaced with its images: follow deploy/RUNBOOK.md, \"Roll an update back\", by hand."
  fi
}
