#!/usr/bin/env bash
# The tests of .github/scripts/monitor.sh (D-276, OPS-3): the monitor against HTTPS servers on loopback ports, with a
# CA made here for the test (MONITOR_CA), in a temp folder; no network beyond localhost. Each case starts a server that
# answers as production does, or breaks one thing: a wrong issuer, Caddy's 502 for the api, a page without the app, a
# certificate that ends in 5 days, one the CA never signed, no server at all, and a first answer that fails once.
#
#   .github/scripts/tests/monitor-test.sh
set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
MONITOR=$HERE/../monitor.sh
WORK=$(mktemp -d)
SERVER_PID=
cleanup() {
  [ -z "$SERVER_PID" ] || kill "$SERVER_PID" 2>/dev/null || true
  rm -rf "${WORK:?}"
}
trap cleanup EXIT

# The CA, a certificate for localhost valid for a year, one valid for 5 days, and one from another CA.
cd "$WORK"
openssl req -x509 -newkey rsa:2048 -nodes -keyout ca.key -out ca.crt -days 30 -subj /CN=monitor-test-ca 2>/dev/null
printf 'subjectAltName=DNS:localhost\n' >san.ext
leaf() {
  openssl req -newkey rsa:2048 -nodes -keyout "$1.key" -out "$1.csr" -subj /CN=localhost 2>/dev/null
  openssl x509 -req -in "$1.csr" -CA "$2.crt" -CAkey "$2.key" -CAcreateserial -out "$1.crt" -days "$3" -extfile san.ext 2>/dev/null
}
leaf year ca 365
leaf soon ca 5
openssl req -x509 -newkey rsa:2048 -nodes -keyout other-ca.key -out other-ca.crt -days 30 -subj /CN=another-ca 2>/dev/null
leaf stranger other-ca 365

cat >server.py <<'PY'
import http.server, json, os, ssl, sys
mode, cert, portfile = sys.argv[1], sys.argv[2], sys.argv[3]
issuer = os.environ["TEST_ISSUER"]
seen = {"count": 0}
class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass
    def answer(self, status, body, kind="text/html"):
        data = body.encode()
        self.send_response(status)
        self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)
    def do_GET(self):
        seen["count"] += 1
        if mode == "flaky" and seen["count"] == 1:
            return self.answer(503, "<html>Service Unavailable</html>")
        if self.path == "/":
            return self.answer(200, "<!doctype html><html><body><p>Caddy</p></body></html>" if mode == "no-root"
                               else '<!doctype html><html><body><div id="root"></div></body></html>')
        if self.path == "/api/me":
            return self.answer(502, "") if mode == "api-502" else self.answer(401, "")
        if self.path == "/realms/myapps/.well-known/openid-configuration":
            name = "https://auth.example.test/realms/myapps" if mode == "wrong-issuer" else issuer
            return self.answer(200, json.dumps({"issuer": name}), "application/json")
        return self.answer(404, "")
httpd = http.server.HTTPServer(("127.0.0.1", 0), Handler)
context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
context.load_cert_chain(cert + ".crt", cert + ".key")
httpd.socket = context.wrap_socket(httpd.socket, server_side=True)
open(portfile, "w").write(str(httpd.server_address[1]))
httpd.serve_forever()
PY

# serve MODE CERT: a server in that mode with that certificate; sets ORIGIN.
serve() {
  [ -z "$SERVER_PID" ] || kill "$SERVER_PID" 2>/dev/null || true
  rm -f port
  TEST_ISSUER=https://localhost/realms/myapps python3 server.py "$1" "$2" port &
  SERVER_PID=$!
  local i
  for ((i = 0; i < 100; i++)); do [ -s port ] && break; sleep 0.1; done
  [ -s port ] || { echo "the test server didn't start"; exit 1; }
  ORIGIN=https://localhost:$(cat port)
}

passed=0
failures=0
# expect NAME STATUS PATTERN... -- ARGS: the monitor against ORIGIN exits with STATUS and prints every PATTERN.
expect() {
  local name=$1 status=$2 out rc=0 pattern
  shift 2
  local patterns=()
  while [ "$1" != -- ]; do patterns+=("$1"); shift; done
  shift
  out=$(MONITOR_APP=$ORIGIN MONITOR_AUTH=$ORIGIN MONITOR_ISSUER=https://localhost/realms/myapps MONITOR_CA=$WORK/ca.crt \
    MONITOR_RETRY_WAIT=0 "$MONITOR" "$@" 2>&1) || rc=$?
  local ok=yes
  [ "$rc" = "$status" ] || { echo "    exit status $rc, not $status"; ok=no; }
  for pattern in "${patterns[@]}"; do
    grep -qE -- "$pattern" <<<"$out" || { echo "    no line matching: $pattern"; ok=no; }
  done
  if [ "$ok" = yes ]; then
    echo "ok   $name"
    passed=$((passed + 1))
  else
    echo "FAIL $name"
    while IFS= read -r line; do echo "      | $line"; done <<<"$out"
    failures=$((failures + 1))
  fi
}

serve good year
expect "every check passes" 0 '^ok    the app.s page' '^ok    the api without a session: .* answered 401' \
  '^ok    the realm.s issuer' '^ok    app.s certificate: localhost: the certificate verifies' '^Every check passed\.$' --
expect "fails on purpose with --fail, after the checks" 1 '^ok    auth.s certificate' '^FAIL  on purpose' '^1 check\(s\) failed\.$' -- --fail
expect "a wrong argument" 2 '^usage: ' -- --other

serve wrong-issuer year
expect "a wrong issuer" 1 '^FAIL  the realm.s issuer: .* names the issuer https://auth.example.test/realms/myapps, not https://localhost/realms/myapps' \
  '^ok    the app.s page' --

serve api-502 year
expect "Caddy's 502 for the api" 1 '^retry the api without a session: .* answered 502' '^FAIL  the api without a session: .* answered 502 without a session, not 401' --

serve no-root year
expect "a page without the app" 1 '^FAIL  the app.s page: .* answered 200 without <div id="root">' --

serve good soon
expect "a certificate that ends in 5 days" 1 '^FAIL  app.s certificate: localhost: the certificate ends .*, less than 14 days from now' \
  '^ok    the app.s page' --

serve good stranger
expect "a certificate the CA never signed" 1 '^FAIL  the app.s page: .*: no answer' '^FAIL  app.s certificate: localhost: the TLS handshake or the certificate.s verification failed' --

serve flaky year
expect "one failed answer, then the retry passes" 0 '^retry the app.s page: .* answered 503' '^ok    the app.s page' '^Every check passed\.$' --

kill "$SERVER_PID" 2>/dev/null || true
wait "$SERVER_PID" 2>/dev/null || true
SERVER_PID=
expect "no server at all" 1 '^FAIL  the app.s page: .*: no answer' '^FAIL  the realm.s issuer: .*: no answer' \
  '^FAIL  auth.s certificate: localhost: the TLS handshake' '^5 check\(s\) failed\.$' --

echo
echo "$passed passed, $failures failed"
[ "$failures" -eq 0 ]
