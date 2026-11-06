#!/usr/bin/env bash
# Starts the production stack on this PC behind a Cloudflare quick tunnel. Run from deploy/. See docker-compose.home.yml.
set -euo pipefail
cd "$(dirname "$0")"
C="docker compose -f docker-compose.oracle.yml -f docker-compose.home.yml"
[ -f .env ] || { echo "no .env: cp oracle.env.example .env and fill it (DOMAIN may stay blank here)"; exit 1; }

# DOMAIN only needs a placeholder to start the tunnel; the real value is the tunnel URL, set below.
grep -q '^DOMAIN=.' .env || sed -i 's/^DOMAIN=.*/DOMAIN=pending.invalid/' .env
$C up -d --no-deps frontend tunnel
for i in $(seq 1 30); do
  URL=$($C logs tunnel 2>&1 | grep -oE 'https://[a-z0-9]+(-[a-z0-9]+)+\.trycloudflare\.com' | tail -1 || true)
  [ -n "$URL" ] && break; sleep 2
done
[ -n "${URL:-}" ] || { echo "tunnel URL not found: $C logs tunnel"; exit 1; }
curl -fsS -m 20 -o /dev/null "$URL" || { echo "tunnel URL $URL does not answer yet: re-run in a minute"; exit 1; }
sed -i "s|^DOMAIN=.*|DOMAIN=${URL#https://}|" .env
$C up -d
echo "NOTE: the URL changes whenever the tunnel container restarts: re-run this script then, and share the new link."
echo "Up at $URL (first backend start runs all migrations: $C logs -f backend)"
