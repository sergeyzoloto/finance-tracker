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
#   deploy/deploy.sh acceptance on|off       changes FAMILY_ACCEPTANCE_ENABLED (HIST-3) in .env and restarts api alone,
#                                           as switch does; reads deploy/checks/acceptance.sql as finance_checks first:
#                                           "on" needs the family switch on, every counter at its head and nothing
#                                           pending; "off" refuses while anything is pending, or that can't be read.
#   deploy/deploy.sh prune-cache            the build cache's bound by hand (OPS-5, D-940): what `run` does after its
#                                           pages, as its own command, since a run executes the script the clone had
#                                           before its merge; build cache only, never an image, container, volume or network
#
#   deploy/deploy.sh db-image <commit>      the app database's image, a new minor release of the same major, by digest
#                                           (OPS-6, D-939, D-945 to D-947): the commit changes the postgres service's
#                                           image line and nothing that is deployed; before the downtime the image is
#                                           pulled and a fresh backup restored; then postgres alone is recreated on its
#                                           volume. It never rolls back: a failure prints the way back, written out.
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
# DEPLOY_CI_INTERVAL, DEPLOY_HEALTH_TRIES, DEPLOY_HEALTH_INTERVAL, DEPLOY_SHUTDOWN_FILE and DEPLOY_SHUTDOWN_NOW (the
# shutdown check's "now", D-131). Every run prints the paths it uses, and that "now" when it is set.

# shellcheck source=deploy/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

usage() {
  cat >&2 <<'EOF'
usage: deploy/deploy.sh run <commit> <stage>
       deploy/deploy.sh finish
       deploy/deploy.sh verify <stage>
       deploy/deploy.sh adopt
       deploy/deploy.sh switch on|off
       deploy/deploy.sh acceptance on|off
       deploy/deploy.sh prune-cache
       deploy/deploy.sh db-image <commit>
<commit> is origin/main's commit, 7 to 40 hex characters; <stage> names deploy/checks/<stage>.sql and .expected.
EOF
  exit 2
}

POSTGRES_PIN_RE='^postgres:([0-9]+)\.([0-9]+)@sha256:[0-9a-f]{64}$'

TOOLS=(git docker flock curl python3 systemctl pg-restore-test install diff paste sha256sum timeout df)

# The check runs of CI's own runs of a commit (ci_check_runs), read by python3 (the server has no jq). Prints one line
# per check run, then "NAMES <names>" and "VERDICT success|pending|failure|none".
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

# The check suites of the runs of .github/workflows/ci.yml for a commit, one id per line, from GitHub's list of that
# workflow's runs; fails when the list doesn't hold every run.
CI_SUITES_PY='
import json, sys
d = json.load(sys.stdin)
runs = d.get("workflow_runs") or []
if d.get("total_count", len(runs)) != len(runs):
    sys.exit("%s runs of ci.yml, more than one page holds" % d.get("total_count"))
for r in runs:
    if r.get("path") != ".github/workflows/ci.yml":
        sys.exit("a run of another workflow: %s" % r.get("path"))
    print(int(r["check_suite_id"]))
'

# The check runs of those suites, one answer each, joined into one in the shape of a commit's check runs.
CI_JOIN_PY='
import json, sys
text, decoder, i, runs, total = sys.stdin.read(), json.JSONDecoder(), 0, [], 0
while True:
    while i < len(text) and text[i].isspace():
        i += 1
    if i == len(text):
        break
    d, i = decoder.raw_decode(text, i)
    runs += d.get("check_runs") or []
    total += d.get("total_count", len(d.get("check_runs") or []))
print(json.dumps({"total_count": total, "check_runs": runs}))
'

# github PATH: one answer of GitHub's API for this repository.
github() {
  curl -fsS --max-time 30 -H 'Accept: application/vnd.github+json' -H 'X-GitHub-Api-Version: 2022-11-28' "$GITHUB_API/$1"
}

# ci_check_runs: the check runs of SHA's runs of the workflow CI (.github/workflows/ci.yml) and of no other (D-276,
# OPS-3): the monitor's scheduled runs (.github/workflows/monitor.yml) attach to main's head, the deployed commit,
# every 15 minutes, and "CI on Ubuntu 26.04"'s to the commit it is started on; neither is CI's verdict on the commit.
# Up to OPS-3 the gate read every check run of the commit (commits/SHA/check-runs), which those runs would have failed,
# kept pending or pushed past one page.
ci_check_runs() {
  local suites id
  suites=$(github "actions/workflows/ci.yml/runs?head_sha=$SHA&per_page=100" | python3 -c "$CI_SUITES_PY") || return 1
  for id in $suites; do
    github "check-suites/$id/check-runs?per_page=100" || return 1
  done | python3 -c "$CI_JOIN_PY"
}

# ci_check: every check run of CI's runs of SHA completed with success; waits up to CI_WAIT seconds while some are
# queued or in progress. The F6a checklist read GitHub's API on the laptop; this reads the check runs of CI's runs.
ci_check() {
  local deadline=$((SECONDS + CI_WAIT)) out verdict
  while :; do
    out=$(ci_check_runs | python3 -c "$CI_PY") \
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

# compose_extensions REV: every top-level extension block (x-…) of deploy/app/docker-compose.yml at REV, such as
# x-logging, whose anchor the postgres service merges in (logging: *logging): a change there changes the postgres
# service as much as one in its own block (OPS-3: the logging driver). Only compared, never run.
compose_extensions() {
  git show "$1:deploy/app/docker-compose.yml" | awk '
    /^x-/ { inside = 1; print; next }
    inside && /^[^ \t#]/ { inside = 0 }
    inside { print }'
}

# postgres_image_of REV: the postgres service's image line in deploy/app/docker-compose.yml at REV, such as
# postgres:17.12@sha256:…, or nothing (OPS-6, D-945). One line exactly, else nothing.
postgres_image_of() {
  local lines
  lines=$(compose_part "$1" '  postgres:' | sed -nE 's/^    image: ([^[:space:]]+)[[:space:]]*$/\1/p')
  [ -n "$lines" ] && [ "$(printf '%s\n' "$lines" | wc -l)" -eq 1 ] || return 0
  printf '%s' "$lines"
}

# pg_cli_version TEXT: "17.12" from what `postgres --version` prints, as run on 2026-11-17 for 17.11 and 17.12:
# "postgres (PostgreSQL) 17.12 (Debian 17.12-1.pgdg13+1)"; nothing for any other text (OPS-6b, D-963: the first version of
# this check expected the SELECT version() form and refused the real image).
pg_cli_version() {
  local re='^postgres \(PostgreSQL\) ([0-9]+)\.([0-9]+)( |$)'
  [[ $1 =~ $re ]] || return 0
  printf '%s.%s' "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}"
}

# pg_sql_version TEXT: "17.12" from what `SELECT version()` prints, "PostgreSQL 17.12 (Debian 17.12-1.pgdg13+1) on
# x86_64-pc-linux-gnu, compiled by gcc …"; nothing for any other text.
pg_sql_version() {
  local re='^PostgreSQL ([0-9]+)\.([0-9]+)( |$)'
  [[ $1 =~ $re ]] || return 0
  printf '%s.%s' "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}"
}

# postgres_block_without_image REV: the postgres service's block at REV with its image line blanked, so that two
# revisions compare equal when only that line differs (OPS-6, D-945).
postgres_block_without_image() {
  compose_part "$1" '  postgres:' | sed -E 's|^(    image: ).*|\1<pin>|'
}

# postgres_pin_applied BASE SHA: the only change of the postgres service that run accepts: nothing differs but the image
# line, and SHA's image is exactly the image the running container was created from (docker's .Config.Image), which is
# what deploy.sh db-image leaves behind (OPS-6, D-945). Prints what it found; returns 1 otherwise.
postgres_pin_applied() {
  local pin running
  [ "$(postgres_block_without_image "$1")" = "$(postgres_block_without_image "$2")" ] || return 1
  pin=$(postgres_image_of "$2")
  [ -n "$pin" ] || return 1
  running=$(docker inspect -f '{{.Config.Image}}' "$PG_CONTAINER" 2>/dev/null) || return 1
  [ "$running" = "$pin" ] || return 1
  say "The postgres image line differs from ${1:0:7}'s, and it is the one the running container already has: $pin (applied by deploy.sh db-image, D-945)"
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

# restore_test WHEN [soft]: pg-restore-test on the dump just written; PASS required. Its table is kept in the run
# folder. With "soft" a failure returns 1 instead of ending the run (restore_test_after's retry, D-1073).
restore_test() {
  local out=$RUN_DIR/restore-test-$1.txt rc=0 checks
  pg-restore-test finance </dev/null >"$out" 2>&1 || rc=$?
  cat "$out"
  if ! { [ "$rc" -eq 0 ] && [ "$(tail -n 1 "$out")" = PASS ]; }; then
    [ "${2:-}" = soft ] && return 1
    fail "the restore test did not PASS ($out)"
  fi
  checks=$(awk '$1 == "tables" || $1 == "migration" || $1 ~ /^family_/ { printf "%s%s %s", sep, $1, $2; sep = ", " }' "$out")
  printf -v "RESTORE_$1" '%s' "PASS ($checks)"
}

# restore_test_after: step 4.6's restore test, with one retry (D-1073). The api's history baseline job (HIST-1, V19)
# writes versions for a minute or so after the api starts; a dump taken while it runs restores with other counts than
# the production database it is compared with, and the test FAILs although nothing is wrong (HIST-1's deploy:
# record_versions 20,815 against 11,992). So the first FAIL is not final: after RESTORE_RETRY_WAIT seconds
# (the baseline of 20,815 versions took 16.8 s) a fresh dump is taken and tested once more; only a second FAIL fails
# the run. The first attempt's table stays as restore-test-after-first.txt. Not used by the pre-flight's tests or by
# db-image, where nothing runs in the background.
restore_test_after() {
  if restore_test after soft; then
    say "Restore test: PASS at the first attempt"
    return 0
  fi
  cp "$RUN_DIR/restore-test-after.txt" "$RUN_DIR/restore-test-after-first.txt"
  say "Restore test FAILED at the first attempt (kept as $RUN_DIR/restore-test-after-first.txt). The api's history baseline may have run during the dump: waiting ${RESTORE_RETRY_WAIT}s, then a fresh dump and one more attempt (D-1073)"
  sleep "$RESTORE_RETRY_WAIT"
  wait_for_another_minute
  backup after
  restore_test after soft && { say "Restore test: PASS at the second attempt; the first failure was not the database's (kept for the record)"; return 0; }
  fail "the restore test did not PASS, at either attempt ($RUN_DIR/restore-test-after-first.txt, $RUN_DIR/restore-test-after.txt)"
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
      KEPT_LINE="the last good deploy's (api $LG_API_CONTENT, web $LG_WEB_CONTENT by content) kept as :$LG_COMMIT and :previous"
    else
      say "Not every image of the last good deploy could be kept: $PREVIOUS_FILE stays as it is"
      KEPT_LINE="the last good deploy's images could not all be kept (missing: ${missing[*]}); $PREVIOUS_FILE unchanged"
    fi
  else
    say "The running images ($RUN_API, $RUN_WEB) are not the last good deploy's content: a failed run's, or unknown."
    say "They take no tag; the tags of the last good deploy, :$LG_COMMIT and :previous, stay as they are."
    KEPT_LINE="the running images were not the last good deploy's content and took no tag; the last good deploy's stayed under :$LG_COMMIT and :previous"
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

# The build cache's bound (OPS-5, D-940): `docker builder prune` leaves what a build used within BUILD_CACHE_AGE alone and
# keeps BUILD_CACHE_KEEP of the rest (BuildKit's reserved space), so the cache that deploys leave behind can't grow
# without bound (13.63 GB on 2026-11-16). It touches build cache only: never an image, container, volume or network.
BUILD_CACHE_AGE=72h
BUILD_CACHE_KEEP=3GB

# prune_build_cache: best effort, after the deploy's health and pages passed. A failure or a timeout warns and never
# fails the deploy, which is over by then; it is never called when the deploy failed (fail() ends the run first).
prune_build_cache() {
  local out
  BUILD_CACHE_RC=0
  say "Disk before: $(df -h / | tail -n 1 | tr -s ' ')"
  out=$(timeout 300 docker builder prune -f --filter "until=$BUILD_CACHE_AGE" --reserved-space "$BUILD_CACHE_KEEP" 2>&1) || BUILD_CACHE_RC=$?
  if [ "$BUILD_CACHE_RC" -eq 0 ]; then
    BUILD_CACHE_LINE="pruned (used more than $BUILD_CACHE_AGE ago, $BUILD_CACHE_KEEP kept): $(printf '%s\n' "$out" | tail -n 1)"
    say "Build cache: $BUILD_CACHE_LINE"
  else
    BUILD_CACHE_LINE="not pruned, exit $BUILD_CACHE_RC ($(one_line "$(printf '%s\n' "$out" | tail -n 3)"))"
    say "WARNING: the build cache wasn't pruned: $BUILD_CACHE_LINE; the deploy goes on"
  fi
  say "Disk after: $(df -h / | tail -n 1 | tr -s ' ')"
}

# cmd_prune_cache: the same bound by hand (D-940). `run` executes the deploy.sh the clone had before its merge, so the
# deploy that brings step 3.7 doesn't run it; this command does, once the clone holds it. Exits 1 when the prune failed.
cmd_prune_cache() {
  READ_ONLY=0
  take_lock exclusive
  say "prune-cache at $(now): the build cache only, never an image, container, volume or network"
  check_tools docker flock timeout df
  prune_build_cache
  [ "$BUILD_CACHE_RC" -eq 0 ] || fail "the build cache wasn't pruned: $BUILD_CACHE_LINE"
  say "prune-cache: done"
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
    say "Build cache: ${BUILD_CACHE_LINE:-not reached}"
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

# preflight_shutdown: D-126, D-131: a deploy stops for a scheduled shutdown less than 2 hours away, such as
# unattended-upgrades' reboot at 04:00 UTC (D-125), for one whose time has passed, and for a file it can't parse; one
# further away is named and the deploy goes on, since unattended-upgrades schedules the reboot about 22 hours ahead.
# Used by run and db-image (OPS-6).
preflight_shutdown() {
  local shutdown_rc=0
  shutdown_scheduled || shutdown_rc=$?
  say "$SHUTDOWN_LINE"
  case $shutdown_rc in
    0)
      if [ "$SHUTDOWN_IN" -lt 0 ]; then
        fail "the scheduled shutdown's time has passed, yet it is still scheduled (D-131): cat $SHUTDOWN_FILE and tell the PM"
      elif [ "$SHUTDOWN_IN" -lt "$SHUTDOWN_MARGIN" ]; then
        fail "a shutdown is scheduled less than 2 hours from now (D-131): deploy after it, or cancel it (shutdown -c) if the PM agrees"
      fi
      say "The scheduled shutdown is at least 2 hours away: the deploy goes on (D-131)"
      ;;
    2) fail "$SHUTDOWN_FILE can't be parsed, so a scheduled shutdown can't be ruled out (D-126): cat it" ;;
  esac
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
  preflight_shutdown

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
  say "$STORE_LINE; content: api ${RUN_API_CONTENT:-unknown}, web ${RUN_WEB_CONTENT:-unknown}"
  [ -n "$RUN_API_CONTENT" ] && [ -n "$RUN_WEB_CONTENT" ] \
    || fail "the running containers' content identity can't be read ($STORE_LINE): look at docker info and docker version, then run this again"
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
  if [ "$out" != "$(compose_part "$SHA" '  postgres:')" ]; then
    # D-945: the one change of the block that run accepts is its image line, and only to the image the running container
    # has already (deploy.sh db-image applied it): the next run after db-image needs no flag.
    postgres_pin_applied "$BASE" "$SHA" \
      || fail "the postgres service changes (its block, other than the image line the running container already has): that stays manual; a new image is applied by deploy.sh db-image"
  fi
  if [ "$(compose_extensions "$BASE")" != "$(compose_extensions "$SHA")" ] \
    || [ "$(compose_part "$BASE" volumes:)" != "$(compose_part "$SHA" volumes:)" ] \
    || ! git diff --quiet "$BASE" "$SHA" -- deploy/app/postgres-init.sh; then
    fail "the postgres service changes (the extension blocks it may merge in, the volumes or postgres-init.sh): that stays manual"
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

  heading "3.7 Deploy: the build cache"
  prune_build_cache

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
  restore_test_after
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
# db-image (OPS-6, D-939, D-945 to D-947)

# never_deployed PATH: a path whose change reaches nothing that runs in production: documents, tests, CI, the laptop's
# scripts, the local compose files and the stage checks. "Deployed" is what an image is built from (backend/ for the api,
# frontend/ for the web), what the deploy scripts run or install (deploy/*.sh, deploy/finance.caddy, deploy/pg-backup/,
# deploy/checks/numbers.sql, which run reads from the clone) and the stack's own folder deploy/app/. The one rule that
# decides what a db-image commit may touch outside deploy/app/ (D-945); deploy/tests/run.sh has a case for each side.
never_deployed() {
  case $1 in
    deploy/checks/numbers.sql) return 1 ;;
    docs/* | e2e/* | scripts/* | .github/* | deploy/tests/* | deploy/checks/* | deploy/RUNBOOK.md) return 0 ;;
    docker-compose.yml | docker-compose.local.yml | docker-compose.dev.yml | change_log.mdx) return 0 ;;
    */*) return 1 ;;
    *.md) return 0 ;;
  esac
  return 1
}

now_ms() { date +%s%3N; }

# secs MS: milliseconds as seconds with three decimals.
secs() { printf '%d.%03d' $((${1} / 1000)) $((${1} % 1000)); }

# shellcheck disable=SC2329  # called from the EXIT trap
# The way back from a db-image that failed after its merge, written out in full (D-946: nothing here runs it; D-947: the
# old image stays on this host). The compose file of the commit before the merge pins the old image, so checking that
# commit out and recreating postgres alone, on the same volume, puts it back.
print_db_image_rollback() {
  [ -n "$HEAD_BEFORE" ] || return 0
  say ""
  say "Nothing was rolled back (D-946). Judge first (the log above), then, only if the database must go back to"
  say "${OLD_PIN:-the old image}, on the server, alone in its block, line by line (the old image is still on this host, D-947;"
  say "the data directory is the same: a minor release's files are read by both, as the rehearsal showed):"
  say "  cd $REPO_DIR && git checkout --detach $HEAD_BEFORE"
  say "  cd $APP_DIR && docker compose up -d --no-deps postgres"
  say "  docker inspect -f '{{.Config.Image}}' $PG_CONTAINER"
  say "  cd $APP_DIR && docker compose exec -T postgres psql -X -At -U $CHECKS_ROLE -d finance -c \"SELECT version()\" </dev/null"
  say "  cd $REPO_DIR && deploy/deploy.sh verify OPS-6"
  say "The clone then stays detached at ${HEAD_BEFORE:0:7} and main at ${SHA:0:7}: tell the PM before anything else runs"
  say "(deploy.sh run refuses a main whose postgres image line the running container doesn't have). The last resort is"
  say "deploy/RUNBOOK.md's \"Restore from a backup\", from the before-dump preserved in $RUN_DIR."
}

# write_db_image_summary OUTCOME: plain text without a pipe character, for the runbook's "Deployed revisions".
write_db_image_summary() {
  {
    say "Database image of ${SHA:0:7} (db-image): $1"
    say "Date: $(date -u +%F); run folder $RUN_DIR"
    say "Previous commit: ${PREVIOUS_LINE:-not reached}"
    say "New commit: $(git log -1 --format='%h (%s)' "$SHA" 2>/dev/null || echo "${SHA:-unknown}")"
    say "Image: ${OLD_PIN:-not reached} to ${NEW_PIN:-not reached}"
    say "Pulled: ${PULLED_LINE:-not reached}"
    say "Files changed: ${DEPLOY_FILES_LINE:-not reached}"
    say "CI: ${CI_LINE:-not reached}"
    say "Indexes to REINDEX: ${INDEXES_LINE:-not reached}"
    say "Before: ${BACKUP_before:-backup not reached}; restore test ${RESTORE_before:-not reached}; preserved as ${PRESERVED_LINE:-not reached}"
    say "Downtime: ${DOWNTIME_LINE:-not reached}"
    say "Server: ${VERSION_LINE:-not reached}"
    say "Postgres log: ${PGLOG_LINE:-not reached}"
    say "Flyway: ${FLYWAY_LINE:-not reached}"
    say "Numbers: ${NUMBERS_LINE:-not reached}"
    say "After: ${BACKUP_after:-backup not reached}; restore test ${RESTORE_after:-not reached}"
    say "Images: ${IMAGES_LINE:-not reached}"
    say "Pages: ${PAGES_LINE:-not reached}"
    say "Browser checks: not yet (deploy/deploy.sh finish)"
    say "Smoke test: not yet (deploy/deploy.sh finish)"
  } | tr '|' '/' >"$RUN_DIR/summary.txt"
}

# shellcheck disable=SC2329  # the EXIT trap
on_exit_db_image() {
  local rc=$?
  trap - EXIT
  if [ "$PHASE" = complete ]; then
    :
  elif [ "$PHASE" = preflight ]; then
    say ""
    say "REFUSED at \"$STEP\": ${REASON:-an unexpected error, exit code $rc}"
    say "Nothing changed: no merge, no container stopped or recreated. (The new image may have been pulled: it runs"
    say "nothing, and the old one stays, D-947.)"
    [ -n "$RUN_DIR" ] && set_status refused
  else
    say ""
    say "FAILED at \"$STEP\": ${REASON:-an unexpected error, exit code $rc}"
    say "The site may still work, but this upgrade isn't good: don't run deploy.sh finish. Send this output (the run"
    say "folder's log holds it) to the developer."
    set_status failed
    write_db_image_summary "FAILED at \"$STEP\": ${REASON:-exit code $rc}"
    say "The run's summary, as far as it got: $RUN_DIR/summary.txt"
    print_db_image_rollback
  fi
  [ "$rc" -ne 0 ] || [ "$PHASE" = complete ] || rc=1
  stop_log
  exit "$rc"
}

# db_image_probe: waits for postgres to accept connections and for the api's health to answer UP again, probing every
# PROBE_INTERVAL seconds from now (T0 is the moment the recreate began); sets PG_MS and API_MS (milliseconds after T0),
# API_FAILED and API_PROBES (the api's probes that answered something else than UP). The api reconnects by itself (its
# connection pool drops the dead connections and opens new ones), which the rehearsal showed (OPS-6): this only
# measures it and fails when it doesn't within PROBE_TRIES probes, never restarting the api (D-946).
db_image_probe() {
  local i t
  PG_MS='' API_MS='' API_FAILED=0 API_PROBES=0
  for ((i = 1; i <= PROBE_TRIES; i++)); do
    t=$(($(now_ms) - T0))
    if [ -z "$PG_MS" ] && docker exec "$PG_CONTAINER" pg_isready -h 127.0.0.1 -U finance -d finance -q >/dev/null 2>&1; then
      PG_MS=$t
    fi
    API_PROBES=$((API_PROBES + 1))
    if docker exec finance-tracker-api curl -fsS --max-time 3 -o /dev/null http://localhost:8080/actuator/health >/dev/null 2>&1; then
      if [ -n "$PG_MS" ]; then
        API_MS=$t
        return 0
      fi
    else
      API_FAILED=$((API_FAILED + 1))
    fi
    sleep "$PROBE_INTERVAL"
  done
  return 1
}

cmd_db_image() {
  local arg=$1 out row version expected f i old_major old_minor new_major new_minor running latest why log bad
  local -a changed=() outside=()
  [[ $arg =~ ^[0-9a-f]{7,40}$ ]] || usage
  STAGE=db-image
  PHASE=preflight
  OLD_PIN='' NEW_PIN='' PULLED_LINE='' INDEXES_LINE='' DOWNTIME_LINE='' VERSION_LINE='' PGLOG_LINE='' IMAGES_LINE=''
  trap on_exit_db_image EXIT
  take_lock exclusive
  # The newest earlier run, read before this run's own folder exists (which has no status yet).
  latest=$(latest_unrefused_run)
  start_log "${arg:0:7}-db-image"
  print_settings

  heading "1.1 Preflight: the tools, the terminal and a scheduled shutdown"
  check_tools git docker flock curl python3 systemctl pg-restore-test diff paste sha256sum
  exec {TTY_FD}<"$TTY" || fail "can't read $TTY: run deploy.sh in a terminal on the server"
  preflight_shutdown
  say "Nothing else guards the windows of the 02:30Z dumps, Hetzner's backup (about 10:49Z) and the reboot window 03:30Z to"
  say "04:30Z: the checklist names them (D-945)."

  heading "1.2 Preflight: the clone at the last good deploy, the stack running and healthy"
  cd "$REPO_DIR" || fail "no clone in $REPO_DIR"
  [ "$(git symbolic-ref --quiet --short HEAD || true)" = main ] \
    || fail "the clone isn't on main (after a rollback it is detached): git checkout main, then run this again"
  [ -z "$(git status --porcelain --untracked-files=no)" ] || fail "the clone has changes to tracked files (git status)"
  [ -f "$APP_DIR/.env" ] || fail "$APP_DIR/.env is missing (the runbook's step 5)"
  HEAD_BEFORE=$(git rev-parse HEAD)
  running_images || fail "no container finance-tracker-api or finance-tracker-web: the stack isn't up"
  say "HEAD: $(git log -1 --format='%h %s' HEAD)"
  say "$STORE_LINE; content: api ${RUN_API_CONTENT:-unknown}, web ${RUN_WEB_CONTENT:-unknown}"
  [ -n "$RUN_API_CONTENT" ] && [ -n "$RUN_WEB_CONTENT" ] \
    || fail "the running containers' content identity can't be read ($STORE_LINE)"
  read_last_good || fail "no record of a last good deploy: deploy.sh run (or adopt) first"
  [ "$LG_COMMIT" = "$HEAD_BEFORE" ] \
    || fail "HEAD ($(git rev-parse --short HEAD)) isn't the last good deploy (${LG_COMMIT:0:7}): db-image starts from it, nothing else"
  clean_finish "$HEAD_BEFORE" || fail "the last good deploy ${HEAD_BEFORE:0:7} has no clean finish (finish ${LG_FINISH:-none}, status $(commit_status "$HEAD_BEFORE")): deploy.sh finish first"
  if ! compare_identity api "$LG_API" "$LG_API_CONTENT" "$RUN_API" "$RUN_API_CONTENT" \
    || ! compare_identity web "$LG_WEB" "$LG_WEB_CONTENT" "$RUN_WEB" "$RUN_WEB_CONTENT"; then
    fail "the running images aren't the last good deploy's content"
  fi
  say "Last good deploy ${HEAD_BEFORE:0:7} at $LG_TIME ($LG_SOURCE), finish ${LG_FINISH:-by $LG_SOURCE}; the running images are its content"
  PREVIOUS_LINE="$(git log -1 --format='%h (%s)' "$HEAD_BEFORE"), the last good deploy"
  if [ -n "$latest" ]; then
    why=$(unfinished_work "$latest")
    [ -z "$why" ] || fail "$why"
  fi
  for f in "$PG_CONTAINER" finance-tracker-api finance-tracker-web; do
    [ "$(health_of "$f")" = healthy ] || fail "$f isn't healthy"
  done
  say "postgres, api and web healthy"
  require_checks_role

  heading "1.3 Preflight: git fetch, the commit and what it changes"
  git fetch origin
  SHA=$(git rev-parse --verify --quiet "$arg^{commit}") \
    || fail "$arg is no commit of this clone after git fetch: push it from the laptop first, then run this again"
  [ "$SHA" = "$(git rev-parse --verify origin/main)" ] \
    || fail "$arg is not origin/main ($(git log -1 --format='%h %s' origin/main))"
  [ "$SHA" != "$HEAD_BEFORE" ] || fail "${SHA:0:7} is the commit that runs: nothing to apply"
  git merge-base --is-ancestor HEAD "$SHA" || fail "$arg is not a fast-forward of HEAD ($(git rev-parse --short HEAD))"
  say "Commits (git log --oneline HEAD..${SHA:0:7}):"
  git log --oneline "HEAD..$SHA"
  DEPLOY_FILES_LINE=$(git diff --name-only --no-renames "$HEAD_BEFORE" "$SHA" | paste -sd ' ' | sed 's/ /, /g')
  say "Files changed: $DEPLOY_FILES_LINE"
  # In deploy/app/ exactly the stack's compose file, and in it exactly the postgres service's image line.
  [ "$(git diff --name-only --no-renames "$HEAD_BEFORE" "$SHA" -- deploy/app/)" = deploy/app/docker-compose.yml ] \
    || fail "in deploy/app/ only docker-compose.yml may change, and in it only the postgres service's image line (a volume, an environment variable, a command, a healthcheck or another service stays manual)"
  OLD_PIN=$(postgres_image_of "$HEAD_BEFORE")
  NEW_PIN=$(postgres_image_of "$SHA")
  [[ $OLD_PIN =~ $POSTGRES_PIN_RE ]] || fail "the postgres image line at ${HEAD_BEFORE:0:7} isn't postgres:<major>.<minor>@sha256:<digest>: '$OLD_PIN'"
  old_major=${BASH_REMATCH[1]} old_minor=${BASH_REMATCH[2]}
  [[ $NEW_PIN =~ $POSTGRES_PIN_RE ]] || fail "the postgres image line at ${SHA:0:7} isn't postgres:<major>.<minor>@sha256:<digest> (pinned by version and digest): '$NEW_PIN'"
  new_major=${BASH_REMATCH[1]} new_minor=${BASH_REMATCH[2]}
  [ "$new_major" = "$old_major" ] \
    || fail "a new major version ($old_major to $new_major) needs a dump and a restore, not db-image"
  [ "$new_minor" -gt "$old_minor" ] || fail "the new pin $NEW_PIN isn't a newer minor release than $OLD_PIN"
  [ "$(git show "$HEAD_BEFORE:deploy/app/docker-compose.yml" | sed -E 's|^(    image: postgres:)[^[:space:]]+|\1<pin>|')" \
    = "$(git show "$SHA:deploy/app/docker-compose.yml" | sed -E 's|^(    image: postgres:)[^[:space:]]+|\1<pin>|')" ] \
    || fail "more than the postgres service's image line changes in deploy/app/docker-compose.yml"
  while IFS= read -r f; do
    [ -n "$f" ] || continue
    changed+=("$f")
    [ "$f" = deploy/app/docker-compose.yml ] || never_deployed "$f" || outside+=("$f")
  done < <(git diff --name-only --no-renames "$HEAD_BEFORE" "$SHA")
  [ ${#outside[@]} -eq 0 ] \
    || fail "db-image changes the database image and nothing that is deployed: outside deploy/app/ it allows documents, tests, CI, scripts, the local compose files, deploy/checks (not numbers.sql) and deploy/RUNBOOK.md; these are deployed or built: ${outside[*]}"
  say "Only deploy/app/docker-compose.yml's postgres image line changes among the deployed files: $OLD_PIN to $NEW_PIN; api and web would be neither rebuilt nor recreated"
  running=$(docker inspect -f '{{.Config.Image}}' "$PG_CONTAINER") || fail "can't inspect $PG_CONTAINER"
  [ "$running" = "$OLD_PIN" ] || fail "$PG_CONTAINER was created from $running, not from $OLD_PIN, the pin at ${HEAD_BEFORE:0:7}"
  # D-947: the old image is the way back and stays; it must be here.
  docker image inspect -f '{{.Id}}' "$OLD_PIN" >/dev/null 2>&1 || docker image inspect -f '{{.Id}}' "${OLD_PIN%@*}" >/dev/null 2>&1 \
    || fail "the old image $OLD_PIN isn't on this host: the way back needs it (D-947)"
  say "The old image $OLD_PIN is on this host and stays (D-947)"

  heading "1.4 Preflight: CI for ${SHA:0:7}"
  ci_check # CI passed on the commit (D-945)

  heading "1.5 Preflight: the new image, pulled by digest, and the disk"
  say "Disk before: $(df -h / | tail -n 1 | tr -s ' ')"
  docker pull "$NEW_PIN"
  out=$(docker run --rm --network none --entrypoint postgres "$NEW_PIN" --version) || fail "the pulled image doesn't run: docker run --rm --entrypoint postgres $NEW_PIN --version"
  say "$out"
  [ "$(pg_cli_version "$out")" = "$new_major.$new_minor" ] || fail "the pulled image says '$out', not PostgreSQL $new_major.$new_minor"
  PULLED_LINE="$NEW_PIN, $out"
  say "Disk after: $(df -h / | tail -n 1 | tr -s ' ')"

  heading "1.6 Preflight: the indexes that the release notes advise to REINDEX (none expected)"
  [ -f deploy/checks/db-image-indexes.sql ] || fail "deploy/checks/db-image-indexes.sql is missing from the clone"
  out=$(sql_file deploy/checks/db-image-indexes.sql) || fail "deploy/checks/db-image-indexes.sql failed"
  if [ -n "$out" ]; then
    printf '%s\n' "$out"
    INDEXES_LINE=$(printf '%s\n' "$out" | paste -sd ';' | sed 's/;/; /g')
    fail "the release notes advise REINDEX for the indexes above (index|method|why): decide with the PM before the upgrade; db-image goes on only when none is listed"
  fi
  INDEXES_LINE="none"
  say "No such index"

  heading "1.7 Preflight: the numbers before"
  git show "HEAD:deploy/checks/numbers.sql" >"$RUN_DIR/numbers.sql" 2>/dev/null || fail "HEAD has no deploy/checks/numbers.sql"
  sql_file "$RUN_DIR/numbers.sql" >"$RUN_DIR/numbers-before.txt" || fail "numbers.sql failed"
  if grep -Eqv '^[a-z_]+=[0-9]+$' "$RUN_DIR/numbers-before.txt"; then fail "numbers.sql printed something else than key=count lines"; fi
  [ -s "$RUN_DIR/numbers-before.txt" ] || fail "numbers.sql printed nothing"
  cat "$RUN_DIR/numbers-before.txt"
  printf 'kind=db-image\ncommit=%s\nstage=db-image\nhead_before=%s\nbase=%s\n' "$SHA" "$HEAD_BEFORE" "$HEAD_BEFORE" >"$RUN_DIR/meta"

  heading "1.8 Preflight: a fresh backup, then the restore test"
  backup before
  restore_test before
  preserve_before_dump

  heading "2. Confirmation"
  say "Upgrade the database from $OLD_PIN to $NEW_PIN, ${SHA:0:7} ($(git log -1 --format=%s "$SHA")), over ${HEAD_BEFORE:0:7}."
  say "Only the postgres container is recreated, on the same volume; the api and the web are not touched. The database is"
  say "away for about a second, and the rehearsal's api answered one or two requests with an error meanwhile."
  ask "Type the first 7 characters of the commit to upgrade it; anything else stops here with nothing changed: "
  [ "$ANSWER" = "${SHA:0:7}" ] || fail "not confirmed (db-image)"

  PHASE=deploy
  set_status deploying

  heading "3.1 Deploy: git merge --ff-only ${SHA:0:7}"
  git_tree merge --ff-only "$SHA"

  heading "3.2 Deploy: recreate postgres alone (docker compose up -d --no-deps postgres, never down, the volume untouched)"
  API_STARTED=$(docker inspect -f '{{.State.StartedAt}}' finance-tracker-api)
  T0=$(now_ms)
  (cd "$APP_DIR" && docker compose up -d --no-deps postgres)
  db_image_probe || fail "postgres accepted connections at ${PG_MS:-never} ms and the api's health answered UP at ${API_MS:-never} ms: not both within $PROBE_TRIES probes of $PROBE_INTERVAL s (the api did not reconnect by itself; it was not restarted, D-946)"
  DOWNTIME_LINE="postgres accepted connections $(secs "$PG_MS") s after the recreate began; the api's health answered UP again $(secs "$API_MS") s after it ($API_FAILED of $API_PROBES probes answered something else)"
  say "Downtime: $DOWNTIME_LINE"

  heading "3.3 Deploy: health"
  for ((i = 1; i <= HEALTH_TRIES; i++)); do
    [ "$(health_of "$PG_CONTAINER")" = healthy ] && [ "$(health_of finance-tracker-api)" = healthy ] \
      && [ "$(health_of finance-tracker-web)" = healthy ] && break
    [ "$i" -lt "$HEALTH_TRIES" ] && sleep "$HEALTH_INTERVAL"
  done
  [ "$(health_of "$PG_CONTAINER")" = healthy ] && [ "$(health_of finance-tracker-api)" = healthy ] \
    && [ "$(health_of finance-tracker-web)" = healthy ] \
    || fail "postgres, api and web not all healthy after $HEALTH_TRIES checks: postgres $(health_of "$PG_CONTAINER"), api $(health_of finance-tracker-api), web $(health_of finance-tracker-web)"
  (cd "$APP_DIR" && docker compose ps --format 'table {{.Name}}\t{{.Status}}')
  [ "$(docker inspect -f '{{.Config.Image}}' "$PG_CONTAINER")" = "$NEW_PIN" ] \
    || fail "$PG_CONTAINER was not created from $NEW_PIN"
  if [ "$(docker inspect -f '{{.State.StartedAt}}' finance-tracker-api)" = "$API_STARTED" ]; then
    say "The api was not restarted: it reconnected by itself (started $API_STARTED)"
  else
    say "WARNING: the api container restarted during the upgrade (started $API_STARTED before)"
  fi

  heading "3.4 Deploy: the server's version"
  out=$(sql_query "SELECT version()") || fail "SELECT version() failed"
  say "$out"
  [ "$(pg_sql_version "$out")" = "$new_major.$new_minor" ] || fail "the server says '${out%% on *}', not PostgreSQL $new_major.$new_minor"
  VERSION_LINE=${out%% on *}

  heading "3.5 Deploy: postgres's log since its start"
  log=$(cd "$APP_DIR" && docker compose logs --no-log-prefix --timestamps --since "$(docker inspect -f '{{.State.StartedAt}}' "$PG_CONTAINER")" postgres)
  printf '%s\n' "$log" | grep -E 'ready to accept connections|ERROR|FATAL|PANIC' || true
  # Probes that arrive while the server starts up are answered with this FATAL: it is the database refusing, not failing.
  bad=$(printf '%s\n' "$log" | grep -E '(ERROR|FATAL|PANIC)' | grep -vF 'FATAL:  the database system is starting up' || true)
  if [ -n "$bad" ]; then
    printf '%s\n' "$bad"
    fail "postgres's log since its start has ERROR, FATAL or PANIC lines other than \"the database system is starting up\" (above)"
  fi
  PGLOG_LINE="no ERROR, FATAL or PANIC line but $(printf '%s\n' "$log" | grep -cF 'FATAL:  the database system is starting up' || true) of \"the database system is starting up\""
  say "Postgres log: $PGLOG_LINE"

  PHASE=postflight
  heading "4.1 Postflight: Flyway"
  expected=$(highest_migration "$SHA")
  row=$(flyway_row) || fail "could not read flyway_schema_history"
  version=${row%% *}
  say "Latest row: $row; the highest migration in ${SHA:0:7}: V$expected"
  [ "${row##* }" = true ] && [ "$version" = "$expected" ] || fail "Flyway isn't at V$expected with success: $row"
  FLYWAY_LINE="version $version ($row), up to date; the api was not restarted, so its log has no new Flyway line"

  heading "4.2 Postflight: the pages"
  page_checks || fail "a page isn't as expected after the upgrade: $PAGES_LINE"

  heading "4.3 Postflight: the numbers after"
  sql_file "$RUN_DIR/numbers.sql" >"$RUN_DIR/numbers-after.txt" || fail "numbers.sql failed"
  if ! diff "$RUN_DIR/numbers-before.txt" "$RUN_DIR/numbers-after.txt" >"$RUN_DIR/numbers.diff"; then
    cat "$RUN_DIR/numbers.diff"
    fail "the numbers differ from preflight's ($RUN_DIR/numbers.diff); a user's entry during the upgrade shows up here too, so judge before going back"
  fi
  NUMBERS_LINE="the same before and after ($(paste -sd ' ' "$RUN_DIR/numbers-after.txt" | sed 's/ /, /g'))"
  say "The same numbers"

  heading "4.4 Postflight: a fresh backup, then the restore test (restored by $NEW_PIN)"
  wait_for_another_minute # D-43: the after-dump never takes the before-dump's name
  backup after
  restore_test after
  check_dumps

  running_images || fail "a container is gone"
  write_last_good "$SHA" "$RUN_API" "$RUN_WEB" "$RUN_API_CONTENT" "$RUN_WEB_CONTENT" db-image
  add_history good "$SHA" "$RUN_API" "$RUN_WEB"
  IMAGES_LINE="$(identity api "$RUN_API" "$RUN_API_CONTENT"), $(identity web "$RUN_WEB" "$RUN_WEB_CONTENT"), unchanged (recorded in last-good)"
  set_status deployed
  write_db_image_summary "deployed"
  PHASE=complete
  heading "5. Summary"
  cat "$RUN_DIR/summary.txt"
  say ""
  say "Left for you: the end-to-end suite of the checklist, then: deploy/deploy.sh finish"
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
  shutdown_scheduled || true
  say "$SHUTDOWN_LINE"

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
  acceptance_state "$(api_log_since_start || true)"
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

# numbers_vs_last_run NUMBERS (OPS-5, D-941): informational only, never a problem and never a stop. Compares the numbers
# `verify` just read with those of the newest run folder that has a numbers-after.txt (a deploy's or a switch's
# postflight), as data: each line must be key=count, any other is ignored, nothing is sourced. A user's own use of the
# app changes the numbers, so a difference is no fault; the line says which keys and from what to what.
numbers_vs_last_run() {
  local dir='' file='' line key val diffs=0 name
  local -A was=() now=()
  while IFS= read -r name; do
    if [ -f "$name/numbers-after.txt" ]; then dir=$name file=$name/numbers-after.txt; break; fi
  done < <(find "$STATE_DIR/runs" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -r)
  if [ -z "$file" ]; then
    say "Compared with the last run: no earlier run left numbers to compare with (informational)"
    return 0
  fi
  while IFS= read -r line; do
    if [[ $line =~ ^([a-z_]+)=([0-9]+)$ ]]; then was[${BASH_REMATCH[1]}]=${BASH_REMATCH[2]}; fi
  done <"$file"
  while IFS= read -r line; do
    if [[ $line =~ ^([a-z_]+)=([0-9]+)$ ]]; then now[${BASH_REMATCH[1]}]=${BASH_REMATCH[2]}; fi
  done <<<"$1"
  while IFS= read -r key; do
    [ -n "$key" ] || continue
    val=${now[$key]:-absent}
    if [ "${was[$key]:-absent}" != "$val" ]; then
      [ "$diffs" -gt 0 ] || say "Compared with the numbers after $(basename "$dir") (informational, never a problem: the users' own use changes them):"
      say "  $key: was ${was[$key]:-absent}, now $val"
      diffs=$((diffs + 1))
    fi
  done < <(printf '%s\n' "${!was[@]}" "${!now[@]}" | sort -u)
  if [ "$diffs" -eq 0 ]; then
    say "Compared with the numbers after $(basename "$dir"): same as the last run"
  fi
}

cmd_verify() {
  local stage=$1 issues=0 api web row expected out api_log seen problem rc
  [[ $stage =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || usage
  READ_ONLY=1
  take_lock shared
  cd "$REPO_DIR" || fail "no clone in $REPO_DIR"
  say "verify $stage at $(git log -1 --format='%h %s' HEAD), $(now); read only"
  check_tools git docker flock diff curl
  shutdown_scheduled || true
  say "$SHUTDOWN_LINE"

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
    say "$STORE_LINE"
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

  heading "The database image"
  say "$PG_CONTAINER was created from: $(docker inspect -f '{{.Config.Image}}' "$PG_CONTAINER" 2>/dev/null || echo 'unknown (no such container)')"
  if [ -z "$problem" ]; then
    if out=$(sql_query "SELECT version()"); then
      say "Server: ${out%% on *}"
    else
      say "PROBLEM: SELECT version() failed"
      issues=$((issues + 1))
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
  acceptance_state "$api_log"

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
    if out=$(sql_file deploy/checks/numbers.sql); then
      printf '%s\n' "$out"
      numbers_vs_last_run "$out"
    else
      say "PROBLEM: numbers.sql failed"
      issues=$((issues + 1))
    fi
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
    good | adopted | baseline | switch-on | switch-off | acceptance-on | acceptance-off)
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

# read_numbers FILE: numbers.sql's key=count lines into FILE, read as finance_checks; NUMBERS_WHY says why they can't
# be read, else it is empty. Called in the script's own shell, never in "$(…)".
read_numbers() {
  local err=$RUN_DIR/numbers-error.txt
  NUMBERS_WHY=
  if ! sql_file "$RUN_DIR/numbers.sql" >"$1" 2>"$err"; then
    NUMBERS_WHY="numbers.sql failed as $CHECKS_ROLE ($(one_line "$(tail -n 3 "$err")"))"
  elif grep -Eqv '^[a-z_]+=[0-9]+$' "$1"; then
    NUMBERS_WHY="numbers.sql printed something else than key=count lines"
  elif [ ! -s "$1" ]; then
    NUMBERS_WHY="numbers.sql printed nothing"
  fi
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
  local api_before web_before api_content_before web_content_before images_line why problem
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
  # D-50 (decided by the PM in OPS-2b): switch off never refuses because the numbers can't be read through
  # finance_checks; it says so, and goes on without them. switch on still refuses.
  problem=$(role_problem)
  if [ -z "$problem" ]; then
    say "The checks run as $CHECKS_ROLE: $ROLE_OK"
  else
    role_command
    [ "$direction" = off ] || fail "$problem"
    NUMBERS_WHY=$problem
    say "WARNING: $problem: the numbers can't be read through $CHECKS_ROLE; switching off goes on without them (D-50)"
  fi

  heading "1.3 Preflight: health"
  if ! wait_healthy; then
    [ "$direction" = off ] || fail "api and web not both healthy"
    say "WARNING: api and web not both healthy; switching off goes on all the same, since the way back must always work"
  fi

  heading "1.4 Preflight: the clone, the last good deploy, no unfinished run"
  [ -z "$(git status --porcelain --untracked-files=no)" ] || fail "the clone has changes to tracked files (git status)"
  running_images || fail "no container finance-tracker-api or finance-tracker-web: the stack isn't up"
  api_before=$RUN_API web_before=$RUN_WEB api_content_before=$RUN_API_CONTENT web_content_before=$RUN_WEB_CONTENT
  say "$STORE_LINE"
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
  if [ -z "$NUMBERS_WHY" ]; then
    read_numbers "$RUN_DIR/numbers-before.txt"
    if [ -z "$NUMBERS_WHY" ]; then
      cat "$RUN_DIR/numbers-before.txt"
    else
      [ "$direction" = off ] || fail "$NUMBERS_WHY"
      say "WARNING: $NUMBERS_WHY; switching off goes on without the numbers (D-50)"
    fi
  else
    say "Not read: $NUMBERS_WHY (D-50)"
  fi

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
  if [ -n "$NUMBERS_WHY" ]; then
    NUMBERS_LINE="not read: $NUMBERS_WHY (D-50)"
    say "Not compared: $NUMBERS_WHY (D-50)"
  else
    read_numbers "$RUN_DIR/numbers-after.txt"
    if [ -n "$NUMBERS_WHY" ]; then
      [ "$direction" = off ] || fail "$NUMBERS_WHY"
      NUMBERS_LINE="not read after the switch: $NUMBERS_WHY (D-50)"
      say "WARNING: $NUMBERS_WHY: not compared; switched off all the same (D-50)"
    elif ! diff "$RUN_DIR/numbers-before.txt" "$RUN_DIR/numbers-after.txt" >"$RUN_DIR/numbers.diff"; then
      cat "$RUN_DIR/numbers.diff"
      fail "the numbers differ from before the switch ($RUN_DIR/numbers.diff)"
    else
      NUMBERS_LINE="the same before and after"
      say "The same numbers"
    fi
  fi

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
    say "Numbers: $NUMBERS_LINE"
  } | tr '|' '/' >"$RUN_DIR/summary.txt"
  set_status "switch-$direction"
  PHASE=complete
  heading "4. Summary"
  cat "$RUN_DIR/summary.txt"
  say ""
  say "Add the summary above as a row of \"Deployed revisions\" in deploy/RUNBOOK.md."
  stop_log
}

# ---------------------------------------------------------------------------------------------------------------------
# acceptance (HIST-3 part 4, ADR 0006 section 7)

ACCEPT_VAR=FAMILY_ACCEPTANCE_ENABLED
ACCEPT_PATTERN='Family acceptance: (on|off)'
ACCEPT_CHECK=deploy/checks/acceptance.sql

# accept_line_of FILE: FAMILY_ACCEPTANCE_ENABLED's current line in FILE, or nothing; never the rest of the file.
accept_line_of() { grep "^$ACCEPT_VAR=" "$1" 2>/dev/null || true; }

# accept_shown LINE: LINE (or "absent"), and "so on" or "so off", read as Spring reads a boolean.
accept_shown() {
  local value=${1#"$ACCEPT_VAR"=}
  [ -n "$1" ] || { printf 'absent, so off'; return; }
  case ${value,,} in
    true | on | yes | 1) printf '%s, so on' "$1" ;;
    *) printf '%s, so off' "$1" ;;
  esac
}

# acceptance_expected: the line FamilyAcceptanceSwitch must log, from FAMILY_ACCEPTANCE_ENABLED as the api's container
# has it, into ACCEPT_HOW and ACCEPT_LINE. Reads that one variable of the container and nothing else of its environment.
acceptance_expected() {
  local line value
  line=$(docker inspect -f '{{range .Config.Env}}{{if eq (index (split . "=") 0) "FAMILY_ACCEPTANCE_ENABLED"}}{{.}}{{end}}{{end}}' finance-tracker-api)
  value=${line#FAMILY_ACCEPTANCE_ENABLED=}
  case ${value,,} in
    '') ACCEPT_HOW="FAMILY_ACCEPTANCE_ENABLED absent, so off" ;;
    true | on | yes | 1) ACCEPT_HOW="FAMILY_ACCEPTANCE_ENABLED=$value, so on" ;;
    false | off | no | 0) ACCEPT_HOW="FAMILY_ACCEPTANCE_ENABLED=$value, so off" ;;
    *) fail "FAMILY_ACCEPTANCE_ENABLED is \"$value\", which the api reads as neither on nor off" ;;
  esac
  ACCEPT_LINE="Family acceptance: ${ACCEPT_HOW##* }"
}

# acceptance_state LOG: one line for verify and finish, information only (D-1178): the .env line and what the api
# logged since its start.
acceptance_state() {
  local seen
  seen=$(log_line "$1" "$ACCEPT_PATTERN")
  say "Family acceptance: $ACCEPT_VAR is $(accept_shown "$(accept_line_of "$APP_DIR/.env")") in .env; the api: ${seen:-no acceptance line in its log since its start} (information)"
}

# shellcheck disable=SC2329  # the EXIT trap
on_exit_acceptance() {
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
    say "$ACCEPT_VAR in .env was already changed to $NEW_LINE before this failed. Look at it by hand, then, on the"
    say "server, alone in its block, $([ "$OTHER" = on ] && echo 'run it again' || echo 'turn it back off'):"
    say "  cd $REPO_DIR && deploy/deploy.sh acceptance off"
    say "Never switch back on its own: it does not do that for you."
    set_status failed
  fi
  [ "$rc" -ne 0 ] || [ "$PHASE" = complete ] || rc=1
  stop_log
  exit "$rc"
}

# cmd_acceptance on|off: changes FAMILY_ACCEPTANCE_ENABLED in .env and restarts api alone (HIST-3, D-1175 to D-1179),
# modelled on cmd_switch. Both directions need the read-only role, since both read deploy/checks/acceptance.sql: "on"
# only with every counter at its head and nothing pending, and with the family switch on; "off" only with nothing
# pending, and refuses when that can't be read (D-1177). Otherwise "off" warns and goes on where "switch off" does.
cmd_acceptance() {
  local direction=$1 want old_line env_file backup_file head api_now web_now latest status api_log rc images_ok
  local api_before web_before api_content_before web_content_before images_line why problem out m l p seen shown
  case $direction in
    on) want=true OTHER=off ;;
    off) want=false OTHER=on ;;
    *) usage ;;
  esac
  NEW_LINE="$ACCEPT_VAR=$want"
  PHASE=preflight
  trap on_exit_acceptance EXIT
  take_lock exclusive
  cd "$REPO_DIR" || fail "no clone in $REPO_DIR"
  head=$(git rev-parse HEAD)
  latest=$(latest_unrefused_run)
  start_log "acceptance-$direction"
  print_settings

  heading "1.1 Preflight: the tools and the terminal"
  check_tools git docker flock install diff
  exec {TTY_FD}<"$TTY" || fail "can't read $TTY: run this in a terminal on the server"

  heading "1.2 Preflight: the role"
  problem=$(role_problem)
  if [ -n "$problem" ]; then
    role_command
    fail "$problem: the pending changes can't be read without it"
  fi
  say "The checks run as $CHECKS_ROLE: $ROLE_OK"

  heading "1.3 Preflight: health"
  if ! wait_healthy; then
    [ "$direction" = off ] || fail "api and web not both healthy"
    say "WARNING: api and web not both healthy; turning acceptance off goes on all the same"
  fi

  heading "1.4 Preflight: the clone, the last good deploy, no unfinished run"
  [ -z "$(git status --porcelain --untracked-files=no)" ] || fail "the clone has changes to tracked files (git status)"
  running_images || fail "no container finance-tracker-api or finance-tracker-web: the stack isn't up"
  api_before=$RUN_API web_before=$RUN_WEB api_content_before=$RUN_API_CONTENT web_content_before=$RUN_WEB_CONTENT
  say "$STORE_LINE"
  say "Before: $(identity api "$api_before" "$api_content_before"); $(identity web "$web_before" "$web_content_before")"
  if ! read_last_good; then
    [ "$direction" = off ] || fail "no record of a last good deploy: deploy at least once first (deploy/deploy.sh run)"
    say "WARNING: no record of a last good deploy; turning acceptance off goes on all the same"
  elif [ "$LG_COMMIT" != "$head" ]; then
    [ "$direction" = off ] \
      || fail "HEAD ${head:0:7} is not the last good deploy (${LG_COMMIT:0:7}): deploy it and finish, or roll back to it, first"
    say "WARNING: HEAD ${head:0:7} is not the last good deploy (${LG_COMMIT:0:7}); turning acceptance off goes on all the same"
  fi
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
    say "WARNING: the running images are not the last good deploy's content, or it is unknown; turning acceptance off goes on all the same"
  fi
  if [ -z "$latest" ]; then
    why="no run recorded in $STATE_DIR/runs: deploy with deploy/deploy.sh run, then deploy/deploy.sh finish, first"
    status=none
  else
    why=$(unfinished_work "$latest")
    status=$(cat "$latest/status" 2>/dev/null || echo 'without a status')
    say "The latest run that isn't refused: ${latest##*/}, $status"
  fi
  if [ -n "$why" ]; then
    [ "$direction" = off ] || fail "${why}"
    say "WARNING: $why; turning acceptance off goes on all the same"
  fi
  say "HEAD: $(git log -1 --format='%h %s' HEAD), the last good deploy ${LG_COMMIT:0:7} ($status)"

  heading "1.5 Preflight: the family switch and the current acceptance"
  env_file=$APP_DIR/.env
  [ -f "$env_file" ] || fail "$env_file is missing (the runbook's step 5)"
  say "The family switch: $SWITCH_VAR is $(switch_shown "$(switch_line_of "$env_file")") in .env"
  switch_expected
  say "The running api: $SWITCH_HOW"
  if [ "$direction" = on ]; then
    case "$(switch_shown "$(switch_line_of "$env_file")") $SWITCH_HOW" in
      *"so on "*"so on") ;;
      *) fail "the family switch is off: family acceptance is turned on only with family budgets on (deploy/deploy.sh switch on first)" ;;
    esac
  fi
  old_line=$(accept_line_of "$env_file")
  say "Current: $ACCEPT_VAR is $(accept_shown "$old_line")"
  acceptance_expected
  say "The running api: $ACCEPT_HOW"
  shown=$(accept_shown "$old_line")
  if [ "${shown##* }" = "$direction" ]; then
    [ "${ACCEPT_HOW##* }" != "$direction" ] || fail "family acceptance is already $direction: nothing to change"
    say ".env already says $(accept_shown "$old_line"), but the api runs with $ACCEPT_HOW: this restarts it"
  fi
  say "Will become: $NEW_LINE"

  heading "1.6 Preflight: the pending changes ($ACCEPT_CHECK)"
  git show "HEAD:$ACCEPT_CHECK" >"$RUN_DIR/acceptance.sql" 2>/dev/null || fail "HEAD has no $ACCEPT_CHECK"
  out=$(sql_file "$RUN_DIR/acceptance.sql" 2>"$RUN_DIR/acceptance-error.txt") \
    || fail "$ACCEPT_CHECK failed as $CHECKS_ROLE ($(one_line "$(tail -n 3 "$RUN_DIR/acceptance-error.txt")")): the pending changes can't be read"
  [[ $out =~ ^acceptance\ members=([0-9]+)\ links=([0-9]+)\ pending=([0-9]+)$ ]] \
    || fail "$ACCEPT_CHECK printed something else than its one line: the pending changes can't be read"
  m=${BASH_REMATCH[1]} l=${BASH_REMATCH[2]} p=${BASH_REMATCH[3]}
  say "Members not at their ledger's revision: $m; attached links not posted at their record's last revision: $l; pending records: $p"
  [ "$p" -eq 0 ] \
    || fail "$p pending record changes: the members accept them first, with the app's Accept action (POST /api/family-ledgers/<id>/accept); accept first"
  if [ "$direction" = on ] && { [ "$m" -ne 0 ] || [ "$l" -ne 0 ]; }; then
    fail "the counters aren't at their heads ($m members, $l links): run the reconcile first (deploy/RUNBOOK.md, \"Family acceptance (HIST-3)\")"
  fi

  heading "2. Confirmation"
  say "This changes $ACCEPT_VAR in $env_file from $(accept_shown "$old_line") to $NEW_LINE, and restarts api alone."
  say "Family changes will $([ "$direction" = on ] && echo 'wait for each member to accept them' || echo 'post at once, as before')."
  ask "Type ACCEPTANCE ${direction^^} to go on; anything else stops here with nothing changed: "
  [ "$ANSWER" = "ACCEPTANCE ${direction^^}" ] || fail "not confirmed"

  PHASE=changing
  set_status changing

  heading "3.1 Change .env"
  backup_file="$env_file.$(date -u +%Y%m%dT%H%M%SZ)"
  install -m 600 "$env_file" "$backup_file"
  say "Copied $env_file, before the acceptance change, to $backup_file (mode 600)"
  awk -v key="$ACCEPT_VAR" -v val="$want" '
    BEGIN { seen = 0 }
    $0 ~ "^" key "=" { print key "=" val; seen = 1; next }
    { print }
    END { if (!seen) print key "=" val }
  ' "$env_file" >"$env_file.tmp"
  chmod 600 "$env_file.tmp"
  if ! diff -q <(grep -vF "$ACCEPT_VAR=" "$env_file") <(grep -vF "$ACCEPT_VAR=" "$env_file.tmp") >/dev/null; then
    rm -f "$env_file.tmp"
    fail "something besides $ACCEPT_VAR would change in .env; refusing, to protect its secrets"
  fi
  mv -f "$env_file.tmp" "$env_file"
  say "Changed, limited to that line:"
  say "  - ${old_line:-($ACCEPT_VAR absent)}"
  say "  + $NEW_LINE"

  heading "3.2 Restart api"
  (cd "$APP_DIR" && docker compose up -d --no-deps api)

  heading "3.3 Health"
  wait_healthy || fail "api and web not both healthy after $HEALTH_TRIES checks"

  heading "3.4 The acceptance line"
  acceptance_expected
  say "Expected: \"$ACCEPT_LINE\" ($ACCEPT_HOW)"
  [ "$ACCEPT_HOW" = "$NEW_LINE, so $direction" ] \
    || fail "the api's container reads acceptance as $ACCEPT_HOW, not $NEW_LINE: docker compose didn't pick it up"
  api_log=$(api_log_since_start)
  seen=$(log_line "$api_log" "$ACCEPT_PATTERN")
  [ -n "$seen" ] || fail "the api's log since its start has no acceptance line"
  say "Logged: $seen"
  case $seen in
    "\"$ACCEPT_LINE\" at "*) say "As production now sets it ($ACCEPT_HOW)" ;;
    *) fail "the acceptance line isn't \"$ACCEPT_LINE\" ($ACCEPT_HOW)" ;;
  esac

  heading "3.5 The images before and after"
  running_images || fail "a container is gone after the restart"
  api_now=$RUN_API web_now=$RUN_WEB
  images_line=$(image_change api "$api_before" "$api_content_before" "$api_now" "$RUN_API_CONTENT")
  images_line="$images_line; $(image_change web "$web_before" "$web_content_before" "$web_now" "$RUN_WEB_CONTENT")"
  say "Images: $images_line"
  case $images_line in
    *CHANGED* | *unknown*)
      [ "$direction" = off ] \
        || fail "the restart started other content, or content that can't be read, than ran before: $images_line"
      say "WARNING: other content, or content that can't be read, than ran before; turned off all the same"
      ;;
  esac
  if [ "$images_ok" = yes ] && { [ "$api_now" != "$LG_API" ] || [ "$web_now" != "$LG_WEB" ]; }; then
    write_last_good "$LG_COMMIT" "$api_now" "$web_now" "$RUN_API_CONTENT" "$RUN_WEB_CONTENT" "${LG_SOURCE:-deploy}" "${LG_FINISH}"
    say "last-good now names the running images, the same content under new IDs: api $api_now, web $web_now"
    images_line="$images_line (last-good updated to the new IDs)"
  fi
  add_history "acceptance-$direction" "$head" "$api_now" "$web_now"
  {
    say "Acceptance $direction on $(date -u +%F) at $(now): $(git log -1 --format='%h (%s)' "$head")"
    say "$ACCEPT_VAR: ${old_line:-absent} -> $NEW_LINE"
    say "Images: $images_line"
    say "Acceptance line: $seen, as production now sets it ($ACCEPT_HOW)"
    say "Pre-check: members $m, links $l, pending $p"
  } | tr '|' '/' >"$RUN_DIR/summary.txt"
  set_status "acceptance-$direction"
  PHASE=complete
  heading "4. Summary"
  cat "$RUN_DIR/summary.txt"
  say ""
  say "The way back, on the server, alone in its block (it never switches back by itself):"
  say "  cd $REPO_DIR && deploy/deploy.sh acceptance $OTHER"
  stop_log
}

main() {
  set -euo pipefail
  shopt -s inherit_errexit
  umask 077
  common_settings
  PHASE='' SHA='' STAGE='' HEAD_BEFORE='' BASE='' LG_COMMIT='' LG_API='' LG_WEB='' LG_FINISH='' LG_SOURCE='' RERUN=no
  DUMP_before='' DUMP_after='' DUMP_SHA_before='' PRESERVED_LINE='' NUMBERS_WHY='' NUMBERS_LINE='' BUILD_CACHE_LINE=''
  case ${1:-} in
    run) [ $# -eq 3 ] || usage; cmd_run "$2" "$3" ;;
    finish) [ $# -eq 1 ] || usage; cmd_finish ;;
    verify) [ $# -eq 2 ] || usage; cmd_verify "$2" ;;
    adopt) [ $# -eq 1 ] || usage; cmd_adopt ;;
    prune-cache) [ $# -eq 1 ] || usage; cmd_prune_cache ;;
    db-image) [ $# -eq 2 ] || usage; cmd_db_image "$2" ;;
    switch) [ $# -eq 2 ] || usage; cmd_switch "$2" ;;
    acceptance) [ $# -eq 2 ] || usage; cmd_acceptance "$2" ;;
    *) usage ;;
  esac
}

main "$@"; exit $?
