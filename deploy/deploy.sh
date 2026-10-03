#!/usr/bin/env bash
# Deploys Finance Tracker on the production server: the steps the F5 and F6a checklists did by hand, in their order
# (deploy/RUNBOOK.md, "Deploying with deploy.sh"). Run as root, on the server, from /opt/finance-tracker:
#
#   deploy/deploy.sh run <commit> <stage>   preflight (changes nothing but a fresh backup), one confirmation typed at
#                                           the terminal, the deploy, postflight; prints a summary
#   deploy/deploy.sh finish                 after the browser checks and the smoke test: checks the pages, asks how
#                                           they went (a page that fails records the browser checks as failed, without
#                                           asking: F7b), compares the family numbers, writes the final summary; may
#                                           run again, and the latest answers count (F6c)
#   deploy/deploy.sh verify <stage>         read only, any time: health, the pages, Flyway, the stage's checks, the D-25
#                                           line, the numbers; no backup, no change
#   deploy/deploy.sh adopt                  after a manual deploy, or a stopped run the operator judged harmless:
#                                           records the running revision as the last good deploy, once confirmed at the
#                                           terminal; never touches git, images or containers (F6b)
#   deploy/deploy.sh switch on|off           changes FAMILY_LEDGERS_ENABLED (D-25) in .env and restarts api alone,
#                                           once confirmed at the terminal; HEAD must be the last good deploy, healthy,
#                                           with no unfinished run (F7). Never run against a stage whose stop you
#                                           haven't resolved; on a failure after the change, it prints the way back
#                                           and never switches back by itself.
#
# Every check, the numbers and Flyway's row are read as the read-only database role finance_checks (F6b;
# deploy/RUNBOOK.md, "A read-only role for the deploy checks"); preflight, finish and verify stop without it.
#
# Every question is answered at the terminal, after it is asked: lines waiting there before it, such as a block pasted
# ahead, are discarded first, and a yes or no question asks again until yes or no is typed (F6c; deploy/common.sh).
#
# The pages (deploy/common.sh, PAGES) are checked through the public address after the deploy's health, by verify, and
# by finish before its questions (F7b): F7's /privacy answered 403 while every other check passed.
#
# Git writes the working tree under umask 022 (deploy/common.sh, git_tree); everything else runs under umask 077.
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
       deploy/deploy.sh adopt
       deploy/deploy.sh switch on|off
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
# "volumes:") to the next key at the same level. Only compared, never run. awk reads to the end, never exits early: under
# pipefail an early exit would kill git show with SIGPIPE whenever it was still writing (F7b).
compose_part() {
  git show "$1:deploy/app/docker-compose.yml" | awk -v start="$2" '
    done { next }
    $0 == start { inside = 1; print; next }
    inside && /^[^ \t#]/ { done = 1; next }
    inside && start ~ /^  / && /^  [^ \t#]/ { done = 1; next }
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
    say "Pages: ${PAGES_LINE:-not reached}"
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
    say "The site may still work, but this deploy isn't good: don't run deploy.sh finish. Send this output (the run"
    say "folder's log holds it) to the developer, then judge the way back below."
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
    say "Its status, its newest line in the history: $(commit_status "$LG_COMMIT")"
    PREVIOUS_LINE="$(git log -1 --format='%h (%s)' "$LG_COMMIT"), the last good deploy"
  else
    say "No record of a last good deploy: deploy.sh's first run. The running revision, HEAD, with its current images,"
    say "counts as the last good deploy once you confirm."
    PREVIOUS_LINE="$(git log -1 --format='%h (%s)' HEAD), HEAD with its images at deploy.sh's first run (no record before)"
  fi
  require_checks_role

  heading "1.3 Preflight: git fetch, the commit and what it brings"
  git fetch origin
  SHA=$(git rev-parse --verify --quiet "$arg^{commit}") \
    || fail "$arg is no commit of this clone after git fetch: push it from the laptop first (deploy/RUNBOOK.md, \"Deploying with deploy.sh\", step 1), then run this again"
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
  # F6c's second run said "over 8ede02e" (HEAD) while 7a60020 (BASE, the running revision after a rollback and a
  # plain "git checkout main") was running: BASE, not HEAD, is what this deploy actually replaces.
  say "Deploy ${SHA:0:7} ($(git log -1 --format=%s "$SHA")), stage $STAGE, over $(git log -1 --format='%h' "$BASE")."
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
  # Under umask 022 (deploy/common.sh, git_tree): the files it writes are 644, whatever this script's umask.
  git_tree merge --ff-only "$SHA"

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

  heading "3.6 Deploy: the pages"
  page_checks || fail "a page isn't as expected: $PAGES_LINE"

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

# The exit status of a finish whose answers or family numbers say not passed: not an error of the script (F6c).
NOT_PASSED=3

# shellcheck disable=SC2329  # the EXIT trap
on_exit_finish() {
  local rc=$?
  trap - EXIT
  if [ "$rc" -ne 0 ] && [ "$rc" -ne "$NOT_PASSED" ]; then
    say ""
    say "finish stopped at \"$STEP\": ${REASON:-an unexpected error, exit code $rc}"
  fi
  stop_log
  exit "$rc"
}

# meta_value KEY: a value of the run's meta file (written by this script, parsed, never sourced).
meta_value() { sed -n "s/^$1=//p" "$RUN_DIR/meta"; }

# latest_deployed_run: the latest run that isn't refused (F6c: a refused run, or a refused adopt, doesn't count), into
# LATEST; it must be a deploy that reached its end (deployed), or one finished before (finished, finish-failed), whose
# finish may run again.
latest_deployed_run() {
  local dir status
  LATEST=''
  while IFS= read -r dir; do
    status=$(cat "$dir/status" 2>/dev/null || echo 'without a status')
    [ "$status" != refused ] || continue
    LATEST=$dir
    case $status in
      deployed | finished | finish-failed) [ -f "$dir/meta" ] && return 0 ;;
    esac
    fail "the latest run that isn't refused, $dir, is $status, not deployed: nothing to finish"
  done < <(find "$STATE_DIR/runs" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -r)
  fail "no deployed run in $STATE_DIR/runs: nothing to finish"
}

cmd_finish() {
  local browser smoke browser_at smoke_at browser_line family api web pages problems=()
  trap on_exit_finish EXIT
  take_lock exclusive
  STEP="the latest deployed run"
  latest_deployed_run
  RUN_DIR=$LATEST
  exec > >(tee -a "$RUN_DIR/log") 2>&1
  TEE_PID=$!
  heading "Finish of $RUN_DIR"
  [ "$(cat "$RUN_DIR/status")" = deployed ] || say "Its finish ran before ($(cat "$RUN_DIR/status")): the answers now replace those."
  check_tools git docker flock diff curl
  require_checks_role
  cd "$REPO_DIR" || fail "no clone in $REPO_DIR"
  SHA=$(meta_value commit)
  valid_commit "$SHA" || fail "$RUN_DIR/meta names no commit"
  [ "$(git rev-parse HEAD)" = "$SHA" ] || fail "HEAD is no longer the run's commit ${SHA:0:7}"

  heading "The pages"
  # Before the questions (F7b): F7's finish recorded yes while /privacy answered 403. A page that fails leaves no way
  # to answer yes.
  if page_checks; then pages=passed; else pages=failed; fi

  heading "The browser checks and the smoke test"
  if [ "$pages" = passed ]; then
    say "Answer only once both are done; type each answer after its question."
    ask_yes_no "Did the browser checks pass? Type yes or no: "
    browser=$ANSWER browser_at=$(now)
    if [ "$browser" = yes ]; then
      browser_line="passed, answered at $browser_at"
    else
      browser_line="NOT passed (answered \"$browser\"), answered at $browser_at"
    fi
  else
    browser=failed browser_at=$(now)
    browser_line="NOT passed (the page checks failed: $PAGES_LINE), recorded at $browser_at without asking"
    say "The page checks failed: the browser checks are recorded as NOT passed, without asking."
    say "Type the smoke test's answer after its question."
  fi
  ask_yes_no "Did the smoke test pass? Type yes or no: "
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
    grep -Ev '^(Pages at finish|Browser checks|Smoke test|Family numbers after the smoke test): ' "$RUN_DIR/summary.txt" || true
    say "Pages at finish: $PAGES_LINE"
    say "Browser checks: $browser_line"
    say "Smoke test: $([ "$smoke" = yes ] && echo passed || echo "NOT passed (answered \"$smoke\")"), answered at $smoke_at"
    say "Family numbers after the smoke test: $family"
  } | tr '|' '/' >"$RUN_DIR/summary.tmp"
  mv -f "$RUN_DIR/summary.tmp" "$RUN_DIR/summary.txt"
  heading "Final summary"
  cat "$RUN_DIR/summary.txt"
  # The latest answers count in last-good and the history too (F6c): the running revision stays the last good deploy,
  # with how its finish went, and the history's newest line for it is good or finish-failed.
  api=$(container_image finance-tracker-api)
  web=$(container_image finance-tracker-web)
  read_last_good || true
  if [ ${#problems[@]} -gt 0 ]; then
    set_status finish-failed
    write_last_good "$SHA" "$api" "$web" "${LG_SOURCE:-deploy}" "not passed $(now)"
    add_history finish-failed "$SHA" "$api" "$web"
    say ""
    say "NOT PASSED: ${problems[*]}. Recorded as finish-failed; nothing was rolled back."
    say "If the cause is fixed, or an answer was wrong, run deploy/deploy.sh finish again: the latest answers count."
    print_rollback_command "$SHA"
    exit "$NOT_PASSED"
  fi
  set_status finished
  write_last_good "$SHA" "$api" "$web" "${LG_SOURCE:-deploy}" "passed $(now)"
  add_history good "$SHA" "$api" "$web"
  say ""
  say "Done. Add the summary above as a row of \"Deployed revisions\" in deploy/RUNBOOK.md."
}

# ---------------------------------------------------------------------------------------------------------------------
# verify

cmd_verify() {
  local stage=$1 issues=0 api web row expected out api_log seen problem
  [[ $stage =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || usage
  take_lock shared
  cd "$REPO_DIR" || fail "no clone in $REPO_DIR"
  say "verify $stage at $(git log -1 --format='%h %s' HEAD), $(now); read only"
  check_tools git docker flock diff curl

  heading "The read-only role"
  problem=$(role_problem)
  if [ -n "$problem" ]; then
    say "PROBLEM: $problem; the database's checks below are skipped"
    role_command
    issues=$((issues + 1))
  else
    say "The checks run as $CHECKS_ROLE: $ROLE_OK"
  fi

  heading "Health"
  api=$(health_of finance-tracker-api)
  web=$(health_of finance-tracker-web)
  say "api: $api, web: $web"
  [ "$api" = healthy ] && [ "$web" = healthy ] || { say "PROBLEM: not both healthy"; issues=$((issues + 1)); }

  heading "The pages"
  page_checks || { say "PROBLEM: a page isn't as expected"; issues=$((issues + 1)); }

  heading "Flyway"
  expected=$(highest_migration HEAD)
  if [ -n "$problem" ]; then
    say "Not read: no read-only role; the highest migration in HEAD: V$expected"
  else
    row=$(flyway_row) || row="unreadable"
    say "Latest row: $row; the highest migration in HEAD: V$expected"
    [ "${row##* }" = true ] && [ "${row%% *}" = "$expected" ] \
      || { say "PROBLEM: Flyway's latest row isn't V$expected with success"; issues=$((issues + 1)); }
  fi
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
  if [ -n "$problem" ]; then
    say "Not run: no read-only role"
  elif [ -f "deploy/checks/$stage.sql" ] && [ -f "deploy/checks/$stage.expected" ]; then
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
  if [ -n "$problem" ]; then
    say "Not read: no read-only role"
  else
    sql_file deploy/checks/numbers.sql || { say "PROBLEM: numbers.sql failed"; issues=$((issues + 1)); }
  fi

  say ""
  if [ "$issues" -eq 0 ]; then
    say "verify $stage: OK"
  else
    say "verify $stage: $issues problem(s)"
    return 1
  fi
}

# ---------------------------------------------------------------------------------------------------------------------
# adopt

# shellcheck disable=SC2329  # the EXIT trap
on_exit_adopt() {
  local rc=$?
  trap - EXIT
  if [ "$PHASE" != complete ]; then
    say ""
    say "REFUSED at \"$STEP\": ${REASON:-an unexpected error, exit code $rc}"
    say "Nothing changed."
    [ -n "$RUN_DIR" ] && set_status refused
    [ "$rc" -ne 0 ] || rc=1
  fi
  stop_log
  exit "$rc"
}

# cmd_adopt: the running revision as the last good deploy (F6b), after a manual deploy (deploy/RUNBOOK.md, "Update the
# app") or a stopped run the operator judged harmless, such as numbers changed by users during the deploy. It reads
# git and Docker, and writes only last-good, a history line and its run folder.
cmd_adopt() {
  local head api web latest status
  PHASE=refusing
  trap on_exit_adopt EXIT
  take_lock exclusive
  cd "$REPO_DIR" || fail "no clone in $REPO_DIR"
  head=$(git rev-parse HEAD)
  latest=$(find "$STATE_DIR/runs" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort | tail -n 1 || true)
  start_log "adopt-${head:0:7}"
  print_settings

  heading "1. What runs"
  check_tools git docker flock
  api=$(container_image finance-tracker-api) || fail "no container finance-tracker-api: the stack isn't up"
  web=$(container_image finance-tracker-web) || fail "no container finance-tracker-web: the stack isn't up"
  say "HEAD: $(git log -1 --format='%h %s' HEAD)"
  say "Running images: api $api ($(health_of finance-tracker-api)), web $web ($(health_of finance-tracker-web))"
  if [ -n "$latest" ]; then
    say "The last run: ${latest##*/}, $(cat "$latest/status" 2>/dev/null || echo 'without a status')"
  else
    say "No run of deploy.sh or rollback.sh yet"
  fi
  if read_last_good; then
    say "Last good deploy: $(git log -1 --format='%h %s' "$LG_COMMIT" 2>/dev/null || echo "$LG_COMMIT") at $LG_TIME ($LG_SOURCE),"
    say "  api $LG_API, web $LG_WEB"
  else
    say "No record of a last good deploy"
  fi
  status=$(commit_status "$head")
  say "HEAD's status, its newest line in the history: ${status:-none}"
  # F6c: the newest line decides. A finish-failed HEAD may be adopted, with a good, adopted or baseline one there is
  # nothing to adopt, as long as last-good names it with the running images.
  case $status in
    good | adopted | baseline | switch-on | switch-off)
      if [ "$LG_COMMIT" = "$head" ] && [ "$LG_API" = "$api" ] && [ "$LG_WEB" = "$web" ]; then
        fail "nothing to adopt: HEAD ${head:0:7} with the running images is the last good deploy already ($status)"
      fi
      ;;
  esac

  heading "2. Confirmation"
  say "This records HEAD ${head:0:7} with the running images as the last good deploy, the one the next deploy.sh run"
  say "keeps and rollback.sh goes back to. It changes no git, image or container."
  ask "Only after a manual deploy, or a stopped run you judged harmless. Type ADOPT ${head:0:7} to record it; anything else changes nothing: "
  [ "$ANSWER" = "ADOPT ${head:0:7}" ] || fail "not confirmed"

  PHASE=adopting
  write_last_good "$head" "$api" "$web" adopt
  add_history adopted "$head" "$api" "$web"
  {
    say "Adopted on $(date -u +%F) at $(now): $(git log -1 --format='%h (%s)' "$head") as the last good deploy"
    say "Images: api $api, web $web"
    say "Before: ${LG_COMMIT:+$(git log -1 --format='%h (%s)' "$LG_COMMIT" 2>/dev/null || echo "$LG_COMMIT")}${LG_COMMIT:-no record}; the last run ${latest:+${latest##*/}, $(cat "$latest/status" 2>/dev/null || echo 'without a status')}${latest:-none}"
  } | tr '|' '/' >"$RUN_DIR/summary.txt"
  set_status adopted
  PHASE=complete
  heading "3. Summary"
  cat "$RUN_DIR/summary.txt"
  stop_log
}

# ---------------------------------------------------------------------------------------------------------------------
# switch

SWITCH_VAR=FAMILY_LEDGERS_ENABLED

# switch_line_of FILE: FAMILY_LEDGERS_ENABLED's current line in FILE, or nothing; never the rest of the file (F7).
switch_line_of() { grep "^$SWITCH_VAR=" "$1" 2>/dev/null || true; }

# switch_shown LINE: LINE (or "absent"), and "so on" or "so off" by the same reading deploy.sh's own switch_expected
# uses, for the operator to see what will change without ever being shown the file.
switch_shown() {
  local value=${1#"$SWITCH_VAR"=}
  [ -n "$1" ] || { printf 'absent, so off'; return; }
  case ${value,,} in
    true | on | yes | 1) printf '%s, so on' "$1" ;;
    *) printf '%s, so off' "$1" ;;
  esac
}

# shellcheck disable=SC2329  # the EXIT trap
on_exit_switch() {
  local rc=$?
  trap - EXIT
  if [ "$PHASE" = preflight ]; then
    say ""
    say "REFUSED at \"$STEP\": ${REASON:-an unexpected error, exit code $rc}"
    say "Nothing changed: .env untouched, api not restarted."
    [ -n "$RUN_DIR" ] && set_status refused
  elif [ "$PHASE" != complete ]; then
    say ""
    say "FAILED at \"$STEP\": ${REASON:-an unexpected error, exit code $rc}"
    say "$SWITCH_VAR in .env was already changed to $NEW_LINE before this failed. Look at it by hand, or switch back"
    say "with, on the server, alone in its block:"
    say "  cd $REPO_DIR && deploy/deploy.sh switch $OTHER"
    say "Never switch back on its own: it does not do that for you."
    set_status failed
  fi
  [ "$rc" -ne 0 ] || [ "$PHASE" = complete ] || rc=1
  stop_log
  exit "$rc"
}

# cmd_switch on|off: changes FAMILY_LEDGERS_ENABLED (D-25) in .env and restarts api alone (F7). Preflight is verify's
# (the role, health, Flyway and the stage aren't read here, since nothing about the code or schema changes): HEAD must
# be the last good deploy, healthy, with the latest run finished, rolled back or adopted, never mid-flight. The pages
# (F7b, D-41): a failure refuses "switch on"; "switch off" reports it and goes on, since the way back must always work.
cmd_switch() {
  local direction=$1 want old_line env_file backup_file head api_now web_now latest status api_log
  case $direction in
    on) want=true OTHER=off ;;
    off) want=false OTHER=on ;;
    *) usage ;;
  esac
  NEW_LINE="$SWITCH_VAR=$want"
  PHASE=preflight
  trap on_exit_switch EXIT
  take_lock exclusive
  cd "$REPO_DIR" || fail "no clone in $REPO_DIR"
  head=$(git rev-parse HEAD)
  # Found before start_log makes this run's own folder, the newest otherwise (as cmd_adopt finds its latest run).
  latest=$(find "$STATE_DIR/runs" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort | tail -n 1 || true)
  start_log "switch-$direction"
  print_settings

  heading "1.1 Preflight: the tools and the terminal"
  check_tools git docker flock install diff
  exec {TTY_FD}<"$TTY" || fail "can't read $TTY: run this in a terminal on the server"

  heading "1.2 Preflight: the role"
  require_checks_role

  heading "1.3 Preflight: health"
  wait_healthy || fail "api and web not both healthy"

  heading "1.4 Preflight: the clone, the last good deploy, no unfinished run"
  [ -z "$(git status --porcelain --untracked-files=no)" ] || fail "the clone has changes to tracked files (git status)"
  api_now=$(container_image finance-tracker-api) || fail "no container finance-tracker-api: the stack isn't up"
  web_now=$(container_image finance-tracker-web) || fail "no container finance-tracker-web: the stack isn't up"
  read_last_good || fail "no record of a last good deploy: deploy at least once first (deploy/deploy.sh run)"
  [ "$LG_COMMIT" = "$head" ] && [ "$LG_API" = "$api_now" ] && [ "$LG_WEB" = "$web_now" ] \
    || fail "HEAD ${head:0:7} with the running images is not the last good deploy (${LG_COMMIT:0:7}): deploy or roll back to it first"
  [ -n "$latest" ] || fail "no run recorded in $STATE_DIR/runs: deploy and finish at least once first"
  status=$(cat "$latest/status" 2>/dev/null || echo 'without a status')
  case $status in
    finished | rolled-back | adopted | switch-on | switch-off) ;;
    *) fail "the latest run, ${latest##*/}, is $status, not finished: run deploy.sh finish first, or resolve the stop" ;;
  esac
  say "HEAD: $(git log -1 --format='%h %s' HEAD), the last good deploy ($status), api and web healthy"

  heading "1.5 Preflight: the current switch"
  env_file=$APP_DIR/.env
  [ -f "$env_file" ] || fail "$env_file is missing (the runbook's step 5)"
  old_line=$(switch_line_of "$env_file")
  say "Current: $SWITCH_VAR is $(switch_shown "$old_line")"
  [ "$old_line" != "$NEW_LINE" ] || fail "$SWITCH_VAR is already $want: nothing to switch"
  say "Will become: $NEW_LINE"

  heading "1.6 Preflight: the numbers before"
  git show "HEAD:deploy/checks/numbers.sql" >"$RUN_DIR/numbers.sql" 2>/dev/null \
    || fail "HEAD has no deploy/checks/numbers.sql"
  sql_file "$RUN_DIR/numbers.sql" >"$RUN_DIR/numbers-before.txt" || fail "numbers.sql failed"
  if grep -Eqv '^[a-z_]+=[0-9]+$' "$RUN_DIR/numbers-before.txt"; then
    fail "numbers.sql printed something else than key=count lines"
  fi
  [ -s "$RUN_DIR/numbers-before.txt" ] || fail "numbers.sql printed nothing"
  cat "$RUN_DIR/numbers-before.txt"

  heading "1.7 Preflight: the pages"
  if ! page_checks; then
    [ "$direction" = off ] \
      || fail "a page isn't as expected ($PAGES_LINE): family budgets aren't switched on while a page of the list fails (D-41)"
    say "WARNING: a page isn't as expected; switching off goes on all the same, since the way back must always work (D-41)"
  fi

  heading "2. Confirmation"
  say "This changes $SWITCH_VAR in $env_file from $(switch_shown "$old_line") to $NEW_LINE, and restarts api alone."
  say "The family endpoints will answer $([ "$direction" = on ] && echo '200 to their members' || echo '404') once it is up."
  ask "Type SWITCH ${direction^^} to go on; anything else stops here with nothing changed: "
  [ "$ANSWER" = "SWITCH ${direction^^}" ] || fail "not confirmed"

  PHASE=switching
  set_status switching

  heading "3.1 Change .env"
  backup_file="$env_file.$(date -u +%Y%m%dT%H%M%SZ)"
  install -m 600 "$env_file" "$backup_file"
  say "Copied $env_file to $backup_file (mode 600)"
  awk -v key="$SWITCH_VAR" -v val="$want" '
    BEGIN { done = 0 }
    $0 ~ "^" key "=" { print key "=" val; done = 1; next }
    { print }
    END { if (!done) print key "=" val }
  ' "$env_file" >"$env_file.tmp"
  chmod 600 "$env_file.tmp"
  if ! diff -q <(grep -vF "$SWITCH_VAR=" "$env_file") <(grep -vF "$SWITCH_VAR=" "$env_file.tmp") >/dev/null; then
    rm -f "$env_file.tmp"
    fail "something besides $SWITCH_VAR would change in .env; refusing, to protect its secrets"
  fi
  mv -f "$env_file.tmp" "$env_file"
  say "Changed, limited to that line:"
  say "  - ${old_line:-($SWITCH_VAR absent)}"
  say "  + $NEW_LINE"

  heading "3.2 Restart api"
  (cd "$APP_DIR" && docker compose up -d --no-deps api)

  heading "3.3 Health"
  wait_healthy || fail "api and web not both healthy after $HEALTH_TRIES checks"

  heading "3.4 The D-25 line"
  switch_expected
  say "Expected: \"$SWITCH_LINE\" ($SWITCH_HOW)"
  [ "$SWITCH_HOW" = "$NEW_LINE, so $direction" ] \
    || fail "the api's container reads the switch as $SWITCH_HOW, not $NEW_LINE: docker compose didn't pick it up"
  api_log=$(api_log_since_start)
  SWITCH_SEEN=$(log_line "$api_log" "$SWITCH_PATTERN")
  [ -n "$SWITCH_SEEN" ] || fail "the api's log since its start has no D-25 line"
  say "Logged: $SWITCH_SEEN"
  case $SWITCH_SEEN in
    "\"$SWITCH_LINE\" at "*) say "As production now sets it ($SWITCH_HOW)" ;;
    *) fail "the D-25 line isn't \"$SWITCH_LINE\" ($SWITCH_HOW)" ;;
  esac

  heading "3.5 Postflight: the numbers before and after"
  sql_file "$RUN_DIR/numbers.sql" >"$RUN_DIR/numbers-after.txt" || fail "numbers.sql failed"
  if ! diff "$RUN_DIR/numbers-before.txt" "$RUN_DIR/numbers-after.txt" >"$RUN_DIR/numbers.diff"; then
    cat "$RUN_DIR/numbers.diff"
    fail "the numbers differ from before the switch ($RUN_DIR/numbers.diff)"
  fi
  say "The same numbers"

  api_now=$(container_image finance-tracker-api)
  web_now=$(container_image finance-tracker-web)
  add_history "switch-$direction" "$head" "$api_now" "$web_now"
  {
    say "Switch $direction on $(date -u +%F) at $(now): $(git log -1 --format='%h (%s)' "$head")"
    say "$SWITCH_VAR: ${old_line:-absent} -> $NEW_LINE"
    say "Images unchanged: api $api_now, web $web_now"
    say "D-25: $SWITCH_SEEN, as production now sets it ($SWITCH_HOW)"
    say "Pages before the switch: $PAGES_LINE"
    say "Numbers: the same before and after"
  } | tr '|' '/' >"$RUN_DIR/summary.txt"
  set_status "switch-$direction"
  PHASE=complete
  heading "4. Summary"
  cat "$RUN_DIR/summary.txt"
  say ""
  say "Add the summary above as a row of \"Deployed revisions\" in deploy/RUNBOOK.md."
  stop_log
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
    adopt) [ $# -eq 1 ] || usage; cmd_adopt ;;
    switch) [ $# -eq 2 ] || usage; cmd_switch "$2" ;;
    *) usage ;;
  esac
}

main "$@"; exit $?
