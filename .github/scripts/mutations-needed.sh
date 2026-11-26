#!/usr/bin/env bash
# Decides whether the job "Deploy scripts" of .github/workflows/ci.yml runs its Mutations step (D-1207, CI-1): the
# mutations (deploy/tests/mutate.sh) take about 12 minutes and depend only on the deploy scripts and their tests, so a
# push that changes none of them skips the step. The job itself always runs (D-95: each push keeps its 5 check runs).
#
#   .github/scripts/mutations-needed.sh BEFORE AFTER
#
# Run from the root of the repository. BEFORE and AFTER are the pushed range (github.event.before and github.sha).
# Prints one line on stdout saying why, starting with "run:" or "skip:". Exit status 0 = run the mutations,
# 1 = skip them. Anything unclear (no BEFORE, a new branch, a commit missing from the history, a git error) runs them.
#
# The decision looks at the net difference between BEFORE and AFTER, not at each commit: a change that a later commit
# in the same push reverts leaves no difference and skips.
set -euo pipefail

PATHS=(
  deploy/deploy.sh deploy/rollback.sh deploy/common.sh deploy/tests
  .github/workflows/ci.yml .github/scripts/mutations-needed.sh .github/scripts/tests/mutations-needed-test.sh
)

BEFORE=${1:-}
AFTER=${2:-}

if [ "$#" -lt 2 ] || [ -z "$BEFORE" ]; then
  echo "run: no earlier commit to compare with"
  exit 0
fi
if [[ $BEFORE =~ ^0+$ ]]; then
  echo "run: new branch"
  exit 0
fi
if ! git cat-file -e "${BEFORE}^{commit}" 2>/dev/null; then
  echo "run: BEFORE is not in the history"
  exit 0
fi
if ! git cat-file -e "${AFTER}^{commit}" 2>/dev/null; then
  echo "run: AFTER is not in the history"
  exit 0
fi
if ! changed=$(git diff --name-only "$BEFORE" "$AFTER" -- "${PATHS[@]}" 2>/dev/null); then
  echo "run: git diff failed"
  exit 0
fi
if [ -n "$changed" ]; then
  echo "run: deploy-script change in $(echo "$changed" | tr '\n' ' ' | sed 's/ $//')"
  exit 0
fi
echo "skip: no deploy-script change between ${BEFORE:0:7} and ${AFTER:0:7}"
exit 1
