#!/usr/bin/env bash
# Nightly backup of Finance Tracker's database (deploy/RUNBOOK.md, step 9), run by finance-tracker-backup.timer:
# - a pg_dump into /var/backups/finance-tracker, kept for 14 days;
# - a copy of every new dump to the Hetzner Storage Box named by OFFSITE in /etc/finance-tracker/backup.env.
# By hand: systemctl start finance-tracker-backup.service, then journalctl -u finance-tracker-backup.service
set -euo pipefail

CONTAINER=finance-tracker-prod-postgres-1
DIR=/var/backups/finance-tracker
KEEP_DAYS=14
SSH_KEY=/root/.ssh/finance-tracker-backup

umask 077
mkdir -p "$DIR"
file="$DIR/finance-$(date -u +%Y-%m-%dT%H%MZ).dump"

# Custom format: compressed, and pg_restore can restore all of it or single tables.
docker exec "$CONTAINER" pg_dump --username=finance --dbname=finance --format=custom > "$file.partial"
# A dump that pg_restore can't read is no backup.
docker exec -i "$CONTAINER" pg_restore --list < "$file.partial" > /dev/null
mv "$file.partial" "$file"
echo "Dumped $(du -h "$file" | cut -f1) to $file"

# Tonight's dump and the 13 before it stay.
find "$DIR" -maxdepth 1 -name 'finance-*.dump' -mtime +$((KEEP_DAYS - 1)) -print -delete
find "$DIR" -maxdepth 1 -name '*.partial' -mmin +60 -delete

if [ -z "${OFFSITE:-}" ]; then
    echo "OFFSITE isn't set in /etc/finance-tracker/backup.env, so nothing was copied off the server" >&2
    exit 1
fi
# New dumps only, and nothing is deleted there: the Storage Box keeps them all.
rsync --archive --ignore-existing --exclude='*.partial' \
    -e "ssh -p 23 -i $SSH_KEY -o BatchMode=yes" "$DIR"/ "$OFFSITE:finance-tracker/"
echo "Copied to $OFFSITE:finance-tracker/"
