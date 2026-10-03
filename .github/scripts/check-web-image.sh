#!/usr/bin/env bash
# Builds the web image (frontend/Dockerfile, target web) and checks it, as CI's job "Web image" does; the same on the
# laptop. From the repository's root:
#
#   .github/scripts/check-web-image.sh <frontend folder> <tag>                  the folder's files as they are
#   .github/scripts/check-web-image.sh <frontend folder> <tag> --server-modes   from a copy whose files are 600 and
#                                                                               folders 700, as a checkout under the
#                                                                               deploy scripts' umask 077 gives
#
# The image is checked twice:
#   1. Inside it, as its own user (nginx, uid 101): every file and folder under /usr/share/nginx/html and
#      /etc/nginx/conf.d readable by others (and every folder searchable), every file there read through, and the
#      files the build puts there owned by root. F7's deploy shipped privacy.html as -rw------- root (2026-10-02).
#   2. Through its own nginx (frontend/web.conf), on a free port of 127.0.0.1, with no other container and no network
#      beyond localhost: the pages and their answers.
# Every failure is printed; the exit status is 1 if there was any.
#
# Every output a check reads (docker's, curl's) is written to a file in full first and only then read: no pipe ends in
# a reader that stops early (grep -q, head), which under pipefail kills its writer with SIGPIPE whenever the writer is
# still writing, depending on timing. CI #46 of 3a6da31 died with exit 141 (F7b), at the one such pipe whose failure
# ended the script under set -e: the assets' "ls | head -n 1".
# .github/scripts/tests/check-web-image-test.sh runs this script against stubs whose outputs exceed any pipe's buffer.
set -euo pipefail

[ $# -ge 2 ] || { echo "usage: $0 <frontend folder> <tag> [--server-modes]" >&2; exit 2; }
src=$1 tag=$2 modes=${3:-}
work=$(mktemp -d)
name=finance-tracker-web-check-$$
fails=0
cleanup() {
  docker rm -f "$name" >/dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT
problem() { echo "❌ $*"; fails=$((fails + 1)); }

context=$src
if [ "$modes" = --server-modes ]; then
  context=$work/frontend
  mkdir "$context"
  tar -C "$src" --exclude=./node_modules --exclude=./dist -cf - . | tar -C "$context" -xf -
  find "$context" -type f -exec chmod 600 {} +
  find "$context" -type d -exec chmod 700 {} +
  echo "== The build context: a copy of $src with files 600 and folders 700"
  ls -la "$context" "$context/public"
fi

echo "== docker build --target web -t $tag"
docker build --target web -t "$tag" "$context"

echo "== Inside $tag, as its own user"
# Prints one line per problem, and nothing when there is none; find's own errors (a folder it may not enter) count too.
inside=$work/inside
docker run --rm --entrypoint sh "$tag" -c '
  echo "user $(id -un) ($(id -u))" >&2
  for top in /usr/share/nginx/html /etc/nginx/conf.d; do
    find "$top" ! -perm -004 -exec echo "not readable by others: {}" \; 2>&1
    find "$top" -type d ! -perm -001 -exec echo "not searchable by others: {}" \; 2>&1
    find "$top" -type f | while IFS= read -r f; do cat "$f" >/dev/null 2>&1 || echo "not readable by $(id -un): $f"; done
  done
  find /usr/share/nginx/html /etc/nginx/conf.d/default.conf ! -user root -exec echo "not owned by root: {}" \; 2>&1
' >"$inside" 2>&1 || problem "the check inside the image failed to run"
grep -v '^user ' "$inside" || true
grep -qxF 'user nginx (101)' "$inside" || problem "the image's user isn't nginx (101): $(sed -n 1p "$inside")"
if grep -qv '^user ' "$inside"; then
  problem "files or folders under /usr/share/nginx/html or /etc/nginx/conf.d that nginx may not read (above)"
else
  echo "✅ every file and folder under /usr/share/nginx/html and /etc/nginx/conf.d readable by nginx, the files root's"
fi
docker run --rm --entrypoint sh "$tag" -c 'ls -la /usr/share/nginx/html /etc/nginx/conf.d/default.conf'

echo "== Through its nginx"
docker run -d --name "$name" -p 127.0.0.1::8080 "$tag" >/dev/null
port=''
for _ in $(seq 30); do
  if [ "$(docker inspect -f '{{.State.Running}}' "$name")" != true ]; then break; fi
  port=$(docker inspect -f '{{with index .NetworkSettings.Ports "8080/tcp"}}{{(index . 0).HostPort}}{{end}}' "$name")
  [ -n "$port" ] && curl -s -o /dev/null "http://127.0.0.1:$port/" && break
  sleep 1
done
if [ "$(docker inspect -f '{{.State.Running}}' "$name")" != true ] || [ -z "$port" ]; then
  problem "nginx didn't start (exit code $(docker inspect -f '{{.State.ExitCode}}' "$name")); its log:"
  docker logs "$name" >"$work/nginx.log" 2>&1 || true
  tail -n 20 "$work/nginx.log"
else
  base="http://127.0.0.1:$port"
  body=$work/body
  check() { # path, expected status, text expected in the body (skipped when empty)
    local path=$1 status=$2 text=${3:-} code
    code=$(curl -s -o "$body" -w '%{http_code}' "$base$path")
    if [ "$code" != "$status" ]; then
      problem "$path: expected $status, got $code"
      return 0
    fi
    if [ -n "$text" ] && ! grep -qF "$text" "$body"; then
      problem "$path: missing '$text'"
      return 0
    fi
    echo "✅ $path: $code"
  }
  check /privacy 200 '<h1>Privacy policy</h1>'
  check /privacy.html 200 '<h1>Privacy policy</h1>'
  check / 200 '<div id="root">'
  check /family/1/report 200 '<div id="root">'
  docker exec "$name" sh -c 'ls /usr/share/nginx/html/assets' >"$work/assets" || true
  asset=$(sed -n 1p "$work/assets")
  if [ -z "$asset" ]; then
    problem "no file under /assets/"
  else
    curl -sI "$base/assets/$asset" >"$work/headers" || true
    if ! grep -qiE $'^cache-control: public, max-age=31536000, immutable\r?$' "$work/headers"; then
      problem "/assets/$asset: not cached as immutable"
    fi
    check "/assets/$asset" 200
  fi
  check /assets/missing.js 404
  check /favicon.svg 200
  check /favicon.ico 200
  docker logs "$name" >"$work/nginx.log" 2>&1 || true
  if grep -F 'Permission denied' "$work/nginx.log"; then
    problem "nginx logged Permission denied (above)"
  fi
fi

echo
if [ "$fails" -eq 0 ]; then
  echo "$tag: every check passed"
else
  echo "$tag: $fails problem(s)"
  exit 1
fi
