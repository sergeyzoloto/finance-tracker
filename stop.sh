#!/usr/bin/env bash
# Stops what dev.sh started: the backend, the Vite dev server and the auth server's dev stack. The containers are
# kept, so the next ./dev.sh starts quickly.
#
# The auth server repository is expected next to this one; set AUTH_SERVER_DIR to point elsewhere.
set -euo pipefail

cd "$(dirname "$0")"
AUTH_SERVER_DIR=${AUTH_SERVER_DIR:-../auth_server}

docker compose -f docker-compose.yml -f docker-compose.local.yml -f docker-compose.dev.yml stop
(cd "$AUTH_SERVER_DIR" && docker compose stop)
