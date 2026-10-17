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

### A. ERGO Internal Team (7 Roles — Company: ERGO Connect Internal)
| Employee | Email | Role Code | Functional Responsibility | Landing Route |
| :--- | :--- | :--- | :--- | :--- |
| **Asha Admin** | `admin@ergo.dev` | `ERGO_ADMIN` | System administrator: User admin, master data, audit logs | `/` |
| **Mohan Management** | `management@ergo.dev` | `ERGO_MANAGEMENT` | Executive KPI dashboard, cross-module visibility | `/` |
| **Kavya Menon** | `kam@ergo.dev` | `ERGO_KAM` | Key Account Manager for customer accounts (Acme, Bharat) | `/` |
| **Ravi Rao** | `rfq@ergo.dev` | `ERGO_RFQ` | RFQ review, BOM flow, quote floating, price comparison | `/rfqs` |
| **Kavya Reddy** | `npd@ergo.dev` | `ERGO_NPD` | 9-stage NPD development, activity gating, sample readiness | `/npd` |
| **Vikram Singh** | `ofp@ergo.dev` | `ERGO_OFP` | Purchase orders, production milestones, dispatch readiness | `/orders` |
| **Kiran Kumar** | `quality@ergo.dev` | `ERGO_QUALITY` | Incoming/final inspection, NCRs, deviations/concessions, CAPA | `/quality/inspections` |

### B. Customer Accounts (6 Roles — Company: Acme Automotive Ltd)
| Employee | Email | Role Code | Functional Responsibility | Landing Route |
| :--- | :--- | :--- | :--- | :--- |
| **Alice Iyer** | `buyer@acme.dev` | `CUSTOMER_RFQ` | Sourcing Buyer: Part RFQ submission, quotation review & approval | `/rfqs` |
| **Rohit Desai** | `npd@acme.dev` | `CUSTOMER_NPD` | Development milestones, tooling dates, sample approval gates | `/npd` |
| **Neha Kulkarni** | `logistics@acme.dev` | `CUSTOMER_OFP` | Delivery progress, shipment tracking, dispatch notes | `/orders` |
| **Amit Verma** | `quality@acme.dev` | `CUSTOMER_QUALITY` | Inspection certificates review, quality complaints, deviations | `/quality/inspections` |
| **Sunita Deshpande** | `account@acme.dev` | `CUSTOMER_KAM` | Account-wide oversight across Acme Automotive contracts | `/` |
| **Raman Athreya** | `management@acme.dev` | `CUSTOMER_MANAGEMENT` | Plant Head: Volume, spend, project statuses, supplier ratings | `/` |

### C. Supplier Partners (5 Roles — Company: Precision Castings Ltd)
| Employee | Email | Role Code | Functional Responsibility | Landing Route |
| :--- | :--- | :--- | :--- | :--- |
| **Prakash Shah** | `sales@precision.dev` | `SUPPLIER_RFQ` | Review invited RFQs, cost breakdown & tooling pricing | `/rfqs` |
| **Harish Gupta** | `tooling@precision.dev` | `SUPPLIER_NPD` | Tooling progress, sample manufacturing, DFM attachments | `/npd` |
| **Prakash Jadhav** | `planner@precision.dev` | `SUPPLIER_OFP` | Production batch tracking, committed dispatch dates, packing | `/orders` |
| **Lakshmi Menon** | `qa@precision.dev` | `SUPPLIER_QUALITY` | Self-inspection submission, 8D/CAPA responses, concessions | `/quality/inspections` |
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
- Seeded the PostgreSQL database (`users` and `user_role_mappings` tables) with all 19 demo personas across ERGO, Acme Automotive, and Precision Castings.
- Aligned user IDs in `demoAccounts.ts` with database primary keys.

---

## 4. How to Conduct the Client Demo

1. **Launch the Application**:
   - Backend: Run `./mvnw spring-boot:run` in `ergo-connect-backend`.
   - Frontend: Run `npm run dev` in `ergo-connect-frontend`.
   - Open **`http://localhost:5173/login`** in your browser.

2. **Login as any Persona**:
   - Select a tab (**ERGO Internal**, **Customer Accounts**, or **Supplier Partners**).
   - Click on any card (e.g., **Mohan Management**, **Ravi Rao**, or **Alice Iyer**).
   - You are immediately signed in and redirected to that role's primary landing dashboard.

3. **Switch Roles During the Walkthrough**:
   - Click **"Switch Demo Role"** in the top navigation bar.
   - Choose a different persona to immediately switch context and show the customer or supplier view of the same data without having to sign out.
