#!/usr/bin/env bash
# Encrypted backup of the production database (docker-compose.oracle.yml, or Neon for docker-compose.micro-prod.yml). Documents live in Postgres, so this
# one dump is everything. The dump is encrypted to BACKUP_GPG_RECIPIENT's public key as it streams: the plaintext never
# touches the disk, and the VM cannot decrypt its own backups (the private key stays off the VM).
# Each run: dump -> copy off the machine (BACKUP_PAR_URL: Oracle write-only pre-authenticated request, or BACKUP_RCLONE_REMOTE) -> prune local copies older than KEEP_DAYS (14) ->
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

# Neon (docker-compose.micro-prod.yml: .env has DB_URL): a pinned Postgres client over verified TLS as ergo_owner, the password
# via the environment (never on a command line). Otherwise the on-VM Postgres of docker-compose.oracle.yml.
DB_URL="$(env_value DB_URL)"
if [ -n "$DB_URL" ]; then
  NEON_HOST="$(echo "$DB_URL" | sed -E 's#^jdbc:postgresql://([^/:?]+).*#\1#')"
  NEON_DB="$(echo "$DB_URL" | sed -E 's#^jdbc:postgresql://[^/]+/([^?]+).*#\1#')"
  PGPASSWORD="$(env_value DB_OWNER_PASSWORD)" docker run --rm -e PGPASSWORD -e PGSSLMODE=verify-full -e PGSSLROOTCERT=system \
    postgres:17.11-alpine@sha256:266d3c257594e195b309ae9d1805e54c788f1d7b375f56bf6c367aac6c917e7b \
    pg_dump -h "$NEON_HOST" -U ergo_owner -d "$NEON_DB" -Fc \
    | gpg --batch --yes --trust-model always --encrypt --recipient "$RECIPIENT" --output "$FILE.part"
else
  docker compose -f docker-compose.oracle.yml exec -T postgres pg_dump -U postgres -d ergo_connect -Fc \
    | gpg --batch --yes --trust-model always --encrypt --recipient "$RECIPIENT" --output "$FILE.part"
fi
# An empty schema alone is well over 10 KB, so anything smaller is a broken dump.
[ "$(wc -c < "$FILE.part")" -gt 10240 ] || { echo "dump is suspiciously small, not keeping it" >&2; exit 1; }
mv "$FILE.part" "$FILE"
echo "$(date -u +%FT%TZ) backup written: $FILE ($(du -h "$FILE" | cut -f1))"

PAR_URL="$(env_value BACKUP_PAR_URL)"
if [ -n "$PAR_URL" ]; then
  # Oracle Object Storage pre-authenticated request (write-only, one bucket, expires): no account credentials on this VM.
  # PAR_URL ends in /o/ ; the object name is appended. The bucket's retention rule stops overwrites and deletes.
  case "$PAR_URL" in https://*/o/) ;; *) echo "BACKUP_PAR_URL must be an https URL ending in /o/" >&2; exit 1;; esac
  # The URL is the secret: it goes to curl on stdin (printf is a shell builtin), never on a command line visible in ps.
  # Content-MD5 makes Object Storage reject a corrupted upload. If a retry hits an object that already landed, the
  # retention rule answers 4xx and the run reports failure (a false alarm: check the bucket before worrying).
  printf 'url = "%s%s"\n' "$PAR_URL" "$(basename "$FILE")" \
    | curl -K - -fsS -m 600 --retry 3 -o /dev/null -H "Content-MD5: $(openssl md5 -binary "$FILE" | base64)" -T "$FILE"
  echo "$(date -u +%FT%TZ) uploaded off the machine (pre-authenticated request)"
elif [ -n "$REMOTE" ]; then
  rclone copy "$FILE" "$REMOTE"
  echo "$(date -u +%FT%TZ) copied off the machine to $REMOTE"
elif [ "${BACKUP_LOCAL_ONLY:-}" != 1 ]; then
  # No ping: a backup that never left the VM must not report success (set BACKUP_LOCAL_ONLY=1 to allow it by hand).
  echo "Neither BACKUP_PAR_URL nor BACKUP_RCLONE_REMOTE is set: this backup exists only on this VM" >&2
  exit 1
fi

find "$DIR" -name 'ergo-*.dump.gpg' -mtime +"$KEEP_DAYS" -delete

if [ -n "$PING_URL" ]; then
  curl -fsS -m 20 --retry 3 -o /dev/null "$PING_URL"
fi
