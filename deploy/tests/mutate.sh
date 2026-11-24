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
  '    good | adopted | baseline | switch-on | switch-off | acceptance-on | acceptance-off)' \
  '    good | adopted | baseline | switch-on | switch-off | acceptance-on | acceptance-off | finish-failed)' \
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
  $'  heading "4.6 Postflight: a fresh backup, then the restore test"\n  wait_for_another_minute\n  backup after' \
  $'  heading "4.6 Postflight: a fresh backup, then the restore test"\n  backup after' \
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
  'is below V7: that needs a restore from a dump (deploy/RUNBOOK.md, \"Restore from a backup\"); $(preserved_dump_of "$DR_DIR")"' \
  'is below V7: that needs a restore from a dump (deploy/RUNBOOK.md, \"Restore from a backup\")"' \
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
  '    finished | rolled-back | adopted | switch-on | switch-off | acceptance-on | acceptance-off) return 0 ;;' \
  '    finished | rolled-back | adopted | switch-on | switch-off | acceptance-on | acceptance-off | failed) return 0 ;;' \
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

# OPS-2b, defect 5: the store and the platform read only inside "$(…)", as OPS-2's scripts did.
mutation_in common.sh "read the store and platform only in subshells" \
  $'  docker_identity\n  RUN_API=' $'  RUN_API=' \
  store_and_platform_named

# OPS-2b, defect 5: a bare "unknown" for what Docker answered.
mutation_in common.sh "print a bare unknown for docker info's answer" \
  $'  image_store || store="not known (docker info -f \'{{json .DriverStatus}}\' $STORE_ANSWER)"' \
  $'  image_store || store=unknown' \
  store_and_platform_named identity_unavailable

# OPS-2b: the last good deploy's tag named by 7 characters, as no tag is named (production's are full hashes).
mutation "name the kept tag by 7 characters" \
  'kept as :$LG_COMMIT and :previous"' 'kept as :${LG_COMMIT:0:7} and :previous"' \
  happy_path

# OPS-2b, D-50: switch off refusing because the numbers can't be read through finance_checks.
mutation "let switch off refuse without the read-only role" \
  $'    role_command\n    [ "$direction" = off ] || fail "$problem"' $'    role_command\n    fail "$problem"' \
  switch_off_without_the_numbers

mutation "let switch off refuse when numbers.sql fails" \
  $'      [ "$direction" = off ] || fail "$NUMBERS_WHY"\n      say "WARNING: $NUMBERS_WHY; switching' \
  $'      fail "$NUMBERS_WHY"\n      say "WARNING: $NUMBERS_WHY; switching' \
  switch_off_without_the_numbers

# OPS-2b, D-50: switch on going on without the numbers.
mutation "let switch on go on without the numbers" \
  $'      [ "$direction" = off ] || fail "$NUMBERS_WHY"\n      say "WARNING: $NUMBERS_WHY; switching' \
  $'      say "WARNING: $NUMBERS_WHY; switching' \
  switch_off_without_the_numbers

# F8a (ADR 0004, "The rollback condition"): rollback.sh's refusal below V11 removed.
mutation_in rollback.sh "drop the refusal below V11" \
  'if [[ $db_version =~ ^[0-9]+$ ]] && [ "$db_version" -ge 11 ] && [ "$target_max" -lt 11 ]; then' \
  'if false; then' \
  rollback_below_v11

# F8d (ADR 0004, "F8d"): rollback.sh's refusals below V13 removed, each on its own.
mutation_in rollback.sh "drop the refusal below V13" \
  'if [[ $db_version =~ ^[0-9]+$ ]] && [ "$db_version" -ge 13 ] && [ "$target_max" -lt 13 ]; then' \
  'if false; then' \
  rollback_below_v13
mutation_in rollback.sh "let a refund through" \
  '[ "$refunds" = 0 ]' \
  'true' \
  rollback_below_v13
mutation_in rollback.sh "let a payment on an account that requires a counterparty through" \
  '[ "$credit" = 0 ]' \
  'true' \
  rollback_below_v13
mutation_in rollback.sh "count a refund only from V14 on" \
  '[ "$db_version" -ge 13 ] && [ "$target_max" -lt 13 ]' \
  '[ "$db_version" -ge 14 ] && [ "$target_max" -lt 13 ]' \
  rollback_below_v13

# D3b (D-148, D-71): rollback.sh's refusal below V14 removed, let through, or counted only from V15.
mutation_in rollback.sh "drop the refusal below V14" \
  'if [[ $db_version =~ ^[0-9]+$ ]] && [ "$db_version" -ge 14 ] && [ "$target_max" -lt 14 ]; then' \
  'if false; then' \
  rollback_below_v14
mutation_in rollback.sh "let a settlement side on a counterparty account or FX_EXCHANGE through" \
  '[ "$sides" = 0 ]' \
  'true' \
  rollback_below_v14
mutation_in rollback.sh "count the settlement sides only from V15 on" \
  '[ "$db_version" -ge 14 ] && [ "$target_max" -lt 14 ]' \
  '[ "$db_version" -ge 15 ] && [ "$target_max" -lt 14 ]' \
  rollback_below_v14

# D-1073: the postflight restore test is tried twice; a second FAIL fails the run, a first one alone does not.
mutation "no retry of the postflight restore test" \
  '  restore_test after soft && { say "Restore test: PASS at the second attempt' \
  '  true && { say "Restore test: PASS at the second attempt' \
  fail_second_restore_test
mutation "the first FAIL of the postflight restore test is final" \
  '  if restore_test after soft; then
    say "Restore test: PASS at the first attempt"' \
  '  if restore_test after; then
    say "Restore test: PASS at the first attempt"' \
  restore_test_retry_passes

# D-126 (F8f): a scheduled shutdown stops run, is named by verify and finish, and only warns in rollback.sh. D-131
# (F8f-fix): only when it is less than 2 hours away or its time has passed; D-128: USEC without MODE is a shutdown.
mutation "let run go on despite a shutdown less than 2 hours away" \
  '      elif [ "$SHUTDOWN_IN" -lt "$SHUTDOWN_MARGIN" ]; then' \
  '      elif false; then' \
  run_refused_by_a_reboot_less_than_2_hours_away
mutation "refuse at the 2 hours themselves" \
  '      elif [ "$SHUTDOWN_IN" -lt "$SHUTDOWN_MARGIN" ]; then' \
  '      elif [ "$SHUTDOWN_IN" -le "$SHUTDOWN_MARGIN" ]; then' \
  run_at_the_2_hour_boundary
mutation_in common.sh "a margin of 1 hour" \
  '  SHUTDOWN_MARGIN=7200' \
  '  SHUTDOWN_MARGIN=3600' \
  run_refused_by_a_reboot_less_than_2_hours_away
mutation_in common.sh "round the distance up" \
  '    SHUTDOWN_IN=$((usec / 1000000 - now))' \
  '    SHUTDOWN_IN=$(((usec + 999999) / 1000000 - now))' \
  run_at_the_2_hour_boundary
mutation_in common.sh "take a passed time as that far ahead" \
  '    SHUTDOWN_IN=$((usec / 1000000 - now))' \
  '    SHUTDOWN_IN=$((usec / 1000000 - now)); SHUTDOWN_IN=${SHUTDOWN_IN#-}' \
  run_refused_by_a_passed_shutdown_time
mutation "drop the passed time's own refusal" \
  '      if [ "$SHUTDOWN_IN" -lt 0 ]; then' \
  '      if false; then' \
  run_refused_by_a_passed_shutdown_time
mutation_in common.sh "the real clock in place of DEPLOY_SHUTDOWN_NOW" \
  '  SHUTDOWN_NOW=${DEPLOY_SHUTDOWN_NOW:-}' \
  '  SHUTDOWN_NOW=' \
  run_goes_on_with_a_reboot_hours_away
mutation_in common.sh "drop the distance from the line" \
  ', $distance ($SHUTDOWN_FILE)"' \
  ' ($SHUTDOWN_FILE)"' \
  run_goes_on_with_a_reboot_hours_away
mutation_in common.sh "require a MODE (D-128)" \
  '[[ $mode =~ ^[a-z-]{0,20}$ ]]' \
  '[[ $mode =~ ^[a-z-]{1,20}$ ]]' \
  a_usec_without_a_mode_is_a_shutdown
mutation "let run go on when the shutdown file can't be parsed" \
  '    2) fail "$SHUTDOWN_FILE' \
  '    2) : "$SHUTDOWN_FILE' \
  run_refused_by_an_unparseable_shutdown_file
mutation_in common.sh "take a file without USEC as no shutdown" \
  '  return 2
}' \
  '  return 1
}' \
  run_refused_by_an_unparseable_shutdown_file
mutation_in common.sh "never read the shutdown file" \
  'SHUTDOWN_FILE=${DEPLOY_SHUTDOWN_FILE:-/run/systemd/shutdown/scheduled}' \
  'SHUTDOWN_FILE=/nonexistent/scheduled' \
  run_refused_by_a_reboot_less_than_2_hours_away shutdown_none_is_named
mutation_in rollback.sh "let rollback.sh refuse for a scheduled shutdown" \
  '    say "WARNING: $SHUTDOWN_LINE; the rollback goes on (D-126), but the reboot may interrupt it"' \
  '    fail "$SHUTDOWN_LINE"' \
  shutdown_lines_of_verify_finish_and_rollback
mutation "drop verify's shutdown line" \
  '  check_tools git docker flock diff curl
  shutdown_scheduled || true
  say "$SHUTDOWN_LINE"

  heading "The read-only role"' \
  '  check_tools git docker flock diff curl

  heading "The read-only role"' \
  shutdown_lines_of_verify_finish_and_rollback

# OPS-3 (D-280, D-281): the gate counts CI's own runs only, and the postgres guard covers x-logging.
mutation "count every check run of the commit again, as before OPS-3" \
  '  suites=$(github "actions/workflows/ci.yml/runs?head_sha=$SHA&per_page=100" | python3 -c "$CI_SUITES_PY") || return 1
  for id in $suites; do
    github "check-suites/$id/check-runs?per_page=100" || return 1
  done | python3 -c "$CI_JOIN_PY"' \
  '  github "commits/$SHA/check-runs?per_page=100"' \
  ci_counts_only_ci_runs refuse_no_ci_run
mutation "read the runs of every workflow" \
  'github "actions/workflows/ci.yml/runs?head_sha=$SHA&per_page=100"' \
  'github "actions/runs?head_sha=$SHA&per_page=100"' \
  ci_counts_only_ci_runs
mutation "take a list of CI's runs longer than its page" \
  '    sys.exit("%s runs of ci.yml, more than one page holds" % d.get("total_count"))' \
  '    pass' \
  refuse_ci_runs_past_a_page
mutation "take a run of another workflow as CI's" \
  '        sys.exit("a run of another workflow: %s" % r.get("path"))' \
  '        pass' \
  refuse_ci_run_of_another_workflow
mutation "leave the extension blocks out of the postgres guard" \
  $'  if [ "$(compose_extensions "$BASE")" != "$(compose_extensions "$SHA")" ] \\\n    || [ "$(compose_part "$BASE" volumes:)"' \
  $'  if false \\\n    || [ "$(compose_part "$BASE" volumes:)"' \
  refuse_logging_changed

# OPS-5 (D-940, D-941): the build cache's bound and verify's comparison with the last run.
mutation "never prune the build cache" \
  $'  heading "3.7 Deploy: the build cache"\n  prune_build_cache\n' $'  heading "3.7 Deploy: the build cache"\n' \
  build_cache_pruned_after_success
mutation "prune the build cache before the pages are checked" \
  $'  heading "3.6 Deploy: the pages"\n  page_checks || fail "a page isn\'t as expected: $PAGES_LINE"\n\n  heading "3.7 Deploy: the build cache"\n  prune_build_cache\n' \
  $'  prune_build_cache\n  heading "3.6 Deploy: the pages"\n  page_checks || fail "a page isn\'t as expected: $PAGES_LINE"\n' \
  build_cache_pruned_after_success no_build_cache_prune_after_a_failed_deploy
mutation "let a failed prune fail the deploy" \
  '    say "WARNING: the build cache wasn'"'"'t pruned: $BUILD_CACHE_LINE; the deploy goes on"' \
  '    fail "the build cache wasn'"'"'t pruned: $BUILD_CACHE_LINE"' \
  build_cache_prune_failure_only_warns
mutation "prune everything unused, volumes included" \
  'docker builder prune -f --filter "until=$BUILD_CACHE_AGE" --reserved-space "$BUILD_CACHE_KEEP"' \
  'docker system prune -af --volumes' \
  build_cache_pruned_after_success
mutation "prune all of the cache regardless of its age" \
  'docker builder prune -f --filter "until=$BUILD_CACHE_AGE" --reserved-space "$BUILD_CACHE_KEEP"' \
  'docker builder prune -f -a' \
  build_cache_pruned_after_success
mutation "keep no space at all" \
  'BUILD_CACHE_KEEP=3GB' 'BUILD_CACHE_KEEP=0' \
  build_cache_pruned_after_success
mutation "verify compares with the oldest run, not the newest" \
  '  done < <(find "$STATE_DIR/runs" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -r)
  if [ -z "$file" ]; then' \
  '  done < <(find "$STATE_DIR/runs" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort)
  if [ -z "$file" ]; then' \
  verify_compares_numbers_with_the_last_run
mutation "let a difference in the numbers fail verify" \
  '      say "  $key: was ${was[$key]:-absent}, now $val"
      diffs=$((diffs + 1))' \
  '      say "  $key: was ${was[$key]:-absent}, now $val"
      issues=$((issues + 1))
      diffs=$((diffs + 1))' \
  verify_compares_numbers_with_the_last_run
mutation "verify never compares the numbers" \
  '      numbers_vs_last_run "$out"' '      : numbers_vs_last_run' \
  verify_compares_numbers_with_the_last_run
mutation "prune-cache prunes nothing" \
  $'  check_tools docker flock timeout df\n  prune_build_cache\n  [ "$BUILD_CACHE_RC" -eq 0 ]' $'  check_tools docker flock timeout df\n  BUILD_CACHE_RC=0\n  [ "$BUILD_CACHE_RC" -eq 0 ]' \
  prune_cache_command
mutation "prune-cache exits 0 when the prune failed" \
  '  [ "$BUILD_CACHE_RC" -eq 0 ] || fail "the build cache wasn'"'"'t pruned: $BUILD_CACHE_LINE"' \
  '  : "$BUILD_CACHE_RC"' \
  prune_cache_failure_exits_1

# --- db-image (OPS-6, D-939, D-945 to D-947)
mutation "db-image starts from any clone, not the last good deploy" \
  $'  [ "$LG_COMMIT" = "$HEAD_BEFORE" ] \\\n    || fail "HEAD ($(git rev-parse --short HEAD)) isn\'t the last good deploy' \
  $'  true \\\n    || fail "HEAD ($(git rev-parse --short HEAD)) isn\'t the last good deploy' \
  db_image_refuses_a_clone_not_at_the_last_good_deploy
mutation "db-image doesn't need a clean finish" \
  '  clean_finish "$HEAD_BEFORE" || fail "the last good deploy' '  true || fail "the last good deploy' \
  db_image_refuses_without_a_clean_finish
mutation "db-image goes on after an unfinished run" \
  '    [ -z "$why" ] || fail "$why"' '    :' \
  db_image_refuses_after_an_unfinished_run
mutation "db-image doesn't look at the containers' health" \
  '    [ "$(health_of "$f")" = healthy ] || fail "$f isn'"'"'t healthy"' '    :' \
  db_image_refuses_an_unhealthy_container
mutation "db-image lets the compose file change beyond the image line" \
  '    || fail "more than the postgres service'"'"'s image line changes in deploy/app/docker-compose.yml"' '    || :' \
  db_image_refuses_more_than_the_image_line
mutation "db-image lets other files of deploy/app change" \
  '= deploy/app/docker-compose.yml ] \
    || fail "in deploy/app/ only' '!= deploy/app/never ] \
    || fail "in deploy/app/ only' \
  db_image_refuses_more_than_the_image_line
mutation "db-image lets any file outside deploy/app change" \
  '    [ "$f" = deploy/app/docker-compose.yml ] || never_deployed "$f" || outside+=("$f")' '    :' \
  db_image_refuses_deployed_files_outside_the_stack_folder
mutation "the rule of never-deployed files allows the api's folder" \
  '    docs/* | e2e/* |' '    backend/* | docs/* | e2e/* |' \
  db_image_refuses_deployed_files_outside_the_stack_folder
mutation "the rule of never-deployed files allows numbers.sql" \
  '    deploy/checks/numbers.sql) return 1 ;;
' '' \
  db_image_refuses_deployed_files_outside_the_stack_folder
mutation "db-image accepts a new major version" \
  '    || fail "a new major version ($old_major to $new_major) needs a dump and a restore, not db-image"' '    || :' \
  db_image_refuses_a_pin_that_is_not_an_upgrade_of_the_same_major
mutation "db-image accepts the same or an older minor release" \
  '[ "$new_minor" -gt "$old_minor" ] ||' 'true ||' \
  db_image_refuses_a_pin_that_is_not_an_upgrade_of_the_same_major
mutation "db-image accepts a pin without a digest" \
  "POSTGRES_PIN_RE='^postgres:([0-9]+)\\.([0-9]+)@sha256:[0-9a-f]{64}\$'" "POSTGRES_PIN_RE='^postgres:([0-9]+)\\.([0-9]+)(@sha256:[0-9a-f]{64})?\$'" \
  db_image_refuses_a_pin_that_is_not_an_upgrade_of_the_same_major
mutation "db-image doesn't compare the container with the old pin" \
  '  [ "$running" = "$OLD_PIN" ] || fail "$PG_CONTAINER was created from' '  true || fail "$PG_CONTAINER was created from' \
  db_image_refuses_a_container_not_on_the_old_pin
mutation "db-image doesn't need the old image to stay" \
  '    || fail "the old image $OLD_PIN isn'"'"'t on this host: the way back needs it (D-947)"' '    || :' \
  db_image_refuses_when_the_old_image_is_gone
mutation "db-image doesn't check CI" \
  '  ci_check # CI passed on the commit (D-945)' '  : ci_check # CI passed on the commit (D-945)' \
  db_image_refuses_failed_ci
mutation "db-image doesn't pull the new image" \
  $'  docker pull "$NEW_PIN"\n' $'  : pull\n' \
  db_image_happy_path
mutation "db-image doesn't run the pulled image" \
  '  out=$(docker run --rm --network none --entrypoint postgres "$NEW_PIN" --version)' '  out="PostgreSQL $new_major.$new_minor "; : $(true)' \
  db_image_happy_path
mutation "db-image goes on with an index to REINDEX" \
  '    fail "the release notes advise REINDEX for the indexes above (index|method|why): decide with the PM before the upgrade; db-image goes on only when none is listed"' '    say ignored' \
  db_image_refuses_while_an_index_needs_a_reindex
mutation "db-image confirmation accepts anything" \
  '[ "$ANSWER" = "${SHA:0:7}" ] || fail "not confirmed (db-image)"' ': accepts "$ANSWER"' \
  db_image_wrong_confirmation
mutation "db-image takes postgres down with the stack" \
  '(cd "$APP_DIR" && docker compose up -d --no-deps postgres)
  db_image_probe' '(cd "$APP_DIR" && docker compose down && docker compose up -d postgres)
  db_image_probe' \
  db_image_happy_path
mutation "db-image recreates api and web too" \
  '(cd "$APP_DIR" && docker compose up -d --no-deps postgres)
  db_image_probe' '(cd "$APP_DIR" && docker compose up -d --no-deps postgres api web)
  db_image_probe' \
  db_image_happy_path
mutation "db-image rolls back by itself when it fails" \
  '    print_db_image_rollback
  fi' '    print_db_image_rollback
    git checkout -q --detach "$HEAD_BEFORE"
    (cd "$APP_DIR" && docker compose up -d --no-deps postgres)
  fi' \
  db_image_fails_when_postgres_never_answers db_image_fails_on_flyway_below
mutation "db-image restarts the api when it doesn't answer" \
  '  db_image_probe || fail "postgres accepted' '  db_image_probe || (cd "$APP_DIR" && docker compose restart api) || fail "postgres accepted' \
  db_image_fails_when_the_api_does_not_reconnect
mutation "db-image prints no way back" \
  '    print_db_image_rollback
  fi' '    :
  fi' \
  db_image_fails_when_postgres_never_answers db_image_fails_on_flyway_below
mutation "db-image tolerates every FATAL line of the log" \
  "grep -vF 'FATAL:  the database system is starting up' || true)" "grep -vF 'FATAL' || true)" \
  db_image_fails_on_an_error_in_postgres_log
mutation "db-image reads postgres's whole log, not since its start" \
  $' --since "$(docker inspect -f \'{{.State.StartedAt}}\' "$PG_CONTAINER")" postgres)' $' postgres)' \
  db_image_ignores_lines_from_before_its_start
mutation "db-image doesn't check the server's version" \
  '  [ "$(pg_sql_version "$out")" = "$new_major.$new_minor" ] || fail "the server says' '  true || fail "the server says' \
  db_image_fails_on_a_wrong_server_version
mutation "db-image ignores other numbers" \
  '    fail "the numbers differ from preflight'"'"'s ($RUN_DIR/numbers.diff); a user'"'"'s entry during the upgrade shows up here too, so judge before going back"' '    say ignored' \
  db_image_fails_on_other_numbers
mutation "db-image doesn't check Flyway" \
  '  [ "${row##* }" = true ] && [ "$version" = "$expected" ] || fail "Flyway isn'"'"'t at V$expected with success: $row"' '  :' \
  db_image_fails_on_flyway_below
mutation "db-image doesn't check the pages" \
  '  page_checks || fail "a page isn'"'"'t as expected after the upgrade: $PAGES_LINE"' '  page_checks || :' \
  db_image_fails_on_a_failed_page
mutation "db-image doesn't record the last good deploy" \
  '  write_last_good "$SHA" "$RUN_API" "$RUN_WEB" "$RUN_API_CONTENT" "$RUN_WEB_CONTENT" db-image' '  :' \
  db_image_happy_path
mutation "db-image removes the old image" \
  '  say "The old image $OLD_PIN is on this host and stays (D-947)"' '  say "The old image $OLD_PIN is on this host and stays (D-947)"; docker rmi "${OLD_PIN%@*}"' \
  db_image_happy_path
mutation "run accepts any new postgres image line" \
  '  [ "$running" = "$pin" ] || return 1' '  true || return 1' \
  run_refuses_a_pin_the_running_container_does_not_have
mutation "run accepts the applied pin with another change of the block" \
  '  [ "$(postgres_block_without_image "$1")" = "$(postgres_block_without_image "$2")" ] || return 1' '  true || return 1' \
  run_refuses_the_applied_pin_with_another_postgres_change
mutation "run lets the extension blocks change with the applied pin" \
  $'  if [ "$(compose_extensions "$BASE")" != "$(compose_extensions "$SHA")" ] \\' $'  if false \\' \
  run_refuses_the_applied_pin_with_a_changed_extension_block
mutation "run never accepts the applied pin" \
  '  [ "$running" = "$pin" ] || return 1' '  return 1' \
  run_accepts_the_pin_the_running_container_has run_after_db_image_goes_on_without_a_flag
mutation "verify doesn't name the database image" \
  '  say "$PG_CONTAINER was created from: $(docker inspect -f '"'"'{{.Config.Image}}'"'"' "$PG_CONTAINER" 2>/dev/null || echo '"'"'unknown (no such container)'"'"')"' '  :' \
  verify_names_the_database_image

# --- OPS-6b (D-963): the version checks read the real formats, exactly
mutation "db-image reads postgres --version as SELECT version() prints it (OPS-6's first check)" \
  '  [ "$(pg_cli_version "$out")" = "$new_major.$new_minor" ] || fail "the pulled image says' \
  '  [[ $out == *"PostgreSQL $new_major.$new_minor "* ]] || fail "the pulled image says' \
  db_image_reads_the_real_postgres_version_line db_image_happy_path
mutation "db-image takes any text that holds the version's digits for the pulled image's" \
  '  [ "$(pg_cli_version "$out")" = "$new_major.$new_minor" ] || fail "the pulled image says' \
  '  [[ $out == *"$new_major.$new_minor"* ]] || fail "the pulled image says' \
  db_image_reads_the_real_postgres_version_line
mutation "db-image takes any text that holds the version's digits for the server's" \
  '  [ "$(pg_sql_version "$out")" = "$new_major.$new_minor" ] || fail "the server says' \
  '  [[ $out == *"$new_major.$new_minor"* ]] || fail "the server says' \
  db_image_reads_the_real_server_version_line
mutation "the --version reader stops at the minor's first digits" \
  "  local re='^postgres \\(PostgreSQL\\) ([0-9]+)\\.([0-9]+)( |\$)'" \
  "  local re='^postgres \\(PostgreSQL\\) ([0-9]+)\\.([0-9]+)'" \
  db_image_reads_the_real_postgres_version_line
mutation "the --version reader takes the old, invented form" \
  "  local re='^postgres \\(PostgreSQL\\) ([0-9]+)\\.([0-9]+)( |\$)'" \
  "  local re='^postgres \\(PostgreSQL\\)? ?([0-9]+)\\.([0-9]+)( |\$)'" \
  db_image_reads_the_real_postgres_version_line
mutation "the SELECT version() reader drops its end of version" \
  "  local re='^PostgreSQL ([0-9]+)\\.([0-9]+)( |\$)'" \
  "  local re='^PostgreSQL ([0-9]+)\\.([0-9]+)'" \
  db_image_reads_the_real_server_version_line

# --- HIST-3 part 4: acceptance on|off (D-1175 to D-1179)
mutation "let acceptance's confirmation accept anything" \
  '[ "$ANSWER" = "ACCEPTANCE ${direction^^}" ] || fail "not confirmed"' ': accepts "$ANSWER"' \
  acceptance_wrong_confirmation
mutation "acceptance on without the family switch" \
  '      *) fail "the family switch is off: family acceptance is turned on only with family budgets on (deploy/deploy.sh switch on first)" ;;' \
  '      *) : ;;' \
  acceptance_on_refuses_with_the_switch_off
mutation "acceptance on with counters behind" \
  '  if [ "$direction" = on ] && { [ "$m" -ne 0 ] || [ "$l" -ne 0 ]; }; then' '  if false; then' \
  acceptance_on_refuses_counters_behind
mutation "acceptance goes on while records are pending" \
  '  [ "$p" -eq 0 ]' '  true' \
  acceptance_refuses_pending
mutation "acceptance off goes on when the pending count can't be read" \
  '  [[ $out =~ ^acceptance\ members=([0-9]+)\ links=([0-9]+)\ pending=([0-9]+)$ ]]' \
  '  [[ $out =~ ^acceptance\ members=([0-9]+)\ links=([0-9]+)\ pending=([0-9]+)$ ]] || out="acceptance members=0 links=0 pending=0"; [[ $out =~ ^acceptance\ members=([0-9]+)\ links=([0-9]+)\ pending=([0-9]+)$ ]]' \
  acceptance_off_refuses_unreadable_pending
mutation "acceptance off without the role" \
  '    fail "$problem: the pending changes can'"'"'t be read without it"' '    :' \
  acceptance_off_refuses_unreadable_pending
mutation "acceptance on from another revision than the last good deploy" \
  '      || fail "HEAD ${head:0:7} is not the last good deploy (${LG_COMMIT:0:7}): deploy it and finish, or roll back to it, first"
    say "WARNING: HEAD ${head:0:7} is not the last good deploy (${LG_COMMIT:0:7}); turning acceptance off goes on all the same"' \
  '      || true
    say "WARNING: HEAD ${head:0:7} is not the last good deploy (${LG_COMMIT:0:7}); turning acceptance off goes on all the same"' \
  acceptance_on_refuses_not_the_last_good_deploy
mutation "acceptance doesn't check the api's line" \
  '    *) fail "the acceptance line isn'"'"'t \"$ACCEPT_LINE\" ($ACCEPT_HOW)" ;;' '    *) : ;;' \
  acceptance_failure_prints_the_way_back
mutation "acceptance rewrites another line of .env" \
  '  awk -v key="$ACCEPT_VAR" -v val="$want" '"'"'
    BEGIN { seen = 0 }
    $0 ~ "^" key "=" { print key "=" val; seen = 1; next }' \
  '  awk -v key="$ACCEPT_VAR" -v val="$want" '"'"'
    BEGIN { seen = 0 }
    NR == 1 { print key "=" val; seen = 1; next }' \
  acceptance_on_and_off
mutation "acceptance doesn't print the way back" \
  '  say "  cd $REPO_DIR && deploy/deploy.sh acceptance $OTHER"' '  :' \
  acceptance_on_and_off

echo
if [ "$survived" -eq 0 ]; then
  echo "Every mutation was caught."
else
  echo "$survived mutation(s) survived."
  exit 1
fi
