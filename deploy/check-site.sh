#!/usr/bin/env bash
# Checks deploy/finance.caddy the way caddy-site install checks it on the server: the auth server's Caddyfile, which
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
cp "$repo/deploy/finance.caddy" "$sites/finance.caddy"
chmod 755 "$sites"
chmod 644 "$sites/finance.caddy"

echo "== caddy validate: $auth/deploy/Caddyfile with deploy/finance.caddy, in $image"
# Stand-ins for the variables of the auth server's .env that its Caddyfile reads. 192.0.2.1 is a documentation address.
valid=1
out=$(docker run --rm --network none -e KC_DOMAIN=auth.finance-nl.com -e ADMIN_ALLOWED_IPS=192.0.2.1 \
  -v "$auth/deploy/Caddyfile:/etc/caddy/Caddyfile:ro" -v "$sites:/etc/caddy/sites:ro" \
  "$image" caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile 2>&1) || valid=0
grep -v '"level":"info"' <<<"$out" || true
[ "$valid" -eq 1 ] || { echo "❌ deploy/finance.caddy is not valid" >&2; exit 1; }

echo "== caddy fmt"
if docker run --rm --network none -v "$sites:/etc/caddy/sites:ro" "$image" caddy fmt /etc/caddy/sites/finance.caddy \
    | diff -u --label deploy/finance.caddy --label "caddy fmt" "$repo/deploy/finance.caddy" -; then
  echo "Formatted as caddy fmt formats it."
else
  echo "⚠️  Not formatted as caddy fmt would; Caddy accepts it anyway."
fi
echo "✅ deploy/finance.caddy is valid"
