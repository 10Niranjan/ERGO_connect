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
- [ ] Phase 1: access model and master data
- [ ] Phase 2: RFQ + Quotation
- [ ] Phases 3–8: NPD, OFP, Quality, Documents/Communication, Dashboards, Hardening (see `ARCHITECTURE.md` §6)
