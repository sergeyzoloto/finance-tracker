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
  '    good | adopted | baseline)' '    good | adopted | baseline | finish-failed)' \
  newest_line_decides_status f6c_scripts_on_production_state

# F6c: the two hints.
mutation "drop the hint to push first" \
  ': push it from the laptop first (deploy/RUNBOOK.md, \"Deploying with deploy.sh\", step 1), then run this again"' '"' \
  step_1_3_says_push_first
mutation_in rollback.sh "drop the hint of the manual rollback" \
  '; without it, roll back by hand: deploy/RUNBOOK.md, \"Roll an update back\""' '"' \
  refuse_role_missing

echo
if [ "$survived" -eq 0 ]; then
  echo "Every mutation was caught."
else
  echo "$survived mutation(s) survived."
  exit 1
fi
