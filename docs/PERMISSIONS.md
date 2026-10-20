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
- **Fixed modules:** DASHBOARD, DOCUMENTS, COMMUNICATION, COMPANIES, COMPANY_PROFILE, MASTER_DATA, USERS, PERMISSIONS
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

## 5. What is proven (tests, all green: 432 backend tests)
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
3. New permission cells/modules: Flyway migration only (V1–V26 are immutable once committed; next is **V27**). A new
   configurable module must seed a row for **every** role, or the app refuses to start (intended).
4. Tests that change the sheet or switches share one database: **restore it in `@AfterEach`** (see the three test
   classes above; also reset `app_modules.access_cap`, bump `access_config.version`, call `policy.refresh()`).
5. Run `security-reviewer` before every commit that touches access (project rule in `CLAUDE.md`).
6. Request bodies are validated (`@Valid`) **before** `@PreAuthorize` runs — a test expecting 403 must send a valid body
   or it gets 400.

## 7. Still open (report, not fixed) — ask the client before changing
- **Do switches freeze background and cross-module paths?** Today a V/NV switch blocks user access, but internal paths
  still run: Quotation → RFQ status changes, OFP handover → NPD `handoverReadyFlag`, Quality linking to hidden NPD/OFP
  records, overdue reminders, deviation-expiry job, comments and document lock/approve on existing records, old
  notifications. Fix would be one guard per shared facade (`AccessPolicy` cap accessor + 409/empty); needs a logged
  decision. Also the older open questions: quality gates when Quality is off, comments/uploads on read-only records.
- **"A" switch copy:** for OFP/Quality an A switch also removes ERGO's own create/run rights (those require approve);
  the UI text says so. Fails closed.
- `PermissionMatrix.MODULES` (SRS reference tab) is frozen at first load.
- **Rolling deploys:** don't flip a switch until every instance runs V26 code (older instances ignore `access_cap`).
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
