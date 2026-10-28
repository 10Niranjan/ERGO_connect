#!/usr/bin/env bash
# Backup/restore drill and encrypted dump (Phase 8n). Needs only Docker (the Postgres client runs in a container, pinned
# by digest) and, for DUMP_OUT, gpg. It never touches the source database.
#
# DRILL (default): dumps the database, restores it into a fresh *_drill database on the same server and proves the copy
# is equivalent: every table's row count, Flyway history, sequence positions, and every trigger (the audit_logs
# append-only guard must also still refuse UPDATE, DELETE and TRUNCATE with its own error). The dump is streamed straight
# into the restore: no copy of the data is ever written to the local disk. Run it against a scratch server or a copy,
# not against production: it creates a full second copy of the database on the server it connects to.
#
# DUMP_OUT=<file>.gpg: instead, write an encrypted logical dump (AES-256, passphrase prompted by gpg; the passphrase is
# not cached in gpg-agent). The plaintext never touches the disk and the file may not be inside a git work tree. The dump holds every competitor quote, target price, hidden margin, password hash and 2FA
# secret: handle it as docs/BACKUP_AND_RECOVERY.md says.
#
# Connection: PGHOST, PGPORT, PGUSER, and the password in PGPASSWORD (if unset you are asked, so it stays out of your
# shell history). TLS defaults to verify-full against the system CA store; for a local scratch server set PGSSLMODE=disable.
#
#   PGSSLMODE=disable PGHOST=host.docker.internal PGPORT=5433 PGUSER=ergo bash infra/ops/restore-drill.sh
#   DUMP_OUT=/secure/place/ergo-2026-10-29.dump.gpg PGHOST=<server>.postgres.database.azure.com PGUSER=ergoadmin bash infra/ops/restore-drill.sh
#   KEEP=1 ...   keep the restored copy (default name ergo_connect_drill) so you can point an app at it
set -euo pipefail

: "${PGHOST:?set PGHOST}" "${PGUSER:?set PGUSER}"
if [ -z "${PGPASSWORD:-}" ]; then read -rsp "Password for $PGUSER@$PGHOST: " PGPASSWORD; echo; fi
export PGPASSWORD
PGPORT="${PGPORT:-5432}"
SRC_DB="${SRC_DB:-ergo_connect}"
COPY_DB="${COPY_DB:-ergo_connect_drill}"
# postgres:17-alpine, by digest: this container receives the admin password and the whole database. Bump by hand.
CLIENT="${PG_CLIENT_IMAGE:-postgres@sha256:266d3c257594e195b309ae9d1805e54c788f1d7b375f56bf6c367aac6c917e7b}"
export MSYS_NO_PATHCONV=1 # Git Bash on Windows would otherwise rewrite /-prefixed arguments

fail() { echo "FAILED: $*" >&2; exit 1; }

SSLMODE="${PGSSLMODE:-verify-full}"
SSLENV=(); [[ "$SSLMODE" == verify-* ]] && SSLENV=(-e "PGSSLROOTCERT=${PGSSLROOTCERT:-system}")
pg() {
  docker run --rm -i -e PGPASSWORD -e "PGSSLMODE=$SSLMODE" ${SSLENV[@]+"${SSLENV[@]}"} "$CLIENT" "$@" -h "$PGHOST" -p "$PGPORT" -U "$PGUSER"
}
q() { pg psql -X -At -v ON_ERROR_STOP=1 -d "$1" -c "$2"; }

if [ -n "${DUMP_OUT:-}" ]; then
  [ ! -e "$DUMP_OUT" ] || fail "$DUMP_OUT already exists"
  [ -d "$(dirname "$DUMP_OUT")" ] || fail "directory of $DUMP_OUT does not exist"
  # An encrypted dump of every quote and margin must never be one 'git add -A' away from GitHub history.
  if git -C "$(dirname "$DUMP_OUT")" rev-parse --is-inside-work-tree >/dev/null 2>&1; then fail "$DUMP_OUT is inside a git work tree; choose a location outside any repository"; fi
  # Written under a temp name and renamed on success: a failed dump must not leave behind an encrypted empty file that
  # looks like a backup.
  trap 'rm -f "$DUMP_OUT.part"' EXIT
  pg pg_dump -Fc --no-owner --no-acl -d "$SRC_DB" | gpg --symmetric --cipher-algo AES256 --no-symkey-cache ${GPG_OPTS:-} --output "$DUMP_OUT.part"
  [ -s "$DUMP_OUT.part" ] || fail "empty dump"
  mv "$DUMP_OUT.part" "$DUMP_OUT"
  echo "wrote $DUMP_OUT ($(wc -c < "$DUMP_OUT") bytes). Restore: gpg -d $DUMP_OUT | docker run --rm -i ... pg_restore --no-owner --no-acl --exit-on-error -d <empty db>"
  exit 0
fi

# The drill drops COPY_DB first, so it may only ever be a throwaway name ending in _drill.
[[ "$COPY_DB" =~ ^[a-z_][a-z0-9_]*_drill$ ]] || fail "COPY_DB must be a throwaway name ending in _drill (got '$COPY_DB')"
[ "$COPY_DB" != "$SRC_DB" ] || fail "COPY_DB equals SRC_DB"

# One line per table: name|row count (exact counts, via query_to_xml, so no statistics are trusted).
COUNTS="select table_name || '|' || (xpath('/row/c/text()', query_to_xml(format('select count(*) as c from %I.%I', table_schema, table_name), false, true, '')))[1]::text
        from information_schema.tables where table_schema = 'public' and table_type = 'BASE TABLE' order by 1"
FLYWAY="select installed_rank || '|' || version || '|' || coalesce(checksum::text, '') || '|' || success from flyway_schema_history order by installed_rank"
SEQS="select sequencename || '|' || coalesce(last_value::text, 'unused') from pg_sequences where schemaname = 'public' order by 1"
# Every user trigger on every table (the append-only guards on audit_logs and the other history tables among them).
TRIGS="select c.relname || '|' || t.tgname || '|' || t.tgenabled::text from pg_trigger t join pg_class c on c.oid = t.tgrelid
       where not t.tgisinternal and c.relnamespace = 'public'::regnamespace order by 1"

# Same non-empty result in both databases. Captured first so a failing query stops the drill (a bare diff of two
# failed queries would compare two empty outputs and pass).
same() {
  local a b
  a="$(q "$SRC_DB" "$1")" || fail "query failed on $SRC_DB"
  b="$(q "$COPY_DB" "$1")" || fail "query failed on $COPY_DB"
  [ -n "$a" ] && [ "$a" = "$b" ]
}

echo "1/6 create fresh database $COPY_DB"
q postgres "drop database if exists \"$COPY_DB\"" >/dev/null
q postgres "create database \"$COPY_DB\"" >/dev/null

echo "2/6 dump $SRC_DB straight into the restore (nothing is written to local disk)"
# pipefail: a failure of either side stops the drill.
pg pg_dump -Fc --no-owner --no-acl -d "$SRC_DB" | pg pg_restore --no-owner --no-acl --exit-on-error -d "$COPY_DB"

echo "3/6 compare row counts of every table"
same "$COUNTS" || fail "row counts differ"
echo "    $(q "$COPY_DB" "$COUNTS" | wc -l | tr -d ' ') tables identical"

echo "4/6 compare Flyway history and sequences"
same "$FLYWAY" || fail "Flyway history differs (checksums must match or the app refuses to start)"
same "$SEQS" || fail "sequence positions differ (new rows would collide with restored ones)"
echo "    $(q "$COPY_DB" "$FLYWAY" | wc -l | tr -d ' ') migrations, sequences identical"

echo "5/6 triggers present and enabled on every table"
same "$TRIGS" || fail "triggers differ"
[ "$(q "$COPY_DB" "select count(*) from pg_trigger where tgrelid = 'public.audit_logs'::regclass and not tgisinternal")" -ge 2 ] \
  || fail "audit_logs append-only triggers are missing"
echo "6/6 audit_logs still refuses UPDATE, DELETE and TRUNCATE (must fail with the trigger's own message)"
[ "$(q "$COPY_DB" "select count(*) from audit_logs")" -gt 0 ] || fail "audit_logs is empty, so the row-level UPDATE/DELETE guard cannot be exercised; drill a database with data"
for stmt in "update audit_logs set module_name = module_name" "delete from audit_logs" "truncate audit_logs"; do
  out="$(pg psql -X -At -d "$COPY_DB" -c "$stmt" 2>&1 || true)"
  case "$out" in *"audit_logs is append-only"*) ;; *) fail "'$stmt' was not stopped by the append-only trigger: $out" ;; esac
done

if [ -z "${KEEP:-}" ]; then q postgres "drop database \"$COPY_DB\"" >/dev/null; echo "(copy dropped; KEEP=1 keeps it)"; fi
echo "DRILL PASSED"
