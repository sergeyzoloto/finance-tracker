#!/usr/bin/env bash
# Deploys Finance Tracker on the production server: the steps the F5 and F6a checklists did by hand, in their order
# (deploy/RUNBOOK.md, "Deploying with deploy.sh"). Run as root, on the server, from /opt/finance-tracker:
#
#   deploy/deploy.sh run <commit> <stage>   preflight (changes nothing but a fresh backup), one confirmation typed at
#                                           the terminal, the deploy, postflight; prints a summary
#   deploy/deploy.sh finish                 after the browser checks and the smoke test: asks how they went, compares
#                                           the family numbers, writes the final summary
#   deploy/deploy.sh verify <stage>         read only, any time: health, Flyway, the stage's checks, the D-25 line, the
#                                           numbers; no backup, no change
#
# It never rolls back by itself: a failure after the merge prints the command of deploy/rollback.sh, for the operator.
# Everything it prints also goes to the run's folder under /var/lib/finance-deploy/runs (deploy/common.sh).
#
# The script is functions only, and its last line calls main: bash has read all of it, and deploy/common.sh, before
# anything runs, so the merge, which replaces both files, changes nothing in a run (deploy/tests/run.sh, case 8).
#
# For the tests only (deploy/tests/run.sh), these variables move the paths and shorten the waits: DEPLOY_REPO_DIR,
# DEPLOY_STATE_DIR, DEPLOY_LOCK, DEPLOY_TTY, DEPLOY_BACKUP_DIR, DEPLOY_PREVIOUS_FILE, DEPLOY_CI_WAIT,
# DEPLOY_CI_INTERVAL, DEPLOY_HEALTH_TRIES and DEPLOY_HEALTH_INTERVAL. Every run prints the paths it uses.

# shellcheck source=deploy/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

usage() {
  cat >&2 <<'EOF'
usage: deploy/deploy.sh run <commit> <stage>
       deploy/deploy.sh finish
       deploy/deploy.sh verify <stage>
<commit> is origin/main's commit, 7 to 40 hex characters; <stage> names deploy/checks/<stage>.sql and .expected.
EOF
  exit 2
}

TOOLS=(git docker flock curl python3 systemctl pg-restore-test install diff paste)

# The GitHub API's check runs of a commit, read by python3 (the server has no jq). Prints one line per check run, then
# "NAMES <names>" and "VERDICT success|pending|failure|none".
CI_PY='
import json, sys
d = json.load(sys.stdin)
runs = d.get("check_runs") or []
for r in runs:
    print("  %s: %s, %s" % (r.get("name"), r.get("status"), r.get("conclusion")))
waiting = ("queued", "in_progress", "waiting", "requested", "pending")
if not runs or d.get("total_count", len(runs)) != len(runs):
    verdict = "none" if not runs else "failure"
elif any(r.get("status") == "completed" and r.get("conclusion") != "success" for r in runs):
    verdict = "failure"
elif any(r.get("status") in waiting for r in runs):
    verdict = "pending"
elif all(r.get("status") == "completed" and r.get("conclusion") == "success" for r in runs):
    verdict = "success"
else:
    verdict = "failure"
print("NAMES " + ", ".join(sorted(set(str(r.get("name")) for r in runs))))
print("VERDICT " + verdict)
'

# ---------------------------------------------------------------------------------------------------------------------
# run

# ci_check: every check run of SHA completed with success; waits up to CI_WAIT seconds while some are queued or in
# progress. The F6a checklist read GitHub's API on the laptop; this reads the check runs of the commit.
ci_check() {
  local deadline=$((SECONDS + CI_WAIT)) out verdict
  while :; do
    out=$(curl -fsS --max-time 30 -H 'Accept: application/vnd.github+json' -H 'X-GitHub-Api-Version: 2022-11-28' \
      "$GITHUB_API/commits/$SHA/check-runs?per_page=100" | python3 -c "$CI_PY") \
      || fail "could not read CI's check runs of $SHA from GitHub"
    verdict=${out##*VERDICT }
    printf '%s\n' "$out" | grep -Ev '^(VERDICT|NAMES) ' || true
    case $verdict in
      success)
        CI_LINE="$(printf '%s\n' "$out" | grep -c '^  ') check runs, every one completed with success ($(printf '%s\n' "$out" | sed -n 's/^NAMES //p'))"
        say "CI: $CI_LINE"
        return 0
        ;;
      pending)
        [ "$SECONDS" -lt "$deadline" ] || fail "CI for $SHA still runs after $((CI_WAIT / 60)) minutes; run deploy.sh again once it is done"
        say "CI still runs; checking again in $CI_INTERVAL s"
        sleep "$CI_INTERVAL"
        ;;
      none) fail "GitHub has no check run for $SHA: push it, and wait for CI to start" ;;
      *) fail "CI for $SHA did not succeed: every check run must complete with success" ;;
    esac
  done
}

# compose_part REV START: a block of deploy/app/docker-compose.yml at REV, from the line START ("  postgres:" or
# "volumes:") to the next key at the same level. Only compared, never run.
compose_part() {
  git show "$1:deploy/app/docker-compose.yml" | awk -v start="$2" '
    $0 == start { inside = 1; print; next }
    inside && /^[^ \t#]/ { exit }
    inside && start ~ /^  / && /^  [^ \t#]/ { exit }
    inside { print }'
}

# backup WHEN: a fresh dump (the runbook's "systemctl start pg-backup@finance.service"), checked by its last-success
# file, into BACKUP_<WHEN>.
backup() {
  local started line file='' size='' time='' epoch=''
  started=$(date +%s)
  systemctl start pg-backup@finance.service \
    || fail "the backup failed: journalctl -u pg-backup@finance.service -n 30 --no-pager"
  [ -r "$BACKUP_DIR/last-success" ] || fail "no $BACKUP_DIR/last-success after the backup"
  while IFS= read -r line; do
    case $line in
      file=*) file=${line#file=} ;;
      size_bytes=*) size=${line#size_bytes=} ;;
      last_success=*) time=${line#last_success=} ;;
      last_success_epoch=*) epoch=${line#last_success_epoch=} ;;
    esac
  done <"$BACKUP_DIR/last-success"
  [[ $epoch =~ ^[0-9]+$ ]] && [ "$epoch" -ge "$started" ] || fail "$BACKUP_DIR/last-success is older than this backup"
  say "Dump $file, $size bytes, written at $time"
  printf -v "BACKUP_$1" '%s' "dump $file at $time, $size bytes"
}

# restore_test WHEN: pg-restore-test on the dump just written; PASS required. Its table is kept in the run folder.
restore_test() {
  local out=$RUN_DIR/restore-test-$1.txt rc=0 checks
  pg-restore-test finance </dev/null >"$out" 2>&1 || rc=$?
  cat "$out"
  [ "$rc" -eq 0 ] && [ "$(tail -n 1 "$out")" = PASS ] || fail "the restore test did not PASS ($out)"
  checks=$(awk '$1 == "tables" || $1 == "migration" || $1 ~ /^family_/ { printf "%s%s %s", sep, $1, $2; sep = ", " }' "$out")
  printf -v "RESTORE_$1" '%s' "PASS ($checks)"
}

# keep_images: the running revision's images under a tag naming its commit and under :previous, if they are the last
# good deploy's; a failed run's images never take those tags.
keep_images() {
  local api web repo id expected
  api=$(container_image finance-tracker-api)
  web=$(container_image finance-tracker-web)
  if [ "$api" = "$LG_API" ] && [ "$web" = "$LG_WEB" ]; then
    # The runbook's "docker tag finance-tracker-api finance-tracker-api:previous", by the running container's image.
    docker tag "$LG_API" "finance-tracker-api:$LG_COMMIT"
    docker tag "$LG_API" finance-tracker-api:previous
    docker tag "$LG_WEB" "finance-tracker-web:$LG_COMMIT"
    docker tag "$LG_WEB" finance-tracker-web:previous
    printf '%s\n' "$LG_COMMIT" >"$PREVIOUS_FILE"
    say "The running images, the last good deploy's, kept as :$LG_COMMIT and :previous; $PREVIOUS_FILE names it"
    KEPT_LINE="the last good deploy's (api $LG_API, web $LG_WEB) kept as :${LG_COMMIT:0:7} and :previous"
  else
    say "The running images ($api, $web) are not the last good deploy's: a failed run's. They take no tag; the tags"
    say "of the last good deploy ${LG_COMMIT:0:7} and :previous stay as they are."
    KEPT_LINE="the running images were a failed run's and took no tag; the last good deploy's stayed under :${LG_COMMIT:0:7} and :previous"
  fi
  for repo in api web; do
    id=$(docker image inspect -f '{{.Id}}' "finance-tracker-$repo:$LG_COMMIT" 2>/dev/null || true)
    expected=$LG_API
    [ "$repo" = api ] || expected=$LG_WEB
    if [ "$id" != "$expected" ]; then
      say "WARNING: finance-tracker-$repo:$LG_COMMIT is missing or not the recorded image: rollback.sh can't go back to it"
    fi
  done
  prune_commit_tags
}

# prune_commit_tags: keeps the tags of the last three revisions (by their place in main's history), removes older ones.
prune_commit_tags() {
  local repo tag n i
  local -a ranked
  for repo in finance-tracker-api finance-tracker-web; do
    ranked=()
    while IFS= read -r tag; do
      valid_commit "$tag" || continue
      if n=$(git rev-list --count "$tag" 2>/dev/null); then
        ranked+=("$n $tag")
      else
        say "WARNING: $repo:$tag names no commit of this clone; left alone"
      fi
    done < <(docker image ls --format '{{.Tag}}' "$repo")
    [ ${#ranked[@]} -gt 0 ] || continue
    i=0
    while read -r n tag; do
      i=$((i + 1))
      if [ "$i" -le 3 ] || [ "$tag" = "$LG_COMMIT" ]; then continue; fi
      if docker rmi "$repo:$tag" >/dev/null; then
        say "Removed $repo:$tag, older than the last three revisions"
      else
        say "WARNING: could not remove $repo:$tag"
      fi
    done < <(printf '%s\n' "${ranked[@]}" | sort -rn)
  done
}

# api_log_since_start: the api's log since its container last started, with Docker's timestamps.
api_log_since_start() {
  local started
  started=$(docker inspect -f '{{.State.StartedAt}}' finance-tracker-api)
  (cd "$APP_DIR" && docker compose logs --no-log-prefix --timestamps --since "$started" api)
}

# switch_expected: the D-25 line the api must log, from FAMILY_LEDGERS_ENABLED as production sets it. Reads that one
# variable of the container and nothing else of its environment.
switch_expected() {
  local line value
  line=$(docker inspect -f '{{range .Config.Env}}{{if eq (index (split . "=") 0) "FAMILY_LEDGERS_ENABLED"}}{{.}}{{end}}{{end}}' finance-tracker-api)
  value=${line#FAMILY_LEDGERS_ENABLED=}
  case ${value,,} in
    '') SWITCH_HOW="FAMILY_LEDGERS_ENABLED absent, so off" ;;
    true | on | yes | 1) SWITCH_HOW="FAMILY_LEDGERS_ENABLED=$value, so on" ;;
    false | off | no | 0) SWITCH_HOW="FAMILY_LEDGERS_ENABLED=$value, so off" ;;
    *) fail "FAMILY_LEDGERS_ENABLED is \"$value\", which the api reads as neither on nor off" ;;
  esac
  case $SWITCH_HOW in
    *on) SWITCH_LINE="Family ledgers (D-25): on" ;;
    *) SWITCH_LINE="Family ledgers (D-25): off; the family endpoints answer 404" ;;
  esac
}

# log_line LOG PATTERN: the last line matching PATTERN as "<the matched text> at <its time>", or nothing.
log_line() {
  local line
  line=$(printf '%s\n' "$1" | grep -E "$2" | tail -n 1 || true)
  [ -n "$line" ] || return 0
  printf '"%s" at %s\n' "$(printf '%s\n' "$line" | grep -oE "$2" | sed 's/[[:space:]]*$//')" "$(printf '%.19sZ' "${line%% *}")"
}

FLYWAY_PATTERN='(Migrating schema .*|Successfully applied .*|Schema .* is up to date.*)'
SWITCH_PATTERN='Family ledgers \(D-25\): .*'
STARTED_PATTERN='Started FinanceTrackerApplication in [0-9.]+ seconds'

write_meta() {
  printf 'kind=deploy\ncommit=%s\nstage=%s\nhead_before=%s\nbase=%s\n' "$SHA" "$STAGE" "$HEAD_BEFORE" "$BASE" \
    >"$RUN_DIR/meta"
}

# write_summary OUTCOME: plain text without a pipe character, ready for the runbook's "Deployed revisions".
write_summary() {
  {
    say "Deploy of ${SHA:0:7} (stage $STAGE): $1"
    say "Date: $(date -u +%F); run folder $RUN_DIR"
    say "Previous commit: ${PREVIOUS_LINE:-not reached}"
    say "New commit: $(git log -1 --format='%h (%s)' "$SHA")"
    say "Commits: ${COMMITS_LINE:-not reached}"
    say "Migrations added: ${MIGRATIONS_LINE:-not reached}; under deploy/ changed: ${DEPLOY_FILES_LINE:-not reached}"
    say "CI: ${CI_LINE:-not reached}"
    say "Before: ${BACKUP_before:-backup not reached}; restore test ${RESTORE_before:-not reached}"
    say "Flyway: ${FLYWAY_LINE:-not reached}"
    say "Started: ${STARTED_LINE:-no line found}"
    say "D-25: ${SWITCH_SEEN:-not reached}"
    say "Numbers: ${NUMBERS_LINE:-not reached}"
    say "Stage checks ($STAGE): ${STAGE_LINE:-not reached}"
    say "finance.conf: ${CONF_LINE:-not reached}"
    say "After: ${BACKUP_after:-backup not reached}; restore test ${RESTORE_after:-not reached}"
    say "Images: ${IMAGES_LINE:-${KEPT_LINE:-not reached}}"
    say "Browser checks: not yet (deploy/deploy.sh finish)"
    say "Smoke test: not yet (deploy/deploy.sh finish)"
  } | tr '|' '/' >"$RUN_DIR/summary.txt"
}

# shellcheck disable=SC2329  # the EXIT trap
on_exit_run() {
  local rc=$?
  trap - EXIT
  if [ "$PHASE" = complete ]; then
    :
  elif [ "$PHASE" = preflight ]; then
    say ""
    say "REFUSED at \"$STEP\": ${REASON:-an unexpected error, exit code $rc}"
    say "Nothing changed: no merge, no image tagged, built or started."
    [ -n "$RUN_DIR" ] && set_status refused
  else
    say ""
    say "FAILED at \"$STEP\": ${REASON:-an unexpected error, exit code $rc}"
    set_status failed
    write_summary "FAILED at \"$STEP\": ${REASON:-exit code $rc}"
    say "The run's summary, as far as it got: $RUN_DIR/summary.txt"
    print_rollback_command "$(git rev-parse HEAD)"
  fi
  [ "$rc" -ne 0 ] || [ "$PHASE" = complete ] || rc=1
  stop_log
  exit "$rc"
}

cmd_run() {
  local arg=$1 api_now web_now expected row version success api_log out
  STAGE=$2
  [[ $arg =~ ^[0-9a-f]{7,40}$ ]] || usage
  [[ $STAGE =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || usage
  PHASE=preflight
  trap on_exit_run EXIT
  take_lock exclusive
  start_log "${arg:0:7}"
  print_settings

  heading "1.1 Preflight: the tools and the terminal"
  check_tools "${TOOLS[@]}"
  # The confirmation is read from the terminal; without one (ssh without a terminal, a pipe), stop before the backup.
  exec {TTY_FD}<"$TTY" || fail "can't read $TTY: run deploy.sh in a terminal on the server"

  heading "1.2 Preflight: the clone"
  cd "$REPO_DIR" || fail "no clone in $REPO_DIR"
  [ "$(git symbolic-ref --quiet --short HEAD || true)" = main ] \
    || fail "the clone isn't on main (after a rollback it is detached): git checkout main, then run this again"
  [ -z "$(git status --porcelain --untracked-files=no)" ] || fail "the clone has changes to tracked files (git status)"
  [ -f "$APP_DIR/.env" ] || fail "$APP_DIR/.env is missing (the runbook's step 5)"
  HEAD_BEFORE=$(git rev-parse HEAD)
  api_now=$(container_image finance-tracker-api) || fail "no container finance-tracker-api: the stack isn't up"
  web_now=$(container_image finance-tracker-web) || fail "no container finance-tracker-web: the stack isn't up"
  say "HEAD: $(git log -1 --format='%h %s' HEAD)"
  say "Running images: api $api_now, web $web_now"
  if read_last_good; then
    say "Last good deploy: $(git log -1 --format='%h %s' "$LG_COMMIT" 2>/dev/null || echo "$LG_COMMIT") at $LG_TIME ($LG_SOURCE)"
    PREVIOUS_LINE="$(git log -1 --format='%h (%s)' "$LG_COMMIT"), the last good deploy"
  else
    say "No record of a last good deploy: deploy.sh's first run. The running revision, HEAD, with its current images,"
    say "counts as the last good deploy once you confirm."
    PREVIOUS_LINE="$(git log -1 --format='%h (%s)' HEAD), HEAD with its images at deploy.sh's first run (no record before)"
  fi

  heading "1.3 Preflight: git fetch, the commit and what it brings"
  git fetch origin
  SHA=$(git rev-parse --verify --quiet "$arg^{commit}") || fail "$arg is no commit of this clone after git fetch"
  [ "$SHA" = "$(git rev-parse --verify origin/main)" ] \
    || fail "$arg is not origin/main ($(git log -1 --format='%h %s' origin/main))"
  git merge-base --is-ancestor HEAD "$SHA" || fail "$arg is not a fast-forward of HEAD ($(git rev-parse --short HEAD))"
  BASE=$HEAD_BEFORE
  if [ -n "$LG_COMMIT" ] && [ "$LG_COMMIT" != "$HEAD_BEFORE" ] && git merge-base --is-ancestor "$LG_COMMIT" "$SHA"; then
    BASE=$LG_COMMIT
  fi
  write_meta
  say "Commits (git log --oneline HEAD..${SHA:0:7}):"
  git log --oneline "HEAD..$SHA"
  if [ "$BASE" != "$HEAD_BEFORE" ]; then
    say "HEAD isn't the last good deploy (a failed run's, or one rolled back from); since the last good deploy:"
    git log --oneline "$BASE..$SHA"
  fi
  COMMITS_LINE=$(git log --reverse --format=%h "$BASE..$SHA" | paste -sd ' ' | sed 's/ /, /g')
  COMMITS_LINE="${COMMITS_LINE:-none} (${BASE:0:7} to ${SHA:0:7})"
  MIGRATIONS_LINE=$(git diff --name-only --diff-filter=A "$BASE" "$SHA" -- "$MIGRATIONS/" | sed 's|.*/||' | paste -sd ' ' | sed 's/ /, /g')
  MIGRATIONS_LINE=${MIGRATIONS_LINE:-none}
  DEPLOY_FILES_LINE=$(git diff --name-only "$BASE" "$SHA" -- deploy/ | paste -sd ' ' | sed 's/ /, /g')
  DEPLOY_FILES_LINE=${DEPLOY_FILES_LINE:-nothing}
  say "Migrations added: $MIGRATIONS_LINE"
  say "Files changed under deploy/:"
  git diff --stat "$BASE" "$SHA" -- deploy/

  heading "1.4 Preflight: what stays manual"
  git diff --quiet "$BASE" "$SHA" -- deploy/finance.caddy \
    || fail "deploy/finance.caddy changes: that stays manual (deploy/RUNBOOK.md, \"Change the site file\")"
  out=$(compose_part "$BASE" '  postgres:')
  [ -n "$out" ] || fail "no postgres service in deploy/app/docker-compose.yml at ${BASE:0:7}"
  if [ "$out" != "$(compose_part "$SHA" '  postgres:')" ] \
    || [ "$(compose_part "$BASE" volumes:)" != "$(compose_part "$SHA" volumes:)" ] \
    || ! git diff --quiet "$BASE" "$SHA" -- deploy/app/postgres-init.sh; then
    fail "the postgres service changes (its block, the volumes or postgres-init.sh): that stays manual"
  fi
  say "deploy/finance.caddy and the postgres service unchanged"

  heading "1.5 Preflight: CI for ${SHA:0:7}"
  ci_check

  heading "1.6 Preflight: the stage's checks"
  if ! git cat-file -e "$SHA:deploy/checks/$STAGE.sql" 2>/dev/null \
    || ! git cat-file -e "$SHA:deploy/checks/$STAGE.expected" 2>/dev/null; then
    fail "${SHA:0:7} has no deploy/checks/$STAGE.sql and $STAGE.expected"
  fi
  git show "$SHA:deploy/checks/$STAGE.sql" >"$RUN_DIR/stage-$STAGE.sql"
  git show "$SHA:deploy/checks/$STAGE.expected" >"$RUN_DIR/stage-$STAGE.expected"
  check_sql_file "$RUN_DIR/stage-$STAGE.sql"
  say "deploy/checks/$STAGE.sql and $STAGE.expected are in ${SHA:0:7}"

  heading "1.7 Preflight: the numbers before"
  # The F6a checklist's numbers (f6_numbers), as a file: this text, read now from the running commit, runs again in
  # postflight, so a new version of numbers.sql takes effect from the next deploy.
  git show "HEAD:deploy/checks/numbers.sql" >"$RUN_DIR/numbers.sql" 2>/dev/null \
    || fail "HEAD has no deploy/checks/numbers.sql: deploy OPS-1 by hand first (deploy/RUNBOOK.md)"
  sql_file "$RUN_DIR/numbers.sql" >"$RUN_DIR/numbers-before.txt" || fail "numbers.sql failed"
  if grep -Eqv '^[a-z_]+=[0-9]+$' "$RUN_DIR/numbers-before.txt"; then
    fail "numbers.sql printed something else than key=count lines"
  fi
  [ -s "$RUN_DIR/numbers-before.txt" ] || fail "numbers.sql printed nothing"
  cat "$RUN_DIR/numbers-before.txt"

  heading "1.8 Preflight: a fresh backup, then the restore test"
  backup before
  restore_test before

  heading "2. Confirmation"
  say "Deploy ${SHA:0:7} ($(git log -1 --format=%s "$SHA")), stage $STAGE, over $(git log -1 --format='%h' HEAD)."
  say "Commits: $COMMITS_LINE"
  ask "Type the first 7 characters of the commit to deploy it; anything else stops here with nothing changed: "
  [ "$ANSWER" = "${SHA:0:7}" ] || fail "not confirmed"

  PHASE=deploy
  set_status deploying
  if [ -z "$LG_COMMIT" ]; then
    write_last_good "$HEAD_BEFORE" "$api_now" "$web_now" baseline
    add_history baseline "$HEAD_BEFORE" "$api_now" "$web_now"
    read_last_good
    say "Recorded HEAD ${HEAD_BEFORE:0:7} with its running images as the last good deploy (baseline)."
  fi

  heading "3.1 Deploy: git merge --ff-only ${SHA:0:7}"
  git merge --ff-only "$SHA"

  heading "3.2 Deploy: keep the running images"
  keep_images

  heading "3.3 Deploy: build api, then web"
  (cd "$APP_DIR" && docker compose build api && docker compose build web)

  heading "3.4 Deploy: start api and web"
  (cd "$APP_DIR" && docker compose up -d --no-deps api web)

  heading "3.5 Deploy: health"
  wait_healthy || fail "api and web not both healthy after $HEALTH_TRIES checks"
  (cd "$APP_DIR" && docker compose ps --format 'table {{.Name}}\t{{.Status}}')
  docker image prune -f

  PHASE=postflight
  heading "4.1 Postflight: Flyway"
  expected=$(highest_migration "$SHA")
  row=$(flyway_row) || fail "could not read flyway_schema_history"
  version=${row%% *}
  success=${row##* }
  say "Latest row: $row; the highest migration in ${SHA:0:7}: V$expected"
  [ "$success" = true ] || fail "Flyway's latest row did not succeed: $row"
  [ "$version" = "$expected" ] || fail "Flyway is at version $version, but ${SHA:0:7}'s highest migration is V$expected"
  api_log=$(api_log_since_start)
  printf '%s\n' "$api_log" | grep -E "$FLYWAY_PATTERN" || true
  FLYWAY_LINE=$(log_line "$api_log" "$FLYWAY_PATTERN")
  [ -n "$FLYWAY_LINE" ] || fail "the api's log since its start has no Flyway line"
  FLYWAY_LINE="version $version ($row); $FLYWAY_LINE"
  STARTED_LINE=$(log_line "$api_log" "$STARTED_PATTERN")
  [ -z "$STARTED_LINE" ] || say "Started: $STARTED_LINE"

  heading "4.2 Postflight: the D-25 line"
  switch_expected
  say "Expected: \"$SWITCH_LINE\" ($SWITCH_HOW)"
  SWITCH_SEEN=$(log_line "$api_log" "$SWITCH_PATTERN")
  [ -n "$SWITCH_SEEN" ] || fail "the api's log since its start has no D-25 line"
  say "Logged: $SWITCH_SEEN"
  case $SWITCH_SEEN in
    "\"$SWITCH_LINE\" at "*) SWITCH_SEEN="$SWITCH_SEEN, as production sets it ($SWITCH_HOW)" ;;
    *) fail "the D-25 line isn't \"$SWITCH_LINE\" ($SWITCH_HOW)" ;;
  esac

  heading "4.3 Postflight: the numbers after"
  sql_file "$RUN_DIR/numbers.sql" >"$RUN_DIR/numbers-after.txt" || fail "numbers.sql failed"
  if ! diff "$RUN_DIR/numbers-before.txt" "$RUN_DIR/numbers-after.txt" >"$RUN_DIR/numbers.diff"; then
    cat "$RUN_DIR/numbers.diff"
    fail "the numbers differ from preflight's ($RUN_DIR/numbers.diff); a user's sign-in or entry during the deploy shows up here too, so judge before rolling back"
  fi
  NUMBERS_LINE="the same before and after ($(paste -sd ' ' "$RUN_DIR/numbers-after.txt" | sed 's/ /, /g'))"
  say "The same numbers"

  heading "4.4 Postflight: the stage's checks ($STAGE)"
  # The copies of ${SHA:0:7}'s files that preflight checked.
  sql_file "$RUN_DIR/stage-$STAGE.sql" >"$RUN_DIR/stage-$STAGE.txt" || fail "deploy/checks/$STAGE.sql failed"
  cat "$RUN_DIR/stage-$STAGE.txt"
  if ! diff "$RUN_DIR/stage-$STAGE.expected" "$RUN_DIR/stage-$STAGE.txt" >"$RUN_DIR/stage.diff"; then
    cat "$RUN_DIR/stage.diff"
    fail "the stage's checks differ from deploy/checks/$STAGE.expected ($RUN_DIR/stage.diff)"
  fi
  STAGE_LINE="the same as deploy/checks/$STAGE.expected ($(wc -l <"$RUN_DIR/stage-$STAGE.txt") lines)"
  say "The stage's checks as expected"

  heading "4.5 Postflight: finance.conf"
  if git diff --quiet "$BASE" "$SHA" -- deploy/pg-backup/finance.conf; then
    CONF_LINE="not installed (unchanged)"
  else
    # The runbook's "If deploy/pg-backup/finance.conf changed".
    install -o root -g root -m 600 "$REPO_DIR/deploy/pg-backup/finance.conf" "$CONF_TARGET"
    CONF_LINE="installed as $CONF_TARGET (it changed)"
  fi
  say "finance.conf: $CONF_LINE"

  heading "4.6 Postflight: a fresh backup, then the restore test"
  backup after
  restore_test after

  api_now=$(container_image finance-tracker-api)
  web_now=$(container_image finance-tracker-web)
  write_last_good "$SHA" "$api_now" "$web_now" deploy
  add_history good "$SHA" "$api_now" "$web_now"
  IMAGES_LINE="api $api_now, web $web_now (recorded in last-good); before the build, $KEPT_LINE"
  set_status deployed
  write_summary "deployed"
  PHASE=complete
  heading "5. Summary"
  cat "$RUN_DIR/summary.txt"
  say ""
  say "Left for you: the browser checks and the smoke test of the stage's checklist, then: deploy/deploy.sh finish"
  stop_log
}

# ---------------------------------------------------------------------------------------------------------------------
# finish

# shellcheck disable=SC2329  # the EXIT trap
on_exit_finish() {
  local rc=$?
  trap - EXIT
  if [ "$rc" -ne 0 ]; then
    say ""
    say "finish stopped at \"$STEP\": ${REASON:-an unexpected error, exit code $rc}"
  fi
  stop_log
  exit "$rc"
}

# meta_value KEY: a value of the run's meta file (written by this script, parsed, never sourced).
meta_value() { sed -n "s/^$1=//p" "$RUN_DIR/meta"; }

cmd_finish() {
  local latest browser smoke browser_at smoke_at family problems=()
  trap on_exit_finish EXIT
  take_lock exclusive
  latest=$(find "$STATE_DIR/runs" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort | tail -n 1)
  [ -n "$latest" ] || fail "no run in $STATE_DIR/runs"
  [ "$(cat "$latest/status" 2>/dev/null)" = deployed ] \
    || fail "the latest run, $latest, is $(cat "$latest/status" 2>/dev/null || echo 'without a status'), not deployed: nothing to finish"
  RUN_DIR=$latest
  exec > >(tee -a "$RUN_DIR/log") 2>&1
  TEE_PID=$!
  heading "Finish of $RUN_DIR"
  check_tools git docker flock diff
  cd "$REPO_DIR" || fail "no clone in $REPO_DIR"
  SHA=$(meta_value commit)
  valid_commit "$SHA" || fail "$RUN_DIR/meta names no commit"
  [ "$(git rev-parse HEAD)" = "$SHA" ] || fail "HEAD is no longer the run's commit ${SHA:0:7}"

  heading "The browser checks and the smoke test"
  ask "Did the browser checks pass? Type yes or no: "
  browser=$ANSWER browser_at=$(now)
  ask "Did the smoke test pass? Type yes or no: "
  smoke=$ANSWER smoke_at=$(now)

  heading "The family numbers after the smoke test"
  sql_file "$RUN_DIR/numbers.sql" >"$RUN_DIR/numbers-finish.txt" || fail "numbers.sql failed"
  if diff <(grep '^family_' "$RUN_DIR/numbers-before.txt") <(grep '^family_' "$RUN_DIR/numbers-finish.txt") >"$RUN_DIR/family.diff"; then
    family="the family lines as before the deploy"
  else
    cat "$RUN_DIR/family.diff"
    family="DIFFERENT from before the deploy ($RUN_DIR/family.diff)"
    problems+=("the family numbers")
  fi
  say "Family numbers: $family"
  [ "$browser" = yes ] || problems+=("the browser checks")
  [ "$smoke" = yes ] || problems+=("the smoke test")

  {
    grep -Ev '^(Browser checks|Smoke test): ' "$RUN_DIR/summary.txt" || true
    say "Browser checks: $([ "$browser" = yes ] && echo passed || echo "NOT passed (answered \"$browser\")"), answered at $browser_at"
    say "Smoke test: $([ "$smoke" = yes ] && echo passed || echo "NOT passed (answered \"$smoke\")"), answered at $smoke_at"
    say "Family numbers after the smoke test: $family"
  } | tr '|' '/' >"$RUN_DIR/summary.tmp"
  mv -f "$RUN_DIR/summary.tmp" "$RUN_DIR/summary.txt"
  heading "Final summary"
  cat "$RUN_DIR/summary.txt"
  if [ ${#problems[@]} -gt 0 ]; then
    set_status finish-failed
    read_last_good || true
    add_history finish-failed "$SHA" "${LG_API:-none}" "${LG_WEB:-none}"
    say ""
    say "Not passed: ${problems[*]}."
    print_rollback_command "$SHA"
    exit 1
  fi
  set_status finished
  say ""
  say "Done. Add the summary above as a row of \"Deployed revisions\" in deploy/RUNBOOK.md."
}

# ---------------------------------------------------------------------------------------------------------------------
# verify

cmd_verify() {
  local stage=$1 issues=0 api web row expected out api_log seen
  [[ $stage =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || usage
  take_lock shared
  cd "$REPO_DIR" || fail "no clone in $REPO_DIR"
  say "verify $stage at $(git log -1 --format='%h %s' HEAD), $(now); read only"
  check_tools git docker flock diff

  heading "Health"
  api=$(health_of finance-tracker-api)
  web=$(health_of finance-tracker-web)
  say "api: $api, web: $web"
  [ "$api" = healthy ] && [ "$web" = healthy ] || { say "PROBLEM: not both healthy"; issues=$((issues + 1)); }

  heading "Flyway"
  expected=$(highest_migration HEAD)
  row=$(flyway_row) || row="unreadable"
  say "Latest row: $row; the highest migration in HEAD: V$expected"
  [ "${row##* }" = true ] && [ "${row%% *}" = "$expected" ] \
    || { say "PROBLEM: Flyway's latest row isn't V$expected with success"; issues=$((issues + 1)); }
  api_log=$(api_log_since_start || true)
  printf '%s\n' "$api_log" | grep -E "$FLYWAY_PATTERN" || say "(no Flyway line left in the api's log since its start)"

  heading "The D-25 line"
  switch_expected
  seen=$(log_line "$api_log" "$SWITCH_PATTERN")
  if [ -z "$seen" ]; then
    say "WARNING: no D-25 line left in the api's retained log since its start; expected \"$SWITCH_LINE\" ($SWITCH_HOW)"
  elif [[ $seen == "\"$SWITCH_LINE\" at "* ]]; then
    say "$seen, as production sets it ($SWITCH_HOW)"
  else
    say "PROBLEM: $seen, but production sets $SWITCH_HOW"
    issues=$((issues + 1))
  fi

  heading "The stage's checks ($stage)"
  if [ -f "deploy/checks/$stage.sql" ] && [ -f "deploy/checks/$stage.expected" ]; then
    out=$(sql_file "deploy/checks/$stage.sql") || out="(deploy/checks/$stage.sql failed)"
    printf '%s\n' "$out"
    if diff "deploy/checks/$stage.expected" - <<<"$out"; then
      say "As deploy/checks/$stage.expected"
    else
      say "PROBLEM: the stage's checks differ from deploy/checks/$stage.expected (above)"
      issues=$((issues + 1))
    fi
  else
    say "PROBLEM: no deploy/checks/$stage.sql and $stage.expected in HEAD"
    issues=$((issues + 1))
  fi

  heading "The numbers"
  sql_file deploy/checks/numbers.sql || { say "PROBLEM: numbers.sql failed"; issues=$((issues + 1)); }

  say ""
  if [ "$issues" -eq 0 ]; then
    say "verify $stage: OK"
  else
    say "verify $stage: $issues problem(s)"
    return 1
  fi
}

main() {
  set -euo pipefail
  shopt -s inherit_errexit
  umask 077
  common_settings
  PHASE='' SHA='' STAGE='' HEAD_BEFORE='' BASE='' LG_COMMIT='' LG_API='' LG_WEB=''
  case ${1:-} in
    run) [ $# -eq 3 ] || usage; cmd_run "$2" "$3" ;;
    finish) [ $# -eq 1 ] || usage; cmd_finish ;;
    verify) [ $# -eq 2 ] || usage; cmd_verify "$2" ;;
    *) usage ;;
  esac
}

main "$@"; exit $?
