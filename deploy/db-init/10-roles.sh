#!/bin/sh
# Runs once, when the Postgres volume is first created (docker-entrypoint-initdb.d). Three logins, least privilege
# (security review H1, ARCHITECTURE.md §8 2026-11-05):
#   postgres   - superuser (POSTGRES_USER): backups and admin only, never used by the app
#   ergo_owner - owns the schema; only Flyway uses it (SPRING_FLYWAY_USER)
#   ergo_app   - the app's connection: read/write rows only. It owns nothing, so it cannot drop tables or disable the
#                audit_logs append-only trigger.
set -eu
: "${DB_OWNER_PASSWORD:?}" "${DB_APP_PASSWORD:?}"
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -v owner_pw="$DB_OWNER_PASSWORD" -v app_pw="$DB_APP_PASSWORD" <<'SQL'
CREATE ROLE ergo_owner LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD :'owner_pw';
CREATE ROLE ergo_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD :'app_pw';
SQL
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f /docker-entrypoint-initdb.d/grants.sql.txt
