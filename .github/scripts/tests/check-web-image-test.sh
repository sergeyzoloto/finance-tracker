#!/usr/bin/env bash
# shellcheck disable=SC2016  # the mutations are code, quoted so that nothing in them expands here
# Runs .github/scripts/check-web-image.sh against stubs of docker and curl (stubs/): no image, no container, no network.
# Every output the script reads is over 1 MB with what it looks for first, so a pipe into a reader that stops early
# fails every time, not now and then (CI #46 of 3a6da31, exit 141; F7b). Two cases, then four mutations, each putting
# one of the script's former pipes back, each of which must make a case fail. From the repository's root:
#
#   .github/scripts/tests/check-web-image-test.sh
set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd "$HERE/../../.." && pwd)
SCRIPT=$ROOT/.github/scripts/check-web-image.sh
WORK=$(mktemp -d "${TMPDIR:-/tmp}/web-image-test.XXXXXX")
trap 'rm -rf "$WORK"' EXIT

# cases SCRIPT: 0 when both cases pass with it; prints the one that doesn't.
cases() {
  local script=$1 rc
  rc=0; (cd "$ROOT" && PATH="$HERE/stubs:$PATH" STUB_MODE=good bash "$script" frontend web-check:test) >"$WORK/out" 2>&1 || rc=$?
  if [ "$rc" -ne 0 ] || ! grep -qxF 'web-check:test: every check passed' "$WORK/out"; then
    echo "  good: expected every check passed, got exit $rc:"; sed 's/^/    /' "$WORK/out" | tail -n 8; return 1
  fi
  rc=0; (cd "$ROOT" && PATH="$HERE/stubs:$PATH" STUB_MODE=unreadable bash "$script" frontend web-check:test) >"$WORK/out" 2>&1 || rc=$?
  if [ "$rc" -ne 1 ] || ! grep -qF 'not readable by others: /usr/share/nginx/html/privacy.html' "$WORK/out" \
    || ! grep -qF 'that nginx may not read (above)' "$WORK/out" || ! grep -qxF 'web-check:test: 1 problem(s)' "$WORK/out"; then
    echo "  unreadable: expected exactly that problem, got exit $rc:"; sed 's/^/    /' "$WORK/out" | tail -n 8; return 1
  fi
}

echo "== The script as it is"
if cases "$SCRIPT"; then echo "ok   both cases"; else echo "FAIL"; exit 1; fi

survived=0
# mutation NAME "OLD" "NEW": the script with OLD (exactly once) replaced by NEW must fail a case.
mutation() {
  local name=$1 copy=$WORK/mutant.sh
  OLD=$2 NEW=$3 python3 - "$SCRIPT" "$copy" <<'PY'
import os, sys
text = open(sys.argv[1]).read()
old, new = os.environ["OLD"], os.environ["NEW"]
if text.count(old) != 1:
    sys.exit("the mutation's text occurs %d times; update check-web-image-test.sh" % text.count(old))
open(sys.argv[2], "w").write(text.replace(old, new))
PY
  if cases "$copy" >"$WORK/why"; then
    echo "SURVIVED  $name"; survived=$((survived + 1))
  else
    echo "caught    $name"
  fi
}

echo "== Mutations: the former pipes"
mutation "the assets through ls | head -n 1 (CI #46)" \
  $'docker exec "$name" sh -c \'ls /usr/share/nginx/html/assets\' >"$work/assets" || true\n  asset=$(sed -n 1p "$work/assets")' \
  $'asset=$(docker exec "$name" sh -c \'ls /usr/share/nginx/html/assets\' | head -n 1)'
mutation "the user through printf | grep -q" \
  $'grep -qxF \'user nginx (101)\' "$inside" ||' \
  $'printf \'%s\\n\' "$(cat "$inside")" | grep -q \'^user nginx (101)$\' ||'
mutation "the problems through printf | grep -qv" \
  $'if grep -qv \'^user \' "$inside"; then' \
  $'if printf \'%s\\n\' "$(cat "$inside")" | grep -qv \'^user \'; then'
mutation "the headers through curl | tr | grep -qi" \
  $'    curl -sI "$base/assets/$asset" >"$work/headers" || true\n    if ! grep -qiE $\'^cache-control: public, max-age=31536000, immutable\\r?$\' "$work/headers"; then' \
  $'    if ! curl -sI "$base/assets/$asset" | tr -d \'\\r\' | grep -qi \'^cache-control: public, max-age=31536000, immutable$\'; then'

echo
if [ "$survived" -eq 0 ]; then echo "Every mutation was caught."; else echo "$survived mutation(s) survived."; exit 1; fi
