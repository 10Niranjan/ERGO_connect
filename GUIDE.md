# ERGO Connect — Local Setup & Development Guide

A complete step-by-step guide to cloning/pulling the repository, setting up all dependencies, and running both the backend and frontend locally on **Windows (PowerShell)** and **macOS / Linux (Bash)**.

---

## 📋 Table of Contents
1. [Prerequisites](#1-prerequisites)
2. [Step 1: Pull the Latest Changes (`sprint-2`)](#step-1-pull-the-latest-changes-sprint-2)
3. [Step 2: Start PostgreSQL & Redis Database Services](#step-2-start-postgresql--redis-database-services)
4. [Step 3: Run the Spring Boot Backend](#step-3-run-the-spring-boot-backend)
5. [Step 4: Setup and Run the React Frontend](#step-4-setup-and-run-the-react-frontend)
6. [Step 5: Login & Test Credentials](#step-5-login--test-credentials)
7. [Step 6: Build & Verification Commands](#step-6-build--verification-commands)
8. [Troubleshooting & FAQ](#troubleshooting--faq)

---

## 1. Prerequisites

Before starting, ensure you have the following installed on your machine:

| Tool | Recommended Version | Verify Command |
|---|---|---|
| **Git** | `v2.40+` | `git --version` |
| **Java JDK** | `Java 21` (LTS) | `java -version` |
| **Node.js** | `Node 20+` or `24` | `node -v` |
| **npm** | `v10+` | `npm -v` |
| **Docker Desktop** | Latest (running) | `docker --version` |

> [!NOTE]
> Ensure **Docker Desktop** is open and running before proceeding to Step 2.

---

## Step 1: Pull the Latest Changes (`sprint-2`)

### If you already have the repository cloned:
Open your terminal in the repository root directory (`ERGO_connect`):

```bash
# Fetch all remote branches
git fetch origin

# Switch to sprint-2 branch
git checkout sprint-2

# Pull the latest changes
git pull origin sprint-2
```

### If you are cloning freshly:
```bash
git clone -b sprint-2 https://github.com/varadyadav02/ERGO_connect.git
cd ERGO_connect
```

---

## Step 2: Start PostgreSQL & Redis Database Services

The backend requires PostgreSQL 17 and Redis. You can launch both with Docker Compose:

### Windows (PowerShell):
```powershell
cd ergo-connect-backend
docker compose -f docker/docker-compose.local.yml up -d
cd ..
```

### macOS / Linux (Bash):
```bash
cd ergo-connect-backend
docker compose -f docker/docker-compose.local.yml up -d
cd ..
```

> **Check Status**: Run `docker ps` to ensure both `postgres` (port `5432`) and `redis` (port `6379`) are running with status `Up`.

---

## Step 3: Run the Spring Boot Backend

The backend is built with Spring Boot 4.1 on Java 21.

### 1. Set JAVA_HOME (if not already set)
Ensure your `JAVA_HOME` environment variable points to a Java 21 JDK installation.

**Windows PowerShell:**
```powershell
# Example (adjust path to your JDK 21 directory):
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
```

**macOS / Linux:**
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21) # macOS
# or export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 # Linux
```

### 2. Start the Backend Server
Navigate to `ergo-connect-backend` and start the server using the Maven wrapper:

**Windows PowerShell:**
```powershell
cd ergo-connect-backend
.\mvnw.cmd spring-boot:run
```

**macOS / Linux:**
```bash
cd ergo-connect-backend
./mvnw spring-boot:run
```

### 3. Verify Backend is Running
- **Server URL**: `http://localhost:8080`
- **Swagger / OpenAPI Documentation**: [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)
- **Health Check**: [http://localhost:8080/actuator/health](http://localhost:8080/actuator/health)

*(Keep this terminal window open while running the app)*

---

## Step 4: Setup and Run the React Frontend

Open a **new terminal window** in the repository root for the frontend.

### 1. Navigate to the Frontend Directory
```bash
cd ergo-connect-frontend
```

### 2. Configure Environment Variables
Copy the example environment template into `.env`:

**Windows PowerShell:**
```powershell
Copy-Item .env.example .env
```

**macOS / Linux (Bash):**
```bash
cp .env.example .env
```

The default `.env` contents are pre-configured to proxy API calls:
```properties
VITE_API_BASE_URL=/api
```

### 3. Install Dependencies
```bash
npm install
```

### 4. Start the Vite Development Server
```bash
npm run dev
```

### 5. Access the Frontend Application
Once started, Vite will output:
```
  VITE ready in ~500 ms

  ➜  Local:   http://localhost:5173/
  ➜  Network: use --host to expose
```
Open [http://localhost:5173](http://localhost:5173) in your browser. It will automatically redirect you to `/login`.

---

## Step 5: Login & Test Credentials

### Demo User Accounts
All seeded accounts use password: **`ErgoDev#2026`**

| Organization | Role | Email | Access Scope |
|---|---|---|---|
| **ERGO** | System Admin | `admin@ergo.dev` | All modules, users, permissions, master data |
| **ERGO** | Management | `management@ergo.dev` | Executive KPI dashboard, cross-module views |
| **ERGO** | RFQ / Sales | `rfq@ergo.dev` | RFQ validation, quote floating, negotiation |
| **ERGO** | Key Account Manager | `kam@ergo.dev` | Customer quotes, RFQs, assigned accounts |
| **ERGO** | Quality Engineer | `quality@ergo.dev` | Inspections, NCRs, deviations, CAPA |
| **ERGO** | Production / OFP | `ofp@ergo.dev` | Order fulfillment, milestones, dispatch |
| **Customer** | Buyer (Acme Corp) | `buyer@acme.dev` | Customer portal (Acme RFQs, orders, NPD) |
| **Supplier** | Sales (Precision Ltd) | `sales@precision.dev` | Supplier portal (Precision RFQs, quotes) |

### 🔐 2-Factor Authentication (OTP Verification)
When logging in locally in the development profile:
1. Enter the email and password above.
2. The system prompts for a **6-digit verification code (OTP)**.
3. Check the **backend terminal log output**. You will see a warning message like:
   ```text
   WARN --- c.e.c.m.access.PasswordResetMailer : No spring.mail.host configured - login code for admin@ergo.dev: 416957
   ```
4. Copy the 6-digit code (e.g. `416957`) and enter it in the browser to complete login.
5. If the user has multiple roles (e.g. `admin@ergo.dev`), you will see the **Role Selection** screen to choose your active session role.

### 🧭 Navigation
All navigation options are available in the **left sidebar**, which dynamically displays modules permitted for your active role:
- **RFQs & Quotations**
- **NPD (New Product Development)**
- **OFP (Order Fulfillment & Production)**
- **Quality (Inspections, NCR, Deviations)**
- **Document Library**
- **Communication Center**
- **Company Management & Master Data**

---

## Step 6: Build & Verification Commands

To verify code quality and build integrity:

### Frontend:
```bash
cd ergo-connect-frontend

# Run fast linter (Oxlint)
npm run lint

# Run TypeScript compilation and production build
npm run build

# Preview production build locally
npm run preview
```

### Backend:
```bash
cd ergo-connect-backend

# Run all unit and integration tests (requires Docker running)
./mvnw verify        # On Linux/macOS
.\mvnw.cmd verify    # On Windows
```

---

## Troubleshooting & FAQ

### Q1: `Error: connect ECONNREFUSED 127.0.0.1:8080` on frontend API calls
- **Solution**: The backend server is not running or still starting. Wait until the backend logs display `Started ErgoConnectApplication in X.XXX seconds`, then refresh the browser.

### Q2: `JAVA_HOME is not set and no 'java' command could be found in your PATH`
- **Solution**: Install JDK 21 and set `JAVA_HOME`.
  - Windows: Set system environment variable `JAVA_HOME` pointing to `C:\Program Files\Java\jdk-21`.
  - Check in PowerShell: `$env:JAVA_HOME`

### Q3: Port 8080 or 5173 is already in use
- **Windows**:
  ```powershell
  # Find process using port 8080
  netstat -ano | findstr :8080
  # Kill process by PID
  taskkill /PID <PID> /F
  ```
- **macOS/Linux**:
  ```bash
  lsof -i :8080
  kill -9 <PID>
  ```

### Q4: Node.js dependency issues
- **Solution**: Remove `node_modules` and reinstall:
  ```bash
  cd ergo-connect-frontend
  rm -rf node_modules package-lock.json # or Remove-Item -Recurse -Force node_modules in PowerShell
  npm install
  ```

### Q5: Docker containers fail to start
- **Solution**: Ensure Docker Desktop is running. Restart containers with:
  ```bash
  cd ergo-connect-backend
  docker compose -f docker/docker-compose.local.yml down -v
  docker compose -f docker/docker-compose.local.yml up -d
  ```
