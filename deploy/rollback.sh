#!/usr/bin/env bash
# Rolls the app back one step, to the commit that the current one's deploy replaced: only for a deploy that failed its
# checks
# (deploy/RUNBOOK.md, "Roll back with rollback.sh"). Run as root, on the server, from /opt/finance-tracker, in a block
# of its own, never in one with a deploy:
#
#   deploy/rollback.sh <commit>
#
# <commit> must be the commit that HEAD's deploy replaced, as that run of deploy/deploy.sh recorded it, with its images
# still under finance-tracker-api:<commit> and finance-tracker-web:<commit> (F6c, decided by the PM: one step back; a
# deploy on top of a commit means the owner accepted it, so its finish's answers don't matter here). It shows what it will do, and does it only once
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
  echo "usage: deploy/rollback.sh <commit>   (the commit HEAD's deploy replaced, 7 to 40 hex characters)" >&2
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

# preserved_dump_of RUN: the dump taken before that deploy, as the run preserved it in its folder (D-43), or why none.
preserved_dump_of() {
  local copy sum
  copy=$(sed -n 's/^before_dump_copy=//p' "$1/meta" 2>/dev/null || true)
  sum=$(sed -n 's/^before_dump_sha256=//p' "$1/meta" 2>/dev/null || true)
  if [ -n "$copy" ] && [ -f "$copy" ]; then
    printf 'the dump taken before HEAD'"'"'s deploy is preserved as %s (SHA-256 %s)' "$copy" "$sum"
  elif [ -n "$copy" ]; then
    printf 'HEAD'"'"'s deploy preserved its before-dump as %s, which is gone: look in %s' "$copy" "$BACKUP_DIR"
  else
    printf 'HEAD'"'"'s deploy (%s) preserved no dump (its script predates D-43): look in %s for the one written before it' \
      "$(basename "$1")" "$BACKUP_DIR"
  fi
}

# target_image REPO COMMIT RECORDED_ID: TARGET_ID, the ID of finance-tracker-REPO:COMMIT, when it is the recorded
# image, by ID or by content; stops the rollback (fail) otherwise.
target_image() {
  local repo=$1 sha=$2 recorded=$3 id content recorded_content
  id=$(docker image inspect -f '{{.Id}}' "finance-tracker-$repo:$sha" 2>/dev/null) || fail "no image finance-tracker-$repo:$sha"
  TARGET_ID=$id
  [ "$id" != "$recorded" ] || return 0
  content=$(content_of_image "finance-tracker-$repo:$sha") || content=''
  recorded_content=$(content_of_id "$recorded") || recorded_content=''
  [ -n "$content" ] && [ -n "$recorded_content" ] \
    || fail "finance-tracker-$repo:$sha is $id, not the recorded $recorded, and the content of one of them can't be read"
  [ "$content" = "$recorded_content" ] \
    || fail "finance-tracker-$repo:$sha is $id (content $content), not the recorded $recorded (content $recorded_content)"
  say "INFO finance-tracker-$repo:$sha is $id, another ID with the recorded content $content (recorded $recorded)"
}

cmd_rollback() {
  local arg=$1 head sha api_now web_now target_max family flyway problem tag_api tag_web
  [[ $arg =~ ^[0-9a-f]{7,40}$ ]] || usage
  PHASE=refusing
  trap on_exit_rollback EXIT
  take_lock exclusive
  start_log "rollback-${arg:0:7}"
  print_settings

  heading "1. The tools, the read-only role and the clone"
  check_tools git docker flock curl
  # Without the read-only role this script can't check the database (D-22): the way back is then the runbook's manual
  # one (F6c).
  problem=$(role_problem)
  if [ -n "$problem" ]; then
    role_command
    fail "$problem; without it, roll back by hand: deploy/RUNBOOK.md, \"Roll an update back\""
  fi
  say "The checks run as $CHECKS_ROLE: $ROLE_OK"
  cd "$REPO_DIR" || fail "no clone in $REPO_DIR"
  [ -z "$(git status --porcelain --untracked-files=no)" ] || fail "the clone has changes to tracked files (git status)"
  head=$(git rev-parse HEAD)
  api_now=$(container_image finance-tracker-api) || fail "no container finance-tracker-api"
  web_now=$(container_image finance-tracker-web) || fail "no container finance-tracker-web"
  say "Now: $(git log -1 --format='%h %s' HEAD), api $api_now, web $web_now"

  heading "2. The commit"
  sha=$(git rev-parse --verify --quiet "$arg^{commit}") || fail "$arg is no commit of this clone"
  rollback_target "$head" \
    || fail "no good deploy before $(git rev-parse --short HEAD): no run of deploy.sh that brought it names the commit it replaced with its images (deploy/RUNBOOK.md, \"Roll an update back\", by hand)"
  [ "$sha" = "$RT_COMMIT" ] \
    || fail "rollback.sh goes only one step back, to the commit HEAD's deploy replaced, $(git log -1 --format='%h %s' "$RT_COMMIT"), not ${sha:0:7}"
  say "Target: $(git log -1 --format='%h %s' "$sha"), the commit HEAD's deploy replaced ($(basename "$DR_DIR")); its status, its newest line in the history: $(commit_status "$sha")"

  heading "3. Its images"
  # OPS-2, defect 4: the tag's image is the recorded one by ID, or by content (another ID with the same content, as
  # the containerd image store gives at each build); a content that can't be read is never taken as the same.
  target_image api "$sha" "$RT_API"
  tag_api=$TARGET_ID
  target_image web "$sha" "$RT_WEB"
  tag_web=$TARGET_ID
  say "api $tag_api, web $tag_web"

  heading "4. The database (D-22)"
  flyway=$(flyway_row) || fail "could not read flyway_schema_history"
  target_max=$(highest_migration "$sha")
  say "Flyway's latest row: $flyway; the target's highest migration: V$target_max"
  if [ "$target_max" -lt 7 ]; then
    # The runbook's check before going back past V7.
    family=$(sql_query "SELECT count(*) FROM app.family_record") || fail "could not count the family records"
    [ "$family" = 0 ] \
      || fail "production holds $family family records, and ${sha:0:7} is below V7: that needs a restore from a dump (deploy/RUNBOOK.md, \"Restore from a backup\"); $(preserved_dump_of "$DR_DIR")"
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
  # Under umask 022 (deploy/common.sh, git_tree): the files it writes are 644, whatever this script's umask.
  git_tree checkout --detach "$sha"
  git log -1 --oneline
  docker tag "finance-tracker-api:$sha" finance-tracker-api
  docker tag "finance-tracker-web:$sha" finance-tracker-web
  (cd "$APP_DIR" && docker compose up -d --no-deps api web)
  add_history rolled-back-from "$head" "$api_now" "$web_now"
  add_history rollback-to "$sha" "$tag_api" "$tag_web"
  write_last_good "$sha" "$tag_api" "$tag_web" "$(content_of_image "$tag_api" || true)" "$(content_of_image "$tag_web" || true)" rollback

  heading "7. Health"
  wait_healthy || fail "api and web not both healthy after $HEALTH_TRIES checks"
  (cd "$APP_DIR" && docker compose ps --format 'table {{.Name}}\t{{.Status}}')

  heading "8. The pages"
  # Reported, never a reason to stop: the rollback is done, and the commit gone back to may have the page's fault (F7's
  # web image answers /privacy with 403).
  page_checks || say "WARNING: a page isn't as expected after the rollback: $PAGES_LINE"

  {
    say "Rollback on $(date -u +%F) at $(now): from $(git log -1 --format='%h (%s)' "$head") to $(git log -1 --format='%h (%s)' "$sha")"
    say "Images: api $RT_API, web $RT_WEB; api and web healthy"
    say "Database untouched: Flyway's latest row $flyway. finance.conf untouched."
    say "Pages: $PAGES_LINE"
  } | tr '|' '/' >"$RUN_DIR/summary.txt"
  set_status rolled-back
  PHASE=complete
  heading "9. Summary"
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
  PHASE='' TARGET_ID=''
  [ $# -eq 1 ] || usage
  cmd_rollback "$1"
}

main "$@"; exit $?
