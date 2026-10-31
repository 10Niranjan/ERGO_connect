# 8p security pass - local run (2026-10-31)

Staging does not exist, so this is the local variant of 8p, not the sign-off run. 8p proper (and 8q) still need a deployed staging.

**What ran:** OWASP ZAP `zap-api-scan.py` (ghcr.io/zaproxy/zaproxy:stable, active scan, OpenAPI from `/v3/api-docs`, ~110 rules) against the dev backend on the scratch DB, twice: signed in as a customer buyer (`buyer@acme.dev`) and anonymous. Reports were not kept in the repo.

| Run | Result |
|---|---|
| Authenticated (customer) | 0 FAIL, 112 PASS, 3 WARN, all on one request: `POST /api/documents/{id}/replace` with a non-multipart body returned 500 (the two "error disclosure" warnings are the same response; its body is the generic Spring error JSON with no stack or message). **Fixed:** `MultipartException` is now a 400. Test in `DocumentsTests`. |
| Anonymous | 0 FAIL, 119 PASS, 1 WARN: `Cross-Origin-Resource-Policy` missing on `/api/auth/logout` and `/api/auth/forgot-password` (204s). Left as is: no cross-origin resource embedding is in play and the wrong value could break a split frontend host. Revisit at staging with the real origins. |

**Not covered here (needs staging):** TLS and HSTS (only sent over HTTPS), cookie `Secure` over a real host, the container image scan, Front Door/WAF behaviour, ERGO-role scans (admin login needs a 2FA code), and authorization fuzzing. Cross-company access is covered by the test suite, not by ZAP, which has no notion of two tenants.
