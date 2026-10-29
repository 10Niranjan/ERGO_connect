# ERGO Connect — Claude Code standing context

> **START HERE (active work): read `docs/FLOW_ALIGNMENT.md` first.** The client's flow document now overrides the older SRS and the build-order list below: slices 1-6 (On Hold decision; multi-item RFQ, sub-RFQs, quotes per part, per-part customer quotation; Sample PO + KAM project creation + plan release to the supplier; plan submission, ERGO agreement and execution gate; dual quality gate and customer final approval; production PO + OFP project) are all done. That file says what was done, where to start and how.

> **CURRENT STATE (2026-10-29), read before starting:** Phases 0-7 are done; Phase 8 is done through 8o, but 8k-8o are only **authored and tested locally, never run in Azure** (nothing is deployed). Branch `sprint-3`, last pushed commit `26e21bb`. **The dashboard cap + totals and server-side RFQ list paging (from the 8o load test) are committed; full `./mvnw verify` green (755), security-reviewed.** Resume: re-run `loadtest/hot-paths.js` (restart the scratch DB `ergo_load`, see memory) and fill in the results in `docs/LOAD_TESTING.md`. After that: convert the other unbounded lists, then 8p (security/pen-test pass, needs a deployed staging) and 8q (final sign-off).

Production B2B platform for ERGO: RFQ → Quotation → NPD → OFP → Quality → Delivery → Closure, shared by three org types (ERGO, Customers, Suppliers) and 18 roles. It handles financial, quality and compliance data. **Correctness and access control come before speed.**

## Sources of truth (read before building anything)

| What | Where | Owns |
|---|---|---|
| Structure, security, conventions | `ARCHITECTURE.md` (§8 = decisions log) | how we build |
| Business rules, fields, permissions, workflows | `docs/SRS.md` (converted from the .docx) | what we build |
| Permissions, module switches, dynamic sidebar (as built) | `docs/PERMISSIONS.md` | read before touching roles, access levels or the sidebar |
| Signed-off original | `ERGO CONNECT – SOFTWARE REQUIREMENTS DOCUMENT (V1).docx` | tie-breaker |

`docs/SRS.md` section map: §4–6 confirmed business rules · §8 access philosophy · §9 role definitions · **§10.x permission matrix per module** · §11 exceptions · §12 screens · §13 DB tables · **§14 workflow states** · §15 audit · §16 MVP scope · §17 page-level logic · §18 API. The appendix at the end is an older draft: use it for context only; it never overrides §1–21.

## Build order (do not skip ahead)

Phase 0 foundation ✅ → 1a access model (login, roles, company scope, audit) ✅ → 1b master data + customer/supplier management ✅ → 2 RFQ + Quotation (2a RFQ core ✅, 2b supplier float + quotes ✅, 2c comparison + customer decision ✅) → 3 NPD (3a ERGO-only conversion + workflow ✅, 3b supplier/customer participation ✅) → 4 OFP (4a order core + production tracking ✅, 4b supplier participation + packaging/dispatch ✅, 4c shipments + customer read access + closure ✅, 4d frontend ✅, 4e NPD handover ✅) → 5 Quality (5a inspections ✅, 5b NCR/CAPA ✅, 5c deviations ✅, 5d OFP/NPD quality-gate integration ✅, 5e quality dashboard — held until Phase 6 is done, see ARCHITECTURE.md §8 2026-10-02) → 6 Documents + Communication (6a documents backend ✅, 6b documents frontend ✅, 6c communication backend ✅, 6d communication frontend ✅, 6e notifications backend ✅, 6f notifications frontend ✅) → 7 Dashboards + audit refinement (7a audit trail backend ✅, 7b audit trail frontend ✅, 7c dashboard backend ✅, 7d dashboard frontend ✅, 7e permission mapping screen ✅) → **8 Hardening & Deployment**, split into 17 one-sitting chunks (ARCHITECTURE.md §8, 2026-10-07): 8a Dependency-Check CVE gate in CI ✅ → 8b secrets/config audit ✅ → 8c transport hardening (HSTS, cookie flags) ✅ → 8d Redis login rate limiter ✅ → 8d-bis change-password + upload rate limiting ✅ → 8e first-admin bootstrap outside dev ✅ → 8f forgot-password email ✅ → 8g optional 2FA ✅ → 8h real Azure Blob Storage ✅ → 8i upload virus/malware scanning ✅ → 8j Dockerfile + container build ✅ → 8k Azure infra provisioning (Bicep authored in `infra/`, not yet deployed) ✅ → 8l GitHub Actions deploy pipeline (`.github/workflows/deploy.yml`, manual, not yet run) ✅ → 8m Application Insights/Monitor wiring ✅ → 8n backup & recovery plan (runbook + local restore drill, authored, no Azure restore run yet) ✅ → 8o load testing (k6 scripts + local run done; staging run and the dashboard/list fix open, see docs/LOAD_TESTING.md) ✅ → 8p security/pen-test pass → 8q final SRS §21 readiness sign-off. Before starting a module, check that its prerequisites exist. For example, RFQ needs companies, users, roles, currencies and payment terms.

## Non-negotiables (every change)

- **Two-layer access control, enforced server-side.** Role check (SRS §10) + record scope (own company / assigned records, SRS §8.2). Hiding things in the UI is never the protection.
- **Never expose to external roles:** competitor quotes, target prices, ERGO margins (`*_hidden` fields), internal notes, internal ranking/scores. Enforce with separate DTOs per audience, not by nulling fields.
- **Approval rights are separate from edit rights** (SRS §8.1, §11.3).
- **Status changes go only through the module's workflow service** (SRS §14). Reopen, reject and send-back require a reason.
- **Every write is audited** (SRS §15) and critical records are versioned (§15.4). `audit_logs` is append-only (DB trigger).
- **RFQ → NPD is manual.** Approval only puts the RFQ in the "ready for conversion" queue.
- **Currency is tagged on every money row** (INR/USD/EUR/RMB).
- **Module switches bind everything** (`docs/PERMISSIONS.md` §9): a module at V refuses every change and at NV shows nothing, from any path - other modules' facades, comments, documents, notifications, scheduled jobs. Use `ModuleSwitch` (`requireWritable` before the first write, `readable` for reads); a new `<module>.api` method must be classified in `ModuleSwitchFacadeTests`.
- **No unbounded lists.** A work list returns a page (`items`, `total`, `page`, `size`) filtered and searched in the query, a dashboard section is capped with a true total (`RfqPage` / `AttentionOrder` are the patterns). Still unbounded: NPD projects, sample and production POs, OFP orders, NCRs, deviations, inspections, supplier `/api/quotes` (`docs/LOAD_TESTING.md`).
- Modules talk only via `<module>.api`. `ModuleBoundaryTest` fails the build otherwise.
- Record responses list the caller's allowed actions (see `CompanyDtos.CompanyAction`), computed by the same checks that enforce them. The UI renders only those buttons.
- Flyway migrations are immutable once committed. Add a new `V<n>__<module>_<change>.sql` instead.
- No secrets in the repo. Prod config reads env vars only.

## Commands

The backend needs **JDK 21**. If `JAVA_HOME` points to 17, prefix commands with `JAVA_HOME="C:/Program Files/Java/jdk-21.0.10"`.

```bash
# backend (from ergo-connect-backend/) — tests need Docker running (Testcontainers)
./mvnw verify                                             # all tests
docker compose -f docker/docker-compose.local.yml up -d   # local Postgres + Redis + ClamAV
./mvnw spring-boot:run                                    # http://localhost:8080 (swagger: /swagger-ui.html)
# dev profile seeds demo users, all with password ErgoDev#2026: admin@ergo.dev, management@ergo.dev,
# rfq@ergo.dev (2 roles), kam@ergo.dev (KAM for Acme only), buyer@acme.dev, buyer@bharat.dev,
# sales@precision.dev, sales@shakti.dev, plus sample materials, processes, coatings and payment terms

# frontend (from ergo-connect-frontend/)
npm run dev      # http://localhost:5173, proxies /api → :8080
npm run lint && npm run build
```

Ops and test tooling (all authored locally; none has run against Azure): `infra/staging.bicepparam` + `infra/main.bicep` (deploy; secrets from env vars), `infra/ops/restore-drill.sh` (restore drill, encrypted `DUMP_OUT`), `infra/ops/prune-orphan-blobs.sh` (after a DB restore), `loadtest/seed.js` + `loadtest/hot-paths.js` (k6, run from the pinned container, see `docs/LOAD_TESTING.md`), `docs/BACKUP_AND_RECOVERY.md` (runbook). Write one-off scripts with the Write tool, not shell heredocs (quoting breaks).

## Agents (`.claude/agents/`)

| Agent | Use it to |
|---|---|
| `srs-analyst` | extract the exact spec for a module/feature (fields, §10 permissions, §14 states, §15 audit, screens) and list ambiguities *before* coding |
| `db-migration-author` | write/review Flyway migrations for a module |
| `backend-module-builder` | implement a backend module slice (entity → repo → service/workflow → api → tests) |
| `frontend-feature-builder` | implement `features/<module>` (RTK Query, pages, role-aware UI) |
| `security-reviewer` | review any diff for access control, data leakage, audit and versioning gaps. **Run before every commit that touches a module.** |

Typical module loop: `srs-analyst` → `db-migration-author` → `backend-module-builder` → `frontend-feature-builder` → `security-reviewer` → `./mvnw verify` + `npm run build` green → commit to `sprint-1`.

## Working agreements

- Ask the user when the SRS is ambiguous or contradicts itself. Do not guess on permissions or business rules. Log answers in ARCHITECTURE.md §8.
- One phase/module per session, and finish with passing tests plus a commit.
- `ergo-connect-mvp/` is the old UI demo. Leave it untouched and never build on it.
