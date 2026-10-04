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

TOOLS=(git docker flock curl python3 systemctl pg-restore-test install diff paste sha256sum)

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
# file, into BACKUP_<WHEN> (the summary's text), DUMP_<WHEN> (the file's name) and DUMP_SHA_<WHEN> (its SHA-256).
backup() {
  local started line file='' size='' time='' epoch='' sum
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
  [[ $file =~ ^[A-Za-z0-9._-]+$ ]] && [ -f "$BACKUP_DIR/$file" ] || fail "the dump $BACKUP_DIR/$file named by last-success is missing"
  sum=$(sha256sum "$BACKUP_DIR/$file")
  sum=${sum%% *}
  say "Dump $file, $size bytes, written at $time, SHA-256 $sum"
  printf -v "DUMP_$1" '%s' "$file"
  printf -v "DUMP_SHA_$1" '%s' "$sum"
  printf -v "BACKUP_$1" '%s' "dump $file at $time, $size bytes, SHA-256 $sum"
}

# preserve_before_dump: a copy of the before-dump in the run folder, mode 600, with its name, size and SHA-256 in the
# run's meta (D-43). The auth server's pg-backup names a dump by its UTC minute (finance-2026-10-03T0923Z.dump), so a
# second dump in the same minute replaces the first: on 2026-10-03 the after-dump of 09:23:38Z replaced the before-dump
# of 09:23:18Z. After a migration that would lose the only dump from before it, which a rollback below V7 needs
# (D-22). A copy, not a hard link: a dump rewritten in place would change a hard link too.
preserve_before_dump() {
  local copy=$RUN_DIR/$DUMP_before sum size
  cp "$BACKUP_DIR/$DUMP_before" "$copy"
  chmod 600 "$copy"
  sum=$(sha256sum "$copy")
  sum=${sum%% *}
  [ "$sum" = "$DUMP_SHA_before" ] || fail "the copy of the before-dump, $copy, differs from $BACKUP_DIR/$DUMP_before"
  size=$(stat -c %s "$copy")
  printf 'before_dump=%s\nbefore_dump_size=%s\nbefore_dump_sha256=%s\nbefore_dump_copy=%s\n' \
    "$DUMP_before" "$size" "$sum" "$copy" >>"$RUN_DIR/meta"
  PRESERVED_LINE="$copy (mode 600, $size bytes, SHA-256 $sum)"
  say "The before-dump preserved as $PRESERVED_LINE"
}

# wait_for_another_minute: before the after-dump, waits while a dump written now would get the before-dump's name
# (pg-backup's finance-<UTC minute>.dump), so that the after-dump never replaces it (D-43).
wait_for_another_minute() {
  local said=0
  while [ "finance-$(date -u +%Y-%m-%dT%H%MZ).dump" = "$DUMP_before" ]; do
    if [ "$said" = 0 ]; then
      say "Waiting for the next minute: a dump written now would be named $DUMP_before, the before-dump's name (D-43)"
      said=1
    fi
    sleep 1
  done
}

# check_dumps: the after-dump has another name than the before-dump, and the preserved copy is as recorded (D-43).
check_dumps() {
  local sum
  [ "$DUMP_after" != "$DUMP_before" ] \
    || fail "the after-dump has the before-dump's name, $DUMP_before, and replaced it in $BACKUP_DIR; the before-dump is preserved as $RUN_DIR/$DUMP_before"
  sum=$(sha256sum "$RUN_DIR/$DUMP_before")
  sum=${sum%% *}
  [ "$sum" = "$DUMP_SHA_before" ] \
    || fail "the preserved before-dump $RUN_DIR/$DUMP_before changed: SHA-256 $sum, recorded $DUMP_SHA_before"
  say "The dumps: before $DUMP_before, after $DUMP_after; the preserved copy as recorded (SHA-256 $sum)"
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

# image_with_content REPO CONTENT ID...: the first of the images (IDs or tags) that exists with that content, or
# nothing. In the containerd store a running container's own manifest list may be gone (OPS-2, defect 4), while a tag
# still names the same content under another ID.
image_with_content() {
  local repo=$1 want=$2 ref
  shift 2
  for ref in "$@" "finance-tracker-$repo:$LG_COMMIT" "finance-tracker-$repo:previous" "finance-tracker-$repo:latest"; do
    if [ "$(content_of_image "$ref" || true)" = "$want" ]; then
      printf '%s' "$ref"
      return 0
    fi
  done
}

# keep_images: the running revision's images under a tag naming its commit and under :previous, if they are the last
# good deploy's content (OPS-2: by content, not ID); a failed run's images never take those tags.
keep_images() {
  local repo src content kept=() missing=()
  if [ "$RUNNING_IS_LG" = yes ]; then
    for repo in api web; do
      content=$LG_API_CONTENT
      [ "$repo" = api ] || content=$LG_WEB_CONTENT
      if [ "$repo" = api ]; then
        src=$(image_with_content api "$content" "$LG_API" "$RUN_API")
      else
        src=$(image_with_content web "$content" "$LG_WEB" "$RUN_WEB")
      fi
      if [ -z "$src" ]; then
        say "WARNING: no image of finance-tracker-$repo with the last good deploy's content ($content) is left to tag"
        missing+=("$repo")
        continue
      fi
      # The runbook's "docker tag finance-tracker-api finance-tracker-api:previous", by the last good deploy's content.
      docker tag "$src" "finance-tracker-$repo:$LG_COMMIT"
      docker tag "$src" "finance-tracker-$repo:previous"
      kept+=("$repo $src")
    done
    if [ ${#missing[@]} -eq 0 ]; then
      printf '%s\n' "$LG_COMMIT" >"$PREVIOUS_FILE"
      say "The running images, the last good deploy's content, kept as :$LG_COMMIT and :previous; $PREVIOUS_FILE names it"
      KEPT_LINE="the last good deploy's (api $LG_API_CONTENT, web $LG_WEB_CONTENT by content) kept as :${LG_COMMIT:0:7} and :previous"
    else
      say "Not every image of the last good deploy could be kept: $PREVIOUS_FILE stays as it is"
      KEPT_LINE="the last good deploy's images could not all be kept (missing: ${missing[*]}); $PREVIOUS_FILE unchanged"
    fi
  else
    say "The running images ($RUN_API, $RUN_WEB) are not the last good deploy's content: a failed run's, or unknown."
    say "They take no tag; the tags of the last good deploy ${LG_COMMIT:0:7} and :previous stay as they are."
    KEPT_LINE="the running images were not the last good deploy's content and took no tag; the last good deploy's stayed under :${LG_COMMIT:0:7} and :previous"
  fi
  for repo in api web; do
    content=$LG_API_CONTENT
    [ "$repo" = api ] || content=$LG_WEB_CONTENT
    if [ -z "$content" ] || [ "$(content_of_image "finance-tracker-$repo:$LG_COMMIT" || true)" != "$content" ]; then
      say "WARNING: finance-tracker-$repo:$LG_COMMIT is missing or not the recorded content: rollback.sh can't go back to it"
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
    say "Before: ${BACKUP_before:-backup not reached}; restore test ${RESTORE_before:-not reached}; preserved as ${PRESERVED_LINE:-not reached}"
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
  running_images || fail "no container finance-tracker-api or finance-tracker-web: the stack isn't up"
  api_now=$RUN_API web_now=$RUN_WEB
  say "HEAD: $(git log -1 --format='%h %s' HEAD)"
  say "Running images: api $api_now, web $web_now"
  # OPS-2, defect 4: by content, which the image ID doesn't tell in the containerd image store.
  say "Image store: ${IMAGE_STORE:-unknown}, platform ${PLATFORM:-unknown}; content: api ${RUN_API_CONTENT:-unknown}, web ${RUN_WEB_CONTENT:-unknown}"
  [ -n "$RUN_API_CONTENT" ] && [ -n "$RUN_WEB_CONTENT" ] \
    || fail "the running containers' content identity can't be read (image store ${IMAGE_STORE:-unknown}, platform ${PLATFORM:-unknown}): look at docker info and docker version, then run this again"
  RUNNING_IS_LG=no
  if read_last_good; then
    say "Last good deploy: $(git log -1 --format='%h %s' "$LG_COMMIT" 2>/dev/null || echo "$LG_COMMIT") at $LG_TIME ($LG_SOURCE)"
    say "Its status, its newest line in the history: $(commit_status "$LG_COMMIT")"
    PREVIOUS_LINE="$(git log -1 --format='%h (%s)' "$LG_COMMIT"), the last good deploy"
    if compare_identity api "$LG_API" "$LG_API_CONTENT" "$api_now" "$RUN_API_CONTENT" \
      && compare_identity web "$LG_WEB" "$LG_WEB_CONTENT" "$web_now" "$RUN_WEB_CONTENT"; then
      RUNNING_IS_LG=yes
      say "The running images are the last good deploy's content"
    else
      say "The running images are not the last good deploy's content (a failed run's, or its content unknown): step 3.2 tags none of them"
    fi
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
  # OPS-2, D-42 (decided by the PM): a run of the running commit after its clean finish deploys nothing, and 4510003's
  # re-pointed :previous and the previous-commit file to that commit itself ("Commits: none", 2026-10-03 and
  # 2026-10-04). Refused here, before CI, any backup and any image. A run again after a stop or a finish that didn't
  # pass keeps :previous and that file as they are (step 3.2).
  RERUN=no
  if [ "$SHA" = "$HEAD_BEFORE" ]; then
    if clean_finish "$SHA"; then
      fail "${SHA:0:7} runs already, deployed and finished cleanly ($(commit_status "$SHA"), finish ${LG_FINISH:-by $LG_SOURCE}): nothing to deploy (D-42). To check it: deploy/deploy.sh verify <stage>; to switch the family budget: deploy/deploy.sh switch on|off"
    fi
    say "${SHA:0:7} runs already, without a clean finish ($(commit_status "$SHA" || true), finish ${LG_FINISH:-none}): a run again (D-42)"
    [ "$LG_COMMIT" != "$SHA" ] || RERUN=yes
  fi
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
  preserve_before_dump

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
    write_last_good "$HEAD_BEFORE" "$api_now" "$web_now" "$RUN_API_CONTENT" "$RUN_WEB_CONTENT" baseline
    add_history baseline "$HEAD_BEFORE" "$api_now" "$web_now"
    read_last_good
    RUNNING_IS_LG=yes
    say "Recorded HEAD ${HEAD_BEFORE:0:7} with its running images as the last good deploy (baseline)."
  fi

  heading "3.1 Deploy: git merge --ff-only ${SHA:0:7}"
  # Under umask 022 (deploy/common.sh, git_tree): the files it writes are 644, whatever this script's umask.
  git_tree merge --ff-only "$SHA"

  heading "3.2 Deploy: keep the running images"
  if [ "$RERUN" = yes ]; then
    # The last good deploy is this commit itself: tagging its images as :previous would make the rollback target the
    # commit being deployed (D-42).
    say "A run again of ${SHA:0:7}, the last good deploy itself: :previous and $PREVIOUS_FILE stay as they are (D-42)"
    KEPT_LINE="a run again of the last good deploy itself: no tag, :previous and $PREVIOUS_FILE as they were (D-42)"
  else
    keep_images
  fi

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
  wait_for_another_minute
  backup after
  restore_test after
  check_dumps

  running_images || fail "a container is gone"
  api_now=$RUN_API web_now=$RUN_WEB
  [ -n "$RUN_API_CONTENT" ] && [ -n "$RUN_WEB_CONTENT" ] || say "WARNING: a running image's content identity is unknown; last-good leaves it out"
  write_last_good "$SHA" "$api_now" "$web_now" "$RUN_API_CONTENT" "$RUN_WEB_CONTENT" deploy
  add_history good "$SHA" "$api_now" "$web_now"
  IMAGES_LINE="$(identity api "$api_now" "$RUN_API_CONTENT"), $(identity web "$web_now" "$RUN_WEB_CONTENT") (recorded in last-good); before the build, $KEPT_LINE"
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
    fail "the latest run that isn't refused, $dir, is $status, not deployed: nothing to finish; deploy/deploy.sh verify <stage> checks what runs"
  done < <(find "$STATE_DIR/runs" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -r)
  fail "no deployed run in $STATE_DIR/runs: nothing to finish; deploy/deploy.sh verify <stage> checks what runs"
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
  running_images || fail "a container is gone"
  api=$RUN_API web=$RUN_WEB
  say "Images: $(identity api "$api" "$RUN_API_CONTENT"), $(identity web "$web" "$RUN_WEB_CONTENT")"
  read_last_good || true
  if [ ${#problems[@]} -gt 0 ]; then
    set_status finish-failed
    write_last_good "$SHA" "$api" "$web" "$RUN_API_CONTENT" "$RUN_WEB_CONTENT" "${LG_SOURCE:-deploy}" "not passed $(now)"
    add_history finish-failed "$SHA" "$api" "$web"
    say ""
    say "NOT PASSED: ${problems[*]}. Recorded as finish-failed; nothing was rolled back."
    say "If the cause is fixed, or an answer was wrong, run deploy/deploy.sh finish again: the latest answers count."
    print_rollback_command "$SHA"
    exit "$NOT_PASSED"
  fi
  set_status finished
  write_last_good "$SHA" "$api" "$web" "$RUN_API_CONTENT" "$RUN_WEB_CONTENT" "${LG_SOURCE:-deploy}" "passed $(now)"
  add_history good "$SHA" "$api" "$web"
  say ""
  say "Done. Add the summary above as a row of \"Deployed revisions\" in deploy/RUNBOOK.md."
}

# ---------------------------------------------------------------------------------------------------------------------
# verify

cmd_verify() {
  local stage=$1 issues=0 api web row expected out api_log seen problem rc
  [[ $stage =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || usage
  READ_ONLY=1
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

  heading "The images"
  # OPS-2, defect 4: by content; another image ID with the recorded content is INFO, never a problem.
  if ! running_images; then
    say "PROBLEM: no container finance-tracker-api or finance-tracker-web"
    issues=$((issues + 1))
  else
    say "Image store: ${IMAGE_STORE:-unknown}, platform ${PLATFORM:-unknown}"
    say "Running: $(identity api "$RUN_API" "$RUN_API_CONTENT"); $(identity web "$RUN_WEB" "$RUN_WEB_CONTENT")"
    if read_last_good; then
      say "Last good deploy: $(git log -1 --format='%h' "$LG_COMMIT" 2>/dev/null || echo "$LG_COMMIT") at $LG_TIME ($LG_SOURCE)"
      rc=0
      compare_identity api "$LG_API" "$LG_API_CONTENT" "$RUN_API" "$RUN_API_CONTENT" || rc=$?
      [ "$rc" -eq 0 ] || { say "PROBLEM: api isn't the last good deploy's content, or its content is unknown"; issues=$((issues + 1)); }
      rc=0
      compare_identity web "$LG_WEB" "$LG_WEB_CONTENT" "$RUN_WEB" "$RUN_WEB_CONTENT" || rc=$?
      [ "$rc" -eq 0 ] || { say "PROBLEM: web isn't the last good deploy's content, or its content is unknown"; issues=$((issues + 1)); }
    else
      say "No record of a last good deploy"
    fi
  fi

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
  running_images || fail "no container finance-tracker-api or finance-tracker-web: the stack isn't up"
  api=$RUN_API web=$RUN_WEB
  say "HEAD: $(git log -1 --format='%h %s' HEAD)"
  say "Running images: api $api ($(health_of finance-tracker-api)), web $web ($(health_of finance-tracker-web))"
  say "Their content: api ${RUN_API_CONTENT:-unknown}, web ${RUN_WEB_CONTENT:-unknown}"
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
  write_last_good "$head" "$api" "$web" "$RUN_API_CONTENT" "$RUN_WEB_CONTENT" adopt
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
    say "$SWITCH_VAR in .env was already changed to $NEW_LINE before this failed. Look at it by hand, then, on the"
    # OPS-2, defect 1: the way is switch off either way, which no stop refuses: back after a failed switch on, again
    # after a failed switch off. A switch on waits until a switch has completed.
    say "server, alone in its block, $([ "$OTHER" = on ] && echo 'run it again' || echo 'switch back') (no stop refuses it):"
    say "  cd $REPO_DIR && deploy/deploy.sh switch off"
    say "Never switch back on its own: it does not do that for you."
    set_status failed
  fi
  [ "$rc" -ne 0 ] || [ "$PHASE" = complete ] || rc=1
  stop_log
  exit "$rc"
}

# image_change NAME ID_BEFORE CONTENT_BEFORE ID_AFTER CONTENT_AFTER: how a container's image changed across a restart:
# "unchanged", "recreated with the same content", "CHANGED", or "content unknown"; never "unchanged" by default.
image_change() {
  local name=$1 b=$2 bc=$3 a=$4 ac=$5
  if [ -z "$bc" ] || [ -z "$ac" ]; then
    printf '%s %s -> %s, content unknown' "$name" "$b" "$a"
  elif [ "$bc" != "$ac" ]; then
    printf '%s CHANGED: %s -> %s' "$name" "$(identity image "$b" "$bc")" "$(identity image "$a" "$ac")"
  elif [ "$b" = "$a" ]; then
    printf '%s unchanged, %s' "$name" "$(identity image "$a" "$ac")"
  else
    printf '%s recreated with the same content: %s -> %s, content %s' "$name" "$b" "$a" "$ac"
  fi
}

# cmd_switch on|off: changes FAMILY_LEDGERS_ENABLED (D-25) in .env and restarts api alone (F7). Preflight is verify's
# (the role, health, Flyway and the stage aren't read here, since nothing about the code or schema changes): HEAD must
# be the last good deploy, healthy, with the latest run finished, rolled back or adopted, never mid-flight. The pages
# (F7b, D-41): a failure refuses "switch on"; "switch off" reports it and goes on, since the way back must always work.
cmd_switch() {
  local direction=$1 want old_line env_file backup_file head api_now web_now latest status api_log rc images_ok
  local api_before web_before api_content_before web_content_before images_line why
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
  # Found before start_log makes this run's own folder: the newest that isn't refused (OPS-2, defect 1).
  latest=$(latest_unrefused_run)
  start_log "switch-$direction"
  print_settings

  heading "1.1 Preflight: the tools and the terminal"
  check_tools git docker flock install diff
  exec {TTY_FD}<"$TTY" || fail "can't read $TTY: run this in a terminal on the server"

  heading "1.2 Preflight: the role"
  require_checks_role

  heading "1.3 Preflight: health"
  if ! wait_healthy; then
    [ "$direction" = off ] || fail "api and web not both healthy"
    say "WARNING: api and web not both healthy; switching off goes on all the same, since the way back must always work"
  fi

  heading "1.4 Preflight: the clone, the last good deploy, no unfinished run"
  [ -z "$(git status --porcelain --untracked-files=no)" ] || fail "the clone has changes to tracked files (git status)"
  running_images || fail "no container finance-tracker-api or finance-tracker-web: the stack isn't up"
  api_before=$RUN_API web_before=$RUN_WEB api_content_before=$RUN_API_CONTENT web_content_before=$RUN_WEB_CONTENT
  say "Image store: ${IMAGE_STORE:-unknown}, platform ${PLATFORM:-unknown}"
  say "Before: $(identity api "$api_before" "$api_content_before"); $(identity web "$web_before" "$web_content_before")"
  if ! read_last_good; then
    [ "$direction" = off ] || fail "no record of a last good deploy: deploy at least once first (deploy/deploy.sh run)"
    say "WARNING: no record of a last good deploy; switching off goes on all the same"
  elif [ "$LG_COMMIT" != "$head" ]; then
    [ "$direction" = off ] \
      || fail "HEAD ${head:0:7} is not the last good deploy (${LG_COMMIT:0:7}): deploy it and finish, or roll back to it, first"
    say "WARNING: HEAD ${head:0:7} is not the last good deploy (${LG_COMMIT:0:7}); switching off goes on all the same"
  fi
  # OPS-2, defect 4: the images by content, not by ID; unknown is never the same. switch off only reports it.
  images_ok=yes
  rc=0
  compare_identity api "$LG_API" "$LG_API_CONTENT" "$api_before" "$api_content_before" || rc=$?
  [ "$rc" -eq 0 ] || images_ok=no
  rc=0
  compare_identity web "$LG_WEB" "$LG_WEB_CONTENT" "$web_before" "$web_content_before" || rc=$?
  [ "$rc" -eq 0 ] || images_ok=no
  if [ "$images_ok" = no ]; then
    [ "$direction" = off ] \
      || fail "the running images are not the last good deploy's content, or their content is unknown: deploy, or roll back, first"
    say "WARNING: the running images are not the last good deploy's content, or it is unknown; switching off goes on all the same, since the way back must always work"
  fi
  # OPS-2, defect 1: only real unfinished work blocks, never a refused run; switch off never refuses for it.
  if [ -z "$latest" ]; then
    why="no run recorded in $STATE_DIR/runs: deploy with deploy/deploy.sh run, then deploy/deploy.sh finish, first"
    status=none
  else
    why=$(unfinished_work "$latest")
    status=$(cat "$latest/status" 2>/dev/null || echo 'without a status')
    say "The latest run that isn't refused: ${latest##*/}, $status"
  fi
  if [ -n "$why" ]; then
    [ "$direction" = off ] || fail "$why"
    say "WARNING: $why; switching off goes on all the same, since the way back must always work"
  fi
  say "HEAD: $(git log -1 --format='%h %s' HEAD), the last good deploy ${LG_COMMIT:0:7} ($status)"

  heading "1.5 Preflight: the current switch"
  env_file=$APP_DIR/.env
  [ -f "$env_file" ] || fail "$env_file is missing (the runbook's step 5)"
  old_line=$(switch_line_of "$env_file")
  say "Current: $SWITCH_VAR is $(switch_shown "$old_line")"
  # OPS-2, defect 1: "nothing to switch" only when the running api reads it too; after a switch stopped between the
  # change of .env and the restart, it restarts api.
  switch_expected
  say "The running api: $SWITCH_HOW"
  if [ "$old_line" = "$NEW_LINE" ]; then
    [ "$SWITCH_HOW" != "$NEW_LINE, so $direction" ] || fail "$SWITCH_VAR is already $want: nothing to switch"
    say ".env already says $NEW_LINE, but the api runs with $SWITCH_HOW: this restarts it"
  fi
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

  heading "3.6 The images before and after"
  # OPS-2, defect 4: "unchanged" only for the same content. A container recreated with the same content (from a
  # :latest built again, under another ID) is the last good deploy still: last-good names its new ID, so that the next
  # comparison by ID matches too.
  running_images || fail "a container is gone after the restart"
  api_now=$RUN_API web_now=$RUN_WEB
  images_line=$(image_change api "$api_before" "$api_content_before" "$api_now" "$RUN_API_CONTENT")
  images_line="$images_line; $(image_change web "$web_before" "$web_content_before" "$web_now" "$RUN_WEB_CONTENT")"
  say "Images: $images_line"
  case $images_line in
    *CHANGED* | *unknown*)
      [ "$direction" = off ] \
        || fail "the restart started other content, or content that can't be read, than ran before: $images_line"
      say "WARNING: other content, or content that can't be read, than ran before; switched off all the same"
      ;;
  esac
  if [ "$images_ok" = yes ] && { [ "$api_now" != "$LG_API" ] || [ "$web_now" != "$LG_WEB" ]; }; then
    write_last_good "$LG_COMMIT" "$api_now" "$web_now" "$RUN_API_CONTENT" "$RUN_WEB_CONTENT" "${LG_SOURCE:-deploy}" "$LG_FINISH"
    say "last-good now names the running images, the same content under new IDs: api $api_now, web $web_now"
    images_line="$images_line (last-good updated to the new IDs)"
  fi
  add_history "switch-$direction" "$head" "$api_now" "$web_now"
  {
    say "Switch $direction on $(date -u +%F) at $(now): $(git log -1 --format='%h (%s)' "$head")"
    say "$SWITCH_VAR: ${old_line:-absent} -> $NEW_LINE"
    say "Images: $images_line"
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
  PHASE='' SHA='' STAGE='' HEAD_BEFORE='' BASE='' LG_COMMIT='' LG_API='' LG_WEB='' LG_FINISH='' LG_SOURCE='' RERUN=no
  DUMP_before='' DUMP_after='' DUMP_SHA_before='' PRESERVED_LINE=''
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
