#!/usr/bin/env bash
# The tests of .github/scripts/mutations-needed.sh (D-1207, CI-1): the helper run from inside a temp git repository
# with the files it cares about, one case for each file it watches, for the files it ignores, and for each reason to
# run when unsure (no BEFORE, a new branch, an unknown BEFORE or AFTER). No network.
#
#   .github/scripts/tests/mutations-needed-test.sh
set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
HELPER=$HERE/../mutations-needed.sh
WORK=$(mktemp -d)
trap 'rm -rf "${WORK:?}"' EXIT

REPO=$WORK/repo
mkdir "$REPO"
cd "$REPO"
git init -q
git config user.name test
git config user.email test@example.invalid
mkdir -p deploy/tests backend/src .github/workflows .github/scripts/tests
for f in deploy/deploy.sh deploy/rollback.sh deploy/common.sh deploy/tests/run.sh deploy/tests/mutate.sh \
  backend/src/Main.java README.md .github/workflows/ci.yml .github/scripts/tests/mutations-needed-test.sh; do
  echo "base $f" >"$f"
done
cp "$HELPER" .github/scripts/mutations-needed.sh
git add -A
git commit -q -m base
BASE=$(git rev-parse HEAD)
UNKNOWN=1234567890123456789012345678901234567890
ZEROS=0000000000000000000000000000000000000000

cases=0
failed=0

reset_repo() {
  git reset -q --hard "$BASE"
  git clean -fdq
}

# change FILE: append a line to FILE (creating it), commit it.
change() {
  echo "change" >>"$1"
  git add -A
  git commit -q -m case
}

# check NAME EXPECTED BEFORE AFTER: EXPECTED is run or skip.
check() {
  local name=$1 expected=$2 before=$3 after=$4 want status=0 out
  want=0
  [ "$expected" = run ] || want=1
  out=$("$HELPER" "$before" "$after" 2>&1) || status=$?
  cases=$((cases + 1))
  if [ "$status" = "$want" ] && [[ $out == "$expected:"* ]] && [ "$(echo "$out" | wc -l)" = 1 ]; then
    echo "ok   $name"
  else
    echo "FAIL $name: exit=$status, output=$out"
    failed=$((failed + 1))
  fi
}

# one_change NAME EXPECTED FILE: one commit on top of the base that changes FILE, BEFORE = the base.
one_change() {
  reset_repo
  change "$3"
  check "$1" "$2" "$BASE" "$(git rev-parse HEAD)"
}

one_change "a deploy/deploy.sh changed" run deploy/deploy.sh
one_change "b deploy/common.sh changed" run deploy/common.sh
one_change "c deploy/rollback.sh changed" run deploy/rollback.sh
one_change "d deploy/tests/run.sh changed" run deploy/tests/run.sh
one_change "e new file under deploy/tests" run deploy/tests/new-case.sh
one_change "f ci.yml changed" run .github/workflows/ci.yml
one_change "g the helper changed" run .github/scripts/mutations-needed.sh
one_change "h the helper's test changed" run .github/scripts/tests/mutations-needed-test.sh
one_change "i only backend changed" skip backend/src/Main.java
one_change "j only README changed" skip README.md

reset_repo
change backend/src/Main.java
AFTER=$(git rev-parse HEAD)
check "k BEFORE all zeros" run "$ZEROS" "$AFTER"
check "l BEFORE unknown" run "$UNKNOWN" "$AFTER"
check "m BEFORE empty" run "" "$AFTER"

reset_repo
check "n AFTER unknown" run "$BASE" "$UNKNOWN"

# A range of two commits: the first changes only the backend, the second a deploy script.
reset_repo
change backend/src/Main.java
change deploy/common.sh
check "o range with a deploy change in the second commit" run "$BASE" "$(git rev-parse HEAD)"

# The net range: a deploy-script change that a later commit reverts leaves no difference, so the step is skipped.
# The decision looks at BEFORE..AFTER as a whole, not at each commit.
reset_repo
change deploy/common.sh
git show "$BASE:deploy/common.sh" >deploy/common.sh
git add -A
git commit -q -m revert
check "p change reverted in the same range (net diff empty)" skip "$BASE" "$(git rev-parse HEAD)"

# No arguments at all.
reset_repo
status=0
out=$("$HELPER" 2>&1) || status=$?
cases=$((cases + 1))
if [ "$status" = 0 ] && [[ $out == "run:"* ]]; then
  echo "ok   q no arguments"
else
  echo "FAIL q no arguments: exit=$status, output=$out"
  failed=$((failed + 1))
fi

# A deleted watched file is a change too.
reset_repo
git rm -q deploy/tests/run.sh
git commit -q -m delete
check "r deleted watched file deploy/tests/run.sh" run "$BASE" "$(git rev-parse HEAD)"

echo "$cases cases, $failed failed"
[ "$failed" = 0 ] || exit 1
