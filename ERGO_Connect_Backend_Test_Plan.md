# ERGO Connect — Backend Test Plan & Prioritized Test-Case Catalog
**Branch reviewed:** `sprint-1`
**Prepared:** 2026-10-03
**Audience:** Backend engineers, QA, DevOps
**Classification:** Internal — Engineering

---

## 0. Scope & Access Note

This document is a **complete, enterprise-grade backend test framework** for ERGO Connect, organized so that the highest-risk, highest-value test suites are built first (P0), then broadened (P1/P2).

> **Access note:** `https://github.com/varadyadav02/ERGO_connect` (branch `sprint-1`) returns **HTTP 404** to both anonymous `git clone` and the GitHub REST API. This means the repository is **private** (or renamed/transferred), so it could not be read from this environment. Everything below is therefore a **stack-agnostic framework with clearly marked placeholders** (`«...»`). Section 13 lists the exact artifacts needed to bind this plan to your real code (endpoints, entities, roles, config). No project-specific behavior has been assumed or fabricated.

### Assumptions to confirm
- A1. Backend exposes a **REST/HTTP API** consumed by a client app (mobile/web).
- A2. There is **authentication + role-based authorization** (multi-role: e.g. admin / org / end-user).
- A3. A **relational database** (SQL) backs the service.
- A4. There are **at least one external integration** (payment, SMS/OTP, email, maps, or third-party API).

If any of A1–A4 is wrong, tell me and the relevant suites will be re-scoped.

---

## 1. Purpose

- Establish a **prioritized, risk-based backend test strategy** for the code as it exists today.
- Define **what to test, how, and in what order**, so a small team gets maximum defect-detection per engineering hour.
- Provide a **reusable catalog** with concrete, ID-tagged test cases for the critical paths.
- Define **quality gates** so "done" is measurable, not subjective.

---

## 2. Test Levels (the Backend Test Pyramid)

Build bottom-up. Do **not** jump straight to E2E — it is slow and brittle.

| Level | What it verifies | Scope | Speed | Share of suite |
|---|---|---|---|---|
| **L1 — Unit** | Single function/class in isolation; business rules, validators, mappers, utils | 1 unit, deps mocked | ms | ~60% |
| **L2 — Integration** | Component + real DB / real message broker / real cache | 1 module + infra | 100 ms–2 s | ~25% |
| **L3 — Component / API** | Full service via HTTP, real DB (Testcontainers), external deps stubbed | 1 service boundary | 1–10 s | ~10% |
| **L4 — Contract** | Provider/consumer schema compatibility (OpenAPI / Pact) | service ↔ service | fast | ongoing |
| **L5 — E2E** | Full user journey across all services | whole system | 10 s–min | ~5% |
| **L6 — Non-functional** | Perf, security, resilience, soak | whole system | minutes–hours | scheduled |

**Rule:** every bug fixed gets a regression test at the **lowest level that can reproduce it**.

---

## 3. Test Types Catalog

### 3.1 Functional

| ID | Type | Purpose | Example focus |
|---|---|---|---|
| FT-01 | Unit / logic | Correctness of business rules | pricing, status transitions, eligibility |
| FT-02 | API functional | Endpoint behaves per spec | status codes, payloads |
| FT-03 | CRUD / persistence | Create-read-update-delete correctness | round-trip integrity |
| FT-04 | Input validation | Reject bad input safely | required, type, format, range |
| FT-05 | Boundary / edge | Off-by-one, empty, max, unicode | min-1/min/max/max+1 |
| FT-06 | State-machine | Legal/illegal transitions | order/booking/approval flows |
| FT-07 | Authorization (RBAC/ABAC) | Roles see/do only what allowed | 401 vs 403 vs 404 |
| FT-08 | Business workflow | Multi-step end-to-end logic | signup → verify → activate |
| FT-09 | Idempotency | Duplicate requests safe | payment/OTP/retries |
| FT-10 | Pagination/filter/sort | List endpoints correct & bounded | page size caps, stable sort |
| FT-11 | Error handling | Fail gracefully & informatively | 4xx/5xx semantics |
| FT-12 | Concurrency / race | No lost updates, no double-spend | optimistic/pessimistic locking |
| FT-13 | Integration | Third-party contract honored | timeouts, error mapping |
| FT-14 | Data integrity | Constraints, transactions, rollback | atomicity, orphans |

### 3.2 Non-Functional

| ID | Type | Purpose | Example focus |
|---|---|---|---|
| NF-01 | Security | OWASP API Top 10 | BOLA, injection, mass-assignment |
| NF-02 | Performance / load | Meet SLO under expected load | p95 latency, RPS |
| NF-03 | Stress / spike | Behavior past capacity | graceful degradation |
| NF-04 | Soak / endurance | Stability over time | memory leaks, connection pools |
| NF-05 | Scalability | Horizontal scaling correctness | statelessness, sticky sessions |
| NF-06 | Reliability / chaos | Survive dependency failure | timeouts, circuit breakers |
| NF-07 | Observability | Logs/metrics/traces/audit | correlation IDs, PII redaction |
| NF-08 | Configuration | Env/config correctness | 12-factor, secrets |
| NF-09 | Compatibility | Backward-compat of API/DB | versioning, deprecation |
| NF-10 | Data migration | Schema/backfill safety | forward + rollback |
| NF-11 | Backup / recovery | RPO/RTO achievable | restore drill |
| NF-12 | Compliance / privacy | PII, GDPR, audit trail | data retention, consent |
| NF-13 | Accessibility of errors | Machine-readable error contract | RFC 7807 problem+json |
| NF-14 | Localization | i18n of messages/currency/tz | tz-correct timestamps |

---

## 4. Priority Model

| Tier | Meaning | Build when | Blast radius if untested |
|---|---|---|---|
| **P0** | Money, identity, data-loss, security, core flow | **Sprint 1 — immediately** | Catastrophic / unrecoverable |
| **P1** | Correctness under load, integration, resilience, observability | Sprint 2 | Major, recoverable |
| **P2** | Edge, compatibility, compliance, DR | Sprint 3+ | Minor / long-tail |

**P0 is defined by risk = probability × impact.** Anything touching **auth, authorization, payments/PII, or irreversible state** is P0 by default.

---

## 5. P0 — Top-Priority Suites (build first)

### 5.1 Authentication (FT-07 / NF-01)

| ID | Title | Steps | Expected | Type |
|---|---|---|---|---|
| AUTH-001 | Valid login | POST credentials «/auth/login» | 200, valid token, no password echoed | API |
| AUTH-002 | Wrong password | valid email + bad password | 401, generic message | API |
| AUTH-003 | Unknown user | unknown email | 401, **same message + similar timing** as AUTH-002 (no user enumeration) | API |
| AUTH-004 | Brute-force / lockout | N failed attempts | 429/423 + lockout after threshold | Sec |
| AUTH-005 | Expired token | call API with expired JWT | 401 | API |
| AUTH-006 | Forged/tampered token | alter JWT signature/claims | 401, signature verified | Sec |
| AUTH-007 | `alg:none` / weak-alg attack | craft token | 401 | Sec |
| AUTH-008 | Refresh rotation + reuse detection | reuse old refresh token | old token invalidated, reuse flagged | API |
| AUTH-009 | Logout invalidation | logout then reuse token | 401 | API |
| AUTH-010 | Password-reset token | reuse / expired / guessed token | single-use, TTL enforced | API |
| AUTH-011 | Password policy | weak passwords on register/change | 400 with reasons | FT |
| AUTH-012 | OTP (if used) | wrong/expired/replayed OTP | rejected; rate-limited | Sec |

### 5.2 Authorization — the #1 real-world API risk (NF-01 BOLA/BFLA)

| ID | Title | Steps | Expected |
|---|---|---|---|
| AUTHZ-001 | Vertical: user → admin endpoint | user token on «/admin/**» | 403 |
| AUTHZ-002 | Horizontal (BOLA): user A reads user B's record | A's token, B's id | 403/404, never B's data |
| AUTHZ-003 | Update/delete ownership | A modifies B's resource | 403/404 |
| AUTHZ-004 | IDOR on nested resources | guess ids in «/org/{id}/...» | denied |
| AUTHZ-005 | Role escalation via mass assignment | send `"role":"admin"` in profile update | ignored/rejected |
| AUTHZ-006 | Missing-auth on each endpoint | call without token | 401 on **every** protected route |
| AUTHZ-007 | Tenant isolation | cross-tenant query | zero cross-tenant leakage |

### 5.3 Input Validation & Injection (FT-04/05, NF-01)

| ID | Title | Expected |
|---|---|---|
| VAL-001 | Missing required field | 400, field-level errors |
| VAL-002 | Wrong type (string for int, etc.) | 400 |
| VAL-003 | Boundary: min-1 / min / max / max+1 | correct accept/reject |
| VAL-004 | Oversized body | 413, no OOM |
| VAL-005 | Malformed JSON / XML | 400, no stack trace leaked |
| VAL-006 | SQL injection payloads (`' OR 1=1--`) | parameterized → no effect |
| VAL-007 | NoSQL injection (`{"$gt":""}`) | rejected/sanitized |
| VAL-008 | Stored/reflected XSS in text fields | escaped on output |
| VAL-009 | Unicode / emoji / RTL / null bytes | handled, no crash |
| VAL-010 | Mass assignment on create/update | unknown fields ignored |
| VAL-011 | SSRF via URL params (webhooks/avatars) | internal IPs blocked |
| VAL-012 | Path traversal in file names | sanitized |

### 5.4 Data Integrity & Transactions (FT-14, FT-12)

| ID | Title | Expected |
|---|---|---|
| DB-001 | Transaction rollback on mid-flow failure | no partial writes |
| DB-002 | Unique-constraint violation (dup email) | 409, friendly message |
| DB-003 | FK integrity | no orphans; cascade correct |
| DB-004 | Concurrent update (lost update) | optimistic lock → conflict detected |
| DB-005 | Double-submit / double-spend | idempotency key prevents duplicate |
| DB-006 | Soft-delete semantics | deleted rows excluded from reads |
| DB-007 | Timestamps & timezone | stored UTC, rendered correctly |
| DB-008 | Money/decimal precision | no float rounding errors |

### 5.5 Core Business Flow (FT-08, FT-06)

| ID | Title | Expected |
|---|---|---|
| BIZ-001 | Happy-path primary journey «...» | completes, correct state + side effects |
| BIZ-002 | Illegal state transition | rejected with clear error |
| BIZ-003 | Duplicate business action | deduped / idempotent |
| BIZ-004 | Cancellation / reversal | compensating actions correct |
| BIZ-005 | Partial failure in multi-step flow | consistent, resumable |

### 5.6 Error Handling & Resilience (FT-11, NF-06)

| ID | Title | Expected |
|---|---|---|
| ERR-001 | Downstream timeout | 503/504, no hung thread |
| ERR-002 | Downstream 5xx | mapped error, retry/backoff |
| ERR-003 | Circuit breaker opens | fast-fail, recovers |
| ERR-004 | DB unavailable | clear 503, no leak |
| ERR-005 | Unhandled exception | 500, correlation id, **no stack trace to client** |
| ERR-006 | Consistent error schema (RFC 7807) | all errors same shape |

---

## 6. P1 — High-Priority Suites

| ID | Type | Focus | Key cases |
|---|---|---|---|
| P1-01 | Performance (NF-02) | p95/p99 latency, throughput | baseline, peak, target RPS |
| P1-02 | Stress/Spike (NF-03) | degradation | 2×–5× load, recovery |
| P1-03 | Soak (NF-04) | stability | 2–24 h run, leak/pool check |
| P1-04 | Concurrency (FT-12) | race conditions | parallel writes on same row |
| P1-05 | Integration (FT-13) | third-party | sandbox, error codes, retries |
| P1-06 | Idempotency (FT-09) | duplicate safety | repeated POSTs, webhook replay |
| P1-07 | Pagination/Filter/Sort (FT-10) | list endpoints | caps, stable order, deep pages |
| P1-08 | Caching | correctness | invalidation, stale reads, TTL |
| P1-09 | Messaging/Events | delivery | ordering, at-least-once, DLQ |
| P1-10 | Observability (NF-07) | logs/metrics/traces | correlation IDs, PII redaction |
| P1-11 | Config/Secrets (NF-08) | env correctness | no secrets in code/logs, fail-fast |
| P1-12 | Contract (L4) | schema compat | OpenAPI diff, consumer-driven |
| P1-13 | Regression | no breakage | full P0 set on every merge |
| P1-14 | File upload/download | safety | type/size limits, AV scan, path safety |

---

## 7. P2 — Medium-Priority Suites

| ID | Type | Focus |
|---|---|---|
| P2-01 | Compatibility (NF-09) | API versioning, deprecation windows |
| P2-02 | Data migration (NF-10) | forward + rollback scripts, backfill |
| P2-03 | Backup/Recovery (NF-11) | restore drill, RPO/RTO |
| P2-04 | Compliance/Privacy (NF-12) | GDPR, data retention, consent, audit |
| P2-05 | Localization (NF-14) | i18n, currency, timezone |
| P2-06 | Accessibility of errors (NF-13) | machine-readable, documented codes |
| P2-07 | Scalability (NF-05) | statelessness, horizontal scale |
| P2-08 | Smoke (L1') | post-deploy sanity |

---

## 8. Cross-Cutting: Test Data, Environments, Tooling

**Test data**
- Deterministic **seed/factory** data; never depend on production data.
- Dedicated **synthetic PII** (no real user data anywhere).
- Data builders per entity; reset DB between integration runs (Testcontainers).

**Environments**
- `local` (docker-compose), `ci` (ephemeral), `staging` (prod-like), `prod` (smoke only).
- Same infra versions across staging/prod; config via env vars.

**Tooling (pick per stack)**
- Unit/IT: «JUnit 5 + Mockito + Testcontainers» | «Jest/Pytest + supertest/pytest».
- API: Postman/Newman, REST Assured, Karate, or pytest+httpx.
- Contract: OpenAPI validator, Pact.
- Perf: k6 / Gatling / JMeter.
- Security: OWASP ZAP, Burp, Semgrep, Trivy, dependency scanning.
- Coverage: JaCoCo / coverage.py; enforce a floor (e.g. 80% on changed code).

---

## 9. Automation & CI/CD Quality Gates

**On every PR (must pass to merge):**
1. Lint + static analysis (Semgrep/ESLint/Checkstyle).
2. Unit tests + coverage floor on **changed** lines.
3. Integration tests (Testcontainers) for touched modules.
4. API contract validation (OpenAPI diff = no breaking change without version bump).
5. Dependency/vulnerability scan (fail on High/Critical).
6. Secret scan (no credentials committed).

**Nightly:**
- Full regression (all P0+P1), performance smoke, soak (weekly), chaos/failure-injection.

**Pre-release:**
- Full E2E on staging, perf test at target load, security scan, DB migration rehearsal + rollback.

---

## 10. Coverage Matrix (layer × type)

| Layer \ Type | Unit | API/IT | Auth | Validation | Data | Perf | Security |
|---|---|---|---|---|---|---|---|
| Controllers/routes | – | ●●● | ●●● | ●●● | – | ● | ●●● |
| Service/business logic | ●●● | ●● | ●● | ●● | ●● | ● | ● |
| Repository/DAO | ●● | ●●● | – | – | ●●● | ● | ● |
| Security/JWT filter | ●● | ●●● | ●●● | – | – | – | ●●● |
| Integrations | ●● | ●●● | – | ●● | – | ● | ●● |
| Config/infra | ● | ●● | – | – | ● | ●● | ●● |

`●●●` = heavy, `●●` = moderate, `●` = light, `–` = N/A.

---

## 11. Test Case Template

```
ID:            AUTHZ-002
Title:         Horizontal access — user A cannot read user B's record
Type:          Security / Authorization (BOLA)
Priority:      P0
Preconditions: Two users A,B exist with a resource each; valid tokens
Steps:         1) GET /records/{B_id} with A's token
               2) GET /records/{B_id} with no token
Expected:      1) 403 or 404 (never B's data)  2) 401
Test data:     userA, userB, recordB
Automation:    API test, part of regression pack
Refs:          OWASP API1:2023 BOLA
```

---

## 12. Exit Criteria / Definition of Done (per feature)

- All **P0** cases for the feature pass.
- New/changed code has tests at the **lowest viable level**; coverage floor met.
- No **High/Critical** security findings open.
- Error responses follow the standard schema; no stack traces / secrets leaked.
- API contract updated and validated; migration has a tested rollback.
- Performance within SLO for the feature's endpoints.
- Regression suite green; runbook/observability updated.

---

## 13. What I Need to Bind This to ERGO Connect (exact asks)

To turn this framework into a **project-specific, endpoint-level test plan with real cases**, provide **any one** of:

1. **Repo access** — add a read-only **fine-grained PAT** (Contents: read) for `varadyadav02/ERGO_connect`, or make the repo public temporarily; or
2. **A ZIP** of the backend placed in this workspace; or
3. **Key files** pasted: `pom.xml`/`build.gradle`/`package.json`, `application*.yml`/`.env.example`, the **controllers/routes**, **entities/models**, **security config**, and **OpenAPI/Swagger** if present.

With access I will produce:
- A **real inventory** of endpoints, roles, entities, and integrations.
- **Concrete, ID-tagged P0 cases per endpoint** (not templates).
- A **risk-ranked test backlog** mapped to your actual sprint-1 scope.
- **Ready-to-run** test scaffolding for your stack.

---

## 14. Immediate Next Steps (Top Priority, in order)

1. **AUTH-001…012** + **AUTHZ-001…007** — identity & access. Highest blast radius. *(P0)*
2. **VAL-001…012** — input validation & injection on every write endpoint. *(P0)*
3. **DB-001…008** — transactional integrity on money/state-changing flows. *(P0)*
4. **BIZ-001…005** — the one core business journey, end to end. *(P0)*
5. **ERR-001…006** — consistent, non-leaking error handling. *(P0)*
6. Wire the above into **CI as merge gates** (Section 9).
7. Then open **P1** (perf, concurrency, integration, observability).

> **Ordering rationale:** identity + authorization + input validation + data integrity + core flow catch the majority of *severe* defects. Ship those gates first; broaden later.
