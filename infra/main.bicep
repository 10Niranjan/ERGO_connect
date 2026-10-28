// ERGO Connect - Azure infrastructure (Phase 8k). Resource-group scoped; see infra/README.md for the
// two-phase deploy (infra first, then apps once 8l has pushed images). Authored, NOT yet deployed.
targetScope = 'resourceGroup'

// ---------- parameters ----------

@description('Short lowercase name used to build resource names, e.g. ergo-staging.')
@minLength(3)
@maxLength(16)
param namePrefix string

param location string = resourceGroup().location

@description('Postgres admin login. Not a secret by itself, but never "postgres"/"admin" (Azure rejects them).')
param postgresAdminLogin string = 'ergoadmin'

@secure()
@minLength(24)
@description('Postgres admin password. No default. The server is internet-reachable (Azure-services rule) with no lockout, so use a long random value, e.g. openssl rand -base64 32.')
param postgresAdminPassword string

@secure()
@description('>= 32 random bytes; the app refuses to start otherwise (SecurityProperties).')
param jwtSecret string

@description('SMTP relay the app sends password-reset / OTP email through. The app refuses to start in prod without it.')
param mailHost string
param mailPort int = 587
param mailUsername string
@secure()
param mailPassword string
@description('From: address - must pass the sending domain SPF/DMARC.')
param mailFrom string

@description('Who is emailed when a monitoring alert fires (8m). Required: an alert nobody receives is the silent failure 8m exists to remove. Use a shared mailbox or distribution list.')
@minLength(5)
param alertEmail string

@description('One-time first-admin bootstrap (8e). Leave both empty after the first successful deploy.')
param bootstrapAdminEmail string = ''
@secure()
param bootstrapAdminPassword string = ''

@description('Full image refs, e.g. <acr>.azurecr.io/ergo-backend:<sha>. While either is empty only the infra is deployed (phase 1).')
param backendImage string = ''
param frontendImage string = ''

@description('Pin by digest once chosen (8i carry-forward); tag is the same one the local compose files use.')
param clamavImage string = 'clamav/clamav:1.4'

param postgresVersion string = '17'

@description('Leave empty. After a point-in-time restore the data lives on a NEW server (Azure cannot restore over the old one); pass its name here from then on, or the next infra deploy would create a fresh empty server under the old name and point the backend at it. See docs/BACKUP_AND_RECOVERY.md.')
param postgresServerName string = ''

@minValue(7)
@maxValue(35)
@description('Days of Postgres point-in-time restore (8n). 7 is the minimum; use 35 (the maximum) for anything holding real data.')
param postgresBackupRetentionDays int = 7

@minValue(7)
@maxValue(365)
@description('Days a deleted document file (or container) can be undeleted (8n). Keep it at least as long as postgresBackupRetentionDays: restoring the database further back than the file retention brings back document rows whose files are already purged.')
param documentRetentionDays int = 7

@description('Copy Postgres backups to the paired region (8n). Can ONLY be chosen when the server is created: changing it later means restoring into a new server. Needs a region with a pair; costs about double backup storage.')
param postgresGeoRedundantBackup bool = false

@description('CanNotDelete locks on Postgres and the documents storage account (8n). The backups of a dropped Flexible Server are only kept for 5 days and reviving is best effort, so a lock is the main protection against losing the data and its backups with one command. Before tearing a staging environment down run: az lock delete (see docs/BACKUP_AND_RECOVERY.md).')
param lockDataStores bool = true

// ---------- names ----------

var suffix = uniqueString(resourceGroup().id)
var deployApps = !empty(backendImage) && !empty(frontendImage)
var bootstrapEnabled = !empty(bootstrapAdminEmail) && !empty(bootstrapAdminPassword)
var databaseName = 'ergo_connect'
var postgresName = empty(postgresServerName) ? 'psql-${namePrefix}-${suffix}' : postgresServerName
var documentsContainer = 'documents'

// ---------- observability (8m) ----------

resource logs 'Microsoft.OperationalInsights/workspaces@2023-09-01' = {
  name: 'log-${namePrefix}'
  location: location
  properties: {
    sku: { name: 'PerGB2018' }
    retentionInDays: 30
  }
}

// Workspace-based: requests, exceptions and WARN+ logs land in the AppRequests / AppExceptions / AppTraces tables of the
// workspace above, next to the container console logs. The backend ships telemetry with the Java agent baked into its image.
resource insights 'Microsoft.Insights/components@2020-02-02' = {
  name: 'appi-${namePrefix}'
  location: location
  kind: 'web'
  properties: {
    Application_Type: 'web'
    WorkspaceResourceId: logs.id
    // The agent authenticates with the connection string (ingestion-only; it cannot read telemetry back).
    DisableIpMasking: false
  }
}

// ---------- identity + secrets ----------

resource backendIdentity 'Microsoft.ManagedIdentity/userAssignedIdentities@2023-01-31' = {
  name: 'id-${namePrefix}-backend'
  location: location
}

// Pull-only. The public-facing nginx must never share the identity that can read Key Vault (security-reviewer H1).
resource frontendIdentity 'Microsoft.ManagedIdentity/userAssignedIdentities@2023-01-31' = {
  name: 'id-${namePrefix}-frontend'
  location: location
}

resource vault 'Microsoft.KeyVault/vaults@2023-07-01' = {
  name: take('kv-${namePrefix}-${suffix}', 24)
  location: location
  properties: {
    sku: { family: 'A', name: 'standard' }
    tenantId: subscription().tenantId
    enableRbacAuthorization: true
    enableSoftDelete: true
    softDeleteRetentionInDays: 7
    // ponytail: no purge protection - it is irreversible and blocks teardown of a staging env; turn it on for prod.
    // Public endpoint: Container Apps without a VNet cannot reach a private one. Revisit with VNet integration.
    publicNetworkAccess: 'Enabled'
  }
}

// ---------- storage (8h: allowBlobPublicAccess=false, container owned by IaC) ----------

resource storage 'Microsoft.Storage/storageAccounts@2023-05-01' = {
  name: take(toLower(replace('st${namePrefix}${suffix}', '-', '')), 24)
  location: location
  kind: 'StorageV2'
  sku: { name: 'Standard_LRS' }
  properties: {
    allowBlobPublicAccess: false
    minimumTlsVersion: 'TLS1_2'
    supportsHttpsTrafficOnly: true
  }
}

resource blobService 'Microsoft.Storage/storageAccounts/blobServices@2023-05-01' = {
  parent: storage
  name: 'default'
  // The account is key-authenticated and internet-reachable; this is the only undo for a leaked key used to delete.
  properties: {
    deleteRetentionPolicy: { enabled: true, days: documentRetentionDays }
    containerDeleteRetentionPolicy: { enabled: true, days: documentRetentionDays }
  }
}

resource documents 'Microsoft.Storage/storageAccounts/blobServices/containers@2023-05-01' = {
  parent: blobService
  name: documentsContainer
  properties: { publicAccess: 'None' }
}

// ---------- postgres ----------

resource postgres 'Microsoft.DBforPostgreSQL/flexibleServers@2024-08-01' = {
  name: postgresName
  location: location
  sku: { name: 'Standard_B1ms', tier: 'Burstable' }
  properties: {
    version: postgresVersion
    administratorLogin: postgresAdminLogin
    administratorLoginPassword: postgresAdminPassword
    storage: { storageSizeGB: 32 }
    backup: { backupRetentionDays: postgresBackupRetentionDays, geoRedundantBackup: postgresGeoRedundantBackup ? 'Enabled' : 'Disabled' }
    highAvailability: { mode: 'Disabled' }
    network: { publicNetworkAccess: 'Enabled' }
    authConfig: { activeDirectoryAuth: 'Disabled', passwordAuth: 'Enabled' }
  }
}

// CanNotDelete, not ReadOnly: ReadOnly would also block listKeys and every write the template itself makes.
// A lock is a management-plane guard against deleting the resource (and, for Postgres, its backups with it). It does NOT
// stop anyone with Contributor from switching soft-delete off, reading the account keys and deleting blobs, or lowering
// the backup retention. Whoever deploys this template (Owner, or Contributor + User Access Administrator) can also
// remove the locks. The CI identity (Contributor on the resource group) cannot create or remove them.
resource postgresLock 'Microsoft.Authorization/locks@2020-05-01' = if (lockDataStores) {
  scope: postgres
  name: 'no-delete'
  properties: { level: 'CanNotDelete', notes: 'A dropped server keeps its backups only 5 days. See docs/BACKUP_AND_RECOVERY.md.' }
}

resource storageLock 'Microsoft.Authorization/locks@2020-05-01' = if (lockDataStores) {
  scope: storage
  name: 'no-delete'
  properties: { level: 'CanNotDelete', notes: 'Holds every uploaded document. See docs/BACKUP_AND_RECOVERY.md.' }
}

resource db 'Microsoft.DBforPostgreSQL/flexibleServers/databases@2024-08-01' = {
  parent: postgres
  name: databaseName
}

// Azure's documented special case: 0.0.0.0-0.0.0.0 admits ANY Azure-hosted workload in ANY tenant (a cheap VM
// qualifies) - not just this app. Password (24+ chars) + TLS are the only controls, and the account is the server
// admin. Acceptable for staging holding no real client data only; private endpoints are required first (8p).
resource allowAzure 'Microsoft.DBforPostgreSQL/flexibleServers/firewallRules@2024-08-01' = {
  parent: postgres
  name: 'AllowAllAzureServicesAndResourcesWithinAzureIps'
  properties: { startIpAddress: '0.0.0.0', endIpAddress: '0.0.0.0' }
}

// ---------- redis (Azure Managed Redis: classic Basic/Standard/Premium creation was blocked from 2026-10-01) ----------

resource redis 'Microsoft.Cache/redisEnterprise@2025-04-01' = {
  name: 'redis-${namePrefix}-${suffix}'
  location: location
  sku: { name: 'Balanced_B0' }
  properties: { minimumTlsVersion: '1.2' }
}

resource redisDb 'Microsoft.Cache/redisEnterprise/databases@2025-04-01' = {
  parent: redis
  name: 'default'
  properties: {
    clientProtocol: 'Encrypted'
    port: 10000
    // Single logical endpoint: Lettuce here is configured as standalone, not cluster-aware (OSSCluster would need that).
    clusteringPolicy: 'EnterpriseCluster'
    // Every key the app writes (rate-limit counters) carries a TTL.
    evictionPolicy: 'VolatileLRU'
    accessKeysAuthentication: 'Enabled'
  }
}

// ---------- container registry (8l pushes here) ----------

resource registry 'Microsoft.ContainerRegistry/registries@2023-07-01' = {
  name: take(toLower(replace('acr${namePrefix}${suffix}', '-', '')), 50)
  location: location
  sku: { name: 'Basic' }
  properties: { adminUserEnabled: false }
}

// ---------- secrets into Key Vault (values built here or passed @secure; none land in the repo) ----------

resource secretDbPassword 'Microsoft.KeyVault/vaults/secrets@2023-07-01' = {
  parent: vault
  name: 'db-password'
  properties: { value: postgresAdminPassword }
}

resource secretJwt 'Microsoft.KeyVault/vaults/secrets@2023-07-01' = {
  parent: vault
  name: 'jwt-secret'
  properties: { value: jwtSecret }
}

// The connection string only allows sending telemetry, but anyone holding it can inject fake alerts and noise.
resource secretInsights 'Microsoft.KeyVault/vaults/secrets@2023-07-01' = {
  parent: vault
  name: 'appinsights-connection-string'
  properties: { value: insights.properties.ConnectionString }
}

resource secretMail 'Microsoft.KeyVault/vaults/secrets@2023-07-01' = {
  parent: vault
  name: 'mail-password'
  properties: { value: mailPassword }
}

// Azure access keys are base64 and can contain / + = - percent-encode, or the URL parser splits on them.
resource secretRedisUrl 'Microsoft.KeyVault/vaults/secrets@2023-07-01' = {
  parent: vault
  name: 'redis-url'
  properties: { value: 'rediss://:${uriComponent(redisDb.listKeys().primaryKey)}@${redis.properties.hostName}:10000' }
}

resource secretStorage 'Microsoft.KeyVault/vaults/secrets@2023-07-01' = {
  parent: vault
  name: 'storage-connection-string'
  properties: {
    value: 'DefaultEndpointsProtocol=https;AccountName=${storage.name};AccountKey=${storage.listKeys().keys[0].value};EndpointSuffix=${environment().suffixes.storage}'
  }
}

resource secretBootstrap 'Microsoft.KeyVault/vaults/secrets@2023-07-01' = if (bootstrapEnabled) {
  parent: vault
  name: 'bootstrap-admin-password'
  properties: { value: bootstrapAdminPassword }
}

// ---------- RBAC for the backend's managed identity ----------

var roleKeyVaultSecretsUser = '4633458b-17de-408a-b874-0445c86b69e6'
var roleAcrPull = '7f951dda-4ed3-4680-a7ca-43fe172d538d'

resource rbacVault 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  scope: vault
  name: guid(vault.id, backendIdentity.id, roleKeyVaultSecretsUser)
  properties: {
    principalId: backendIdentity.properties.principalId
    principalType: 'ServicePrincipal'
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', roleKeyVaultSecretsUser)
  }
}

resource rbacRegistry 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  scope: registry
  name: guid(registry.id, backendIdentity.id, roleAcrPull)
  properties: {
    principalId: backendIdentity.properties.principalId
    principalType: 'ServicePrincipal'
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', roleAcrPull)
  }
}

resource rbacRegistryFrontend 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  scope: registry
  name: guid(registry.id, frontendIdentity.id, roleAcrPull)
  properties: {
    principalId: frontendIdentity.properties.principalId
    principalType: 'ServicePrincipal'
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', roleAcrPull)
  }
}

// No Storage Blob Data Contributor grant: AzureBlobDocumentStorage authenticates with the connection string, so the
// role would be unused, account-wide standing access (security-reviewer H1c). Grant it, scoped to the documents
// container, in the same change that moves the app to DefaultAzureCredential.

// ---------- container apps ----------

resource appEnv 'Microsoft.App/managedEnvironments@2024-03-01' = {
  name: 'cae-${namePrefix}'
  location: location
  properties: {
    appLogsConfiguration: {
      destination: 'log-analytics'
      logAnalyticsConfiguration: {
        customerId: logs.properties.customerId
        sharedKey: logs.listKeys().primarySharedKey
      }
    }
    workloadProfiles: [{ name: 'Consumption', workloadProfileType: 'Consumption' }]
  }
}

// The environment's default domain is known without the frontend existing, so there is no circular reference.
var frontendUrl = 'https://frontend.${appEnv.properties.defaultDomain}'

var backendSecretRefs = concat(
  [
    { name: 'db-password', keyVaultUrl: secretDbPassword.properties.secretUri, identity: backendIdentity.id }
    { name: 'jwt-secret', keyVaultUrl: secretJwt.properties.secretUri, identity: backendIdentity.id }
    { name: 'mail-password', keyVaultUrl: secretMail.properties.secretUri, identity: backendIdentity.id }
    { name: 'redis-url', keyVaultUrl: secretRedisUrl.properties.secretUri, identity: backendIdentity.id }
    { name: 'storage-connection-string', keyVaultUrl: secretStorage.properties.secretUri, identity: backendIdentity.id }
    { name: 'appinsights-connection-string', keyVaultUrl: secretInsights.properties.secretUri, identity: backendIdentity.id }
  ],
  bootstrapEnabled
    ? [{ name: 'bootstrap-admin-password', keyVaultUrl: secretBootstrap!.properties.secretUri, identity: backendIdentity.id }]
    : []
)

var backendEnv = concat(
  [
    { name: 'DB_URL', value: 'jdbc:postgresql://${postgres.properties.fullyQualifiedDomainName}:5432/${databaseName}?sslmode=require' }
    { name: 'DB_USERNAME', value: postgresAdminLogin }
    { name: 'DB_PASSWORD', secretRef: 'db-password' }
    { name: 'JWT_SECRET', secretRef: 'jwt-secret' }
    { name: 'REDIS_URL', secretRef: 'redis-url' }
    { name: 'CORS_ALLOWED_ORIGINS', value: frontendUrl }
    { name: 'FRONTEND_BASE_URL', value: frontendUrl }
    { name: 'MAIL_HOST', value: mailHost }
    { name: 'MAIL_PORT', value: string(mailPort) }
    { name: 'MAIL_USERNAME', value: mailUsername }
    { name: 'MAIL_PASSWORD', secretRef: 'mail-password' }
    { name: 'MAIL_FROM', value: mailFrom }
    { name: 'AZURE_STORAGE_CONNECTION_STRING', secretRef: 'storage-connection-string' }
    { name: 'AZURE_STORAGE_CONTAINER', value: documentsContainer }
    // Telemetry (8m): the agent JAR and its config are baked into the image and switched on by JAVA_TOOL_OPTIONS below.
    { name: 'APPLICATIONINSIGHTS_CONNECTION_STRING', secretRef: 'appinsights-connection-string' }
    { name: 'APPLICATIONINSIGHTS_CONFIGURATION_FILE', value: '/app/applicationinsights.json' }
    // The agent otherwise also reports usage statistics to Microsoft; this turns off the optional part.
    { name: 'APPLICATIONINSIGHTS_STATSBEAT_DISABLED', value: 'true' }
    // CLAMAV_HOST/PORT left unset: the sidecar below shares the replica's network namespace, so the app's own
    // default (localhost:3310) is the right answer, and clamd is reachable by nothing else (8i carry-forward).
  ],
  bootstrapEnabled
    ? [
        { name: 'ERGO_BOOTSTRAP_ADMIN_EMAIL', value: bootstrapAdminEmail }
        { name: 'ERGO_BOOTSTRAP_ADMIN_PASSWORD', secretRef: 'bootstrap-admin-password' }
      ]
    : []
)

resource backend 'Microsoft.App/containerApps@2025-01-01' = if (deployApps) {
  name: 'backend' // must stay "backend": the frontend's nginx upstream resolves this app by name
  location: location
  identity: { type: 'UserAssigned', userAssignedIdentities: { '${backendIdentity.id}': {} } }
  dependsOn: [rbacVault, rbacRegistry]
  properties: {
    managedEnvironmentId: appEnv.id
    workloadProfileName: 'Consumption'
    configuration: {
      // The platform still uses the identity (image pull, Key Vault secret refs) but no container - including the
      // ClamAV sidecar that parses hostile uploads - can mint tokens from it. Nothing in the app uses it at runtime.
      identitySettings: [{ identity: backendIdentity.id, lifecycle: 'None' }]
      activeRevisionsMode: 'Single'
      // Internal only: the sole way in is the frontend's nginx. allowInsecure skips the 80->443 redirect for that
      // trusted in-environment hop (nginx calls http://backend); it does not expose anything to the internet.
      ingress: { external: false, targetPort: 8080, transport: 'http', allowInsecure: true }
      registries: [{ server: registry.properties.loginServer, identity: backendIdentity.id }]
      secrets: backendSecretRefs
    }
    template: {
      containers: [
        {
          name: 'backend'
          image: backendImage
          resources: { cpu: json('1.0'), memory: '2Gi' }
          // JVM default heap is 25% of the limit, too small beside in-memory uploads up to 50MB. Starting point; tune in 8o.
          env: concat(backendEnv, [{ name: 'JAVA_TOOL_OPTIONS', value: '-XX:MaxRAMPercentage=60 -javaagent:/app/applicationinsights-agent.jar' }])
          probes: [
            { type: 'Startup', httpGet: { path: '/actuator/health', port: 8080 }, periodSeconds: 10, failureThreshold: 18 }
            { type: 'Liveness', httpGet: { path: '/actuator/health', port: 8080 }, periodSeconds: 30, failureThreshold: 3 }
            { type: 'Readiness', httpGet: { path: '/actuator/health', port: 8080 }, periodSeconds: 15, failureThreshold: 3 }
          ]
        }
        {
          // Same clamd hardening as docker/docker-compose.local.yml (8i H1) - except CLAMAV_NO_FRESHCLAMD, which
          // prod must NOT inherit or signatures go stale.
          name: 'clamav'
          image: clamavImage
          // clamd with the full signature set needs well over 1Gi; an OOM here fails every upload closed (503).
          resources: { cpu: json('1.0'), memory: '2Gi' }
          // A hung clamd is otherwise never restarted. Generous delay: signatures take a while to load.
          probes: [
            { type: 'Liveness', tcpSocket: { port: 3310 }, initialDelaySeconds: 60, periodSeconds: 30, failureThreshold: 3 }
          ]
          env: [
            // No second copy of the signature DB in memory during a reload (halves peak memory; scans block meanwhile).
            { name: 'CLAMD_CONF_ConcurrentDatabaseReload', value: 'no' }
            { name: 'CLAMD_CONF_StreamMaxLength', value: '60M' }
            { name: 'CLAMD_CONF_MaxScanTime', value: '5000' }
            { name: 'CLAMD_CONF_AlertExceedsMax', value: 'yes' }
            { name: 'CLAMD_CONF_AlertEncryptedArchive', value: 'yes' }
            { name: 'CLAMD_CONF_AlertEncryptedDoc', value: 'yes' }
            { name: 'CLAMD_CONF_AlertOLE2Macros', value: 'yes' }
          ]
        }
      ]
      // ponytail: pinned to 1 replica - two @Scheduled daily jobs (OverdueReminders, DeviationService expiry) have no
      // distributed lock and would run once per replica. Raise only after making them single-run (e.g. ShedLock).
      scale: { minReplicas: 1, maxReplicas: 1 }
    }
  }
}

resource frontend 'Microsoft.App/containerApps@2025-01-01' = if (deployApps) {
  name: 'frontend'
  location: location
  identity: { type: 'UserAssigned', userAssignedIdentities: { '${frontendIdentity.id}': {} } }
  // After the backend: nginx resolves "backend" once at startup and exits if it cannot.
  dependsOn: [rbacRegistryFrontend, backend]
  properties: {
    managedEnvironmentId: appEnv.id
    workloadProfileName: 'Consumption'
    configuration: {
      identitySettings: [{ identity: frontendIdentity.id, lifecycle: 'None' }]
      activeRevisionsMode: 'Single'
      // The only public entry point. Azure terminates TLS here and sets X-Forwarded-Proto, which nginx passes on (8j H2).
      ingress: { external: true, targetPort: 8080, transport: 'auto' }
      registries: [{ server: registry.properties.loginServer, identity: frontendIdentity.id }]
    }
    template: {
      containers: [
        {
          name: 'frontend'
          image: frontendImage
          resources: { cpu: json('0.25'), memory: '0.5Gi' }
          // Container Apps fronts every app on 80/443 whatever port its container listens on (see nginx.conf.template).
          env: [
            { name: 'BACKEND_UPSTREAM', value: 'http://backend' }
            { name: 'BACKEND_HOST_HEADER', value: '$proxy_host' }
          ]
          probes: [
            { type: 'Liveness', httpGet: { path: '/', port: 8080 }, periodSeconds: 30, failureThreshold: 3 }
          ]
        }
      ]
      scale: { minReplicas: 1, maxReplicas: 2 }
    }
  }
}

// ---------- alerts + audit logging (8m) ----------

resource alertsGroup 'Microsoft.Insights/actionGroups@2023-01-01' = {
  name: 'ag-${namePrefix}'
  location: 'global'
  properties: {
    groupShortName: take(replace(namePrefix, '-', ''), 12)
    enabled: true
    emailReceivers: [{ name: 'ops', emailAddress: alertEmail, useCommonAlertSchema: true }]
  }
}

// Log alerts over the workspace. Thresholds are starting points for staging; tune them against real traffic.
var logAlerts = [
  {
    name: 'server-errors'
    title: 'Backend returning 5xx'
    description: 'More than 4 requests answered with a 5xx in 15 minutes.'
    severity: 2
    threshold: 4
    query: 'AppRequests | where toint(ResultCode) >= 500'
  }
  {
    // Every limiter logs a "Redis unavailable ..." warning on each request it lets through because Redis is unreachable
    // (8d carry-forward). The login limiter words it differently ("... for login rate limiting"), so match the common start.
    name: 'rate-limiter-failing-open'
    title: 'Rate limiters are failing open'
    description: 'Redis is unreachable or misconfigured: the login, change-password, upload and forgot-password limiters are letting everything through.'
    severity: 2
    threshold: 0
    query: 'AppTraces | where Message has "Redis unavailable"'
  }
  {
    // DocumentStore rejects the upload (fail closed) when clamd cannot scan: ClamAV down, out of memory or still loading.
    name: 'malware-scan-failing'
    title: 'Upload malware scan is failing'
    description: 'Uploads are being rejected because the ClamAV sidecar could not scan them.'
    severity: 2
    threshold: 0
    query: 'AppTraces | where Message has "Malware scan failed"'
  }
]

resource queryAlerts 'Microsoft.Insights/scheduledQueryRules@2022-06-15' = [
  for rule in logAlerts: {
    name: 'alert-${namePrefix}-${rule.name}'
    location: location
    dependsOn: [insights]
    properties: {
      displayName: '${namePrefix}: ${rule.title}'
      description: rule.description
      severity: rule.severity
      enabled: true
      scopes: [logs.id]
      evaluationFrequency: 'PT5M'
      windowSize: 'PT15M'
      criteria: {
        allOf: [
          {
            query: rule.query
            timeAggregation: 'Count'
            operator: 'GreaterThan'
            threshold: rule.threshold
            failingPeriods: { numberOfEvaluationPeriods: 1, minFailingPeriodsToAlert: 1 }
          }
        ]
      }
      actions: { actionGroups: [alertsGroup.id] }
    }
  }
]

// The log alerts above only work while the agent is sending. It fails open by design (a wrong connection string or a
// blocked ingestion endpoint just means silence), so alert when nothing at all has arrived for 30 minutes. The agent
// reports JVM metrics every minute, so a healthy backend never trips this. Only meaningful once the apps exist.
resource telemetryMissingAlert 'Microsoft.Insights/scheduledQueryRules@2022-06-15' = if (deployApps) {
  name: 'alert-${namePrefix}-telemetry-missing'
  location: location
  dependsOn: [insights]
  properties: {
    displayName: '${namePrefix}: No backend telemetry'
    description: 'Nothing has reached Application Insights for 30 minutes: the other alerts are blind until this is fixed (connection string, ingestion, agent) or the backend is down.'
    severity: 2
    enabled: true
    scopes: [logs.id]
    evaluationFrequency: 'PT10M'
    windowSize: 'PT30M'
    criteria: {
      allOf: [
        {
          query: 'union isfuzzy=true AppMetrics, AppPerformanceCounters, AppRequests, AppTraces, AppDependencies'
          timeAggregation: 'Count'
          operator: 'LessThan'
          threshold: 1
          failingPeriods: { numberOfEvaluationPeriods: 1, minFailingPeriodsToAlert: 1 }
        }
      ]
    }
    actions: { actionGroups: [alertsGroup.id] }
  }
}

// A crash-looping backend sends no telemetry at all, so it needs a platform metric rather than a log query.
// RestartCount is cumulative over the replica's life (one data point a minute), so a Total over a window would add the
// running totals together and fire on the first restart ever. Maximum > 2 means "restarted more than twice since this
// replica was created"; with the single-replica pin that is the signal we want. A new replica resets it.
resource restartAlert 'Microsoft.Insights/metricAlerts@2018-03-01' = if (deployApps) {
  name: 'alert-${namePrefix}-backend-restarts'
  location: 'global'
  properties: {
    description: 'The backend replica has restarted more than twice since it was created (crash loop, repeated OOM).'
    severity: 2
    enabled: true
    scopes: [backend!.id]
    evaluationFrequency: 'PT5M'
    windowSize: 'PT15M'
    criteria: {
      'odata.type': 'Microsoft.Azure.Monitor.SingleResourceMultipleMetricCriteria'
      allOf: [
        {
          name: 'restarts'
          metricNamespace: 'microsoft.app/containerapps'
          metricName: 'RestartCount'
          operator: 'GreaterThan'
          threshold: 2
          timeAggregation: 'Maximum'
          criterionType: 'StaticThresholdCriterion'
        }
      ]
    }
    actions: [{ actionGroupId: alertsGroup.id }]
  }
}

// Who read which secret, and when. Key Vault keeps no history of reads on its own.
resource vaultAudit 'Microsoft.Insights/diagnosticSettings@2021-05-01-preview' = {
  scope: vault
  name: 'audit-to-log-analytics'
  properties: {
    workspaceId: logs.id
    logs: [{ categoryGroup: 'audit', enabled: true }]
  }
}

// ---------- outputs (no secrets) ----------

output registryLoginServer string = registry.properties.loginServer
output keyVaultName string = vault.name
output postgresHost string = postgres.properties.fullyQualifiedDomainName
output frontendUrl string = frontendUrl
