# Dynamic Permission Management — implementation plan

**Status: IMPLEMENTED 2026-10-20 (see ARCHITECTURE.md §8, 2026-10-20). This file is the original plan; where it differs, the §8 entry wins.** Differences from the plan below: ERGO Admin = Super Admin (no new role); any level incl. supplier `O`; the sidebar is DB-driven (`nav_items`) not code-driven; every sidebar page is a registered module, the non-workflow ones locked (`configurable=false`); the 39 class-load role sets became live views inside `ModuleAccess` instead of being rewritten one by one. No source, migration or config file was changed
while preparing it. Written 2026-10-20 from a read-only inspection of `ergo-connect-backend/` and
`ergo-connect-frontend/`. Nothing was executed (no build, no tests), so "current behaviour" below is read from code.

Goal: ERGO Admin sets, per **role × module**, one of **O / A / V / NV**; the backend enforces it on every protected
API; the frontend (sidebar, routes, buttons) renders from what the backend says; changes apply without a redeploy.

---

## 0. The finding that shapes everything

**ERGO Connect already has the permission model you describe. It is just frozen in code.**

`common/security/ModuleAccess.java` is a hardcoded 18 roles × 5 modules (RFQ, QUOTATION, NPD, OFP, QUALITY) sheet with
levels `NONE / V / A / O / CO` — the client's "role access.xlsx" (ARCHITECTURE.md §8, 2026-10-19). `V` = view,
`A` = create/edit/submit without final approval, `O` = owns + final approval, `CO` = the customer-side `O`. Your
O/A/V/NV legend is this sheet's legend (`NONE` = NV).

So this is **not** a rewrite of the authorization system. It is: *move the sheet from a Java constant to a database
table, make the lookups read it at request time, and put an editor on top.* That is why the plan is small relative to
the request, and why "preserve existing business workflows" is achievable: the seeded table will be identical to
today's sheet, and every existing test is the regression net.

---

## 1. Current architecture and its problems

### 1.1 What exists (verified in code)

| Layer | Where | How it works today |
|---|---|---|
| Identity | `AccessTokens.java` | JWT (HS256) carries `cid`, `ctype`, `role` (ONE active role per session), `sid`. **Permissions are not in the token.** |
| Role sheet | `ModuleAccess.java` | `static` `EnumMap<Role, Level[]>`; `level/canView/canAct/canApprove`, plus `viewers/actors/approvers(module[, side])` returning role sets |
| Layer 1 (controllers) | `AccessGuard` bean `@access` | `@PreAuthorize("@access.view('RFQ','ERGO') or ...")` — already evaluated per request, already reads `ModuleAccess` |
| Layer 1 (services) | 10 service classes | `private static final Set<Role> EDITORS = ModuleAccess.actors(...)` — **~39 constants computed once at class load** |
| Layer 2 (records) | each module's service | company scope; KAM = assigned accounts; supplier = invited/assigned; NPD/OFP-linked |
| Per-record actions | `*Dtos` `Set<XAction> actions` | server computes the caller's allowed buttons with the same code that enforces them |
| Admin-only areas | `@PreAuthorize("hasRole('ERGO_ADMIN')")` | users, master data, document approve/lock, permission screen |
| Non-matrix areas | `hasAnyRole('ERGO_ADMIN', ...18 names...)` | documents, comments, notifications, dashboard, companies — fixed lists, not in the sheet |
| Permission screen | `admin/service/PermissionMatrix.java`, `PermissionMatrixPage.tsx` | **Read-only** (Phase 7e, ARCHITECTURE.md §8 2026-10-07). §10.4–10.8 tables are generated from `ModuleAccess`; §10.1–10.3, 10.9–10.12 are hand-transcribed constants |
| Audit | `AuditLogService`, `audit_logs` | append-only (DB trigger); `record(module, entity, id, event, field, old, new)` usable for non-JPA writes |
| Frontend | `features/auth/utils/access.ts` | a **second copy** of the sheet (`SHEET`) + ~25 `*_VIEWERS/*_EDITORS` constants; `router.tsx` (`RequireRole roles=…`), `Sidebar.tsx` (`roles?: Role[]`), 8 list/detail pages check `activeRole` in an array |
| Infra | `infra/main.bicep` | backend pinned to **1 replica** (scheduled jobs lack a lock) |

### 1.2 Problems this plan fixes

1. **Two hand-synced copies of the sheet** (`ModuleAccess.java`, `access.ts`) — the file header literally says "keep this identical". Drift = UI shows a button the API refuses, or the reverse.
2. **Change = code change + redeploy.** Nothing is editable at runtime.
3. **39 class-load snapshots** (`static final Set<Role>`) would ignore a runtime change even if the sheet were dynamic. This is the one real refactor (§5.2).
4. **The admin screen is a reference page that "cannot change what a role may actually do"** (its own banner text).
5. **~330 hardcoded role-name references across ~45 backend files** (`Role.X`, `hasAnyRole(...)`, `switch (role)`); most are fixed system areas or record-scope, but some sit outside the sheet (`DashboardService`, `RfqApiService`, `CompanyService`, notification recipients).

### 1.3 What is already right (do not touch)

- Role in JWT, permission looked up per request → **a permission change needs no re-login and no token reissue.**
- DTO audience is chosen by `companyType`, not by role/level (`isErgo(me)`, separate `CustomerRfqRow`/`ErgoRfqRow` …) → raising a customer's level cannot leak `*_hidden` fields, competitor quotes, internal notes, target prices.
- Record scope is by company/assignment, not by level → raising a level never widens which company's records a role sees.
- Documents / comments / notifications decide access by asking the owning module's own read check "as that person" (`CurrentUser.runAs`, `*Api` facades) → they inherit the dynamic sheet for free.
- Approval separation: `canApprove` ≠ `canAct`; NPD `mayDecide` also checks the approval's own side; `me.userId() != requestedByUserId`.

---

## 2. Proposed architecture

### 2.1 Principle
One source of truth: table `role_module_permissions`. `ModuleAccess` keeps its **exact public method signatures** but
reads a cached snapshot of that table instead of a constant. The 86 call sites and every `@access.*` expression keep
compiling and keep working. No new engine, no annotation framework, no per-endpoint permission strings.

```
Admin UI ──PUT──► PermissionAdminService ──tx──► role_module_permissions (+ audit_logs, + access_config.version++)
                                                        │ afterCommit
                                                        ▼
request ──► @access.act('RFQ','ERGO') ──► ModuleAccess.level() ──► AccessPolicy snapshot (AtomicReference<Map>)
                                                                        ▲ refreshed on save and every 5 s (version check)
SPA  ◄── permissions in session response + GET /api/auth/permissions + X-Permissions-Version header
```

### 2.2 Levels
Stored: `NV, V, A, O`. (`CO` is not stored: `O` on a CUSTOMER role evaluates to the existing `CO` enum value, so
`Level.canApprove()` etc. and all NPD/Quotation side logic are unchanged.)

| Level | canView | canAct (create/edit/submit) | canApprove |
|---|---|---|---|
| NV | no | no | no |
| V | yes | no | no |
| A | yes | yes | no |
| O | yes | yes | yes |

Valid levels per side (server-validated, UI only offers these):

| Side | NV | V | A | O |
|---|---|---|---|---|
| ERGO | ✔ | ✔ | ✔ | ✔ |
| CUSTOMER | ✔ | ✔ | ✔ | ✔ (= CO, "customer's final approval") |
| SUPPLIER | ✔ | ✔ | ✔ | ✘ — no supplier-side final approval exists in any workflow (SRS §14); "where applicable" in your spec |

### 2.3 Database (next free migration is V25; V1–V24 exist and are immutable)

`V25__admin_role_module_permissions.sql`:

```sql
CREATE TABLE role_module_permissions (
    permission_id     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,   -- audit_logs.record_id needs a BIGINT
    role_code         VARCHAR(40) NOT NULL REFERENCES roles,
    module_code       VARCHAR(30) NOT NULL CHECK (module_code IN ('RFQ','QUOTATION','NPD','OFP','QUALITY')),
    access_level      VARCHAR(2)  NOT NULL CHECK (access_level IN ('NV','V','A','O')),
    updated_by_user_id BIGINT REFERENCES users,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (role_code, module_code)
);
-- DB-level guard mirroring the existing user_role_mappings trigger: supplier roles can never hold 'O'.
-- single-row optimistic-concurrency + cache-invalidation counter
CREATE TABLE access_config (singleton BOOLEAN PRIMARY KEY DEFAULT true CHECK (singleton), version BIGINT NOT NULL DEFAULT 1);
-- seed: 18 roles × 5 modules = 90 rows = today's sheet; access_config row version 1
```

- Seed rows are generated from the current `ModuleAccess` values; a test asserts DB == the independently transcribed
  sheet already in `ModuleAccessTests`.
- `module_code` stays a CHECK-constrained string matching `ModuleAccess.Module`, not a `modules` table: adding a module
  already needs code (a controller, a service), so a lookup table adds a second place to forget. (Revisit only if
  modules ever become runtime-creatable — they will not.)
- History = `audit_logs` (append-only, already the project's §15 mechanism): one row per changed cell, with `reason`.
  No separate history table.
- Roles come from the existing `roles` table (`active` flag included). `CUSTOMER_MANAGEMENT` is inactive in the MVP: shown, greyed, flagged "no user can hold this role".

### 2.4 Backend components
| New / changed | Purpose |
|---|---|
| `common/security/AccessPolicy` (new) | loads the 90 rows into an immutable `EnumMap` snapshot; `level(role, module)`; `refresh()`; scheduled version check (5 s). **Fails closed:** startup aborts if any role×module cell is missing; a failed refresh keeps the last good snapshot and logs. Lives in `common/` (shared kernel) so no module boundary is crossed. |
| `common/security/ModuleAccess` (changed) | `level()` delegates to `AccessPolicy`; `viewers/actors/approvers` compute from it at call time. The hardcoded sheet moves to `DefaultAccessSheet` (used only by "reset to default" and the parity test). |
| `admin/service/PermissionAdminService` (new) | read matrix, validate, save (single transaction), audit, bump version, refresh after commit |
| `admin/api/PermissionAdminController` (new) | endpoints in §3 |
| `AuthDtos.SessionResponse` + `AuthService.response()` (changed) | include the active role's `permissions` map + `permissionsVersion` |
| `SecurityConfig` (changed) | expose `X-Permissions-Version` response header to CORS clients; small filter sets it |
| `PermissionMatrix.sheetModule` (changed) | keeps working, now reads the live values |

---

## 3. Backend APIs

All under `/api`. Admin endpoints: `@PreAuthorize("hasRole('ERGO_ADMIN')")` — a single named expression, so a future
separate Super Admin role (see §9 Q1) is a one-line change.

| Method | Path | Who | Purpose |
|---|---|---|---|
| GET | `/admin/permissions?side=ERGO\|CUSTOMER\|SUPPLIER` | ERGO_ADMIN | `{version, levels[], modules[{code,label}], roles[{code,name,active,cells:{RFQ:{level,default,allowed:[…]}}}]}` |
| PUT | `/admin/permissions/{side}` | ERGO_ADMIN | body `{expectedVersion, reason, changes:[{role,module,level}]}` — **diff only**, all-or-nothing. 200 → new matrix. 409 version mismatch (someone else saved). 422 invariant violation with a per-cell message |
| POST | `/admin/permissions/{side}/reset` | ERGO_ADMIN | restore the signed-off defaults for that side (needs `reason`) |
| GET | `/admin/permissions/history?role=&module=` | ERGO_ADMIN | audit entries (who/when/old/new/reason) |
| GET | `/auth/permissions` | any signed-in user | `{version, role, modules:{RFQ:"O",…}}` for the **caller's active role only** (never other roles' rows) |
| GET | `/admin/permission-matrix` (existing, 7e) | ERGO_ADMIN | kept for the non-configurable SRS reference tables (§10.1–10.3, 10.9–10.12) |

### 3.1 Authorization logic (server, every request)
- Controllers: unchanged `@access.view/act/approve(...)` — now dynamic through `ModuleAccess`.
- Services: layer-1 checks call `ModuleAccess` at use time (§5.2); layer-2 record scope unchanged.
- **Final approval cannot be bypassed:** the only gate is `Level.canApprove()` (`O`/`CO`). `A`, `V`, `NV` fail it in
  controller, service, and the per-record `actions` set. There is no other code path that approves.
- Save-time invariants (server-enforced, UI merely mirrors them):
  1. level must be valid for the role's side (§2.2);
  2. every module keeps ≥ 1 **ERGO** role at `O` (else no one can approve → workflow deadlock);
  3. `ERGO_ADMIN` cannot exceed `V` on the five workflow modules (separation of duties: the 2026-10-19 decision made Admin view-only) — *pending your answer, §9 Q3*;
  4. role/module must exist; unknown values rejected; `reason` required (CLAUDE.md: reasons on sensitive actions);
  5. the admin's own access to Permission Management is **not** a matrix cell (it is `hasRole('ERGO_ADMIN')`), so an admin cannot lock themselves out by editing the grid.
- Warnings, not blocks: a customer side with no `O` on RFQ/Quotation (quotes could never be approved by the customer).

---

## 4. Frontend changes

### 4.1 Permission state
- New `features/permissions/`: `permissionsApi.ts` (RTK Query `getMyPermissions`), `permissionsSlice.ts` (or fold into `authSlice`), `usePermission.ts` → `can(module, 'view'|'act'|'approve')`, `level(module)`.
- Populated from the session response (login / refresh / switch-role already return `SessionResponse`) so there is no second round-trip on load.
- **Promptness:** `baseApi.ts` compares `X-Permissions-Version` on every response with the stored version; mismatch → refetch `/auth/permissions` and invalidate affected tags. Also refetch on window focus, and on any `403`. Worst case a user sees stale buttons for one request; the server has already enforced the new rule.
- Delete the frontend `SHEET` in `access.ts`. Keep only role-independent helpers.

### 4.2 Sidebar, submenus, routes
- **Menu structure stays in code** (labels, icons, paths) — routes are code, a DB-driven menu could not add a page without a deploy anyway. **Visibility** comes from the backend: each nav item declares `{module, need, sides}` instead of `roles: Role[]`, e.g. `{ label:'RFQs', to:'/rfqs', module:'RFQ', need:'view', sides:['ERGO','CUSTOMER'] }`.
- Sub-menus (Quality → Inspections / NCR / Deviations) are filtered the same way.
- New group **"ERGO Admin"** (replaces "Administration"'s admin entries): Users, Master data, **Permission Management**; visible only to `ERGO_ADMIN` (system area, not a matrix cell).
- `RequireRole` → `RequirePermission module need sides` for the five modules; `RequireRole` stays for fixed system areas (`/admin/*`, `/companies`, `/company`). Direct-URL navigation to a denied page renders the existing `AccessDeniedPage`; the API refuses regardless.
- Page buttons: list-page "New …" buttons use `can(module,'act'|'approve')`; **record-level buttons keep using the server's `actions` set** (already correct, already dynamic).

### 4.3 Permission Management page (`/admin/permissions`, replaces `PermissionMatrixPage`)
- Three tabs: **ERGO · Customer · Supplier** → `GET /admin/permissions?side=…`.
- Grid: rows = that side's roles, columns = RFQ, Quotation, NPD, OFP, Quality; each cell a compact select showing only the valid levels (§2.2) with colour chips; cells changed from the saved value are highlighted ("modified from default" marker as well).
- Legend (O Owner / A Act / V View / NV No access). Sticky **Save / Discard** bar showing the number of pending changes; save opens a confirm dialog listing each change (`Customer RFQ · NPD: V → O`) + mandatory reason; sends the diff with `expectedVersion`; handles 409 ("someone else changed this — reload") and 422 (per-cell errors).
- History drawer per cell/role. "Reset side to defaults".
- The static SRS reference tables (non-configurable areas) move to a collapsed "Fixed by design (not configurable here)" panel so nobody thinks they are live.

---

## 5. Existing files to modify / create

### 5.1 Create
Backend: `V25__admin_role_module_permissions.sql`; `common/security/AccessPolicy.java`, `DefaultAccessSheet.java`;
`admin/service/PermissionAdminService.java`; `admin/api/PermissionAdminController.java`, `PermissionAdminDtos.java`;
header filter; tests (§7). Frontend: `features/permissions/{permissionsApi,permissionsSlice,usePermission}.ts`,
`components/RequirePermission.tsx`, `pages/PermissionManagementPage.tsx`, `components/PermissionGrid.tsx`,
`components/PermissionHistoryDrawer.tsx`. Docs: ARCHITECTURE.md §8 decision entry.

### 5.2 Modify
**Backend — the mechanical refactor (the main risk):** replace each `private static final Set<Role> X = ModuleAccess.…(…)`
with a call-time lookup (`private static Set<Role> editors() { return ModuleAccess.actors(…); }`) in
`NpdService` (7), `NpdApiService` (3), `OrderService` (4), `OfpApiService` (1), `DeviationService` (5),
`InspectionService` (5), `NcrService` (5), `QuoteService` (4), `CustomerQuoteService` (1), `RfqService` (3) (≈38–39 by my count; `QuoteService.EVALUATORS` is package-visible and used elsewhere — grep before editing). Without this step a saved change would silently not apply
in those services while the controller (already dynamic) would apply it — an inconsistent half-state, so 9b must land
before the editor.
Also: `ModuleAccess`, `AccessGuard` (unchanged API), `AuthDtos`, `AuthService`, `SecurityConfig`, `PermissionMatrix`,
`PermissionMatrixController`.

**Backend — role-name `switch` blocks to audit for "grant safety"** (a role newly granted a module hits a `default`
branch): `RfqService` (list/inScope), `RfqApiService`, `OrderService` (list, 749, 768), `OfpApiService`,
`QualityScope.of`, `DashboardService`, `QualityNotices`/`Recipients` (notification targeting lists). Today several end
in `default -> all records` for ERGO roles (e.g. `RfqService.list()` default = every RFQ). Granting `ERGO_OFP` view on
RFQ would therefore show **all** RFQs. Decision per block: keep (ERGO roles are internal, SRS §8.2 allows it) or
change `default` to deny/own-scope. Customer/supplier branches are keyed by `companyType` and are already safe.

**Frontend:** `app/router.tsx`, `layouts/Sidebar.tsx`, `features/auth/utils/access.ts`,
`features/auth/store/authSlice.ts` (+`permissions`), `services/baseApi.ts`, `features/auth/components/RequireRole.tsx`
(keep), pages reading role arrays: `RfqListPage`, `RfqDetailPage`, `NpdDetailPage`, `OrderListPage`,
`InspectionListPage`, `NcrListPage`, `DeviationListPage`, `QuotePage`; `features/admin/adminApi.ts` + `index.ts`.
**Note:** the frontend has half-finished duplicate trees (`src/auth/*` re-export shims, `src/components/ui` vs
`src/shared/components`, `src/layouts` vs `src/shared/layout`, `xApi.ts` vs `services/xApi.ts`). Pages currently import
`'../../../auth/access'` (the shim). I will edit the real `features/*` files and update those imports; I will not do the
wider duplicate-tree cleanup unless you ask.

---

## 6. Migration strategy (no behaviour change until the first save)

1. V25 creates the tables and seeds **exactly today's sheet**. `ModuleAccessTests` (independent transcription) is rewritten to assert *DB-backed effective access == signed-off sheet* for all 90 cells — this is the "nothing broke" proof.
2. `ModuleAccess` switches from constant to snapshot; same signatures, so the rest of the code is untouched in 9a.
3. 9b converts the 39 snapshots to call-time lookups. Still no behaviour change; run the full suite.
4. Only then do the admin API/UI (9c+) appear; until someone saves, production behaviour equals today's.
5. Rollback: redeploy the previous build; the new tables are ignored by it (Flyway migrations are additive, V25 is never edited). "Reset to defaults" restores the sheet in-product.
6. Defaults stay in code (`DefaultAccessSheet`) so a corrupted/over-restricted matrix is always recoverable.
7. No feature flag / dual-source mode: fail-closed startup is simpler and safer than a code-vs-DB fallback.

---

## 7. Security considerations and testing

### 7.1 Security
- **Tamper surface:** only `ERGO_ADMIN` reaches write endpoints; checked server-side; `403` for every other role, including Management. Add a rate limit on the write endpoints (the project already has a Redis fixed-window limiter to reuse); ERGO Admin logins already go through the 2FA flow (`AuthService`).
- **Privilege escalation:** an admin editing the grid can grant e.g. `SUPPLIER_QUALITY` → `A` on RFQ. Mitigations: valid-level-per-side, ERGO-`O` floor, Admin ≤ V cap, mandatory reason, full audit, confirm dialog with explicit diff, optimistic version (no blind overwrite).
- **Data isolation is independent of levels** (verified: scope is by company/assignment; DTO audience by `companyType`). The test suite must prove it, not assume it (§7.2 #3).
- **Never exposed to external roles** (competitor quotes, target prices, `*_hidden` margins, internal notes, internal ranking): audience is chosen by `companyType`, so no level can unlock them. Add a regression test.
- **Caching:** snapshot is immutable and swapped atomically; ≤ 5 s staleness across replicas, 0 on the saving replica (single replica today). If a second replica is ever added, the 5 s version poll already covers it.
- **Fail-closed:** missing cell = startup failure; unreadable table during refresh = keep last good snapshot, alert via log.
- **Audit:** every changed cell → `audit_logs` (`PERMISSION_CHANGED`, field = module, old/new, reason, actor/role/IP/channel). Append-only at DB level.
- **In-flight records after a downgrade:** a record already "awaiting" a role that just lost `O` becomes unactionable by that role (correct), but stays visible to others. The save dialog warns generally; a per-role "open items affected" count via the module `*Api` facades is an optional follow-up (9h).
- **Documents/comments/notifications** need no new rules (inherit module read checks), but notification *recipient lists* (`QualityNotices`, `Recipients.ergo(...)`) are static role lists: with NV they self-filter (the read check drops them); with newly-granted roles they just won't be notified until the list is widened — acceptable, noted.
- **Dashboard widgets** are shaped by role group, not module level: a role set to NV on a module must not receive that module's counts. Needs an explicit `canView` guard per widget (9g).

### 7.2 Tests (extend the existing Testcontainers suites; they share one DB, so tests that edit the matrix must restore it)
1. **Parity:** DB-effective == signed-off sheet, all 90 cells (replaces the static `ModuleAccessTests`).
2. **Admin API:** 403 for all 17 non-admin roles on every admin endpoint; 401 anonymous; valid save applies; 409 on stale version; 422 for each invariant (supplier `O`, no ERGO `O`, Admin > V, unknown role/module, missing reason); reset; history; audit rows written once per cell.
3. **Exhaustive isolation matrix** (the key test): for every role × module × level in `{NV,V,A,O}`, set the cell, then assert (a) NV → list empty / get 403-404 / no menu entry / no notifications; (b) V → reads work, every write 403; (c) A → create/edit/submit work, every approval 403; (d) O → approval works; (e) **a customer/supplier role never sees another company's record at any level**; (f) external DTOs never contain hidden fields at any level.
4. **Immediacy:** change a level, next request from an already-issued token reflects it (no re-login); both a controller check and a service check.
5. **Frontend:** `npm run build` + `npm run lint`; manual matrix walk with the demo personas (one per role is already seeded) for: sidebar items, direct URL to a denied route, a hidden "New" button, 409 handling.
6. **Boundary:** `ModuleBoundaryTest` must stay green (the store lives in `common/`).
7. `security-reviewer` agent before each commit (project rule); `./mvnw verify` green before each phase starts.

---

## 8. Phased roadmap (one sitting each, mirrors the Phase 8 chunking)

> This feature sits **outside** the CLAUDE.md build order (Phase 8 hardening, next chunk 8m). It also reverses the
> 2026-10-07 "no dynamic engine" decision and the 2026-10-20 "tighten-only" plan. Both need a logged ARCHITECTURE.md §8
> entry; I recommend running it as **Phase 9**, after or interleaved with 8m, at your call.

| Chunk | Scope | Acceptance criteria |
|---|---|---|
| **9a** Store + engine | V25 migration, `AccessPolicy`, `ModuleAccess` delegates, `DefaultAccessSheet`, startup validation | `./mvnw verify` green with **zero behaviour change**; parity test passes for 90/90 cells; app refuses to start with a missing cell |
| **9b** De-snapshot | ~39 constants → call-time lookups; grant-safety audit of the `switch (role)` blocks (decide keep/deny per block, logged) | Full suite green; a test that edits a DB cell (restoring after) proves a service-level rule changes without restart |
| **9c** Admin API | GET/PUT/reset/history, invariants, audit, optimistic version | All §7.2 #2 tests green; every changed cell audited; non-admin always 403 |
| **9d** Permission delivery | permissions in session response, `/auth/permissions`, version header + CORS expose | Token issued before a change sees the new level on its next call; switch-role returns the new role's map |
| **9e** Frontend foundation | permission state, `usePermission`, `RequirePermission`, dynamic Sidebar, router, page buttons; delete frontend `SHEET` | `npm run build/lint` green; for all 18 demo personas the visible menu equals today's (before any edit) |
| **9f** Permission Management UI | 3-tab grid, diff/confirm/reason, 409/422 handling, history drawer, reset | Admin edits a cell → affected user's menu/buttons change on their next request/focus without redeploy; screen unreachable (route + API) for others |
| **9g** Side doors | dashboard per-widget `canView`, notification recipient lists, sidebar "ERGO Admin" group | NV role gets no module counts on dashboard; no leaked widget |
| **9h** Hardening | exhaustive isolation matrix (§7.2 #3), `security-reviewer`, ARCHITECTURE.md §8 + README updates; optional "open items affected" preview | Matrix test green across all combos; reviewer has no High findings; docs updated |

Prerequisite for 9a: a green `./mvnw verify` baseline. ARCHITECTURE.md §8 (2026-10-19) says the Testcontainers suites
were rewritten for the role sheet but **not run** on the author's machine, so the current state of the suite is unknown to me.

---

## 9. Assumptions, gaps, and decisions I need from you

**Assumptions** (from the repo; say if wrong): ERGO Admin = the existing `ERGO_ADMIN` role; "modules" = the five sheet
modules; Create/Edit/Submit are one tier (`A`) exactly as today (no per-verb toggles); the menu structure remains in code.
**Missing information:** I could not verify (nothing was run) whether the current test suite is green; the client's
original `role access.xlsx` is not in the repo (the Java sheet and its independent test transcription are my only evidence of it).

**Decisions needed (my recommendation first):**

1. **Is "Super Admin" just `ERGO_ADMIN`?** My notes from earlier today record a *separate* Super Admin (console-only, with platform-wide ON / READ_ONLY / HIDDEN module switches) at plan stage, not started. I assumed they are the same person-role *for this feature* and gated on one named expression so splitting later is trivial. The module switches are a different feature layered on top (`effective = min(switch, matrix)`); I would not build them here.
2. **Can the admin raise a cell above today's sheet (e.g. NV → O)?** The earlier 2026-10-20 decision was *tighten-only*. Your request reads as *any level*. I planned **any valid level** (needed for "completely backend-dependent" control) and made the safety come from side validity, the ERGO-`O` floor, and the exhaustive isolation test. Tighten-only would be a ~5-line check in the save validator if you prefer it.
3. **`ERGO_ADMIN` capped at `V` on the five workflow modules?** Recommended (separation of duties, and it matches the 2026-10-19 decision). Otherwise one login can both widen its own rights and approve records.
4. **Supplier roles never get `O`; customer `O` = existing `CO`.** Matches "final approval where applicable". Confirm.
5. **Scope of "modules":** five workflow modules now (recommended — the other areas have bespoke semantics: own-upload-only edit, restricted view, admin-only approval). Documents/Communication/Dashboard/Companies/Users/Master data would be a follow-up with its own decisions.
