#!/usr/bin/env bash
# Post-deploy smoke test. Also runs against the local compose stack: .github/scripts/smoke.sh http://localhost:8081
set -euo pipefail

base="${1:?usage: smoke.sh <base-url>}"
base="${base%/}"
fail() { echo "::error::smoke test failed: $*"; exit 1; }
hdrs="$(mktemp)"
trap 'rm -f "$hdrs"' EXIT

# The new revision may still be taking traffic.
code="$(curl -s -o /dev/null -w '%{http_code}' --retry 10 --retry-delay 6 --retry-all-errors --max-time 20 "$base/" || true)"
[ "$code" = 200 ] || fail "GET / returned $code"
curl -sI --max-time 20 "$base/" | grep -qi '^x-frame-options: deny' \
  || fail "SPA response lacks X-Frame-Options - nginx template not rendered (8j)"

# Bad credentials must reach the backend and be refused by it: proves frontend -> backend -> database end to end.
code=000
for _ in 1 2 3 4 5 6 7 8 9 10; do
  code="$(curl -s -D "$hdrs" -o /dev/null -w '%{http_code}' --max-time 30 -X POST "$base/api/auth/login" \
    -H 'Content-Type: application/json' -d '{"email":"smoke@invalid.example","password":"not-a-real-password"}' || true)"
  case "$code" in 000|502|503|504) sleep 6 ;; *) break ;; esac
done
[ "$code" = 401 ] || fail "POST /api/auth/login with bad credentials returned $code (expected 401)"

# HSTS only appears when the backend believes the request was HTTPS, i.e. X-Forwarded-Proto survived every hop (8c/8j).
case "$base" in
  https://*) grep -qi '^strict-transport-security:' "$hdrs" \
    || fail "/api response lacks Strict-Transport-Security - X-Forwarded-Proto is not reaching the backend" ;;
esac

echo "smoke test passed for $base"
