# 8p security pass - local run (2026-10-31)

Staging does not exist, so this is the local variant of 8p, not the sign-off run. 8p proper (and 8q) still need a deployed staging.

**What ran:** OWASP ZAP `zap-api-scan.py` (ghcr.io/zaproxy/zaproxy:stable, active scan, OpenAPI from `/v3/api-docs`, ~110 rules) against the dev backend on the scratch DB, twice: signed in as a customer buyer (`buyer@acme.dev`) and anonymous. Reports were not kept in the repo.

| Run | Result |
|---|---|
| Authenticated (customer) | 0 FAIL, 112 PASS, 3 WARN, all on one request: `POST /api/documents/{id}/replace` with a non-multipart body returned 500 (the two "error disclosure" warnings are the same response; its body is the generic Spring error JSON with no stack or message). **Fixed:** `MultipartException` is now a 400. Test in `DocumentsTests`. |
| Anonymous | 0 FAIL, 119 PASS, 1 WARN: `Cross-Origin-Resource-Policy` missing on `/api/auth/logout` and `/api/auth/forgot-password` (204s). Left as is: no cross-origin resource embedding is in play and the wrong value could break a split frontend host. Revisit at staging with the real origins. |

**Not covered here (needs staging):** TLS and HSTS (only sent over HTTPS), cookie `Secure` over a real host, the container image scan, Front Door/WAF behaviour, ERGO-role scans (admin login needs a 2FA code), and authorization fuzzing. Cross-company access is covered by the test suite, not by ZAP, which has no notion of two tenants.

**Supplier scan (2026-11-01):** `sales@precision.dev` (Precision Castings, `SUPPLIER_RFQ`), same ZAP setup against `spring-boot:test-run`: 0 FAIL, 119 PASS, 1 WARN (the same missing `Cross-Origin-Resource-Policy`). The scan was authenticated as the supplier: ~400 admin, quality, OFP, user-write and NPD-write requests came back 403, as expected. The only 200s on endpoints the supplier has no module for are `GET /api/users` and `GET /api/companies?type=ERGO&status=PENDING_APPROVAL`; both are company-scoped in the service (own company's users, own company only), so a supplier sees no one else. Not re-checked live: ZAP's login attacks locked the account out afterwards ("Invalid email or password"), so the response bodies were read from the code, not replayed. Reports were not kept in the repo.

**ERGO role scans (2026-11-01):** `rfq@ergo.dev` (ERGO_RFQ, first of two roles), `quality@ergo.dev`, `kam@ergo.dev`, `ofp@ergo.dev` and `npd@ergo.dev` (ERGO_NPD, first of two roles), same setup: each 0 FAIL, 119 PASS, 1 WARN (the same missing `Cross-Origin-Resource-Policy`). All five were authenticated (0 responses of 401; 403 counts differ by role: 394-429, OFP the most). ERGO_ADMIN is still not scanned (login needs a 2FA code), and the second role of the two-role users (ERGO_NPD for rfq, ERGO_OFP for npd) was not scanned via switch-role.

**Re-run on the fixed build (same day):** customer (`buyer@acme.dev`) and ERGO management (`management@ergo.dev`) scans, each 0 FAIL, 119 PASS, 1 WARN: the same missing `Cross-Origin-Resource-Policy` header (now on authenticated 200s too). The 500 on `/api/documents/{id}/replace` is gone. The first re-run attempt hit the old backend process (it still held port 8080), so the numbers above come from the second one, after the old process was stopped.
