#!/usr/bin/env bash
# The external monitor (D-276, OPS-3), run by .github/workflows/monitor.yml every 15 minutes and by hand: four checks
# of production from outside, each with a short timeout and one retry. Read only: it sends no secret, signs in nowhere
# and writes nothing but its own output. Exit status 0 when every check passed, 1 when one failed, 2 for a wrong call.
#
#   .github/scripts/monitor.sh            the checks
#   .github/scripts/monitor.sh --fail     the checks, then a failure on purpose (the email's test, deploy/RUNBOOK.md)
#
# The checks:
#   1. the app's page https://app.finance-nl.com/ answers 200 with <div id="root">;
#   2. /api/me without a session answers 401: the api container itself answers, never Caddy's 502, 503 or 504;
#   3. the realm's discovery document answers 200 with the issuer https://auth.finance-nl.com/realms/myapps;
#   4. both hosts' certificates are valid, for the host, for at least 14 more days.
#
# For its tests only (.github/scripts/tests/monitor-test.sh): MONITOR_APP and MONITOR_AUTH (the two origins),
# MONITOR_ISSUER, MONITOR_CA (a CA file trusted instead of the system's), MONITOR_MIN_DAYS and MONITOR_RETRY_WAIT.
set -euo pipefail

APP=${MONITOR_APP:-https://app.finance-nl.com}
AUTH=${MONITOR_AUTH:-https://auth.finance-nl.com}
ISSUER=${MONITOR_ISSUER:-https://auth.finance-nl.com/realms/myapps}
MIN_DAYS=${MONITOR_MIN_DAYS:-14}
RETRY_WAIT=${MONITOR_RETRY_WAIT:-10}
CA=${MONITOR_CA:-}

fail_on_purpose=no
case ${1:-} in
  '') ;;
  --fail) fail_on_purpose=yes ;;
  *) echo "usage: $0 [--fail]" >&2; exit 2 ;;
esac

WORK=$(mktemp -d)
trap 'rm -rf "${WORK:?}"' EXIT

curl_ca=()
openssl_ca=()
if [ -n "$CA" ]; then
  curl_ca=(--cacert "$CA")
  openssl_ca=(-CAfile "$CA")
fi

# fetch URL: the answer's status into STATUS and its body into $WORK/body. Fails on no answer at all (DNS, connection,
# TLS, timeout); an answer of any status is no failure here.
fetch() {
  STATUS=$(curl -sS --connect-timeout 10 --max-time 20 "${curl_ca[@]}" -o "$WORK/body" -w '%{http_code}' "$1" 2>"$WORK/error") \
    || { STATUS="no answer ($(tr '\n' ' ' <"$WORK/error"))"; return 1; }
}

check_page() {
  fetch "$APP/" || { WHY="$APP/: $STATUS"; return 1; }
  [ "$STATUS" = 200 ] || { WHY="$APP/ answered $STATUS"; return 1; }
  grep -qF '<div id="root">' "$WORK/body" || { WHY="$APP/ answered 200 without <div id=\"root\">"; return 1; }
  WHY="$APP/ answered 200 with <div id=\"root\">"
}

check_api() {
  fetch "$APP/api/me" || { WHY="$APP/api/me: $STATUS"; return 1; }
  [ "$STATUS" = 401 ] || { WHY="$APP/api/me answered $STATUS without a session, not 401"; return 1; }
  WHY="$APP/api/me answered 401 without a session"
}

check_issuer() {
  local url=$AUTH/realms/myapps/.well-known/openid-configuration seen
  fetch "$url" || { WHY="$url: $STATUS"; return 1; }
  [ "$STATUS" = 200 ] || { WHY="$url answered $STATUS"; return 1; }
  seen=$(python3 -c 'import json, sys; print(json.load(open(sys.argv[1])).get("issuer"))' "$WORK/body" 2>/dev/null) \
    || { WHY="$url answered 200 without a JSON document"; return 1; }
  [ "$seen" = "$ISSUER" ] || { WHY="$url names the issuer $seen, not $ISSUER"; return 1; }
  WHY="$url names the issuer $ISSUER"
}

# check_certificate ORIGIN: the certificate the host serves verifies (chain and host name) and stays valid for MIN_DAYS.
check_certificate() {
  local origin=$1 hostport host
  hostport=${origin#https://}
  host=${hostport%%:*}
  [[ $hostport == *:* ]] || hostport=$host:443
  timeout 20 openssl s_client -connect "$hostport" -servername "$host" -verify_hostname "$host" -verify_return_error \
    "${openssl_ca[@]}" </dev/null >"$WORK/tls" 2>"$WORK/tls-error" \
    || { WHY="$host: the TLS handshake or the certificate's verification failed ($(grep -m1 -iE 'verify|error|connect' "$WORK/tls-error" || echo "no detail"))"; return 1; }
  openssl x509 -noout -enddate <"$WORK/tls" >"$WORK/enddate" 2>/dev/null || { WHY="$host: no certificate read"; return 1; }
  if ! openssl x509 -noout -checkend $((MIN_DAYS * 86400)) <"$WORK/tls" >/dev/null; then
    WHY="$host: the certificate ends $(sed 's/^notAfter=//' "$WORK/enddate"), less than $MIN_DAYS days from now"
    return 1
  fi
  WHY="$host: the certificate verifies and ends $(sed 's/^notAfter=//' "$WORK/enddate"), at least $MIN_DAYS days from now"
}

failed=0
# run NAME FUNCTION [ARG]: the check, and once more after RETRY_WAIT seconds if it failed.
run() {
  local name=$1
  shift
  if "$@"; then
    echo "ok    $name: $WHY"
    return 0
  fi
  echo "retry $name: $WHY"
  sleep "$RETRY_WAIT"
  if "$@"; then
    echo "ok    $name: $WHY"
  else
    echo "FAIL  $name: $WHY"
    failed=$((failed + 1))
  fi
}

run "the app's page" check_page
run "the api without a session" check_api
run "the realm's issuer" check_issuer
run "app's certificate" check_certificate "$APP"
run "auth's certificate" check_certificate "$AUTH"

if [ "$fail_on_purpose" = yes ]; then
  echo "FAIL  on purpose (--fail): the monitor's email test"
  failed=$((failed + 1))
fi

if [ "$failed" -eq 0 ]; then
  echo "Every check passed."
else
  echo "$failed check(s) failed."
  exit 1
fi
