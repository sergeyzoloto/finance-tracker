#!/usr/bin/env bash
# Restores a dump into a throwaway PostgreSQL container and checks it (deploy/RUNBOOK.md, step 10). Production isn't
# touched: the container has no network and is removed at the end, pass or fail.
#   restore-test.sh                  the newest dump in /var/backups/finance-tracker
#   restore-test.sh /path/to.dump    that dump
set -euo pipefail

DIR=/var/backups/finance-tracker
LIVE=finance-tracker-prod-postgres-1
TEST=finance-tracker-restore-test

# The names are UTC timestamps, so the last in alphabetical order is the newest.
dumps=("$DIR"/finance-*.dump)
dump=${1:-${dumps[-1]}}
echo "Dump: $dump ($(du -h "$dump" | cut -f1), written $(date -r "$dump" '+%Y-%m-%d %H:%M'))"

docker rm -f "$TEST" > /dev/null 2>&1 || true
docker run -d --name "$TEST" --network none --memory 256m \
    -e POSTGRES_USER=finance -e POSTGRES_DB=finance -e POSTGRES_PASSWORD=restore-test postgres:17 > /dev/null
trap 'docker rm -f "$TEST" > /dev/null' EXIT
# Over TCP, which PostgreSQL opens only once its first-start setup is done.
until docker exec "$TEST" pg_isready -q -h 127.0.0.1 -U finance -d finance; do sleep 1; done

docker exec -i "$TEST" pg_restore --username=finance --dbname=finance --exit-on-error < "$dump"
echo "Restored without errors."

CHECKS="
SELECT 'users', count(*) FROM app.users
UNION ALL SELECT 'accounts', count(*) FROM app.account
UNION ALL SELECT 'categories', count(*) FROM app.category
UNION ALL SELECT 'entries', count(*) FROM app.journal_entry
UNION ALL SELECT 'postings', count(*) FROM app.posting
UNION ALL SELECT 'manual rates', count(*) FROM app.exchange_rate WHERE source = 'MANUAL'
UNION ALL SELECT 'schema version', max(version::int) FROM app.flyway_schema_history WHERE success
UNION ALL SELECT 'unbalanced entries (must be 0)', count(*) FROM (
    SELECT 1 FROM app.posting GROUP BY entry_id, currency HAVING sum(amount) <> 0) unbalanced"
query() {
    docker exec "$1" psql --username=finance --dbname=finance --no-align --tuples-only --field-separator='|' -c "$CHECKS"
}

echo
echo "                                  restored   live now"
paste -d '|' <(query "$TEST") <(query "$LIVE" | cut -d '|' -f 2) |
    awk -F '|' '{ printf "%-32s %10s %10s\n", $1, $2, $3 }'
echo
echo "The restored numbers can be lower than the live ones by what was entered after the dump was written."

unbalanced=$(query "$TEST" | awk -F '|' '/^unbalanced/ { print $2 }')
[ "$unbalanced" = 0 ] || { echo "FAILED: the restored ledger has unbalanced entries" >&2; exit 1; }
echo "PASSED"
