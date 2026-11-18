#!/usr/bin/env bash
# Checks deploy/finance.caddy and deploy/finance-apex.caddy the way caddy-site install checks it on the server: the auth server's Caddyfile, which
# imports it, validated by the Caddy image the auth stack pins, without a network. Also shows where the file differs
# from caddy fmt's layout. Runs on the laptop, from anywhere in this repository:
#   deploy/check-site.sh
# Needs Docker and the auth server's repository in ~/dev/auth_server (or AUTH_SERVER_DIR). Exit code 0 means valid.
set -euo pipefail

repo=$(cd "$(dirname "$0")/.." && pwd)
auth=${AUTH_SERVER_DIR:-$HOME/dev/auth_server}
compose=$auth/deploy/docker-compose.yml
[ -r "$compose" ] && [ -r "$auth/deploy/Caddyfile" ] || { echo "❌ No auth server in $auth; set AUTH_SERVER_DIR" >&2; exit 1; }

# The caddy service's image line, such as caddy:2.11.4@sha256:...
image=$(awk '/^  caddy:/ { found = 1 } found && $1 == "image:" { print $2; exit }' "$compose")
[ -n "$image" ] || { echo "❌ No image for caddy in $compose" >&2; exit 1; }

sites=$(mktemp -d)
trap 'rm -rf "$sites"' EXIT
# Both site files of this project (WEB-1: the apex's is finance-apex.caddy).
files="finance.caddy finance-apex.caddy"
for f in $files; do
  cp "$repo/deploy/$f" "$sites/$f"
  chmod 644 "$sites/$f"
done
chmod 755 "$sites"

echo "== caddy validate: $auth/deploy/Caddyfile with deploy/finance.caddy and deploy/finance-apex.caddy, in $image"
# Stand-ins for the variables of the auth server's .env that its Caddyfile reads. 192.0.2.1 is a documentation address.
valid=1
out=$(docker run --rm --network none -e KC_DOMAIN=auth.finance-nl.com -e ADMIN_ALLOWED_IPS=192.0.2.1 \
  -v "$auth/deploy/Caddyfile:/etc/caddy/Caddyfile:ro" -v "$sites:/etc/caddy/sites:ro" \
  "$image" caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile 2>&1) || valid=0
grep -v '"level":"info"' <<<"$out" || true
[ "$valid" -eq 1 ] || { echo "❌ the site files are not valid" >&2; exit 1; }

echo "== caddy fmt"
for f in $files; do
  if docker run --rm --network none -v "$sites:/etc/caddy/sites:ro" "$image" caddy fmt "/etc/caddy/sites/$f" \
      | diff -u --label "deploy/$f" --label "caddy fmt" "$repo/deploy/$f" -; then
    echo "deploy/$f: formatted as caddy fmt formats it."
  else
    echo "⚠️  deploy/$f: not formatted as caddy fmt would; Caddy accepts it anyway."
  fi
done

# The apex's headers are finance.caddy's (a copy, see finance-apex.caddy): they must stay equal.
directives=$'^\t\t(Strict-Transport-Security|X-Content-Type-Options|Referrer-Policy|Content-Security-Policy) '
if diff <(grep -E "$directives" "$repo/deploy/finance.caddy") <(grep -E "$directives" "$repo/deploy/finance-apex.caddy" | head -n 4) >/dev/null; then
  echo "The apex's headers equal the app's."
else
  echo "⚠️  The apex's header block differs from finance.caddy's: keep them equal."
fi
echo "✅ deploy/finance.caddy and deploy/finance-apex.caddy are valid"
