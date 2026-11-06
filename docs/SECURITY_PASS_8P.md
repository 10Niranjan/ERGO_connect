# 8p security pass - local run (2026-10-31)

Staging does not exist, so the first run below is the local variant of 8p. The live run against the deployed production system is the last section (2026-11-06).

**What ran:** OWASP ZAP `zap-api-scan.py` (ghcr.io/zaproxy/zaproxy:stable, active scan, OpenAPI from `/v3/api-docs`, ~110 rules) against the dev backend on the scratch DB, twice: signed in as a customer buyer (`buyer@acme.dev`) and anonymous. Reports were not kept in the repo.

| Run | Result |
|---|---|
| Authenticated (customer) | 0 FAIL, 112 PASS, 3 WARN, all on one request: `POST /api/documents/{id}/replace` with a non-multipart body returned 500 (the two "error disclosure" warnings are the same response; its body is the generic Spring error JSON with no stack or message). **Fixed:** `MultipartException` is now a 400. Test in `DocumentsTests`. |
| Anonymous | 0 FAIL, 119 PASS, 1 WARN: `Cross-Origin-Resource-Policy` missing on `/api/auth/logout` and `/api/auth/forgot-password` (204s). Left as is: no cross-origin resource embedding is in play and the wrong value could break a split frontend host. Revisit at staging with the real origins. |

**Not covered here (needs staging):** TLS and HSTS (only sent over HTTPS), cookie `Secure` over a real host, the container image scan, Front Door/WAF behaviour, ERGO-role scans (admin login needs a 2FA code), and authorization fuzzing. Cross-company access is covered by the test suite, not by ZAP, which has no notion of two tenants.

**Supplier scan (2026-11-01):** `sales@precision.dev` (Precision Castings, `SUPPLIER_RFQ`), same ZAP setup against `spring-boot:test-run`: 0 FAIL, 119 PASS, 1 WARN (the same missing `Cross-Origin-Resource-Policy`). The scan was authenticated as the supplier: ~400 admin, quality, OFP, user-write and NPD-write requests came back 403, as expected. The only 200s on endpoints the supplier has no module for are `GET /api/users` and `GET /api/companies?type=ERGO&status=PENDING_APPROVAL`; both are company-scoped in the service (own company's users, own company only), so a supplier sees no one else. Not re-checked live: ZAP's login attacks locked the account out afterwards ("Invalid email or password"), so the response bodies were read from the code, not replayed. Reports were not kept in the repo.

**ERGO role scans (2026-11-01):** `rfq@ergo.dev` (ERGO_RFQ, first of two roles), `quality@ergo.dev`, `kam@ergo.dev`, `ofp@ergo.dev` and `npd@ergo.dev` (ERGO_NPD, first of two roles), same setup: each 0 FAIL, 119 PASS, 1 WARN (the same missing `Cross-Origin-Resource-Policy`). All five were authenticated (0 responses of 401; 403 counts differ by role: 394-429, OFP the most). ERGO_ADMIN is still not scanned (login needs a 2FA code), and the second role of the two-role users (ERGO_NPD for rfq, ERGO_OFP for npd) was not scanned via switch-role.

**Re-run on the fixed build (same day):** customer (`buyer@acme.dev`) and ERGO management (`management@ergo.dev`) scans, each 0 FAIL, 119 PASS, 1 WARN: the same missing `Cross-Origin-Resource-Policy` header (now on authenticated 200s too). The 500 on `/api/documents/{id}/replace` is gone. The first re-run attempt hit the old backend process (it still held port 8080), so the numbers above come from the second one, after the old process was stopped.

## 8p live run against production (2026-11-06)

Target: `https://130-210-58-133.sslip.io` (Oracle Micro VM + Neon, commit `c477b11`, prod profile). Production holds real data, so **no active attack scan ran against it**: the active ZAP API scans stay the local ones above (same code). This run covers what only a deployment shows. Scripts were throwaway and are not in the repo.

**Result: 0 High, 0 Medium confirmed exploitable; 1 hardening gap worth fixing (F1) and 5 Low/Info items.**

| Check | Result |
|---|---|
| TLS | 1.0 and 1.1 refused; 1.2 (ECDHE-ECDSA-AES128-GCM) and 1.3 accepted; Let's Encrypt certificate, 89 days left, Caddy renews it automatically |
| HTTP | port 80 answers 308 to https |
| Headers (SPA) | HSTS 1 year + includeSubDomains, nosniff, `frame-ancestors 'none'`, `Referrer-Policy: no-referrer`, no `X-Powered-By`; `Server: nginx` carries no version |
| Headers (API) | HSTS, nosniff, `X-Frame-Options: DENY`, `Cache-Control: no-store` |
| Session cookie | `ergo_session`: `Secure; HttpOnly; SameSite=Strict; Path=/api/auth` |
| Exposed paths | 19 probes (actuator env/heapdump/beans, swagger, api-docs, `.env`, `.git`, h2-console, path traversal, server-status): none exposed (a 200 is the web app's HTML fallback; API-prefixed ones answer 401); the actuator health path is not public |
| Authentication | 9 representative API endpoints refuse anonymous and bogus-bearer requests (401); anonymous POST /api/users refused |
| Errors | malformed JSON 400, wrong content type 415, TRACE 405, oversized header 400, odd unicode path 400: no stack traces or internals in any body |
| CORS | foreign origin gets no `Access-Control-Allow-Origin`; preflight from a foreign origin 403 |
| Login | wrong login 401 with a generic message (does not reveal whether the account exists); forgot-password answers 204 for an unknown address |
| Rate limiting | from the VM: attempts 1-20 answered 401, 21+ answered 429; the limiter is per client IP (another IP was unaffected); 5 wrong passwords lock an account 15 minutes (tested in `AdminTwoFactorOptOutTest`) |
| Audit log | `UPDATE`, `DELETE` and `TRUNCATE` on `audit_logs` are all refused ("append-only") even as the schema-owner database login; 138 rows survive; all of the day's actions are present (BOOTSTRAP_ADMIN_CREATED, CREATE, ROLE_ADDED, USER_DELETED, LOGIN_WITHOUT_SECOND_FACTOR, ...) |
| Client IP | `user_sessions.ip_address` holds the real public address, not an internal `172.x` one |
| Database logins | `ergo_app` cannot DROP/TRUNCATE/disable triggers on `audit_logs`, create tables or `SET ROLE ergo_owner`; neither role has CREATEROLE/CREATEDB/BYPASSRLS or any membership (checked after the role rebuild) |
| Server | only 22, 80, 443 reachable (iptables ends in REJECT; the Oracle security list agrees); SSH key-only (`PasswordAuthentication no`); `.env` and `authorized_keys` mode 600; unattended-upgrades active, 0 security updates pending, no reboot pending; backend runs as uid 100, frontend as 101, no privileged containers or added capabilities |
| Passive ZAP baseline | `zap-baseline.py` against the live site: **0 FAIL, 59 PASS, 9 WARN** (all on the static web app, see F1-F4) |
| Supply chain | the deployed images passed Trivy (CI gate: Maven dependencies, plus OS packages of both architectures); 14 real CVEs were found and fixed on the way (Spring Boot 4.1.1 to 4.1.2, Alpine packages) |

### Findings
| # | Severity | Finding | Recommended fix |
|---|---|---|---|
| F1 | Low-Medium | The web app's Content-Security-Policy is only `frame-ancestors 'none'`: it blocks framing but sets no `default-src`/`script-src`, so it gives no second line of defence if an XSS bug ever appears (ZAP 10055, x12). | Add a full CSP in `ergo-connect-frontend/nginx.conf.template`: `default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'`, after self-hosting the Inter font (F4). Needs a real browser test of every screen (MUI/emotion injects inline styles, uploads use blob: previews) before it ships, so it was not changed blindly on production. |
| F2 | Low | No `Permissions-Policy` (ZAP 10063), no cross-origin isolation headers (COEP, 90004). | `Permissions-Policy: camera=(), microphone=(), geolocation=()` in nginx; COEP only if needed. |
| F3 | Low | API JSON responses have no `Referrer-Policy` (the web app does). No impact on JSON. | Add `headers().referrerPolicy(NO_REFERRER)` in `SecurityConfig`. |
| F4 | Low | The page loads Google Fonts (CSS and font files) from fonts.googleapis.com/gstatic.com without Subresource Integrity (ZAP 90003). Third-party dependency, and each visitor's IP goes to Google. | Self-host Inter in the frontend image; this also lets F1's CSP drop the Google origins. |
| F5 | Low | Server: `rpcbind` listens on all interfaces (the firewall blocks it); SSH root login is `without-password` (root has no key); SSH is open to the whole internet with no fail2ban (key-only makes this low). | `systemctl disable --now rpcbind rpcbind.socket`; `PermitRootLogin no`; optionally restrict port 22 to known IPs. |
| F6 | Info | The Caddy and Redis containers run as root inside their containers (image defaults), not privileged. | Accept, or switch to the non-root Caddy variant. |
| F7 | Info | ZAP: cache-control/"storable" on static assets, "Unix timestamp" and "suspicious comments" in the bundled JavaScript, "modern web application": normal for a built SPA. | None. |

**Open by decision, not by defect:** the ERGO Admin signs in with the password alone while `ERGO_SECURITY_ADMIN_2FA_REQUIRED=false` (ARCHITECTURE.md §8, 2026-11-06); each such sign-in is audited and emailed. Re-enable the emailed code before real customers are onboarded.

**Not covered:** active scanning of the live system; role-by-role and cross-company tests on the live instance (it has no pilot users; the automated campaign covers them); denial-of-service and load testing on the 1 GB VM (`docs/LOAD_TESTING.md` is local); the provider consoles (Neon, Oracle, Brevo) beyond what is stated; a third-party penetration test.

