# ERGO Connect

A digital collaboration platform that connects ERGO, its customers and its suppliers across the sourcing lifecycle: **RFQ → Quotation → NPD → OFP (order/production) → Quality → Delivery → Closure**. Access control is strict and has two layers: role-based and company/record-based.

| Path | What |
|---|---|
| `ARCHITECTURE.md` | Architecture, security plan, build phases, **decisions log (§8)** |
| `docs/SRS.md` | Software Requirements Document (Markdown conversion of the .docx) |
| `ergo-connect-backend/` | Java 21 · Spring Boot 4.1 · PostgreSQL 17 · Flyway: modular monolith |
| `ergo-connect-frontend/` | React 19 · TypeScript · MUI · Redux Toolkit / RTK Query · Vite |
| `.claude/agents/` | Claude Code agents: `srs-analyst`, `db-migration-author`, `backend-module-builder`, `frontend-feature-builder`, `security-reviewer` |
| `ergo-connect-mvp/` | Early UI demo (reference only, not part of the build) |

## Prerequisites
JDK 21 (`JAVA_HOME` must point to it) · Node 24 · Docker Desktop (running)

## Run locally
```bash
# 1. dependencies (Postgres + Redis)
cd ergo-connect-backend
docker compose -f docker/docker-compose.local.yml up -d

# 2. backend → http://localhost:8080  (API docs: /swagger-ui.html)
./mvnw spring-boot:run

# dev demo users (password ErgoDev#2026): admin@ergo.dev, management@ergo.dev, rfq@ergo.dev, kam@ergo.dev,
# buyer@acme.dev, sales@precision.dev …

# 3. frontend → http://localhost:5173  (proxies /api to the backend)
cd ../ergo-connect-frontend
npm install
npm run dev
```

## Test
```bash
cd ergo-connect-backend && ./mvnw verify          # needs Docker (Testcontainers)
cd ergo-connect-frontend && npm run lint && npm run build
```
CI (GitHub Actions) runs both on every push.

## Status
- [x] Phase 0: foundation (scaffold, security shell, append-only audit log, module-boundary test, CI)
- [x] Phase 1a: login, one active role per session, company-scoped access, user/company admin, audit trail
- [x] Phase 1b: master data (currencies, materials, processes, coatings, payment terms, incoterms) + customer/supplier management with approval, KAM account scope, self-service company profile
- [x] Phase 2a: RFQs: create (customer or ERGO), process flow, drawings with visibility, ERGO review, clarification, validation, audit trail
- [x] Phase 2b: supplier float (invite with due date), supplier quotes (tooling lines, cost breakdown, own process route), clarification, regret, re-open with reason, immutable versions with a change view, RFQ close
- [x] Phase 2c: exchange rates, quote comparison (converted prices, best-value highlighting, scores, shortlist/select), quotes to the customer (supplier quote as-is or ERGO quote with hidden margin), customer approve/revise/decline
- [ ] Phases 3–8: NPD, OFP, Quality, Documents/Communication, Dashboards, Hardening (see `ARCHITECTURE.md` §6)
