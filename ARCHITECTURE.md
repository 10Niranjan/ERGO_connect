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
| 2026-09-25 | **The quotation module owns supplier invitations and quotes** (`rfq_supplier_assignments`, `supplier_quotes` with tooling lines and the supplier's own process route, `supplier_quote_versions`). It uses the RFQ module only through `rfq.api.RfqApi`: the supplier-safe RFQ (`SupplierRfq`: no customer, targets, remarks or notes, and the planned route as process and step names only, because step remarks and sub-suppliers may name third parties), the files ERGO shared with suppliers, and two status hooks. The RFQ module knows nothing about quotes. RFQ status follows the invitations: first invitation → Sent to suppliers; then Quotes partly received / Quotes received (a re-opened quote is awaited again, a regret no longer counts). From comparison on, ERGO's actions drive it (2c). | One-way dependency; supplier data shapes are defined where the data lives. |
| 2026-09-25 | **Drafts are private: ERGO sees only submitted versions.** Each submission stores an immutable JSONB snapshot (`supplier_quote_versions`, update/delete/truncate blocked by triggers) with who submitted it, the re-open reason that led to it and the ids of the supplier's files sent with it: ERGO sees a supplier's files only once submitted, and a submitted file can no longer be deleted. The working quote is `@AuditExclude` because the versions are the record; invitations, submissions, regrets, clarifications and re-opens are audited with their reasons. The UI shows every version and the field-by-field changes between them. | SRS §14.3 and §15.4; ERGO must never compare against numbers a supplier has not committed to. |
| 2026-09-25 | **Quote rules** (one rule book, `QuoteService.allowed`, also drives the buttons): Supplier RFQ and KAM edit and submit while the quote is open and the RFQ still takes quotes. Submitting needs currency, a validity date that is not past, the production piece price, MOQ, production lead time and all five cost-breakdown parts; tool lines, when given, make up the tooling total. A supplier can ask ERGO a question (one open at a time, kept on the invitation until Phase 6 threads) or, before its first submission, regret with a reason (final). No prices are stored without the quote currency (DB check). ERGO Admin, Management and RFQ invite active suppliers (each once per RFQ, with a due date), answer questions and re-open a submitted quote with a reason. ERGO KAM and NPD view within their RFQ scope. Customers have no quote access until ERGO sends them one (2c). | SRS §5.3, §9.3, §10.5, §14.3, §17.2. |
| 2026-09-25 | **Close vs cancel**: ERGO can still cancel an RFQ that was sent to suppliers while no quote has arrived. Once quotes are in (partly or fully received, under comparison, sent to customer, revision requested), the RFQ is **closed** with a reason instead, so the quotes stay on record. | Cancelling after suppliers have quoted would misstate what happened. |
| 2026-09-25 | **Deferred**: changing the RFQ after the float (re-float of a new revision; for now re-open quotes one by one, or close and clone), due-date reminders and overdue notifications (Phase 6), supplier self-registration (Phase 3 per §16.3), quote export/PDF. | Not needed for the 2b flow. |
| 2026-09-25 | **Optimistic locking** (`row_version`, JPA `@Version`) on RFQs and supplier invitations, whose status the float now changes from both sides: of two concurrent changes the later one gets 409 instead of silently overwriting the other. **ERGO NPD sees customer-approved RFQs only**, so closing an RFQ never exposes it or its quotes to NPD; Phase 3 defines visibility after conversion. | Found by the Phase 2b security review, fixed with tests before commit. |
| 2026-09-25 | **Client decisions for comparison and quotes to the customer**: (1) ERGO RFQ (and ERGO Admin) compare, decide and send quotes to the customer in one step; ERGO Management, KAM and NPD view the comparison and margins. A Management approval step can come with the Phase 7 approval matrix. (2) One quote at a time waits for the customer's answer (partial unique index); after a revision request ERGO sends a new one, and earlier quotes stay visible to the customer as history. (3) Suppliers never see ERGO's evaluation or the outcome in the portal: shortlisted, selected and not selected show to them as submitted/resubmitted; award notices come with notifications (Phase 6) and NPD (Phase 3). | SRS §9.1, §10.5 and §14.8 left open who releases quotes; §12.7.2 says "quote(s)". |
| 2026-09-25 | **Comparison** (`quote_comparisons`, `quote_comparison_lines`): the last submitted version of each quote side by side (never drafts, never a regretted supplier), converted to the RFQ currency at the newest exchange rate dated on or before today; without a rate an amount is shown unconverted and left out of the best-value highlighting. ERGO's scores are 0-10, higher is better (risk 10 = lowest risk), and the total is their average. Shortlist, select-for-customer and not-selected are the quote's own status (SRS §14.3), not flags, so there is one source of truth. The first comparison action moves the RFQ to Under Comparison. **Exchange rates** are a new master list (ERGO Admin enters, Admin and Management view, every change audited; a wrong rate is deleted and re-entered), ERGO-internal only. | SRS §6 "currency conversion view (optional) for ERGO internal comparison", §12.7.1, §17.3. |
| 2026-09-25 | **Quotes to the customer** (`customer_quote_submissions`, which also holds the SRS §13.3.10 ERGO-quote fields): always based on a quote selected for the customer. A supplier quote as-is must still be valid. An ERGO quote is prefilled from the supplier quote and stores ERGO's margin on the production piece price (`margin_amount_hidden`, `margin_percentage_hidden`), frozen at the rate of the day and empty without a rate. What the customer sees is stored as JSON when sent (`customer_view`). Triggers allow only the customer's single response afterwards, block delete and truncate, and require the RFQ's own customer and the RFQ's own quotes. Customer answers: approve (comment optional), or request a revision / decline (comment required). These move the RFQ to Customer Approved (the ready-for-NPD queue, never converted automatically), Revision Requested (suppliers can be re-opened, then ERGO sends a new quote) or Declined. The customer's response type has no margin and no basis, and for an ERGO quote no supplier either. | SRS §5.3, §12.7.2, §13.3.9-13.3.10, §14.4. |
| 2026-09-25 | **Deferred from 2c**: several options at once (client chose one at a time), a Management approval step and value thresholds (Phase 7 approval matrix), quote PDFs for the customer, notifying suppliers of the outcome (Phase 6), and automatic 'not selected' for the other suppliers after approval. | Not needed for the 2c flow. |
| 2026-09-25 | **Security review fixes (2c, before commit)**: a Not Selected decision now requires a reason (audited, ERGO-internal, never shown to the supplier or customer). A supplier quote already sent to the customer one way (as-is vs. an ERGO quote) can never be sent the other way for the same RFQ — that would disclose the margin or the supplier's identity. The customer's copy of a forwarded supplier quote is its own type (`CustomerSupplierQuote`), not the shared `QuoteData`: it carries only what the client decision promises (name, prices, cost breakdown, lead times and process steps by name) and drops the supplier's own quote reference, assumptions, remarks and sub-supplier names. A quote's shortlist/select/not-selected decision is frozen once it is with the customer, so it cannot change under an answer already given. Sending an ERGO quote at zero or negative margin now asks for confirmation. | Found by the Phase 2c security review; fixed and tested before commit. |
| 2026-09-26 | **Phase 3 (NPD) split into 3a/3b**, confirmed with the user, mirroring 1a/1b and 2a/2b/2c: 3a = the manual conversion queue (customer-approved RFQs only), the 9-stage workflow (Pending Conversion → ... → Closed), stage history, and activities that only ERGO edits, with stage-close gated on mandatory activities. 3b = supplier/customer participation in their own activities, prompt checklists, issues/risks, approvals. Table view only for the activity list; Kanban and Gantt stay deferred (SRS §16.2 explicitly defers Gantt; Kanban is treated the same way here, since a board view adds real UI work for no MVP value). | Keeps each commit reviewable, same as earlier phases. |
| 2026-09-26 | **Client decision: the customer never sees which supplier is developing their part in NPD either** — the RFQ-phase anonymity rule (§8.1: suppliers never see the customer) extends into NPD as a two-way rule. `npd_projects.supplier_company_id` is ERGO-internal; the customer's NPD view omits it, the same way `RfqApi.SupplierRfq` omits the customer today. | SRS §8.2 is silent on this for NPD; the client chose to keep the established RFQ-phase rule rather than relax it. |
| 2026-09-26 | **3a scope calls** (SRS silent or only a recommendation; decided here rather than asked, since each is either a safe default or reversible): one approved RFQ converts to at most one NPD project (`source_rfq_id` UNIQUE, per §13.9's own "recommended"); `npd_activities` gets a `mandatory_flag` (default true) since stage-close needs a way to know which activities gate it and §13.4.3 has none; a closed stage is not reopened in 3a (no such action is named in §12.8.3/§17.4, unlike RFQ's explicit reopen); the "Handover to OFP" action that creates an OFP record is deferred to Phase 4 — 3a's NPD workflow reaches Closed on its own without it; "Request Approval" and a Documents tab are left out of 3a (their tables/scope arrive with 3b and Phase 6 respectively, per the declared build order). ERGO RFQ sees NPD projects for RFQs it created/handled (same scope as its RFQ access); ERGO OFP and ERGO Quality get no NPD visibility yet, since those modules don't exist — safe under-provisioning, revisited when Phase 4/5 land. | §10.6's RV row for ERGO RFQ/OFP/Quality has no matching detail in §8.2; conservative defaults chosen over guessing at scope that would be exercised before its own module exists. |
| 2026-09-26 | **RFQ stays in `CUSTOMER_APPROVED` after conversion; no new RFQ status.** `npd_projects.source_rfq_id` existing is what marks an RFQ as already converted (the conversion queue greys out its own "Convert" action once a project exists for it). This avoids a migration to the already-committed `rfq` module and keeps `ERGO_NPD`'s existing `NPD_VISIBLE = {CUSTOMER_APPROVED}` RFQ-module scope working unchanged for the NPD project's "linked RFQ" header field. **`quotation.api.QuotationApi`** (new facade, mirroring `RfqApi`) exposes the RFQ's selected/approved quote (supplier company, quote reference) to the npd module, so npd never reaches into `quotation.domain`. | Keeps the module-boundary rule intact without touching a committed migration. |
| 2026-09-26 | **`npd` module built** (`api`+`domain`, matching every other module): `NpdProject`/`NpdActivity`/`NpdStageHistoryEntry` entities, ordinary automatic field audit (not `@AuditExclude` — NPD has no separate versioning requirement, unlike `SupplierQuote`), plus an explicit `npd_stage_history` row written on every stage move (conversion's implicit Pending Conversion → Planning, and every Close Stage) since that ledger is a dedicated SRS §13.4.2 requirement the generic audit trail doesn't replace. `RfqApi` gained `customerApproved()` (conversion-queue source, scoped exactly like `list()`) and `quotation.api.QuotationApi` (new facade) exposes the RFQ's approved quote's supplier and reference — both needed so `npd` never reaches into `rfq.domain`/`quotation.domain`. | Keeps the module-boundary rule intact for a module built from RFQ+quotation data it doesn't own. |
| 2026-09-26 | **Corrected an over-restriction from the original 3a brief**: the customer/supplier anonymity rule (NPD keeps RFQ's "customer never sees supplier") was mistakenly applied to *ERGO's own* list view too, hiding the supplier column ERGO needs and that SRS §12.8.1 itself names as a List Screen column. Fixed: the NPD list row (like the detail view) shows the supplier to ERGO; the rule only matters once a customer/supplier NPD view exists (3b), and none does yet in 3a. | Caught reviewing the module-builder's output against §12.8.1's own column list. |
| 2026-09-26 | **Added an "update project" endpoint** (owner reassignment, risk level, target/actual sample date) that the initial 3a brief omitted despite §12.8.1/§12.8.3 listing these as fields to show — without it they could never be set. ERGO Admin/NPD only, a full replace of these four fields (not a patch; matches every other form in this module). | Found while reviewing the builder's own flagged ambiguity #1; a small, clearly in-scope gap-fill, not a new decision to ask about. |
| 2026-09-26 | **`features/npd/` built**: conversion queue page, list page (table view only, per SRS §16.2's Kanban/Gantt deferral), detail page (header, Overview/Activities/Stage History tabs, project-edit and activity dialogs gated by the backend's own `actions` arrays). Reuses `Facts`/`FormSection`/`CommentDialog`/`shared/form.ts` throughout; no new shared components needed. `NPD_VIEWERS`/`NPD_EDITORS` added to `auth/access.ts`, routes and nav gated the same way as every other module. | Matches the RFQ/quotation frontend's established shape exactly. |
| 2026-09-26 | **Two small backend additions found reviewing the frontend build**: `NpdDetailView` now carries `delayDays` and `ownerUserId` (not just the display name), and `ActivityView` carries `responsibleUserId` alongside its display name. Without these the frontend either had to re-fetch the list to find a project's own delay figure (missing on the most common path: viewing a project immediately after converting it, before the list was ever fetched) or guess a user's id by matching their display name back against the users list (silently wrong if two ERGO users share a name). Both are now returned directly, closing a real correctness gap rather than leaving it as a documented client-side workaround. | Two view fields short of what their own edit forms need; cheap, additive, no migration required. |
| 2026-09-26 | **UI bug fixed before commit**: the Add Activity dialog defaulted a new activity's "Mandatory" checkbox to unchecked, even though the DB column itself defaults to `true` and the backend correctly enforces the mandatory-gate rule. An ERGO NPD user who didn't notice the checkbox would silently create an activity that could never block Close Stage — the browser walkthrough caught this (Close Stage succeeded on the first attempt instead of being blocked); the backend's own tests never would have, since they set `mandatory` explicitly. Fixed: the form now defaults to `true` for a new activity, matching the column. | Found in the pre-commit walkthrough, not by either sub-agent's own verification (backend tests pass a value explicitly; the frontend agent verified only compile/lint/build, not a live click-through). |
| 2026-09-26 | **Phase 3b scope, from the srs-analyst's extraction of §10.6/§12.8/§13.4.4-7/§14.5/§14.8**: supplier NPD activity edit rights extend to **all four supplier-side roles** (Supplier NPD, OFP, Quality, KAM), not just Supplier NPD — confirmed with the user; matches §10.6's literal matrix and the identical grant already given in the OFP module (§10.7). Raising a new Issue/Risk is **ERGO-only** (Admin/NPD); customer and supplier can respond to / update the status of one ERGO already raised, within their own company's scope, but never create one — confirmed with the user against §10.6's literal Create=X for every customer/supplier role in NPD. | User confirmed both against the SRS's literal matrix rather than the more permissive real-world-intuitive reading. |
| 2026-09-26 | **ERGO Quality gets no NPD edit surface in 3b**, despite §10.6's unscoped "E" — neither §10.6, §12.8.3 nor §17.4 says what ERGO Quality would edit, and the Quality module/tab it would use doesn't exist until Phase 5. Kept at the same "no NPD visibility yet" status 3a already gave ERGO OFP/Quality, revisited when Phase 5 lands. §10.6's footnote header text `A****` is treated as the same single marker as every table cell's `A**` (a literal SRS typo — no cell anywhere uses more than two asterisks). | Self-decided: safe under-provisioning, consistent with the 3a precedent for the same two roles; the notation question doesn't change any role's actual grant either way. |
| 2026-09-26 | **`npd_issues_risks` gets `owner_organization_type`/`owner_company_id` columns added beyond §13.4.6's literal field list** (mirroring `npd_activities`). Without them an issue/risk with no `linked_activity_id` has no company basis at all to scope customer/supplier visibility against — a bare read of §13.4.6 would leave a hole in the anonymity rule. **`npd_approvals.approval_type`** is a fixed enum (`STAGE_GATE`, `QUALITY_GATE`, `CUSTOMER_SAMPLE_APPROVAL`, `CUSTOMER_MILESTONE_ACKNOWLEDGMENT`, `CUSTOMER_QUALITY_ACCEPTANCE`) each mapped server-side to exactly one approver role/role-set (ERGO NPD/Admin; ERGO Quality; Customer NPD/Quality ×3), validated at request time — `approver_role` is never a free-chosen field, since that would let the requester route a customer-facing approval to itself and bypass §10.6 entirely. | Both additions needed to hold the "approval rights separate from edit rights" and two-way anonymity non-negotiables; no defensible alternative design existed once those two rules are taken as fixed. |
| 2026-09-26 | **Stage-gate approvals reuse 3a's existing mandatory-activity gate instead of hardcoding stage names**: when `npd_activities.approval_required_flag` is set, that activity additionally needs an `APPROVED` `npd_approvals` row linked to it (on top of `status = DONE`) before it counts toward Close Stage's mandatory-activity check. §14.5's "quality gate approval... where applicable" and "customer-side approval... only on designated stages" never name which stage, so this composes with the one mechanism 3a already built rather than inventing per-stage rules the SRS doesn't state. Prompt-checklist `mandatory_flag` stays purely informational in 3b (flagged in the UI, does not block Close Stage or marking an activity DONE) for the same reason — nothing in §12.8.5 describes it as a gate. | Self-decided, additive only: never removes or weakens the existing mandatory-activity check. |
| 2026-09-26 | **Prompt Checklist is built for all three audiences** (ERGO, supplier, customer), even though §12.8.5's screen description names only ERGO and supplier — Customer NPD/Quality's Submit right in §10.6 and §9.2's "milestone acknowledgment" need a response mechanism to attach to, and a checklist response is the natural fit. **Enums with no SRS-given values** (self-decided, no access-control impact): `npd_prompt_checklists.response_type` ∈ {TEXT, YES_NO, FILE}; `npd_issues_risks.severity` ∈ {LOW, MEDIUM, HIGH} (matches `npd_projects.risk_level`); `npd_issues_risks.status` ∈ {OPEN, IN_PROGRESS, CLOSED}; `npd_approvals.approval_status` ∈ {PENDING, APPROVED, REJECTED}. | Labels only; none change who can do what. |
| 2026-09-26 | **Per-audience NPD DTOs, extending the RfqView/QuoteData precedent**: `NpdService.view()` in 3a always resolved and returned both `customer` and `supplier` `Named` regardless of caller — harmless while NPD was ERGO-only, but would leak the supplier to the customer (and vice versa) the instant either got a detail endpoint. 3b splits the detail/activity/list views by audience so the caller's own org type is the only one ever populated, and each audience only ever sees activities/checklists/issues where their own company is the owner (or, for ERGO, everything). | Anonymity rule enforced by DTO shape, not by nulling a shared field — CLAUDE.md non-negotiable. |
| 2026-09-26 | **3b built** (`npd` module extended: `NpdPromptChecklist`/`NpdPromptResponse`/`NpdIssueRisk`/`NpdApproval` entities, `CustomerNpdView`/`SupplierNpdView`, `updateOwnActivity`, issue/approval workflows, `V10__npd_activity_remarks.sql` adding a `remarks` column the "issue responses" grant needed and 3a's `ActivityForm` didn't have). Two bugs caught before this went further: (1) issue/risk visibility had unioned "owned by my company" with "linked to one of my own activities" — since an issue's owner and its linked activity's owner are independent (ERGO can raise an issue owned by the supplier but linked to the customer's own activity), the union leaked the other party's company name through `IssueRiskView.ownerCompanyName`; fixed by scoping strictly to `ownerCompanyId`. (2) `RfqService.visible()`'s role switch never recognized `CUSTOMER_NPD`/`CUSTOMER_QUALITY` (only `CUSTOMER_RFQ/KAM/MANAGEMENT` existed, a Phase 2 gap invisible until a 3b role first called `RfqApi.forCustomer()`), 404ing `NpdService.view()`'s own RFQ-number lookup for a customer viewing their own project; fixed by widening that switch (read-scope only, own company; no new RFQ-module edit/HTTP rights for either role). | Found reviewing the interrupted first build pass; both fixed and regression-tested before the security review. |
| 2026-09-26 | **`quoteReference` dropped from `CustomerNpdView`** (kept on `SupplierNpdView`/`ErgoNpdView`): it is free text the supplier typed onto their own quote when submitting to ERGO (`SupplierQuote.quoteReference`), so nothing stops it naming their own company — showing it to the customer would leak supplier identity through a field the anonymity rule never considered a disclosure channel. Flagged by the build agent, fixed directly (one field removed from one DTO variant, no migration). | Same two-way anonymity rule as every other NPD DTO decision this session. |
| 2026-09-26 | **`ERGO_QUALITY` cannot yet decide a `QUALITY_GATE` approval it is nominally the approver of**, because `visible()`'s `FULL_VIEWERS` still excludes `ERGO_QUALITY` (2026-09-26's earlier "no NPD visibility yet" decision) — it 404s before reaching the approval-decide check, so only `ERGO_ADMIN` can decide a `QUALITY_GATE` approval in practice today. Left as-is (not a security hole: layer 2 still enforces the 404, `ERGO_ADMIN` always covers it) rather than widening `ERGO_QUALITY`'s visibility as an unplanned addition. Revisit when Phase 5 (Quality) gives that role a real reason to see NPD projects. | Flagged by the build agent; deferring is consistent with the precedent already set for the same role in 3a. |
| 2026-09-26 | **Process note + a real security pass found in the working tree, not in any report**: after the backend "finish and verify" step (previous row), the working tree turned out to contain substantially more than that agent's own summary described — a 5th migration (`V11__npd_activity_required_approval_type.sql`), `NpdActivity`/`NpdDtos`/`NpdService`/`NpdController` changes the agent's file list omitted, and 15 more `NpdTests` methods (12 → 27) — found only because the total test count (70) didn't match what a later `git status` showed as modified, prompting a manual read of the diff before trusting it. What's actually there is sound: five labelled findings, each with its own fix and test. **H1**: a stage-move comment is free text ERGO types and could name the other party or a price — the customer/supplier stage-history view (`ExternalStageHistoryView`) now drops `remarks` entirely (keeps who/when only), unlike ERGO's own view. **H2**: reassigning an activity's owner company carried the *previous* owner's remarks, responsible-person and checklist answers over to whoever it's reassigned to — now cleared on any owner change (the checklist *questions* stay; only the answers, which are that company's own words, are wiped). **M1**: an activity's `responsibleUserId` had no check that the person belonged to the activity's own owner company, so ERGO could hand a customer (or the wrong supplier)'s employee name across the anonymity boundary as "Responsible" — now rejected unless `responsible.companyId() == activity.ownerCompanyId`. **M2**: the Close-Stage approval gate accepted *any* `APPROVED` approval linked to an activity, so ERGO could clear a `CUSTOMER_SAMPLE_APPROVAL`-gated activity by requesting and self-approving an unrelated `STAGE_GATE` instead — activities needing approval now also name which `ApprovalType` they need (`npd_activities.required_approval_type`, V11; non-null exactly when `approval_required_flag` is set), and the gate checks for that specific type. **L2**: a customer/supplier or the wrong ERGO role hitting the wrong approval got 409 (already-decided-style) instead of 404, which would let them tell a hidden approval exists on their own project just from the status code — now split cleanly (404 = you could never decide this one; 409 = not decidable right now). | Logging this now, fully, rather than leaving it undocumented because the report that should have described it didn't. All five re-verified directly in the code (not just taken from a comment) and confirmed covered by name-matching tests; full backend suite re-run afterward: 84 tests, 0 failures. |
| 2026-09-26 | **Added `GET /api/npd/mine`** (`NpdOwnProjectRow`: id, npdNumber, partNumber, revision, stage, targetSampleDate, delayDays — no field naming the other company, safe for both audiences): the build brief had deliberately left customer/supplier with detail-by-id only, reasoning notifications/navigation were Phase 6 scope, but that leaves 3b's entire customer/supplier surface unreachable from the UI — RFQ and Quotation already give customers a scoped "my own company's records" list, so NPD lacking the equivalent isn't a deferral, it's an inconsistency. Reuses the existing `findByCustomerCompanyIdInOrderByIdDesc`/a new one-line `findBySupplierCompanyIdOrderByIdDesc`; matches SRS §12.15's customer "NPD" / supplier "NPD Tasks" nav entries. | Self-decided and built directly (small, mechanical, same scope pattern as everything else in this module) rather than reopened with another agent round-trip; verified with a new scope test (own company only, empty for an uninvolved company, 403 for ERGO roles) and a full green `./mvnw verify`. |
| 2026-09-26 | **Security review of Phase 3a (before push)**: no Critical/High/Medium findings. One Low: `NpdProjectForm`'s Javadoc wrongly called it a partial update when `NpdService.updateProject()` always does a full replace of the four project-header fields (matching the service's own correct comment and how the frontend already uses it) — fixed the DTO's comment to match; no behavior changed. | Reviewed against the SRS §10.6 matrix and every 2026-09-26 NPD decision above; folded into the Phase 3a commit before it was pushed. |
| 2026-09-27 | **Security review of Phase 3b (before commit)**: one High, two Medium, six Low findings; two policy questions put to the user, both settled with the stricter answer. **H1 (confirmed with the user)**: ERGO Admin's blanket approval override had applied to every `ApprovalType`, including the three `CUSTOMER_*` ones — nobody had actually confirmed ERGO should be able to decide a customer's own sample-approval/milestone/quality-acceptance gate on their behalf. Narrowed to an explicit `ApprovalType.ergoOwned()` flag (true only for `STAGE_GATE`/`QUALITY_GATE`); `CUSTOMER_*` types now 404 for anyone but the named customer role, same as any other role that could never decide that type. **M1 (confirmed with the user)**: the same user who requested an approval could also decide it (e.g. one ERGO NPD user both flags and clears its own `STAGE_GATE`), which defeats the point of an approval gate for that role. `requireCanDecideApproval`/`allowed()` now reject `me.userId() == approval.requestedByUserId` with 409, regardless of role — a different ERGO NPD/Admin user, or the named customer role, still can. Two new tests cover both. Also fixed two frontend/backend gaps found by the same review pass: (1) the "Owner" (project) and "Responsible" (activity) pickers in `NpdDetailPage.tsx` listed every company's users unfiltered — now filtered to ERGO-only for project owner and to the activity's own owner company for Responsible, with Responsible cleared when "Assign to" changes; `NpdService.updateProject` now rejects a non-ERGO owner server-side, mirroring the existing M1 (3a) check on an activity's responsible person. | Two Medium/six Low findings from the same pass (self-approval aside, audit-on-delete for reassigned checklist answers, cross-audience activity-ID references in issues/approvals/dependencies, inactive users as owner/responsible, shared `severity`/view records across audiences, migration hygiene) are logged as known gaps, not fixed in this pass — none crosses the two-way anonymity boundary by name, only by numeric ID or convenience; revisit before Phase 5/6 if any becomes load-bearing. |
