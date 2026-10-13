# infra/ — Azure infrastructure (Phase 8k)

`main.bicep` provisions one environment (staging-sized, cheapest tiers) into an existing resource group:
Log Analytics, Key Vault (RBAC), Storage (private `documents` container, 7-day blob soft-delete), Postgres Flexible
Server (Burstable B1ms), Azure Managed Redis (`Balanced_B0` — classic Azure Cache for Redis creation was blocked from
2026-10-01), Container Registry (Basic), two user-assigned identities, and a Container Apps environment running
`backend` (with a ClamAV sidecar) and `frontend`.

**Status: authored; `az bicep build` compiles (two harmless BCP334 warnings); never deployed, never `what-if`'d.**
Treat the first deploy as a verification step. **Staging only: do not put real client data in it until the
"Required before real data" list below is done.**

## Deploy (two phases)

The apps need images, and the registry is created by this same template, so deploy infra first. The deploying identity
needs **Owner** (or Contributor + User Access Administrator) on the resource group — the template creates role assignments.

```bash
az group create -n rg-ergo-staging -l <region>   # a region where Azure Managed Redis is available

# Secrets come from your shell / a secret store — never from a file in this repo.
az deployment group what-if -g rg-ergo-staging -f infra/main.bicep \
  -p namePrefix=ergo-staging mailHost=<smtp-host> mailUsername=<user> mailFrom=<from@domain> \
  -p postgresAdminPassword="$PG_PASSWORD" jwtSecret="$JWT_SECRET" mailPassword="$MAIL_PASSWORD"
# then the same command with `create` instead of `what-if`
```

Phase 1 (no image params) creates everything except the two Container Apps. After 8l — or by hand with `az acr login`
+ `docker push` of the two 8j images to the `registryLoginServer` output — re-run with
`-p backendImage=<acr>/ergo-backend:<tag> frontendImage=<acr>/ergo-frontend:<tag>` to create the apps.

- `postgresAdminPassword` ≥ 24 chars (`openssl rand -base64 32`); `jwtSecret` ≥ 32 random bytes (`openssl rand -base64 48`).
- First deploy against an empty database may also pass `bootstrapAdminEmail` / `bootstrapAdminPassword` (8e). **Drop both
  from every later deploy, and after the first admin login run `az keyvault secret delete` + `az keyvault secret purge`
  for `bootstrap-admin-password`** — an incremental deploy never removes it.

## How the pieces connect

- `frontend` is the only public ingress. Its nginx proxies `/api` to `http://backend` (`BACKEND_UPSTREAM`; see
  `ergo-connect-frontend/nginx.conf.template`). Container Apps fronts every app on 80/443 whatever port the container
  listens on, so the upstream carries no `:8080`, and the `Host` sent upstream is the upstream's own name
  (`BACKEND_HOST_HEADER=$proxy_host`) because Envoy routes by Host. Spring then sees a cross-origin call, which the
  `CORS_ALLOWED_ORIGINS` allow-list permits — a future custom domain must be added there.
- `backend` ingress is internal-only. `allowInsecure` only skips the 80→443 redirect for that in-environment hop.
- Secrets live in Key Vault and reach the container as env vars through Container Apps secret references resolved with
  the backend identity. Both apps set `identitySettings … lifecycle: None`, so the platform can use their identities
  (image pull, Key Vault refs) but no container — including the ClamAV sidecar that parses hostile uploads, and the
  internet-facing nginx — can mint tokens from them. The frontend has its own AcrPull-only identity.
- ClamAV is a sidecar of the backend replica, so the app's default `localhost:3310` reaches it. It does **not** get
  `CLAMAV_NO_FRESHCLAMD` (8i: prod must keep signatures fresh) and runs with `ConcurrentDatabaseReload=no` to halve
  peak memory.
- Backend is pinned to **one replica**: `OverdueReminders` and the deviation-expiry job have no distributed lock.

## Unverified — check on first deploy

- Nothing here has run against Azure. `what-if` first; expect to fix at least a property or two.
- **Envoy hop (nginx → backend):** that `Host: backend` + HTTP/1.1 reaches the backend (not a redirect loop, 404 or 426).
  Only the plain-Docker variant was tested.
- **`identitySettings: None`:** that Key Vault secret refs and image pull still work with it (expected: yes; if not,
  deploy fails loudly at secret resolution). Then confirm from inside a container that `$IDENTITY_ENDPOINT` yields no token.
- **Rate limiters fail open silently.** If `REDIS_URL` (percent-encoded key, port 10000, access-key auth, EVAL on an
  EnterpriseCluster database) is wrong, the app still boots healthy with the login, change-password and upload limiters
  off. Check: 21 bad logins from one IP return 429, and the logs contain no `Redis unavailable for rate limiting`.
  Also: `redisDb.listKeys()` is untested, and an open Bicep issue reports repeat-deploy failures when a `redisEnterprise`
  cluster and its database share one file (reported on a preview API; this uses `2025-04-01`).
- **ClamAV:** 2Gi is a starting point, not a measurement. Uploads return 503 until clamd has loaded its signatures
  (the liveness probe waits 60s). Confirm freshclam can reach its mirrors from Container Apps egress (silent staleness
  otherwise, until 8m), run an EICAR upload end to end, and confirm clamd listens only where intended — whether other
  replicas in the environment can reach it is not something the template controls (`CLAMD_CONF_TCPAddr=127.0.0.1` was
  not tried; the image may add rather than replace the listener).
- **Forwarded headers:** `X-Forwarded-Proto: https` from the public ingress through two hops (check `Strict-Transport-Security`
  on an `/api` response), and that `audit_logs` and the limiters see the real client IP, not an Envoy address.
- Key Vault role-assignment propagation can lag behind app creation; if the first app deploy fails on secret
  resolution, re-run it. The frontend's nginx resolves `backend` once at startup (ordered after it via `dependsOn`).
- The blob HTTPS path to a real storage account (8h carry-forward), Spring parsing the percent-encoded `REDIS_URL`, and
  JVM heap (`-XX:MaxRAMPercentage=60` against 2Gi) are all untested.

## Required before real data

| What | Why |
|---|---|
| App runs as a DML-only Postgres role; Flyway runs as a separate owner role | The app now connects as the server admin, which owns `audit_logs` and could disable its append-only trigger — the SRS §15 guarantee holds only while app code never issues DDL. App + migration change. |
| Private endpoints / VNet integration for Postgres, Redis, Key Vault, Storage | Postgres rule `0.0.0.0–0.0.0.0` admits any Azure workload in any tenant; Storage and Redis are internet-reachable with account keys. Password + TLS are the only controls today. (8p) |
| `sslmode=verify-full` for the JDBC URL | `require` encrypts but does not authenticate the server; needs the CA trust path checked first. |
| Key Vault purge protection (irreversible) | Left off so a staging env can be torn down. |
| Container-level encryption between apps (`peerTrafficConfiguration`) | The nginx→backend hop is plaintext inside the environment, carrying cookies, JWTs and quote data. Not enabled: untested alongside `allowInsecure`. |
| `AzureBlobDocumentStorage` on Managed Identity; grant Blob Data Contributor scoped to the `documents` container; drop `createIfNotExists()` | No Blob role is granted today (the app uses the account key). |

## Other deferred items

| What | Where |
|---|---|
| More than one backend replica (single-run scheduled jobs first, e.g. ShedLock) | before scaling out |
| Application Insights, diagnostic settings, alerts (incl. a Redis fail-open counter) | 8m |
| Backup retention / geo-redundancy decisions, restore runbook | 8n |
| Pin the ClamAV image by digest; image CVE scan | 8l |
| Narrow `server.tomcat.remoteip.internal-proxies`; confirm `X-Forwarded-For` format. Any app later added to the environment can spoof audit-log IPs until then | after first deploy |
| Upload byte-quota sizing (8d-bis) | with real traffic |
