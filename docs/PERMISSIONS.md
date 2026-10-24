# ERGO Connect — Permissions, module switches and the dynamic sidebar

**Read this before touching anything about roles, access levels, the sidebar, or the Permission Management screen.**
It is the handover for the 2026-10-20 work (branch `sprint-3`, commits `875a97f` and `4a83956`). It records what was
asked, what was decided, how it is built, how to extend it, what is proven, and what is still open.
Short decision log: `ARCHITECTURE.md` §8, the two 2026-10-20 "Dynamic Permission Management" entries. This file is the
long version. If the two disagree, the code wins, then this file, then §8.

---

## 1. What the client asked for (in order, today's chat)

1. Replace the static, hardcoded role checks with a **database-backed, backend-driven permission system**.
   ERGO Admin gets a sidebar page **ERGO Admin → Permission Management** with three sections: **ERGO / Customer /
   Supplier**, each showing a role × module matrix.
2. Levels per role × module: **O** Owner (full, incl. final approval) · **A** Act (create/edit/update/submit, no final
   approval) · **V** View (read-only) · **NV** No access.
3. Everything backend-enforced: sidebar, page/URL guarding, Create/Edit/Submit/Approve buttons, every protected API;
   ERGO / Customer / Supplier data stays isolated; changes apply without redeploy; only authorised users can change
   permissions.
4. Answers to my planning questions (these are **settled**, do not re-ask):
   - "Super Admin" = the existing **ERGO Admin** role. No new role.
   - Any level may be given to any role (not "tighten only"), including **O for suppliers**.
   - **ERGO Admin is view-only (NV/V) on the five workflow modules**, but still creates accounts, manages permissions
     and adds companies.
   - The matrix covers the **5 workflow modules** (RFQ, Quotation, NPD, OFP, Quality). **Every other sidebar page is
     also registered as a module** and the **sidebar is fully dynamic**, so permissions for other modules can be added
     later.
5. Follow-up request: review the implementation against the requirements and fix remaining issues:
   - RFQ/order scoping must never give another company's data to anyone. **Clarified by the client:** ERGO roles seeing
     every company's records on RFQ/OFP is **correct and stays**.
   - Customer approvals can get stuck if all customer roles are set below O. **Clarified:** the admin will set this
     properly; **no guard wanted**.
   - Build the **module on/off/read-only switches**, as a dropdown with the same options **O / A / V / NV**.
   - The **demo login stays for now**; the client removes it after testing all roles.

## 2. Final design in one page

```
role_module_permissions (role × module → NV|V|A|O)   app_modules (module, label, configurable, access_cap)   nav_items
                      \                                  /
                       AccessPolicy (immutable snapshot, effective = lower(role level, module access_cap))
                       /                |                      \
   ModuleAccess (live role sets)   AccessGuard @access.*     AccessPolicy.view(role, side) -> {permissions, menu}
   (services, ~39 role-set consts)  (@PreAuthorize on         sent in the session response, GET /api/auth/access,
                                     controllers)              header X-Permissions-Version on every response
```

- **Effective level = the lower of the role's own level and the module's switch** (`access_cap`). The cap is applied
  in exactly one place, `AccessPolicy.readOnce`, so every check sees it.
- Level semantics (`ModuleAccess.Level`): NV no view · V view · A view+act · O view+act+approve. A **customer-side O is
  the engine's `CO`** (same powers, different name); stored codes are only `NV,V,A,O`.
- **Module switch meaning:** `O` on (as the grid) · `A` nobody gives final approval · `V` read-only · `NV` hidden for
  every role and company (data kept, open records wait). Only configurable modules can be switched.
- **Fixed modules:** DASHBOARD, DOCUMENTS, COMMUNICATION, COMPANIES, SUPPLIERS, COMPANY_PROFILE, MASTER_DATA, USERS, PERMISSIONS
  are rows in the sheet (seeded with their old effective access, used for sidebar + route guards) but
  `configurable=false`: shown **locked** in the matrix, rejected on save, and their backend checks are still role
  lists in code (`hasAnyRole(...)`). To make one configurable: set `app_modules.configurable=true` in a new migration,
  replace its controller's role list with `@access.view/act/approve('MODULE'[, 'SIDE'])`, make its service use
  `ModuleAccess`, add tests. Nothing else.
- **Record scope is separate and untouched by any level or switch** (own company, KAM assigned accounts, supplier
  assignments, NPD/OFP linked). DTO audience is chosen by `companyType`, never by role/level, so no level unlocks
  competitor quotes, margins, internal notes, etc.

## 3. Where everything lives

### Backend (`ergo-connect-backend/src/main/java/com/ergo/connect/`)
| File | Role |
|---|---|
| `common/security/AccessPolicy.java` | The live snapshot. Loads at startup (fails closed: missing role×module row = app does not start), reloads on save and every 5 s (`ergo.access.refresh-ms`), never replaces a newer snapshot with an older one, reads version before/after to stay consistent, **pins one snapshot per request** (ThreadLocal) so a permission edit can't change a caller's audience mid-request. Also `view(role, side)` (permissions + server-filtered menu) and `lower(a,b)`. |
| `common/security/ModuleAccess.java` | Same public API as before (`level/canView/canAct/canApprove`, `viewers/actors/approvers(module[,side])`, new `viewersExcept`). The role sets are **live views** recomputed on every use, so `private static final Set<Role> X = ModuleAccess.actors(...)` in services follows edits. **Do not copy them into immutable sets at class-load.** |
| `common/security/DefaultAccessSheet.java` | The signed-off default sheet. Used only by "reset to defaults" and parity tests. Not enforced. |
| `common/security/AccessGuard.java` | `@access.view/act/approve('MODULE'[,'SIDE'])` for `@PreAuthorize` (unchanged API). |
| `common/security/PermissionsVersionFilter.java` | Pins the snapshot for the request; sets `X-Permissions-Version`. Header exposed via CORS in `SecurityConfig`. |
| `admin/service/PermissionAdminService.java`, `PermissionAdminDtos.java` | Matrix read, save (diff), reset, module switch, history. All rules live here. |
| `admin/api/PermissionAdminController.java` | The endpoints (below). `hasRole('ERGO_ADMIN')` on every one. |
| `admin/service/PermissionMatrix.java` + `PermissionMatrixController` | The older **read-only SRS reference tables** (`GET /api/admin/permission-matrix`), kept as the "SRS reference" tab. Its `MODULES` list is built once at class load, so it does not follow live edits (known, low). |
| `masterdata/access/AuthService.java`, `AuthDtos.java`, `masterdata/api/AuthController.java` | `SessionResponse.access` and `GET /api/auth/access`. |
| `dashboard/service/DashboardService.java` | Every module's widgets built only if the caller can view that module (`items()/count()` helpers). |
| `db/migration/V25__admin_role_module_permissions.sql` | Tables + seed (the exact old sheet and the old sidebar). DB CHECK: ERGO_ADMIN can't exceed V on the 5 workflow modules. |
| `db/migration/V26__admin_module_switches.sql` | `app_modules.access_cap` (+ `module_id` for auditing). |

### Frontend (`ergo-connect-frontend/src/`) — edit the real files under `features/`, not the re-export shims in `auth/`, `components/ui/` etc.
| File | Role |
|---|---|
| `features/permissions/permissions.ts` | `useCan(rules)`, `allows()`, and the **named rule sets** (`RFQ_VIEW`, `OFP_CREATE`, …). Rules name modules + needs (`view`/`act`/`approve`) + optional sides, **never roles**. |
| `features/permissions/RequirePermission.tsx` | Route guard (replaces `RequireRole` for matrix/sidebar modules). |
| `features/auth/store/authSlice.ts` | `Session.access` (`version`, `permissions`, `menu`), `accessUpdated`. |
| `services/baseApi.ts` | Compares `X-Permissions-Version` (and any 403) with the stored version and refetches `/auth/access` once. |
| `layouts/Sidebar.tsx`, `layouts/MainLayout.tsx` | Renders `session.access.menu` (icon key → MUI icon map in Sidebar). MainLayout fetches access if a restored demo session lacks it. |
| `app/router.tsx` | All guarded routes use `RequirePermission`. |
| `features/admin/pages/PermissionManagementPage.tsx`, `services/adminApi.ts` | The screen: tabs ERGO/Customer/Supplier/SRS reference, module-switch dropdowns, grid, diff + reason dialog, reset, history. |
| `features/auth/utils/access.ts` | Now only `COMPANY_CREATORS` (fixed Companies page). The old frontend copy of the sheet is gone. |

### The sidebar is data
`nav_items` rows: `item_key, section_title, section_order, sort_order, label, path, icon, module_code, min_level, sides`.
An item shows when the role's **effective** level on `module_code` ≥ `min_level` and its company type is in `sides`.
Adding a menu entry = a migration inserting a row (+ the route and page in the frontend; icon key must exist in
`Sidebar.tsx`'s `ICONS` map, otherwise it falls back to the dashboard icon). Sections today: Sourcing & Workflow,
Quality, Workspace, Administration, ERGO Admin.

## 4. API (all under `/api`)
| Method + path | Who | Purpose |
|---|---|---|
| `GET /admin/permissions/{ERGO\|CUSTOMER\|SUPPLIER}` | ERGO_ADMIN | Matrix: `version, levels, modules[{code,label,configurable,cap}], roles[{code,name,active,cells{module:{level,effective,defaultLevel,editable,allowed[]}}}]` |
| `PUT /admin/permissions/{side}` | ERGO_ADMIN | `{expectedVersion, reason, changes:[{role,module,level}]}` — diff only, all-or-nothing. 409 stale version, 422 rule violation, 400 missing reason. |
| `POST /admin/permissions/{side}/reset` | ERGO_ADMIN | `{expectedVersion, reason}` — restores the shipped sheet for that side's configurable modules. **Leaves module switches alone.** |
| `PUT /admin/permissions/{side}/modules/{module}` | ERGO_ADMIN | `{expectedVersion, reason, level}` — the module switch. `{side}` only chooses which matrix is returned. |
| `GET /admin/permissions/history[?role=&module=]` | ERGO_ADMIN | Audit entries; module-switch rows have `role = null` (only when no role filter). |
| `GET /auth/access` | any signed-in user | The caller's own `{version, permissions, menu}`. Also embedded in login/refresh/switch-role as `SessionResponse.access`. |

**Server-enforced save rules** (UI only mirrors them): configurable module only · role must belong to the side being
edited · valid level · **ERGO_ADMIN only NV/V** · **every configurable module keeps ≥1 ERGO role at O** (ERGO_KAM does
not count: it is account-scoped) · reason required (≤500) · `expectedVersion` must match (row lock on `access_config`,
one version bump per save) · no-op saves are refused · one `audit_logs` row per changed cell
(`RoleModulePermission`) or per switch (`ModuleSwitch`) with the reason.
Deliberately **not** enforced: a customer-side O floor (admin is trusted; see §1).

## 5. What is proven (tests, all green: 454 backend tests)
- `common/security/ModuleAccessTests` — the seed equals the signed-off sheet for all 90 cells.
- `admin/PermissionAdminTests` — who may use the API, every refusal rule, immediate effect on an already signed-in
  token (sidebar, permissions, API), supplier-at-O gets no ERGO approval, KAM at NV on NPD is cut off, DB CHECK for
  ERGO_ADMIN, audit + reset.
- `admin/ModuleSwitchTests` — O/A/V/NV switch effects (incl. dashboard with a real record), switch rules, audit/history,
  **two simultaneous saves → exactly one 200 and one 409**, revocation of an approval, reset on all three sides,
  ERGO Admin limits while still managing users/companies/permissions.
- `admin/AccessIsolationTests` — with a real RFQ and order owned by company A, **every active customer and supplier
  role in company B, at NV/V/A/O**, never lists, opens, reads history, performs any write/ERGO action, or sees it on
  the dashboard. Controls: owner and ERGO still reach their records. (`CUSTOMER_MANAGEMENT` is inactive in the MVP and
  can't sign in, so it is skipped.)
- Security reviews were run twice (before each commit). Fixed from them: audience chosen by `companyType` not role-set
  membership (`NpdService.view`, `OrderService.list`, `OfpApiService.dashboardItems`, `RfqService.customerFor`),
  per-request snapshot pinning, monotonic snapshot install, KAM NPD view check, inspection `customerVisible` needs an
  ERGO Quality approver, RFQ conversion queue closed when RFQ is hidden, NPD reminders notify nobody on an empty editor
  set, scheduler pool size 3 (`application.yml`).

## 6. Rules for anyone changing code here
1. **Never** write a new `hasRole(...)`/role-name list for a workflow module. Use `@access.*` on controllers and
   `ModuleAccess`/live role sets in services. Pick the side (`'ERGO'`, `'CUSTOMER'`, `'SUPPLIER'`) wherever the action
   belongs to one side — a supplier or customer at O must **never** reach an ERGO-only action. Branch on
   `me.companyType()`, not on role-set membership, when choosing which DTO or which "all records" query to use.
2. Don't cache role sets or levels in an immutable collection; don't read `role_module_permissions` directly (use
   `AccessPolicy`) except in `PermissionAdminService`, which needs the stored (uncapped) values.
3. New permission cells/modules: Flyway migration only (V1–V28 are immutable once committed; next is **V29**). A new
   configurable module must seed a row for **every** role, or the app refuses to start (intended).
4. Tests that change the sheet or switches share one database: **restore it in `@AfterEach`** (see the three test
   classes above; also reset `app_modules.access_cap`, bump `access_config.version`, call `policy.refresh()`).
5. Run `security-reviewer` before every commit that touches access (project rule in `CLAUDE.md`).
6. Request bodies are validated (`@Valid`) **before** `@PreAuthorize` runs — a test expecting 403 must send a valid body
   or it gets 400.

## 7. Still open (report, not fixed) — ask the client before changing
- ~~**Do switches freeze background and cross-module paths?**~~ **Resolved 2026-10-20 - yes, strictly; see §9.** The
  older open questions (quality gates when Quality is off, comments/uploads on read-only records) are decided there too.
- **"A" switch copy:** for OFP/Quality an A switch also removes ERGO's own create/run rights (those require approve);
  the UI text says so. Fails closed.
- `PermissionMatrix.MODULES` (SRS reference tab) is frozen at first load.
- **Rolling deploys:** don't flip a switch until every instance runs the strict-switch code (V27): older instances ignore `access_cap` (before V26) or apply it only to user requests (before V27).
- `RfqService`/`OrderService` ERGO scope switches default to "all records" for ERGO roles — **intended**, do not
  "fix". Giving an ERGO role access to a module it didn't have shows that module's records across all companies.
- Not implemented: rate limit on the admin write endpoints (ERGO Admin logins go through the 2FA flow);
  "open items affected" preview before a downgrade; per-feature (finer than module) switches.
- **Security smell:** `features/auth/utils/demoAccounts.ts` signs JWTs in the browser with the dev secret (1-click demo
  login). The client keeps it for role testing and removes it later — **must be gone before real go-live.**

## 8. Environment notes learned today
- Backend needs JDK 21 (`JAVA_HOME="C:/Program Files/Java/jdk-21.0.10"`); tests need Docker Desktop running
  (Testcontainers). Full run: `./mvnw -o test` (~3–4 min). Frontend: `npm run build && npm run lint` (lint prints many
  pre-existing `export *` warnings, no errors).
- On the author's machine a **native PostgreSQL holds port 5432**, so `docker compose` Postgres is not reachable on
  `localhost:5432` and `spring-boot:run` fails with "password authentication failed for user ergo". Workaround: run a
  throwaway container on another port and set `SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:55432/ergo_connect`.
- Demo sign-in on the login page (dev profile) signs you in as any of 20 personas with one click; it works against a
  dev backend only.
- The shell tool's heredocs containing apostrophes/quotes sometimes fail to parse; write files with the file tools.
- Frontend has half-finished duplicate trees (`src/auth/*` re-export shims, `components/ui` vs `shared/components`,
  `layouts` vs `shared/layout`, `xApi.ts` vs `services/xApi.ts`). Edit the `features/*` versions; cleanup not done.

---

## 9. Strict module switches (decided 2026-10-20, replaces the "still open" first bullet of §7)

The client chose the **stricter** reading: a module switch binds the *whole backend*, not only what a signed-in user's
role may click. The switch only **caps** effective permissions; `role_module_permissions` is never rewritten, so
switching back to **O** restores everything exactly (data, role grid, pending work).

### 9.1 The rule
| Switch | Reads | Writes / status changes / background jobs |
|---|---|---|
| **O** | as the grid | as the grid |
| **A** | as the grid | as the grid, but nobody gives final approval (levels already enforce this) |
| **V** | unchanged for authorised users | **refused for everyone and everything**: other modules' calls, internal service calls, comments, document upload/replace/lock/approve, status transitions, scheduled jobs |
| **NV** | **nothing**: API, dashboards, conversion queues, internal facade calls, comments, documents, notifications, jobs. Data is kept | refused |

The role-level grid is unchanged: the switch lowers it, `AccessPolicy.readOnce` already did that for user requests.
What §9 adds is every path that had **no role check of its own on the module it touches**.

### 9.2 How it is enforced (one class, `common/security/ModuleSwitch`)
- `ModuleSwitch.requireWritable(Module...)` is the **only** way a write path checks a switch. It reads `app_modules`
  from the database (never the snapshot) and, inside a writable transaction, **share-locks** the rows until the
  transaction ends. A concurrent switch change therefore waits for the in-flight operation; one operation never
  straddles a switch change, on any instance or thread. A refusal is HTTP 409 with a plain message; the operation's
  transaction rolls back, so cross-module operations are all-or-nothing.
- `ModuleSwitch.readable(Module)` / `requireReadable` (snapshot) decide reads: hidden = 404 / empty / false / 0.
- `PermissionsVersionFilter` re-checks the access version at the start of **every** API request, so a switch or grid
  change saved on instance A applies to the next request on instance B (previously: up to 5 s).
- Every method of the five module facades (`RfqApi`, `QuotationApi`, `NpdApi`, `OfpApi`, `QualityApi`) is classified
  (read / write / job) in `ModuleSwitchFacadeTest`, which fails the build if a facade method is added unclassified.
- Cross-module operations check **every** affected module before the first write: Quotation→RFQ (invite, submit,
  reopen, decide, compare, send to customer, customer answer), OFP→NPD (handover), Documents→OFP/Quality (replace),
  Quality→NPD/OFP (link). The *caller's* authority still comes from the initiating module's rule (a supplier
  submitting a quote has no RFQ rights and must not need any); the **switch** of the touched module is what is checked.

### 9.3 Background work
- **Overdue reminders** (`OverdueReminders`): a module that is not writable (V or NV) is not scanned and its items are
  not reported (V: no one can act on a reminder; NV: must not expose it). Nothing is lost: the scan is stateless and
  dedupes per day, so the next run, or the catch-up run fired when the switch is changed back, sends what is due.
- **Deviation expiry** (`DeviationService.expireOverdueDeviations`): runs only while Quality accepts changes. It selects
  by date, so the first run after restore (a catch-up also fires on restore) expires everything that lapsed meanwhile.
- **Notifications**: nothing is delivered for a hidden module; rows already stored keep their `module_code` (V27) and
  are hidden from the list, the unread count and mark-read while it is NV, then reappear unchanged.

### 9.4 Business workflows that stall under the strict rule (known, intended, reversible)
| Switch | What is delayed / blocked until it is back on |
|---|---|
| **RFQ = V** | Supplier invitations ("float"), supplier quote submit/regret/reopen/clarification (each moves the RFQ status), comparison decisions, sending a quote to the customer, the customer's approve / revise / decline (customer approvals sit unanswered), RFQ file uploads. Suppliers cannot draft quotes (quoting window reads as closed). |
| **RFQ = NV** | All of the above, plus: every Quotation screen that embeds RFQ data (supplier quote list/detail, ERGO comparison, customer quote) is empty/404, NPD conversion (needs the RFQ) is blocked and NPD shows no source-RFQ number, RFQ comments/documents/notifications/dashboard widgets are hidden, quote-overdue reminders pause. |
| **Quotation = V** | Supplier quotes cannot be saved/submitted, ERGO cannot shortlist/select/send, the customer cannot answer a quote. RFQ statuses stop advancing. |
| **Quotation = NV** | Same, and NPD conversion is blocked (no selected quote readable); quote files/comments/notifications hidden; supplier "quote due" dashboard empty. |
| **NPD = V** | Convert RFQ→NPD, stage closes (incl. Final Sample Readiness), activities/issues/approvals, and **OFP "Handover to OFP"** (it marks the NPD project handed over): new orders from NPD projects wait. Quality records can still be linked to NPD projects (read). |
| **NPD = NV** | Same, and Quality cannot link new inspections/NCRs/deviations to NPD projects, NPD overdue reminders pause, the RFQ conversion queue is closed. |
| **OFP = V** | All order changes: production/packaging updates, dispatch, shipments, POD upload/replace, close, NPD handover. Quality records can still be raised against orders (read). |
| **OFP = NV** | Same, and Quality cannot link to orders, order comments/documents/notifications hidden, supplier-performance widget empty. |
| **Quality = V** | No inspections/NCRs/deviations/CAPA changes or approvals, no CAPA evidence upload. **Dispatch of orders and NPD Final Sample Readiness still read the gate**, so a dispatch waits if its inspection is not yet passed (nothing can be passed now). Deviation expiry pauses (APPROVED deviations stay APPROVED past validity until the catch-up run). |
| **Quality = NV** | The gates **fail closed**: **no order can be dispatched and no NPD project can close Final Sample Readiness** while Quality is hidden. This is the largest operational impact; it is deliberate (a hidden gate cannot be evaluated). |
| any | Comments (create/edit/resolve/escalate/attach), document upload/replace/lock/approve/reject on that module's records are blocked at V and hidden at NV. |

### 9.5 Decisions taken while building it
1. **Caller authority vs. module switch.** A cross-module step is authorised by the *initiating* module's rule (a supplier
   submitting a quote has no RFQ rights and must not need any). What is checked in **every touched module** is its
   **switch** (the effective ceiling). The caller's role level in the touched module is deliberately *not* required: it
   would change the signed-off grid (Customer NPD raises deviations on OFP orders with OFP = NV) and break suppliers.
2. **Quality links read, they do not change, NPD/OFP.** Raising an inspection/NCR/deviation against an NPD project or
   order needs the target *readable* (V ok, NV refused with the usual "linked record not found"). Quality itself must
   accept changes (its own role levels already cap that).
3. **Gates fail closed.** OFP dispatch-ready/dispatch and NPD Final Sample Readiness read Quality. At NV they cannot be
   evaluated, so they refuse with a clear reason; at V they are read normally.
4. **RFQ at V or NV freezes quoting**: `RfqApi` reports quoting/inviting/evaluating/offering closed, so no quotation
   action that would move the RFQ is offered or accepted. The supplier's "assigned RFQs" pages are RFQ-gated, so they
   close (403) when RFQ is hidden.
5. **Reminders and expiry are paused, never queued.** They are stateless (select by date, one reminder per person per
   item per day), so nothing needs a backlog; a `ModuleSwitchChanged` event runs them straight away on restore.
6. **Notifications are filed under a module** (`notifications.module_code`, V27) so an NV module's can be hidden without
   deleting them. A notification about a *document* takes the module of the document's own record.
7. **Every instance, every thread.** Writes read the database (and lock the switch row for the transaction); each API
   request first re-checks the access version; background threads use the same guards.

### 9.6 Affected files
Backend `common/security/` (`ModuleSwitch` new, `AccessPolicy`, `PermissionsVersionFilter`), `common/event/ModuleSwitchChanged`
(new), `rfq/domain/RfqApiService`, `quotation/domain/{QuotationApiService,QuoteService}`, `npd/domain/{NpdApiService,NpdService}`,
`ofp/domain/{OfpApiService,OrderService}`, `quality/domain/{QualityApiService,DeviationService}`,
`communication/{api/CommunicationApi,domain/CommunicationApiService,CommentService,RecordScope,NotificationService,OverdueReminders}`,
`documents/{api/DocumentsApi,api/DocumentStore,domain/DocumentService,domain/DocumentsApiService}`,
`admin/service/PermissionAdminService`, migration `V27__communication_notification_module.sql`.
Frontend: `features/npd` (null-safe RFQ number), `features/admin/pages/PermissionManagementPage.tsx` (switch copy).
Tests: `admin/{StrictModuleSwitchTests,StrictSwitchSideChannelTests,ModuleSwitchFacadeTests,SwitchTestSupport}`.

### 9.7 Known residual (from the security review, accepted)
- A module's **own** single-module writes (e.g. a supplier editing a draft quote while only QUOTATION is flipped to V in
  the same instant) trust the access snapshot taken at the start of that request (re-synced from the database at request
  start, so never older than that request). The window is one request, it can only let a change already in flight finish,
  and every cross-module write (the dangerous kind) locks the switch rows until commit. Closing it for single-module
  writes would need a transactional aspect over every service method; not done.
- A switch change waits (max 10 s, then a clear 409 "try again") for in-flight operations holding the switch row.
- Hidden-module comments still consume the capped comment-inbox window before being filtered out (no leak; a very busy
  hidden module could push visible threads off the first page).
- Under RFQ = V the RFQ's quoting window reads as closed, so the quote-overdue reminders and the supplier "overdue" flag
  go quiet too (functional, intended).

---

## 10. Super Admin, and the permission module end to end

### 10.1 Super Admin = the `ERGO_ADMIN` role (decided 2026-10-20)
Only an account of the **ERGO company** can hold it, and only an existing Super Admin can give it out. No other role
(ERGO Management, KAM, RFQ, NPD, OFP, Quality), no customer and no supplier can ever get it. Enforced three times:
| Layer | Rule |
|---|---|
| Controller | every user/role/permission endpoint is `hasRole('ERGO_ADMIN')`: a non-admin is refused 403 before any logic |
| `UserAdminService.checkRoles` | giving `ERGO_ADMIN` to a non-ERGO company's user is 422 ("can only be held by an ERGO account"); the acting user must themselves be a Super Admin (403) - create and edit-roles alike |
| Database | V2 trigger on `user_role_mappings` (role type must equal the user's company type); **V28** triggers on `users.company_id` and `companies.company_type` close the remaining ways round it (moving an admin to another company; re-typing the ERGO company). Holds for manual SQL and any future code |
- It is **not** a new role: no `SUPER_ADMIN` code exists, so there is nothing to assign by mistake. Mandatory 2FA, "cannot remove your own Super Admin role" and the bootstrap admin (`AdminBootstrapRunner`, ERGO company only) are unchanged.
- Super Admin still manages accounts, companies and permissions, but is capped at NV/V on the five workflow modules (§4), so it can never create or approve business records.
- Tests: `masterdata/access/SuperAdminTests`.

### 10.2 One request, end to end
1. **Sign in**: the user gets a session with `access` (their level per module and a server-filtered sidebar). The SPA only renders what that says; the server re-checks everything below.
2. **Request arrives**: `PermissionsVersionFilter` re-checks the access version (a change saved on any instance applies now) and pins one snapshot for the request.
3. **Layer 1 - role**: `@access.view/act/approve('MODULE'[,'SIDE'])` on the controller. Effective level = lower of the role's grid level and the module switch (`AccessPolicy`). Fixed modules (users, permissions, companies, ...) use `hasRole(...)` lists.
4. **Layer 2 - record scope**: own company / KAM accounts / supplier assignments, inside each module's service. Never changed by a level or switch. DTO audience is chosen by `companyType`.
5. **Module switch (§9)**: every cross-module call, comment, document, notification and job re-checks the switch of each module it touches (`ModuleSwitch`); writes lock the switch row until commit.
6. **Workflow + audit**: status changes only through the module's workflow service; every write is audited with its reason.
7. **Response**: the record lists the caller's allowed `actions`, computed by the same checks.

### 10.3 Who can change what
| Change | Who | Where |
|---|---|---|
| Role level for a role x module | Super Admin | Permission Management (`PUT /api/admin/permissions/{side}`), diff + reason + version |
| Module switch O/A/V/NV | Super Admin | same screen (`.../modules/{module}`), audited, never touches the grid |
| Create users, set roles, activate, reset password | Super Admin | Users screen (`/api/users...`) |
| Give the Super Admin role | an existing Super Admin, to an ERGO-company user only | §10.1 |
| Anything on a customer's or supplier's record | their own roles, per the grid, in their own company | layer 2 |

## 11. Changes for the flow document (2026-10-23, Slice 3, migration V35)

The shipped sheet (`DefaultAccessSheet`) and the seeded table moved in two cells, agreed with the client:

| Role | Module | Was | Now | Why |
|---|---|---|---|---|
| ERGO_KAM | NPD | V | **A** | The KAM decides a customer's Sample PO and creates the sample project (flow doc step 7). |
| SUPPLIER_RFQ | NPD | NV | **V** | The plan is assigned to Supplier NPD, RFQ and Quality (step 7); RFQ only reads it. |

The migration changes a cell only if it still holds the old shipped value (an ERGO Admin's edit is kept) and bumps
`access_config.version`.

- **The KAM's A on NPD is deliberately narrow.** It covers Sample PO return / accept and project creation
  (`SamplePoService`, confined to the KAM's assigned accounts). `NpdService.EDITORS` excludes ERGO_KAM
  (`ModuleAccess.actorsExcept`), so a KAM still cannot edit a project, work customer or supplier steps, raise issues or
  request approvals. A security review found that a plain "A" would have allowed all of that.
- **Sample PO**: raised only by the customer-side NPD approver (`approve('NPD','CUSTOMER')` = Customer NPD); returned or
  accepted only by ERGO_KAM and ERGO_MANAGEMENT that can act on NPD; read by customer NPD viewers (own company), ERGO
  roles with NPD view (KAM: assigned accounts only). Suppliers never reach a Sample PO.
- **Plan release gates the supplier**: while `npd_projects.plan_status = AWAITING_PLAN` the supplier cannot see the
  project through any path (list, detail, dashboard, notifications, comments, `NpdApi.projectRef`), and stages cannot be
  closed. `POST /api/npd/{id}/plan/assign` (ERGO_NPD / ERGO_MANAGEMENT, the "O" roles) releases it.
- New record kind `sample-po` for notifications (`RecordScope`, `ModuleSwitch.ownerOf` = NPD).
- **Plan submission (slice 4, V36)**: the supplier's roles with A on NPD fill in their own steps (`PUT /api/npd/activities/{id}/plan`) while the plan is with them; only **Supplier NPD** submits (`/plan/submit`); only ERGO's "O" roles (ERGO NPD, Management) agree or send back (`/plan/agree`, `/plan/send-back`, comment mandatory on send-back). No cell of the sheet changed. Working a step and closing a stage need `plan_status = AGREED`; while `SUBMITTED` nobody edits the steps.
- **Quality validation (slice 5, V37)**: the party is taken from the caller's role - Supplier Quality submits (internal approval) and validates (a colleague of the submitter), ERGO Quality validates, Customer NPD and Customer Quality give the customer's final approval - each as long as the role can act on NPD. This is a deliberate exception to the legend "A acts but gives no final approval": the flow document names these roles, so demoting Customer NPD from O to A does not remove its final approval. No cell of the sheet changed.

## 12. Changes for the flow document (2026-10-24, Slice 6, migration V38)

| Role | Module | Was | Now | Why |
|---|---|---|---|---|
| ERGO_KAM | OFP | V | **A** | The KAM decides a customer's production PO and creates the OFP project (flow doc step 11). |

The migration changes the cell only if it still holds the shipped value and bumps `access_config.version`. Two new
sidebar items are seeded: `production-pos` (Production POs) and `ofp-projects` (OFP projects).

- **The KAM's A on OFP is deliberately narrow.** It covers production PO return / accept and OFP project creation
  (`ProductionPoService`, confined to the KAM's assigned accounts). `OrderService.ERGO_ACTORS` excludes ERGO_KAM, so a
  KAM still cannot hold, release or clear orders, and `NpdRights` keeps it out of the editors of a project.
- **Roles per project kind (`NpdRights.of(project)`).** A sample project is governed by the NPD column as before. A
  production (OFP) project is governed by the OFP column of the sheet, unioned with the NPD roles that already have
  access, and it is bound by both the NPD and the OFP switch. Customer OFP is **not** an approver: the customer's final
  approval stays with the NPD column's approving role.
- **Production PO**: raised only by the customer-side OFP approver (Customer OFP) for an approved sample project of its
  own company; returned or accepted only by ERGO_KAM (assigned accounts) and ERGO_MANAGEMENT that can act on OFP; read by
  customer OFP viewers (own company) and ERGO roles with OFP view. Suppliers never reach a production PO.
- **Handover to an order** exists only for production projects.
