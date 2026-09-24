# ERGO Connect — System Architecture & Implementation Plan

**Stack (as built, see §8 Decisions Log):** React 19 + TypeScript 6 (strict) + MUI 9 + Redux Toolkit/RTK Query + React Router · Vite 8 · Java 21 + Spring Boot 4.1 (Maven wrapper) · PostgreSQL 17 + Flyway · Redis · Azure Blob/S3 · Docker + GitHub Actions + Azure · Kafka deferred (§6)
*(Original ref sheet said React 18 / Spring Boot 3 — superseded, see §8.)*
**Pattern:** Modular monolith, single deployable Spring Boot service, module boundaries along RFQ · Quotation · NPD · OFP · Quality · Documents · Communication · Admin
**Target:** Client-deliverable, production-grade — not a prototype

---

## 1. Architecture Principles (apply to every module you build)

1. **Module isolation inside one codebase.** Each business module (`rfq`, `quotation`, `npd`, `ofp`, `quality`, `documents`, `communication`, `admin`) is a self-contained Java package with its own controller/service/repository/entity layers. Modules talk to each other only through a small `api` interface package each module exposes — never by reaching into another module's repository or entity directly. This is what makes future extraction into a separate service possible without a rewrite.
2. **Security is cross-cutting, not per-endpoint.** Every request passes through one JWT auth filter → one role-check layer → one record-level (company-scope) filter, applied identically regardless of module. Individual controllers should never hand-roll access checks.
3. **Every write is audited by construction, not by convention.** Use a single `AuditingEntityListener`/interceptor at the JPA layer so no developer can "forget" to log a change — this matches the SRS's non-negotiable audit requirement (Section 15).
4. **Every business object carries status + history.** Status transitions go through a single `WorkflowService` per module, not ad-hoc `setStatus()` calls — this is what gives you the state machine described in SRS Section 14 and keeps illegal transitions (e.g. skipping "ERGO Review") impossible.
5. **Boring technology, correct scope.** No microservices, no premature Kafka topics beyond what's actually driving an MVP workflow trigger — see Section 6 (Build Sequence) for what's deferred.

---

## 2. Backend Folder Structure (Spring Boot 4.1, Java 21, Maven)

```
ergo-connect-backend/
├── pom.xml + mvnw               # Maven wrapper: always use ./mvnw, never a global mvn
├── src/main/java/com/ergo/connect/
│   ├── ErgoConnectApplication.java
│   │
│   ├── common/                     # shared kernel — no business logic
│   │   ├── config/                 # SecurityConfig, KafkaConfig, RedisConfig, CorsConfig, OpenApiConfig
│   │   ├── security/
│   │   │   ├── jwt/                # JwtAuthFilter, JwtService, JwtProperties
│   │   │   ├── rbac/               # RoleGuard annotations, @RequiresRole, @RequiresRecordScope
│   │   │   └── CompanyScopeContext.java   # holds current user's companyId/role for record filtering
│   │   ├── audit/
│   │   │   ├── AuditEntityListener.java
│   │   │   ├── AuditLogService.java
│   │   │   └── AuditLog.java (entity → audit_logs table)
│   │   ├── workflow/
│   │   │   ├── WorkflowEngine.java         # generic status-transition + guard evaluation
│   │   │   ├── WorkflowDefinition.java
│   │   │   └── ApprovalMatrixService.java  # Section 14.8 concept
│   │   ├── exception/               # GlobalExceptionHandler, domain exceptions
│   │   ├── event/                   # internal domain event base classes + Kafka publisher wrapper
│   │   └── util/
│   │
│   ├── masterdata/                  # companies, users, roles, currencies, materials, processes, incoterms...
│   │   ├── api/                     # facade other modules may call (added, see §8)
│   │   ├── company/
│   │   ├── user/
│   │   ├── role/
│   │   └── lookup/                  # materials, coatings, payment_terms, document_categories, etc.
│   │
│   ├── rfq/
│   │   ├── api/                     # RfqController, DTOs, RfqModuleFacade (what other modules may call)
│   │   ├── domain/                  # Rfq, RfqProcessFlow, RfqSupplierAssignment entities
│   │   ├── repository/
│   │   ├── service/                 # RfqService, RfqWorkflowService, RfqValidationService
│   │   └── mapper/
│   │
│   ├── quotation/
│   │   ├── api/
│   │   ├── domain/                  # SupplierQuote, QuoteVersion, QuoteComparison, ErgoQuote
│   │   ├── repository/
│   │   └── service/                 # QuoteComparisonService, QuoteReopenService
│   │
│   ├── npd/
│   │   ├── api/
│   │   ├── domain/                  # NpdProject, NpdStageHistory, NpdActivity, PromptChecklist
│   │   ├── repository/
│   │   └── service/                 # NpdConversionQueueService (manual RFQ→NPD trigger, Section 14.2)
│   │
│   ├── ofp/
│   │   ├── api/
│   │   ├── domain/                  # Order, OrderProductionProgress, Shipment
│   │   ├── repository/
│   │   └── service/                 # DispatchGuardService (blocks dispatch until quality release)
│   │
│   ├── quality/
│   │   ├── api/
│   │   ├── domain/                  # Inspection, NCR, Deviation, CapaAction
│   │   ├── repository/
│   │   └── service/
│   │
│   ├── documents/
│   │   ├── api/
│   │   ├── domain/                  # Document, DocumentLink
│   │   ├── repository/
│   │   ├── service/                 # BlobStorageService, VirusScanService, DocumentVersionService
│   │   └── storage/                 # Azure Blob adapter, S3-compatible adapter (interface + impl)
│   │
│   ├── communication/
│   │   ├── api/
│   │   ├── domain/                  # Comment, Notification
│   │   ├── repository/
│   │   ├── service/                 # NotificationDispatchService (WebSocket + email)
│   │   └── websocket/
│   │
│   ├── dashboard/
│   │   └── ...                      # read-optimized query services, no business logic here
│   │
│   └── admin/
│       ├── api/                     # user administration, permission mapping, master data CRUD
│       └── service/
│
├── src/main/resources/
│   ├── application.yml
│   ├── application-dev.yml
│   ├── application-prod.yml
│   └── db/migration/                # Flyway: V1__init_masters.sql, V2__rfq_tables.sql, ...
│
├── src/test/java/com/ergo/connect/
│   ├── <module>/                    # mirrors main structure, one test package per module
│   └── integration/                 # Testcontainers-based full-stack tests
│
└── docker/
    ├── Dockerfile
    └── docker-compose.local.yml     # postgres + redis for local dev (Kafka deferred, §6)
```

**Why package-by-module, not package-by-layer:** a `com.ergo.connect.controller` / `com.ergo.connect.service` split (layer-first) is what makes monoliths turn into unmanageable spaghetti — every developer ends up able to call every service. Package-by-module with an explicit `api/` facade per module is what actually gives you the option to extract `quality` or `ofp` into its own service later, cleanly.

---

## 3. Frontend Folder Structure (React + TypeScript + MUI + Redux Toolkit)

```
ergo-connect-frontend/
├── src/
│   ├── app/
│   │   ├── store.ts                 # Redux store, RTK Query API root
│   │   └── router.tsx                # role-aware route guards
│   │
│   ├── shared/
│   │   ├── components/               # Button, DataTable, StatusChip, FileUploader, CommentThread...
│   │   ├── layout/                   # AppShell, Sidebar, TopBar (role-based nav per SRS 12.15)
│   │   ├── hooks/
│   │   └── theme/                    # MUI theme tokens
│   │
│   ├── auth/
│   │   ├── authSlice.ts
│   │   ├── authApi.ts                # RTK Query: login, refresh, roleSelect
│   │   └── RequireRole.tsx           # route/component guard component
│   │
│   ├── features/
│   │   ├── rfq/
│   │   │   ├── rfqApi.ts             # RTK Query endpoints
│   │   │   ├── components/           # RfqList, RfqDetail, RfqCreateForm, ProcessFlowTable
│   │   │   └── pages/
│   │   ├── quotation/
│   │   ├── npd/
│   │   ├── ofp/
│   │   ├── quality/
│   │   ├── documents/
│   │   ├── communication/
│   │   ├── dashboard/
│   │   └── admin/
│   │
│   └── types/                        # shared TS types generated/synced from OpenAPI spec
│
├── openapi/                          # generated client types from backend Swagger (keep in sync)
├── .env.example
└── vite.config.ts                    # dev proxy /api → :8080
```

Each `features/<module>` folder mirrors a backend module 1:1 — when you're implementing RFQ end-to-end, you touch exactly one backend package and one frontend folder, never scattered files.

---

## 4. Security Implementation Plan

| Area | Implementation |
|---|---|
| **Authentication** | JWT (short-lived access token + refresh token), Spring Security filter chain. OAuth2/SSO as a pluggable provider for Phase 2 client SSO requirements. |
| **Authorization — role** | Method-level `@PreAuthorize` / custom `@RequiresRole` annotations per SRS Section 10 module-permission tables. |
| **Authorization — record scope** | A single `CompanyScopeFilter` (Spring AOP or JPA `@Filter`) applied to every repository query, so a customer/supplier query is physically incapable of returning another company's rows — don't rely on service-layer `if` checks alone. |
| **Approval-sensitive actions** | Separate permission check even when the user already has module edit access (SRS 11.3) — implement as a distinct `@RequiresApprovalRight` guard, not folded into the edit check. |
| **Secrets management** | Azure Key Vault for DB credentials, JWT signing keys, storage keys — never in `application.yml` or source control. **(Flag this to your partner — it wasn't in the original stack doc.)** |
| **File uploads** | Virus/malware scan before persisting to Blob storage (ClamAV container or Azure Defender for Storage) — mandatory given suppliers/customers upload drawings and documents directly. **(Also not in the original doc — add it.)** |
| **Input validation** | Bean Validation (`jakarta.validation`) on every DTO; never trust client-supplied status/role fields — recompute server-side. |
| **Rate limiting** | Redis-backed rate limiter on auth endpoints and file upload endpoints specifically (not just globally). |
| **Audit trail integrity** | Audit log table is append-only at the DB level (no UPDATE/DELETE grants for the app's DB role on `audit_logs`). |
| **Transport** | TLS everywhere (Azure-managed certs), HSTS, secure cookies for refresh token if used. |
| **Dependency hygiene** | GitHub Dependabot + OWASP Dependency-Check in CI, fail build on high/critical CVEs. |
| **PII/logging hygiene** | Logback config to redact tokens, passwords, and customer commercial data (piece prices, target prices) from application logs. |
| **CORS** | Explicit allow-list of frontend origins, never `*`, especially since suppliers and customers are external domains. |

---

## 5. Database Strategy

- Single PostgreSQL instance, single schema — logical module boundaries via table-name prefixing already implied by the SRS's own table names (`rfq_*`, `npd_*`, `order_*`, etc.), not separate schemas. This keeps foreign-key integrity across the workflow chain (Principle 1 above) without cross-schema joins.
- Flyway migrations named by module and sequence: `V1__master_tables.sql`, `V2__rfq_tables.sql`, `V3__quotation_tables.sql`, etc. — one migration file per logical change, never edited after it ships.
- Row-level security enforced in the application layer (`CompanyScopeFilter`), not Postgres RLS, to keep the permission logic in one place your team actually reads and tests. (Revisit Postgres RLS only if a future compliance requirement demands defense-in-depth at the DB layer.)

---

## 6. Phased Build Sequence (maps to SRS Section 16.4, refined for execution)

Each phase below is scoped so it's a sensible unit of work for a Claude Code session — a phase should end with passing tests and a working vertical slice, not half-finished code.

**Phase 0 — Foundation**
Repo scaffold (backend + frontend), Docker Compose for local Postgres/Redis, CI pipeline skeleton (GitHub Actions: build + test on push), base `common/` package (security config shell, exception handler, audit listener stub).

**Phase 1 — Access Model & Master Data**
`masterdata` module: companies, users, roles, role-mappings, currencies, materials, processes, payment terms. JWT login, role selection screen, `CompanyScopeFilter` implemented and tested with at least 2 fake companies to prove isolation.

**Phase 2 — RFQ + Quotation**
Full RFQ lifecycle (SRS 14.2), supplier assignment, supplier quote submission with version control and re-open logic (14.3), quote comparison, customer review (14.4). This is the largest phase — the rest of the system depends on its data model.

**Phase 3 — NPD**
Manual RFQ→NPD conversion queue, NPD stage workflow (14.5), activity board, prompt checklists.

**Phase 4 — OFP**
Order/production tracking, dispatch-blocked-until-quality-release rule (14.6), shipment tracking.

**Phase 5 — Quality**
Inspections, NCR workflow, deviation approval (with conditional customer approval), CAPA (14.7).

**Phase 6 — Documents & Communication**
Document library with versioning, virus scanning, threaded comments, notification engine (WebSocket + email), notification triggers (14.9).

**Phase 7 — Dashboards & Audit Refinement**
Role-specific dashboards (12.5), audit history tabs on major records, admin permission-mapping UI.

**Phase 8 — Hardening & Deployment**
Load/security testing, Dependabot + Dependency-Check gates green, Azure deployment pipeline, Monitor/App Insights wired up, final checklist against SRS Section 21.

**Explicitly deferred out of MVP** (per your partner's own stack doc + this plan): Kafka event bus (use direct service calls / a Postgres outbox table until a real multi-consumer or ERP-integration need shows up), Elasticsearch/OpenSearch full-text search, OAuth2/SSO (JWT-only is fine for MVP), payment/invoice tracking.

---

## 7. Running This Plan Through Claude Code

Suggested execution pattern in the terminal:

1. Keep this file at the repo root as `ARCHITECTURE.md` — point Claude Code at it at the start of every session so it has the module boundaries and security rules as standing context, not something you re-explain each time.
2. Work one phase at a time, one module at a time within a phase. A good session prompt looks like: *"Implement Phase 1 → masterdata module → companies and users, per ARCHITECTURE.md folder structure and the SRD's Section 13.2 table definitions. Include Flyway migration, entity, repository, service, controller, and unit tests."*
3. End each phase with: `npm test` / `mvn test` passing, then a commit — don't let Claude Code carry uncommitted work across phases.
4. Re-paste (or re-point to) the relevant SRS section number for each module when you start it — the SRS is the source of truth for fields/business rules; this document is the source of truth for structure/security.

---

## 8. Decisions Log

Append-only. Record every decision that changes or clarifies this document, with date and reason.

| Date | Decision | Reason |
|---|---|---|
| 2026-09-24 | **Spring Boot 4.1** (not 3.x) on Java 21 | 3.5 (last 3.x line) is out of open-source support; 4.0 has about 2 months left. 4.1 is supported until about mid-2027, and its auto-config is modular (smaller footprint). |
| 2026-09-24 | **React 19 + MUI 9 + Vite 8 + TS strict** (not React 18) | Current stable line; the old demo already used Vite. `strict: true` is set explicitly in tsconfig. |
| 2026-09-24 | **Maven** with the committed wrapper (`mvnw`) | Maven is already installed; Gradle is not. The wrapper pins the version for devs and CI. |
| 2026-09-24 | **Monorepo**: `ergo-connect-backend/`, `ergo-connect-frontend/`, `docs/` in one repo (`varadyadav02/ERGO_connect`) | One commit = one vertical slice (backend + frontend + migration), as §2–3 intend. |
| 2026-09-24 | **Module boundaries enforced by a test**: `ModuleBoundaryTest` (ArchUnit) fails the build if module A touches anything in module B except `B.api` | Principle 1 enforced by the build, not by convention. `common/` is the open shared kernel. `masterdata/` got an `api/` package so the rule applies to it too. |
| 2026-09-24 | **Cross-module references by ID** (e.g. `rfqs.currency_id BIGINT` with FK), not JPA associations to another module's entity | Required by the boundary rule; FKs still give DB integrity (§5). |
| 2026-09-24 | **IDs are `BIGINT GENERATED ALWAYS AS IDENTITY`**; money is `NUMERIC(18,4)` + a `currency_id` on the same row; timestamps are `TIMESTAMPTZ` | One convention across all tables. Currency is always tagged (SRS §6). |
| 2026-09-24 | **`audit_logs` created in V1, append-only via DB trigger** (UPDATE/DELETE/TRUNCATE raise) | The audit trail exists before any business table (SRS §15). The trigger enforces append-only no matter which code path writes. Prod must additionally run the app with a DB role that does not own the table (§4). |
| 2026-09-24 | **Errors use Spring's built-in RFC 9457 `ProblemDetail`** (`spring.mvc.problemdetails.enabled`); a `GlobalExceptionHandler` is added only when the first domain exception exists | No speculative code. |
| 2026-09-24 | **Frontend talks to `/api` same-origin** (Vite proxy in dev) | Keeps the backend CORS allow-list strict; no backend URL baked into the bundle. |
| 2026-09-24 | SRD converted to **`docs/SRS.md`** (tables preserved); the older draft appended at the end of the .docx is kept as a marked "superseded" appendix | Agents and devs can grep section numbers. The .docx remains the signed-off source. |
