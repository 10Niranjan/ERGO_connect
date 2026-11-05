#!/usr/bin/env bash
# Encrypted backup of the Oracle production database (docker-compose.oracle.yml). Documents live in Postgres, so this
# one dump is everything. The dump is encrypted to BACKUP_GPG_RECIPIENT's public key as it streams: the plaintext never
# touches the disk, and the VM cannot decrypt its own backups (the private key stays off the VM).
# Each run: dump -> copy off the machine (BACKUP_RCLONE_REMOTE) -> prune local copies older than KEEP_DAYS (14) ->
# ping BACKUP_PING_URL. Any failure stops the run before the ping, so a missed ping means a failed backup.
#
# Nightly via cron (crontab -e), e.g.:  30 2 * * * /home/ubuntu/ERGO_connect/deploy/backup.sh >> /home/ubuntu/ergo-backup.log 2>&1
# Restore: README.md "Production on Oracle", step 7.
set -euo pipefail
cd "$(dirname "$0")"

env_value() { grep -E "^$1=" .env | tail -n1 | cut -d= -f2- || true; }
RECIPIENT="$(env_value BACKUP_GPG_RECIPIENT)"
REMOTE="$(env_value BACKUP_RCLONE_REMOTE)"
PING_URL="$(env_value BACKUP_PING_URL)"
[ -n "$RECIPIENT" ] || { echo "BACKUP_GPG_RECIPIENT is not set in .env" >&2; exit 1; }

DIR="${BACKUP_DIR:-$HOME/ergo-backups}"
KEEP_DAYS="${KEEP_DAYS:-14}"
mkdir -p "$DIR" && chmod 700 "$DIR"
FILE="$DIR/ergo-$(date -u +%Y%m%dT%H%M%SZ).dump.gpg"
trap 'rm -f "$FILE.part"' EXIT

docker compose -f docker-compose.oracle.yml exec -T postgres pg_dump -U postgres -d ergo_connect -Fc \
  | gpg --batch --yes --trust-model always --encrypt --recipient "$RECIPIENT" --output "$FILE.part"
# An empty schema alone is well over 10 KB, so anything smaller is a broken dump.
[ "$(wc -c < "$FILE.part")" -gt 10240 ] || { echo "dump is suspiciously small, not keeping it" >&2; exit 1; }
mv "$FILE.part" "$FILE"
echo "$(date -u +%FT%TZ) backup written: $FILE ($(du -h "$FILE" | cut -f1))"

if [ -n "$REMOTE" ]; then
  rclone copy "$FILE" "$REMOTE"
  echo "$(date -u +%FT%TZ) copied off the machine to $REMOTE"
elif [ "${BACKUP_LOCAL_ONLY:-}" != 1 ]; then
  # No ping: a backup that never left the VM must not report success (set BACKUP_LOCAL_ONLY=1 to allow it by hand).
  echo "BACKUP_RCLONE_REMOTE is empty: this backup exists only on this VM" >&2
  exit 1
fi

find "$DIR" -name 'ergo-*.dump.gpg' -mtime +"$KEEP_DAYS" -delete

if [ -n "$PING_URL" ]; then
  curl -fsS -m 20 --retry 3 -o /dev/null "$PING_URL"
fi
