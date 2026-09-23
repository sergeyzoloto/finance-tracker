#!/usr/bin/env bash
# Starts the stack in dev mode: the auth server's dev Keycloak, then the backend and the Vite dev server with hot
# reload. Open http://localhost:5173 and sign in as testuser / test1234.
#
# The auth server repository is expected next to this one; set AUTH_SERVER_DIR to point elsewhere.
# Extra arguments go to "docker compose up", e.g. ./dev.sh -V after changing frontend dependencies.
set -euo pipefail

cd "$(dirname "$0")"
AUTH_SERVER_DIR=${AUTH_SERVER_DIR:-../auth_server}

(cd "$AUTH_SERVER_DIR" && docker compose up -d)
docker compose -f docker-compose.yml -f docker-compose.local.yml -f docker-compose.dev.yml up --build -d "$@"

echo "Started. The backend needs a few seconds more; then open http://localhost:5173"
