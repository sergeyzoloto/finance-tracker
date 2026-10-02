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
  local name=$1 old=$2 new=$3 dir=$WORK/$1 out
  shift 3
  mkdir -p "$dir"
  cp "$ROOT/deploy/deploy.sh" "$ROOT/deploy/rollback.sh" "$ROOT/deploy/common.sh" "$dir/"
  OLD=$old NEW=$new python3 - "$dir/deploy.sh" <<'PY'
import os, sys
path = sys.argv[1]
text = open(path).read()
old, new = os.environ["OLD"], os.environ["NEW"]
if text.count(old) != 1:
    sys.exit("the mutation's text occurs %d times in deploy.sh; update deploy/tests/mutate.sh" % text.count(old))
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

echo
if [ "$survived" -eq 0 ]; then
  echo "Every mutation was caught."
else
  echo "$survived mutation(s) survived."
  exit 1
fi
