#!/usr/bin/env bash
# Rolls the app back to the last good deploy before the current one: only for a deploy that failed its checks
# (deploy/RUNBOOK.md, "Roll back with rollback.sh"). Run as root, on the server, from /opt/finance-tracker, in a block
# of its own, never in one with a deploy:
#
#   deploy/rollback.sh <commit>
#
# <commit> must be the last good deploy before the current one, as deploy/deploy.sh recorded it, with its images still
# under finance-tracker-api:<commit> and finance-tracker-web:<commit>. It shows what it will do, and does it only once
# "ROLLBACK <first 7 characters>" is typed at the terminal. Then, as the runbook's "Roll an update back": the clone to
# that commit (detached), its images retagged, api and web started, health checked. The database and
# /etc/pg-backup/finance.conf stay as they are. Going back below V7 while production holds family records is refused
# (D-22): that needs a restore from a dump.
#
# Like deploy.sh, it is functions only and its last line calls main: the checkout replaces this file while it runs.
# The test variables are deploy.sh's (deploy/common.sh).

# shellcheck source=deploy/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

usage() {
  echo "usage: deploy/rollback.sh <commit>   (the last good deploy before the current one, 7 to 40 hex characters)" >&2
  exit 2
}

# shellcheck disable=SC2329  # the EXIT trap
on_exit_rollback() {
  local rc=$?
  trap - EXIT
  if [ "$PHASE" = refusing ]; then
    say ""
    say "REFUSED at \"$STEP\": ${REASON:-an unexpected error, exit code $rc}"
    say "Nothing changed."
    [ -n "$RUN_DIR" ] && set_status refused
  elif [ "$PHASE" != complete ]; then
    say ""
    say "FAILED at \"$STEP\": ${REASON:-an unexpected error, exit code $rc}"
    say "The rollback did not finish: look at it by hand (deploy/RUNBOOK.md, \"Roll an update back\")."
    set_status failed
    [ "$rc" -ne 0 ] || rc=1
  fi
  stop_log
  exit "$rc"
}

cmd_rollback() {
  local arg=$1 head sha api_now web_now id target_max family flyway
  [[ $arg =~ ^[0-9a-f]{7,40}$ ]] || usage
  PHASE=refusing
  trap on_exit_rollback EXIT
  take_lock exclusive
  start_log "rollback-${arg:0:7}"
  print_settings

  heading "1. The tools, the read-only role and the clone"
  check_tools git docker flock
  require_checks_role
  cd "$REPO_DIR" || fail "no clone in $REPO_DIR"
  [ -z "$(git status --porcelain --untracked-files=no)" ] || fail "the clone has changes to tracked files (git status)"
  head=$(git rev-parse HEAD)
  api_now=$(container_image finance-tracker-api) || fail "no container finance-tracker-api"
  web_now=$(container_image finance-tracker-web) || fail "no container finance-tracker-web"
  say "Now: $(git log -1 --format='%h %s' HEAD), api $api_now, web $web_now"

  heading "2. The commit"
  sha=$(git rev-parse --verify --quiet "$arg^{commit}") || fail "$arg is no commit of this clone"
  rollback_target "$head" || fail "no good deploy before $(git rev-parse --short HEAD) is recorded in $STATE_DIR/history"
  [ "$sha" = "$RT_COMMIT" ] \
    || fail "rollback.sh goes only to the last good deploy before the current one, $(git log -1 --format='%h %s' "$RT_COMMIT"), not ${sha:0:7}"
  say "Target: $(git log -1 --format='%h %s' "$sha"), the last good deploy before the current one"

  heading "3. Its images"
  id=$(docker image inspect -f '{{.Id}}' "finance-tracker-api:$sha" 2>/dev/null) || fail "no image finance-tracker-api:$sha"
  [ "$id" = "$RT_API" ] || fail "finance-tracker-api:$sha is $id, not the recorded $RT_API"
  id=$(docker image inspect -f '{{.Id}}' "finance-tracker-web:$sha" 2>/dev/null) || fail "no image finance-tracker-web:$sha"
  [ "$id" = "$RT_WEB" ] || fail "finance-tracker-web:$sha is $id, not the recorded $RT_WEB"
  say "api $RT_API, web $RT_WEB"

  heading "4. The database (D-22)"
  flyway=$(flyway_row) || fail "could not read flyway_schema_history"
  target_max=$(highest_migration "$sha")
  say "Flyway's latest row: $flyway; the target's highest migration: V$target_max"
  if [ "$target_max" -lt 7 ]; then
    # The runbook's check before going back past V7.
    family=$(sql_query "SELECT count(*) FROM app.family_record") || fail "could not count the family records"
    [ "$family" = 0 ] \
      || fail "production holds $family family records, and ${sha:0:7} is below V7: that needs a restore from a dump (deploy/RUNBOOK.md, \"Restore from a backup\")"
    say "No family record: the code before V7 can run on this database."
  fi

  heading "5. Confirmation"
  say "This will: check out ${sha:0:7} (detached), tag finance-tracker-api:$sha and finance-tracker-web:$sha as the"
  say "images to run, start api and web with them, and check their health. The database stays at its version, and"
  say "/etc/pg-backup/finance.conf stays as it is."
  ask "Only for a deploy that failed its checks. Type ROLLBACK ${sha:0:7} to go on; anything else changes nothing: "
  [ "$ANSWER" = "ROLLBACK ${sha:0:7}" ] || fail "not confirmed"

  PHASE=rolling-back
  set_status rolling-back
  heading "6. Roll back"
  git checkout --detach "$sha"
  git log -1 --oneline
  docker tag "finance-tracker-api:$sha" finance-tracker-api
  docker tag "finance-tracker-web:$sha" finance-tracker-web
  (cd "$APP_DIR" && docker compose up -d --no-deps api web)
  add_history rolled-back-from "$head" "$api_now" "$web_now"
  add_history rollback-to "$sha" "$RT_API" "$RT_WEB"
  write_last_good "$sha" "$RT_API" "$RT_WEB" rollback

  heading "7. Health"
  wait_healthy || fail "api and web not both healthy after $HEALTH_TRIES checks"
  (cd "$APP_DIR" && docker compose ps --format 'table {{.Name}}\t{{.Status}}')

  {
    say "Rollback on $(date -u +%F) at $(now): from $(git log -1 --format='%h (%s)' "$head") to $(git log -1 --format='%h (%s)' "$sha")"
    say "Images: api $RT_API, web $RT_WEB; api and web healthy"
    say "Database untouched: Flyway's latest row $flyway. finance.conf untouched."
  } | tr '|' '/' >"$RUN_DIR/summary.txt"
  set_status rolled-back
  PHASE=complete
  heading "8. Summary"
  cat "$RUN_DIR/summary.txt"
  say ""
  say "The clone is detached at ${sha:0:7}. To go forward again: git checkout main, then deploy/deploy.sh run."
  stop_log
}

main() {
  set -euo pipefail
  shopt -s inherit_errexit
  umask 077
  common_settings
  PHASE=''
  [ $# -eq 1 ] || usage
  cmd_rollback "$1"
}

main "$@"; exit $?
