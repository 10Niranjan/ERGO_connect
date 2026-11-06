# infra/ — Azure infrastructure (Phase 8k)

`main.bicep` provisions one environment (staging-sized, cheapest tiers) into an existing resource group:
Log Analytics, Key Vault (RBAC), Storage (private `documents` container, 7-day blob soft-delete), Postgres Flexible
Server (Burstable B1ms), Azure Managed Redis (`Balanced_B0` — classic Azure Cache for Redis creation was blocked from
2026-10-01), Container Registry (Basic), two user-assigned identities, and a Container Apps environment running
`backend` (with a ClamAV sidecar) and `frontend`. Monitoring (8m): Application Insights on the Log Analytics workspace, an
email action group, four alert rules and Key Vault audit logging.

**Status: authored; `az bicep build` compiles (two harmless BCP334 warnings); never deployed, never `what-if`'d.**
Treat the first deploy as a verification step. **Staging only: do not put real client data in it until the
"Required before real data" list below is done.**

## Deploy (two phases)

The apps need images, and the registry is created by this same template, so deploy infra first. The deploying identity
needs **Owner** (or Contributor + User Access Administrator) on the resource group — the template creates role assignments.

```bash
az group create -n rg-ergo-staging -l <region>   # a region where Azure Managed Redis is available

# Everything that is not a secret is in infra/staging.bicepparam (in git, so it cannot be forgotten on a command line).
# Secrets and per-run values come from the environment; the build fails if a required one is unset.
read -rsp 'Postgres admin password: ' PG_PASSWORD; export PG_PASSWORD; echo   # same for JWT_SECRET and MAIL_PASSWORD (no secret on a command line)
export MAIL_HOST=… MAIL_USERNAME=… MAIL_FROM=… ALERT_EMAIL=…
unset BACKEND_IMAGE FRONTEND_IMAGE BOOTSTRAP_ADMIN_EMAIL BOOTSTRAP_ADMIN_PASSWORD   # infra-only unless you set them on purpose
az deployment group what-if -g rg-ergo-staging --parameters infra/staging.bicepparam
# then the same command with `create` instead of `what-if`
```

Phase 1 (no image variables) creates everything except the two Container Apps. After 8l — or by hand with `az acr login`
+ `docker push` of the two 8j images to the `registryLoginServer` output — re-run with
`BACKEND_IMAGE=<acr>/ergo-backend@sha256:… FRONTEND_IMAGE=<acr>/ergo-frontend@sha256:…` exported to create the apps.

- `postgresAdminPassword` ≥ 24 chars (`openssl rand -base64 32`); `jwtSecret` ≥ 32 random bytes (`openssl rand -base64 48`).
- First deploy against an empty database may also export `BOOTSTRAP_ADMIN_EMAIL` / `BOOTSTRAP_ADMIN_PASSWORD` (8e). **Drop both
  from every later deploy, and after the first admin login run `az keyvault secret delete` + `az keyvault secret purge`
  for `bootstrap-admin-password`** — an incremental deploy never removes it.

## CI deploy setup (Phase 8l) — once, by the repo admin who also has rights in Azure

`.github/workflows/deploy.yml` (manual `workflow_dispatch` only) runs CI, then builds and Trivy-scans the backend,
frontend and ClamAV images in a job with **no cloud access**; a second job checks the two tarballs against the
build job's checksums, pushes them to the registry **by digest**, rolls the two Container Apps with
`az containerapp update`, waits until each latest revision is ready *and* running the new image, and runs
`.github/scripts/smoke.sh`. It authenticates with OIDC — no stored Azure secret — and never touches infrastructure,
which stays a manual `az deployment` because it needs secrets the pipeline should not hold.

**Order matters.** The Azure trust is only as strong as the GitHub environment it is scoped to, and the Azure IDs are
identifiers, not secrets: any workflow on any branch that declares `environment: staging` with `id-token: write` gets
a token matching the credential. Create the protection first, the trust second.

1. **GitHub → Settings → Environments → New `staging`** (admin only; an environment created implicitly by a first run
   has no protection at all): required reviewers, **Prevent self-review on**, **Allow administrators to bypass off**,
   and deployment branches restricted to exactly one branch. Protect that branch (pull request + review) — otherwise
   whoever can push to it can edit `deploy.yml`. Merge `deploy.yml` to the repository's default branch: a
   `workflow_dispatch` workflow does not appear until it exists there.
2. **Then** create the Azure side:

```bash
APP_ID=$(az ad app create --display-name ergo-connect-github-staging --query appId -o tsv)
az ad sp create --id "$APP_ID"
az ad app federated-credential create --id "$APP_ID" --parameters '{
  "name": "github-staging", "issuer": "https://token.actions.githubusercontent.com",
  "subject": "repo:varadyadav02/ERGO_connect:environment:staging", "audiences": ["api://AzureADTokenExchange"] }'
az role assignment create --assignee "$APP_ID" --role AcrPush --scope "$(az acr show -n <acr> --query id -o tsv)"
az role assignment create --assignee "$APP_ID" --role Contributor --scope "$(az group show -n rg-ergo-staging --query id -o tsv)"
```

3. Add environment *secrets* `AZURE_CLIENT_ID` (= `$APP_ID`), `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID` and
   environment *variables* `AZURE_RESOURCE_GROUP` (`rg-ergo-staging`) and `ACR_NAME` (without `.azurecr.io`).
   (They gate nothing by being secret; they are stored there for tidiness.)
4. No CVE-gate secret is needed: the Java gate is Trivy since 2026-11-06 (no NVD key) and always runs, on a deploy too (8a).

Optional hardening that moves the protection into code: customise the repository's OIDC subject claim to include
`job_workflow_ref` and use that subject in the federated credential, so only `deploy.yml` on the allowed branch can
obtain a token.

**First run:** the apps don't exist until infra phase 2, so the first run pushes the images, then stops with an error
that prints the exact `BACKEND_IMAGE=… FRONTEND_IMAGE=…` digests to export before `az deployment group create --parameters infra/staging.bicepparam`. Run that, then
re-run the workflow. From then on the pipeline owns the image versions; an infra-only change should be deployed
*without* image parameters (the apps are then left untouched), otherwise it would revert them.

**Rollback** is not "re-run on an old commit": `workflow_dispatch` takes a branch, the images are rebuilt from floating
base tags, and the rebuild must pass Trivy again, so a newly published base-image CVE can block a rollback exactly
when you need it. The real rollback is `az containerapp update --name <app> --container-name <app> --image <previous
digest>` using the digests in that earlier run's summary (the images stay in the registry). **Schema changes are
forward-only:** Flyway runs from the new revision and is never undone, so migrations must stay backward-compatible with
the previous release. During any switch the old and new backend revisions overlap, so the unlocked `@Scheduled` jobs can
fire twice.

**Unverified (never run on GitHub or against Azure):** the OIDC login; `az acr login`; that `az containerapp update
--container-name` on a multi-container app preserves the Key Vault secret references and `identitySettings` set by Bicep;
that the `az containerapp` core command set on the runner's CLI version needs no extension; the revision-readiness
query against a real app (tested only on sample JSON); that Trivy reads the runner's `docker save` format; artifact
quota for the two tarballs on a private repo; and that a revision which never becomes ready fails `update` (the script
does not rely on it). The smoke test sends one bad login per deploy, which counts toward the per-IP login limiter — if
the backend sees every client as one Envoy address (the open forwarded-headers item), a full shared bucket would fail
a deploy and the smoke test cannot tell the two apart. The CI gate builds with Node 24, the image with Node 22.

**Known limits.** `Contributor` on the resource group is broad: it can add a federated credential to the backend's
managed identity (and so read Key Vault), reset the Postgres admin password, read the storage, Redis and Log Analytics
keys, change network rules and delete everything. A custom role needs at least `Microsoft.App/containerApps/*`,
`Microsoft.App/managedEnvironments/join/action`, `Microsoft.ManagedIdentity/userAssignedIdentities/*/assign/action` and
`Microsoft.ContainerRegistry/registries/read` — and is still broad (it can delete apps, list their secrets, and attach
the Key Vault-reading identity to the frontend). Anything that can deploy can run code holding every backend secret;
least privilege here limits blast radius, not secret exposure. The Trivy digest in `deploy.yml` and the ClamAV tag in
`main.bicep` are not updated by Dependabot. The ClamAV sidecar is scanned on every deploy but its tag is still
floating (pinning it by digest in Bicep is open). Failure diagnostics print revision state only; backend logs hold user
emails and IPs and live in Log Analytics.

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
  off. Check: 21 bad logins from one IP return 429, and the logs contain no `Redis unavailable` warning.
  Also: `redisDb.listKeys()` is untested, and an open Bicep issue reports repeat-deploy failures when a `redisEnterprise`
  cluster and its database share one file (reported on a preview API; this uses `2025-04-01`).
- **ClamAV:** 2Gi is a starting point, not a measurement. Uploads return 503 until clamd has loaded its signatures
  (the liveness probe waits 60s). Confirm freshclam can reach its mirrors from Container Apps egress (silent staleness
  otherwise: no alert watches freshclam), run an EICAR upload end to end, and confirm clamd listens only where intended — whether other
  replicas in the environment can reach it is not something the template controls (`CLAMD_CONF_TCPAddr=127.0.0.1` was
  not tried; the image may add rather than replace the listener).
- **Forwarded headers:** `X-Forwarded-Proto: https` from the public ingress through two hops (check `Strict-Transport-Security`
  on an `/api` response), and that `audit_logs` and the limiters see the real client IP, not an Envoy address.
- Key Vault role-assignment propagation can lag behind app creation; if the first app deploy fails on secret
  resolution, re-run it. The frontend's nginx resolves `backend` once at startup (ordered after it via `dependsOn`).
- The blob HTTPS path to a real storage account (8h carry-forward), Spring parsing the percent-encoded `REDIS_URL`, and
  JVM heap (`-XX:MaxRAMPercentage=60` against 2Gi) are all untested.

## Backup and recovery (Phase 8n)

Full runbook, drill, dump-handling rules and open decisions: **`docs/BACKUP_AND_RECOVERY.md`**. In the template (set in
`infra/staging.bicepparam`): `postgresBackupRetentionDays` (7 default, use 35 for real data), `documentRetentionDays` (keep it
at least that long), `postgresGeoRedundantBackup` (**create-time only**), `postgresServerName` (empty; set it after a
point-in-time restore, which lands on a new server, and **commit it**: a deploy without it points the backend back at the
old, stale server), and `CanNotDelete` locks on Postgres and the documents storage account (`lockDataStores`; remove them
with `az lock delete` before tearing a staging environment down). The locks guard against accidental deletion only: they do
not stop a Contributor from disabling soft-delete, using the storage keys or lowering retention. `infra/ops/` holds the
restore drill / encrypted dump and the orphan-blob cleanup used after a restore.
Unverified: that the locks coexist with the template's own incremental redeploys; that a template deploy onto a
point-in-time-restored server updates it cleanly (the restore runbook's cutover step); every Azure command in the runbook.

## Monitoring (Phase 8m)

- **Telemetry:** the Application Insights Java agent (3.7.10, sha256-checked in `ergo-connect-backend/Dockerfile`) is in the
  image but only switched on by the Bicep (`JAVA_TOOL_OPTIONS=-javaagent:...` plus the connection string from Key Vault), so
  local `docker run` and CI are unaffected. It sends requests (with status codes), exceptions, database and Redis calls
  and WARN-or-higher logs (of requests that are not sampled out) to the workspace tables `AppRequests`, `AppDependencies`, `AppExceptions`, `AppTraces`.
  `ergo-connect-backend/applicationinsights.json` sets the role name and drops the health probes and the 10-second
  `AccessPolicy.refreshIfChanged` job (they would otherwise be most of the volume). A sampled-out request takes its logs
  with it, so the job's "Access policy refresh failed; keeping the last good snapshot" warning (tightened roles and module
  switches stop taking effect) is in the container console logs only: query `ContainerAppConsoleLogs_CL` for it. Bump the agent version and its sha256
  together; Dependabot and Trivy do not see this JAR.
- **Alerts** (email to `alertEmail`, checked every 5 min over 15 min): more than 4 5xx responses; Redis unreachable so the
  rate limiters (login included) fail open (`Redis unavailable`); the upload malware scan failing closed (`Malware scan
  failed`); the backend replica restarting more than twice in its life; and no telemetry at all for 30 minutes (the agent
  fails open, so without this a broken connection string silences every other alert). Thresholds are starting points for staging.
- **Key Vault audit logs** go to the workspace (`AzureDiagnostics`, who read which secret).
- **Privacy:** telemetry goes to your own workspace (the agent's optional usage statistics to Microsoft are switched off).
  Request URLs, client IPs and WARN log text (which can contain user emails) are recorded, and so are Redis calls with
  their keys, which embed IPs and, for forgot-password, the email address. INFO logs are not sent. No endpoint puts a
  secret in a query string (checked 8m), but every paged list takes `q` and filters in the query string: `GET /api/rfqs`, `/api/quotes`, `/api/ofp/orders`, `/api/ofp/production-pos`, `/api/npd`, `/api/npd/sample-pos`, `/api/quality/ncrs`, `/api/quality/deviations` and `/api/quality/inspections` (search terms can be customer and supplier names, PO, part and batch/lot numbers), which are recorded in request URLs and nginx logs. The Postgres driver is set to `logServerErrorDetail=false` so constraint errors
  no longer carry the failing row (prices, hidden margins) into logs; the same text was already reaching the console logs
  before 8m. Anyone with Reader on the workspace sees all of this.
- **Cost:** sampling is 100% and the workspace has no daily cap, so an unauthenticated login flood creates billable rows
  even when it only gets 429s. Decide on sampling or a cap before real traffic; a cap lets an attacker blind the alerts,
  and sampling needs the alert queries changed to `sum(ItemCount)`.
- **Verified locally (8m):** the image builds with the checksum check; with the agent on, the backend boots on Spring Boot
  4.1 and sends request, database and log telemetry to a stand-in ingestion endpoint; the sampling overrides drop the
  health probes and the job; the Postgres driver, with `logServerErrorDetail=false`, no longer puts row values in errors. The agent also fails open: a bad config file logs an error and the app still starts.
- **Unverified (needs Azure):** that the connection string reaches the agent through the Key Vault secret ref; that the
  telemetry arrives in the workspace; that the alert queries match real rows (`AppTraces.Message`); that
  `RestartCount` is the right metric name for Container Apps and `Maximum` the right aggregation; that the
  no-telemetry query finds rows on a healthy backend; the extra heap the agent needs inside the 2Gi limit
  (watch `WorkingSetBytes` in 8o).

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
| Postgres diagnostic logs, a freshclam-staleness alert, dashboards, telemetry sampling tuned to real traffic | after first deploy (8m shipped the rest) |
| Scheduled off-Azure logical dumps, RPO/RTO sign-off, geo-redundancy decision, blob versioning (`docs/BACKUP_AND_RECOVERY.md`, open decisions) | before go-live (8n shipped the runbook and drill) |
| Pin the ClamAV image by digest in `main.bicep` (it is now scanned on every deploy) | after first deploy |
| Narrow `server.tomcat.remoteip.internal-proxies`; confirm `X-Forwarded-For` format. Any app later added to the environment can spoof audit-log IPs until then | after first deploy |
| Upload byte-quota sizing (8d-bis) | with real traffic |
