# ERGO Connect — Client Demo & Role-Based Login Documentation

This document summarizes all modifications, architectural designs, and usage instructions implemented for the **Client Presentation & Role Testing Mode** on branch `demo`.

---

## 1. Overview & Objective

During client presentations and stakeholder walkthroughs, manual authentication (typing email, passwords, and completing 2FA email OTPs) creates friction when showcasing different role perspectives.

To facilitate seamless demonstration:
- **Login screen bypassed with a 1-click role picker**: Select any employee persona to sign in immediately without passwords or OTP prompts.
- **In-app quick role switcher**: Switch roles on the fly directly from the top navigation bar without logging out.
- **Full backend API authorization**: Tokens are cryptographically signed using the dev HMAC-SHA256 secret, ensuring all Spring Boot endpoints accept requests with `HTTP 200 OK`.
- **Demo session resilience**: Protected API queries from terminating sessions if unseeded data returns a 401.

---

## 2. Catalog of Personas & Roles (18 Roles)

Per the **Software Requirements Document (SRS §2)** and `Role.java`:

### A. ERGO Internal Team (7 Roles — Company: ERGO)
| Employee | Email | Role Code | Functional Responsibility | Landing Route |
| :--- | :--- | :--- | :--- | :--- |
| **Asha Admin** | `admin@ergo.dev` | `ERGO_ADMIN` | System administrator: User admin, master data, audit logs | `/` |
| **Mohan Management** | `management@ergo.dev` | `ERGO_MANAGEMENT` | Executive KPI dashboard, cross-module visibility | `/` |
| **Kavya Menon** | `kam@ergo.dev` | `ERGO_KAM` | Key Account Manager for customer accounts (Acme, Bharat) | `/` |
| **Ravi Rao** | `rfq@ergo.dev` | `ERGO_RFQ` | RFQ review, BOM flow, quote floating, price comparison | `/rfqs` |
| **Kavya Reddy** | `npd@ergo.dev` | `ERGO_NPD` | 9-stage NPD development, activity gating, sample readiness | `/npd` |
| **Vikram Singh** | `ofp@ergo.dev` | `ERGO_OFP` | Purchase orders, production milestones, dispatch readiness | `/ofp` |
| **Kiran Kumar** | `quality@ergo.dev` | `ERGO_QUALITY` | Incoming/final inspection, NCRs, deviations/concessions, CAPA | `/quality` |

### B. Customer Accounts (6 Roles — Company: Acme Automotive)
| Employee | Email | Role Code | Functional Responsibility | Landing Route |
| :--- | :--- | :--- | :--- | :--- |
| **Alice Iyer** | `buyer@acme.dev` | `CUSTOMER_RFQ` | Sourcing Buyer: Part RFQ submission, quotation review & approval | `/rfqs` |
| **Rohit Desai** | `npd@acme.dev` | `CUSTOMER_NPD` | Development milestones, tooling dates, sample approval gates | `/npd/mine` |
| **Neha Kulkarni** | `logistics@acme.dev` | `CUSTOMER_OFP` | Delivery progress, shipment tracking, dispatch notes | `/ofp` |
| **Amit Verma** | `quality@acme.dev` | `CUSTOMER_QUALITY` | Inspection certificates review, quality complaints, deviations | `/quality` |
| **Sunita Deshpande** | `account@acme.dev` | `CUSTOMER_KAM` | Account-wide oversight across Acme Automotive contracts | `/` |
| **Raman Athreya** | `management@acme.dev` | `CUSTOMER_MANAGEMENT` | Plant Head: Volume, spend, project statuses, supplier ratings | `/` |

### C. Supplier Partners (5 Roles — Company: Precision Castings)
| Employee | Email | Role Code | Functional Responsibility | Landing Route |
| :--- | :--- | :--- | :--- | :--- |
| **Prakash Shah** | `sales@precision.dev` | `SUPPLIER_RFQ` | Review invited RFQs, cost breakdown & tooling pricing | `/quotes` |
| **Harish Gupta** | `tooling@precision.dev` | `SUPPLIER_NPD` | Tooling progress, sample manufacturing, DFM attachments | `/npd/mine` |
| **Prakash Jadhav** | `planner@precision.dev` | `SUPPLIER_OFP` | Production batch tracking, committed dispatch dates, packing | `/ofp` |
| **Lakshmi Menon** | `qa@precision.dev` | `SUPPLIER_QUALITY` | Self-inspection submission, 8D/CAPA responses, concessions | `/quality` |
| **Farhan Sheikh** | `lead@precision.dev` | `SUPPLIER_KAM` | Commercial lead: Quotation reviews, order delivery capacity | `/` |

---

## 3. Key Technical Changes

### 1. Frontend Demo Login Screen & Card Grid
- **`src/features/auth/pages/LoginPage.tsx`**: Replaced standard login form with the presentation portal. A collapsible traditional manual login form is retained in an accordion at the bottom if manual credentials are ever required.
- **`src/features/auth/components/RolePickerGrid.tsx`**: 3-tab layout (**ERGO Internal**, **Customer Accounts**, **Supplier Partners**). Each persona card shows the employee avatar, job title, company name, role chip, and scope description.
- **`src/features/auth/components/DemoRoleModal.tsx`**: Modal dialog embedding the role picker grid, accessible from any page.
- **`src/layouts/Navbar.tsx`**: Added a **"Switch Demo Role"** button to the top navigation bar.

### 2. Cryptographic JWT Token Generation
- **`src/features/auth/utils/demoAccounts.ts`**:
  - Implemented `createSignedDemoJwt(account)` using browser WebCrypto (`window.crypto.subtle`).
  - Signs the JWT with HMAC-SHA256 using the dev backend secret (`dev-only-jwt-secret-never-use-outside-local-dev`).
  - Sets claims (`iss: "ergo-connect"`, `sub`, `cid`, `ctype`, `role`, `sid: 1`, `pwc: false`, 7-day TTL).
  - Spring Boot's `NimbusJwtDecoder` validates the signature and assigns `SESSION_ACTIVE` authority, returning `HTTP 200 OK` for all secured API calls.

### 3. Session Persistence & 401 Protection
- **`src/services/baseApi.ts`**:
  - Added a demo session guard. If an API request returns `401 Unauthorized` during demo mode, RTK Query **will not** trigger `/api/auth/refresh` or call `sessionEnded()`, preventing premature logouts or redirects.
- **`src/features/auth/components/RequireAuth.tsx`**:
  - Rehydrates the active demo session from `sessionStorage` on page refresh.

### 4. Database Alignment
- `DevDataSeeder` (dev profile) creates every persona by email, idempotently, in a fixed order so a fresh database gives them ids 1..20. `demoAccounts.ts` signs tokens with those ids, so **do not reorder the `user(...)` calls** (append new personas at the end and give them the next id).
- Each persona's `allRoles` in `demoAccounts.ts` equals its role set in the database, so the role dropdown in the top bar matches what the server knows.
- Demo role switching never calls the backend: `startDemoSession` re-signs the token for the chosen role, clears the API cache and stores the session, exactly like a real login/role switch does.

### 5. Demo dataset (every module, every field)
`node ergo-connect-backend/docker/seed-demo-data.mjs` (backend on :8080) fills a fresh dev database through the real REST API, as the real demo personas, so every workflow rule, audit row and notification fires exactly as in use. It takes about 45 seconds and is safe to re-run (each phase skips what already exists). `--only=3,4` runs just those phases (phase 1 always runs, it feeds the others).

| Phase | File | What it creates |
| :--- | :--- | :--- |
| 1 | `seed/01-master-and-companies.mjs` | 13 materials, 15 processes, 9 coatings, 6 payment terms, 7 shipment modes, 6 months of exchange rates; full profiles for 8 companies (one left pending approval); 10 extra users so Bharat, Shakti and Sahyadri have their own NPD/OFP/quality people; phone numbers for everyone |
| 2 | `seed/02-rfq-and-quotes.mjs` | 16 RFQs covering every status (draft, submitted, under review, clarification asked and answered, validated, sent to suppliers, quotes partial/complete, sent to customer, revision requested, approved, declined, cancelled, closed); multi-supplier quotes with scoring, shortlisting, a re-opened quote and a supplier clarification; drawings, specs, cost sheets |
| 3 | `seed/03-npd.mjs` | 4 NPD projects at different stages (one delayed with a blocked activity) with ERGO/supplier/customer activities, dependencies, checklists, issues/risks and approvals, advanced through the real stage-gate rules |
| 4 | `seed/04-orders.mjs` | 9 orders from "created" to "closed" (hold, delayed, in transit, delivered with proof of delivery, NPD handover), material status, production progress, packaging, shipments |
| 5 | `seed/05-quality.mjs` | 9 inspections in every result state, 5 NCRs from open to closed with CAPA actions and evidence, 5 deviations through the ERGO and customer approval chain |
| 6 | `seed/06-comments-documents-notifications.mjs` | 27 comment threads (replies, mentions, queries, escalations, attachments), document-library approvals/rejection/lock/new version, real sign-ins, notifications spread over the last four weeks with most marked read |

Clean slate: recreate the database container, start the backend (it seeds the 20 personas as user ids 1..20), then run the script.
Overdue-reminder notifications come from a scheduled job; in the dev profile it runs every 5 minutes, so they appear a few minutes after seeding.

### 6. Local setup notes
- If another PostgreSQL already listens on 5432 (common on Windows), the backend's password check fails against it. Run the demo database on another port and point the backend there: `docker run -d --name ergo-pg -e POSTGRES_DB=ergo_connect -e POSTGRES_USER=ergo -e POSTGRES_PASSWORD=ergo -p 5433:5432 postgres:17-alpine`, then start the backend with `SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5433/ergo_connect`.
