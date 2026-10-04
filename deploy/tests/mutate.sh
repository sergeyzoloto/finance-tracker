#!/usr/bin/env bash
# shellcheck disable=SC2016  # the mutations are code, quoted so that nothing in them expands here
# Breaks deploy.sh on purpose, one way at a time, and checks that deploy/tests/run.sh notices: each mutation must make
# at least one of its cases fail. The mutated copies live in a temp folder; the repository's files are never changed.
#
#   deploy/tests/mutate.sh
set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
WORK=$(mktemp -d "${TMPDIR:-/tmp}/deploy-mutations.XXXXXX")
trap 'rm -rf "$WORK"' EXIT

survived=0

# mutation NAME "OLD" "NEW" CASE...: deploy.sh with OLD (which must occur exactly once) replaced by NEW.
mutation() {
  mutation_in deploy.sh "$@"
}

# mutation_in FILE NAME "OLD" "NEW" CASE...: as mutation, in deploy/FILE (deploy.sh, rollback.sh or common.sh).
mutation_in() {
  local file=$1 name=$2 old=$3 new=$4 dir=$WORK/$2 out
  shift 4
  mkdir -p "$dir"
  cp "$ROOT/deploy/deploy.sh" "$ROOT/deploy/rollback.sh" "$ROOT/deploy/common.sh" "$dir/"
  OLD=$old NEW=$new python3 - "$dir/$file" <<'PY'
import os, sys
path = sys.argv[1]
text = open(path).read()
old, new = os.environ["OLD"], os.environ["NEW"]
if text.count(old) != 1:
    sys.exit("the mutation's text occurs %d times in %s; update deploy/tests/mutate.sh" % (text.count(old), path))
open(path, "w").write(text.replace(old, new))
PY
  if out=$(DEPLOY_TEST_SCRIPTS=$dir "$HERE/run.sh" "$@" 2>&1); then
    echo "SURVIVED  $name: every case passed ($*)"
    survived=$((survived + 1))
  else
    echo "caught    $name, by: $(grep '^FAIL ' <<<"$out" | awk '{ print $2 }' | paste -sd ' ')"
  fi
}

mutation "drop the CI check" \
  $'\n  ci_check\n' $'\n  : ci_check dropped\n' \
  refuse_ci_failed refuse_ci_still_running refuse_ci_none

mutation "let the confirmation accept anything" \
  '[ "$ANSWER" = "${SHA:0:7}" ] || fail "not confirmed"' ': accepts "$ANSWER"' \
  wrong_confirmation

mutation "let a failure call the rollback" \
  '    print_rollback_command "$(git rev-parse HEAD)"' \
  '    print_rollback_command "$(git rev-parse HEAD)"
    if rollback_target "$(git rev-parse HEAD)"; then
      git checkout -q --detach "$RT_COMMIT"
      docker tag "finance-tracker-api:$RT_COMMIT" finance-tracker-api
      docker tag "finance-tracker-web:$RT_COMMIT" finance-tracker-web
      (cd "$APP_DIR" && docker compose up -d --no-deps api web)
    fi' \
  fail_never_healthy fail_flyway_below fail_switch_line_missing fail_switch_line_wrong fail_numbers_differ \
  fail_stage_diff fail_second_restore_test

mutation "print the environment" \
  $'switch_expected() {\n  local line value\n' \
  $'switch_expected() {\n  local line value\n  docker inspect -f \'{{range .Config.Env}}{{println .}}{{end}}\' finance-tracker-api\n' \
  secret_never_printed

mutation "run on past main (no exit after it)" \
  $'main "$@"; exit $?\n' $'main "$@"\n' \
  script_replaced_mid_run

# F6b: every check as the read-only role, never as the app's login.
mutation_in common.sh "run the checks as finance" \
  '-At -U "$CHECKS_ROLE" -d finance "$@")' '-At -U finance -d finance "$@")' \
  happy_path verify_read_only finish_records_answers

# F6b: preflight without the role's check.
mutation "skip the role's check in preflight" \
  $'  fi\n  require_checks_role\n' $'  fi\n  : require_checks_role skipped\n' \
  refuse_role_missing refuse_role_superuser refuse_role_may_write

# F6b: adopt without its confirmation.
mutation "let adopt accept anything" \
  '[ "$ANSWER" = "ADOPT ${head:0:7}" ] || fail "not confirmed"' ': accepts "$ANSWER"' \
  adopt_after_a_harmless_stop

# F6c: lines pasted ahead taken as answers.
mutation_in common.sh "keep the lines pasted ahead" \
  $'  discard_waiting\n  printf \'%s\' "$1"\n' $'  printf \'%s\' "$1"\n' \
  pasted_ahead_is_discarded

# F6c: a yes or no question taking anything.
mutation_in common.sh "let yes or no take anything" \
  '      yes | no) return 0 ;;' '      *) return 0 ;;' \
  yes_no_asks_again

# F6c: finish counting refused runs, as F6b's did.
mutation "let finish count refused runs" \
  '    [ "$status" != refused ] || continue
    LATEST=$dir' '    LATEST=$dir' \
  finish_skips_refused_runs f6c_scripts_on_production_state

# F6c: a commit's status by its oldest line.
mutation_in common.sh "take a commit's status from its oldest line" \
  '    [ "$c" = "$1" ] && status=$ev' '    [ "$c" = "$1" ] && [ -z "$status" ] && status=$ev' \
  newest_line_decides_status f6c_scripts_on_production_state

# F6c: OPS-1's rollback target, which skips a commit whose finish failed.
mutation_in common.sh "skip a finish-failed commit as the rollback target" \
  '  [ -n "$RT_API" ] || return 1
  RT_COMMIT=$DR_BASE' '  [ -n "$RT_API" ] || return 1
  [ "$(commit_status "$DR_BASE")" != finish-failed ] || return 1
  RT_COMMIT=$DR_BASE' \
  f6b_run_on_production_state finish_after_the_f6c_run

# F6c: adopt refusing a finish-failed HEAD.
mutation "let adopt refuse a finish-failed HEAD" \
  '    good | adopted | baseline | switch-on | switch-off)' \
  '    good | adopted | baseline | switch-on | switch-off | finish-failed)' \
  newest_line_decides_status f6c_scripts_on_production_state

# F6c: the two hints.
mutation "drop the hint to push first" \
  ': push it from the laptop first (deploy/RUNBOOK.md, \"Deploying with deploy.sh\", step 1), then run this again"' '"' \
  step_1_3_says_push_first
mutation_in rollback.sh "drop the hint of the manual rollback" \
  '; without it, roll back by hand: deploy/RUNBOOK.md, \"Roll an update back\""' '"' \
  refuse_role_missing

# F7: run's confirmation named HEAD instead of the running revision after a rollback and a plain "git checkout main".
mutation "name HEAD instead of the running revision in run's confirmation" \
  $'stage $STAGE, over $(git log -1 --format=\'%h\' "$BASE")."' $'stage $STAGE, over $(git log -1 --format=\'%h\' HEAD)."' \
  run_names_the_running_revision

# F7: switch's confirmation accepting anything.
mutation "let switch's confirmation accept anything" \
  '[ "$ANSWER" = "SWITCH ${direction^^}" ] || fail "not confirmed"' ': accepts "$ANSWER"' \
  switch_wrong_confirmation

# F7: the awk that changes FAMILY_LEDGERS_ENABLED in .env, matching by position instead of by key, so it can rewrite
# another line (here, the first one, a secret).
mutation '"switch" rewrites another line of .env' \
  '    $0 ~ "^" key "=" { print key "=" val; done = 1; next }' '    NR == 1 { print key "=" val; done = 1; next }' \
  switch_on_and_off

# F7: the environment printed while switching (as "print the environment" does for switch_expected).
mutation "print the environment while switching" \
  'say "Copied $env_file to $backup_file (mode 600)"' $'say "Copied $env_file to $backup_file (mode 600)"\n  cat "$env_file"' \
  switch_secret_never_printed

# F7: the way back not printed after a failure past the change.
mutation "drop switch's way back after a failure" \
  '    say "  cd $REPO_DIR && deploy/deploy.sh switch off"' '    : way back not printed' \
  switch_failure_prints_the_way_back

# F7b: the page check dropped from run, after health.
mutation "drop the page check from run" \
  '  page_checks || fail "a page isn'"'"'t as expected: $PAGES_LINE"' '  : page check dropped' \
  run_fails_on_a_page

# F7b: finish asking about the browser checks although a page failed.
mutation "let finish ask despite a failed page check" \
  '  if [ "$pages" = passed ]; then' '  if true; then' \
  finish_records_failed_pages_without_asking

# F7b: /privacy, the page F7's deploy lost, dropped from the one list.
mutation_in common.sh "drop /privacy from the pages" \
  $'  \'/privacy|200|<h1>Privacy policy</h1>\'\n' '' \
  run_fails_on_a_page finish_records_failed_pages_without_asking

# F7b: git writing the working tree under the scripts' umask 077 again.
mutation_in common.sh "drop the umask 022 of git" \
  'git_tree() { (umask 022 && git "$@"); }' 'git_tree() { (git "$@"); }' \
  merged_files_land_644

# F7b: switch's confirmation saying "202" again.
mutation "say 202 in switch's confirmation" \
  "echo '200 to their members'" "echo '202 as usual'" \
  switch_says_200_and_prints_the_numbers

# F7b's fourth commit: the page check reading curl through a pipe into grep -q, which stops at the text while curl is
# still writing the (stub's 1 MB) page.
mutation_in common.sh "read the page through curl | grep -q" \
  '    elif [ -n "$text" ] && ! grep -qF -- "$text" "$body"; then' \
  '    elif [ -n "$text" ] && ! curl -sS --max-time 15 "https://$host$path" 2>/dev/null | grep -qF -- "$text"; then' \
  happy_path large_pages_pass

# F7b's fourth commit: switch on going on although a page failed (D-41).
mutation "let switch on ignore a failed page" \
  $'    [ "$direction" = off ] \\\n      || fail "a page isn\'t as expected' $'    true \\\n      || fail "a page isn\'t as expected' \
  switch_on_refuses_on_a_failed_page

# OPS-2, defect 3 (D-43): the before-dump preserved by a hard link, which an in-place rewrite changes too.
mutation "preserve the before-dump by a hard link" \
  '  cp "$BACKUP_DIR/$DUMP_before" "$copy"' '  ln "$BACKUP_DIR/$DUMP_before" "$copy"' \
  dump_overwritten_in_place

# OPS-2, defect 3: no wait for the next minute before the after-dump.
mutation "don't wait for the next minute" \
  $'  wait_for_another_minute\n  backup after' $'  backup after' \
  dumps_in_the_same_minute

# OPS-2, defect 3: the after-dump's name not compared with the before-dump's.
mutation "don't compare the dumps' names" \
  $'  [ "$DUMP_after" != "$DUMP_before" ] \\\n    || fail' $'  true \\\n    || fail' \
  dump_overwritten_in_place

# OPS-2, defect 3: the preserved copy's SHA-256 not checked after the after-dump.
mutation "don't check the preserved copy" \
  $'  [ "$sum" = "$DUMP_SHA_before" ] \\\n    || fail "the preserved' $'  true \\\n    || fail "the preserved' \
  preserved_dump_changed

# OPS-2, defect 3: rollback.sh's refusal below V7 without the preserved dump.
mutation_in rollback.sh "don't name the preserved dump below V7" \
  '; $(preserved_dump_of "$DR_DIR")"' '"' \
  rollback_below_v7

# OPS-2, defect 4: images compared by ID again, so a rebuild of the same content looks like a failed run's.
mutation_in common.sh "compare images by ID, not content" \
  '  if [ "$c" != "$rc" ]; then' '  if [ "$id" != "$rid" ]; then' \
  last_good_id_differs_same_content

# OPS-2, defect 4: an unknown content identity taken as the same.
mutation_in common.sh "take an unknown content as the same" \
  $'    say "$name: the content identity is unknown (running $(identity image "$id" "$c"), recorded $(identity image "$rid" "$rc"))"\n    return 2' \
  $'    say "$name: the content identity is unknown (running $(identity image "$id" "$c"), recorded $(identity image "$rid" "$rc"))"\n    return 0' \
  identity_unavailable

# OPS-2, defect 4: switch saying "unchanged" for a content it can't read.
mutation "let switch call an unknown content unchanged" \
  '  if [ -z "$bc" ] || [ -z "$ac" ]; then' '  if false; then' \
  identity_unavailable

# OPS-2, defect 4: switch leaving last-good at the IDs before a recreation.
mutation "let switch leave last-good at the old IDs" \
  '    write_last_good "$LG_COMMIT" "$api_now" "$web_now" "$RUN_API_CONTENT" "$RUN_WEB_CONTENT" "${LG_SOURCE:-deploy}" "$LG_FINISH"' \
  '    : last-good left as it was' \
  same_content_new_id

# OPS-2, defect 4: last-good of 4510003's scripts left without content identities.
mutation_in common.sh "don't work out old last-good's content" \
  $'  valid_image "$LG_API_CONTENT" || LG_API_CONTENT=$(content_of_id "$LG_API") || LG_API_CONTENT=\'\'' \
  $'  valid_image "$LG_API_CONTENT" || LG_API_CONTENT=\'\'' \
  old_format_state

# OPS-2, defect 4: rollback.sh by ID only.
mutation_in rollback.sh "let rollback.sh compare by ID only" \
  '  [ "$content" = "$recorded_content" ] ' '  false ' \
  last_good_id_differs_same_content

# OPS-2, defect 1: switch's step 1.4 taking a refused run as the latest, as 4510003's did.
mutation_in common.sh "let a refused run decide what comes next" \
  $'    [ "$(cat "$dir/status" 2>/dev/null || true)" != refused ] || continue\n    printf' $'    printf' \
  switch_refused_at_the_confirmation switch_refused_as_already_on switch_refused_at_a_precheck

# OPS-2, defect 1: switch off refusing for unfinished work.
mutation "let switch off refuse for unfinished work" \
  $'    [ "$direction" = off ] || fail "$why"' $'    fail "$why"' \
  switch_after_an_unfinished_run switch_interrupted_after_the_change

# OPS-2, defect 1: an interrupted switch taken as done.
mutation_in common.sh "take an interrupted switch as done" \
  '    finished | rolled-back | adopted | switch-on | switch-off) return 0 ;;' \
  '    finished | rolled-back | adopted | switch-on | switch-off | failed) return 0 ;;' \
  switch_interrupted_after_the_change

# OPS-2, defect 1: "nothing to switch" by .env alone, though the api still runs with the other value.
mutation "say nothing to switch by .env alone" \
  '    [ "$SWITCH_HOW" != "$NEW_LINE, so $direction" ] || fail "$SWITCH_VAR is already $want: nothing to switch"' \
  '    fail "$SWITCH_VAR is already $want: nothing to switch"' \
  switch_off_after_a_failed_restart

# OPS-2, defect 1: a failed switch off naming switch on as the way back, as 4510003's did.
mutation "name switch on after a failed switch off" \
  $'    say "  cd $REPO_DIR && deploy/deploy.sh switch off"\n    say "Never' $'    say "  cd $REPO_DIR && deploy/deploy.sh switch $OTHER"\n    say "Never' \
  switch_off_after_a_failed_restart

# OPS-2, D-42: run over the running commit after its clean finish, not refused.
mutation "let run deploy the running commit again after its finish" \
  $'    if clean_finish "$SHA"; then\n      fail' $'    if false; then\n      fail' \
  run_of_the_running_commit_after_its_finish

# OPS-2, D-42: a run again re-pointing :previous and the previous-commit file at the commit itself.
mutation "let a run again re-point :previous" \
  $'  if [ "$RERUN" = yes ]; then\n    # The last good deploy' $'  if false; then\n    # The last good deploy' \
  run_of_the_running_commit_without_a_clean_finish

# OPS-2, D-42: a finish that didn't pass taken as clean.
mutation_in common.sh "take any finish as clean" \
  $'    passed*) ;;\n    *) [ "$LG_SOURCE" = adopt ] || return 1 ;;' $'    *) ;;' \
  run_of_the_running_commit_without_a_clean_finish

echo
if [ "$survived" -eq 0 ]; then
  echo "Every mutation was caught."
else
  echo "$survived mutation(s) survived."
  exit 1
fi
