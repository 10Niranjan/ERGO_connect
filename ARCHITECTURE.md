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
│   │   │   ├── jwt/                # AccessTokens: issues/reads the JWT (the only place that knows claim names)
│   │   │   ├── Role.java, CompanyType.java, SecurityProperties.java
│   │   │   └── CurrentUser.java    # caller's user, company, active role + record scope (see §8)
│   │   ├── audit/
│   │   │   ├── AuditEntityListener.java    # Hibernate listener: every insert/update/delete, per field
│   │   │   ├── AuditLogService.java        # writes audit_logs in the caller's transaction
│   │   │   └── AuditExclude / AuditMasked  # opt-outs for noise and secrets
│   │   ├── workflow/
│   │   │   ├── WorkflowEngine.java         # generic status-transition + guard evaluation
│   │   │   ├── WorkflowDefinition.java
│   │   │   └── ApprovalMatrixService.java  # Section 14.8 concept
│   │   ├── exception/               # GlobalExceptionHandler, domain exceptions
│   │   ├── event/                   # internal domain event base classes + Kafka publisher wrapper
│   │   └── util/
│   │
│   ├── masterdata/                  # companies, users, roles, currencies, materials, processes, incoterms...
│   │   ├── api/                     # controllers + facades other modules may call (added, see §8)
│   │   ├── access/                  # companies, users, roles, login sessions, auth (one package, see §8)
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
| 2026-09-24 | **Client decision: only ERGO Admin manages users**: creates them, maps company and roles, activates/deactivates, resets passwords. Everyone else can only view users in their record scope and change their own password. No second approval is needed; every change is audited. | Resolves the conflict between SRS §8.2/§9.1 and table §10.1. Customer/supplier self-onboarding stays in Phase 3 (§16.3). |
| 2026-09-24 | **Client decision: one active role per session.** The default role opens after login; users switch in the top bar, which issues a new token. Every audit row stores the role that was used. | SRS §12.4.2 was silent on this. Makes clear which capacity a person acted in (e.g. approvals, §15.3). |
| 2026-09-24 | **One company per login.** Someone working with two companies gets two logins. ERGO KAMs reach customer accounts through account assignment (§13.2.5, Phase 2). So `user_role_mappings` has no `company_id`. | Keeps every company's data strictly separate. |
| 2026-09-24 | **Auth design**: a 15-min HS256 access token (kept in browser memory) plus a server-side login session (`user_sessions`, 12 h absolute) in an HttpOnly + Secure + SameSite=Strict cookie scoped to `/api/auth`. Only the token's SHA-256 is stored. No rotation, because rotation adds little for an HttpOnly cookie and breaks multiple tabs. Logout, deactivation and password reset revoke sessions. Role and account changes reach open sessions within 15 min. | Revocable, XSS-resistant, and uses standard Spring Security resource-server code (no custom JWT filter). |
| 2026-09-24 | **Login hardening**: the same error for unknown email and wrong password (with timing equalised by a dummy hash), a 15-min lock after 5 failures, bcrypt (DelegatingPasswordEncoder), and temporary passwords that must be changed at first sign-in. The Redis IP rate limiter from §4 moves to Phase 8. | Lockout covers per-account guessing now; IP limits matter once the app is exposed to the internet. |
| 2026-09-24 | **The `roles` table is keyed by `role_code`** (no surrogate id). `audit_logs.changed_by_role_code` replaces `changed_by_role_id`, and `audit_logs.entity_name` was added. A DB trigger blocks giving a role to the wrong company type. Customer Management is seeded inactive (§9.2). | Codes are what the code and audit rows use. |
| 2026-09-24 | **Automatic audit**: a Hibernate listener writes CREATE / UPDATE (one row per field) / DELETE rows for every entity in the same transaction. `@AuditExclude` skips noise, `@AuditMasked` hides secret values, and services add business events (e.g. `ROLE_ADDED`). | Principle 3 by construction; rollback removes the audit rows too (tested). |
| 2026-09-24 | **Record-scope pattern** (replaces the planned generic `CompanyScopeFilter`): repositories are package-private and extend Spring Data `Repository` with explicit methods only. User-facing reads use scoped queries that take `CurrentUser.companyScope()`, and out-of-scope IDs return 404. | Hibernate filters don't apply to `findById`, and scope rules differ per entity (e.g. suppliers see RFQs through their assignments), so each module owns its scoped queries. |
| 2026-09-24 | `masterdata/access` holds companies, users, roles, sessions and auth in one package (instead of the plan's company/user/role split). | Keeps entities and repositories package-private. |
| 2026-09-24 | **Deferred**: 2FA (optional in SRS §12.4.1), "forgot password" email (needs email, Phase 6), first-admin bootstrap outside dev (Phase 8), ERGO KAM account assignments (Phase 2), customer/supplier management rules §10.2/§10.3 (Phase 1b). The dev profile seeds demo companies and users. | Nothing built before it is needed. |
| 2026-09-24 | **Client decision: new companies need approval.** Customers and suppliers added by ERGO RFQ (both types) or ERGO KAM (customers only) start as `PENDING_APPROVAL`. ERGO Admin or ERGO Management approves (comment optional) or rejects (comment required), and a rejected company can be fixed and resubmitted. ERGO Admin additions are active at once. Nobody approves a company they added themselves. Only `ACTIVE` companies can have users and sign-ins. | SRS §10.2/§10.3 give "Approve" only to ERGO Admin and Management. §11.4: the approver, time, old and new status and comment are all stored in the audit trail. |
| 2026-09-24 | **Client decision: customers and suppliers edit their own company profile as SRS §10.2/§10.3 say.** That means Customer RFQ, KAM and Management, and Supplier RFQ and KAM: direct edits, audited. Their NPD, OFP and Quality roles view only. Code and type never change for anyone, and approval/active status stays with ERGO. | Client asked to follow the SRS criteria. |
| 2026-09-24 | **Client decision: an ERGO KAM sees only their assigned customers** (`user_account_assignments`, set by ERGO Admin in the user dialog). A customer a KAM adds is assigned to them automatically, and a DB trigger allows only ERGO user ↔ customer company links. The "RV" rights of ERGO KAM (suppliers) and ERGO NPD/OFP/Quality (customers, suppliers) mean the companies on their own records, which arrive in Phases 2–5; until then they have no company list. | SRS §8.1/§11.1 take precedence over table §10.2's "V". |
| 2026-09-24 | **Allowed actions come from the backend**: every company response lists what the caller may do right now (`EDIT`, `APPROVE`, `REJECT`, `RESUBMIT`, `ACTIVATE`, `DEACTIVATE`), computed by the same checks that enforce the actions. The UI shows exactly those buttons. Use this pattern for every workflow module. | SRS §11.4/§12.14 ("buttons by role and status") with one source of truth. |
| 2026-09-24 | **Audit reasons**: `AuditLogService.reason(...)` attaches a comment to every audit row the current transaction writes, including the automatic field changes. Used for approval/rejection comments now and reopen reasons in Phase 2. | SRS §15.1 "know why". |
| 2026-09-24 | **Master lists**: currencies (INR/USD/EUR/RMB seeded), materials, processes, coatings, payment terms and incoterms (Incoterms 2020 seeded) share one registry (`MasterList`), one JDBC service that validates and audits every change itself, and one generic admin screen. ERGO Admin maintains them, ERGO Management views them, and every signed-in role reads the active options (RFQ and quote dropdowns need them). Codes are upper-case and never change; entries are deactivated, never deleted. RMB stays as the SRS code (ISO 4217: CNY). | Six near-identical lists, one implementation. |
| 2026-09-24 | **Deferred masters**: exchange rates (Phase 2 comparison view), shipment modes (Phase 4), defect and NCR/CAPA categories (Phase 5), document categories (Phase 6). No dual approval for master changes in MVP (§10.12 A* says "if company wishes"). | Built when first used. |
| 2026-09-24 | Validation errors name the failing fields (`ValidationErrorHandler`), and form inputs carry the backend's length limits. | Found in UI testing: "Invalid request content" alone was not helpful. |
| 2026-09-24 | **Client decisions for RFQ/quotation**: (1) suppliers never see the customer's name; ERGO decides which files suppliers get (e.g. a copy without the customer's title block). (2) Per quote, ERGO either forwards the supplier's quote as-is (the customer then sees that supplier's name, prices and cost breakdown) or sends an ERGO quote (supplier and margin hidden). (3) Process flow on both: the RFQ holds the planned steps (required before validation), and each supplier's quote gets its own copy with cycle times, sourcing and sub-suppliers (Phase 2b). | SRS §5.5, §13.3.9 and §17.1 leave these open. |
| 2026-09-24 | **RFQ commercial fields are the customer's targets** (tooling, PPAP and production prices, payment terms, lead times, MOQ, with the currency tag). They are never exposed to suppliers. Customers see simplified statuses (all internal quoting steps show as "Quotation in progress"). | SRS §4, §5.6, §11.1. |
| 2026-09-24 | **Separate response types per audience**: sealed `RfqView` = `ErgoRfqView` / `CustomerRfqView` (the supplier view comes in 2b). Internal notes, owner and internal status do not exist on the customer type, and tests assert the JSON keys are absent. | CLAUDE.md non-negotiable. |
| 2026-09-24 | **Module layout**: each module has `api/` (controllers, facades for other modules) and one internal package (`domain/`) holding entities, repositories and services, so entity fields and repositories stay package-private. The planned `repository/service/mapper` sub-packages were dropped. Cross-module needs go through `masterdata.api.Directory` (an interface implemented in `access`) and `MasterDataApi`. | Encapsulation without public setters, and the module-boundary test stays meaningful. |
| 2026-09-24 | **Documents (basic)**: a generic `DocumentStore` (documents module) holds files in Postgres (`bytea`, 50 MB cap). Types are allowlisted (drawings, 3D, office, images, zip), files are always served as downloads (never inline) with `no-store`, and every change is audited. The owning module decides access; visibility (INTERNAL/CUSTOMER/SUPPLIER/ALL) narrows it. Customers' uploads are visible to ERGO and themselves only; only ERGO shares with suppliers. Files can be deleted only before the RFQ reaches suppliers. **Before go-live**: Azure Blob and virus scanning (Phase 6). | Drawings are needed now; the full DMS is Phase 6. |
| 2026-09-24 | RFQ clarification: ERGO's question and the customer's answer are stored on the RFQ (and in the audit trail as reasons) until Phase 6 threaded comments. RFQ numbers are `RFQ-YYYY-NNNNN` from one sequence. Splitting a customer RFQ into sub-RFQs is done with **Clone**. Deferred: autosaving drafts, server-side list filtering/paging, notifications (Phase 6). | Minimal now, room to grow. |
