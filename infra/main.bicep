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

// ---------- names ----------

var suffix = uniqueString(resourceGroup().id)
var deployApps = !empty(backendImage) && !empty(frontendImage)
var bootstrapEnabled = !empty(bootstrapAdminEmail) && !empty(bootstrapAdminPassword)
var databaseName = 'ergo_connect'
var documentsContainer = 'documents'

// ---------- observability sink (Container Apps requires one; Application Insights is 8m) ----------

resource logs 'Microsoft.OperationalInsights/workspaces@2023-09-01' = {
  name: 'log-${namePrefix}'
  location: location
  properties: {
    sku: { name: 'PerGB2018' }
    retentionInDays: 30
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
    deleteRetentionPolicy: { enabled: true, days: 7 }
    containerDeleteRetentionPolicy: { enabled: true, days: 7 }
  }
}

resource documents 'Microsoft.Storage/storageAccounts/blobServices/containers@2023-05-01' = {
  parent: blobService
  name: documentsContainer
  properties: { publicAccess: 'None' }
}

// ---------- postgres ----------

resource postgres 'Microsoft.DBforPostgreSQL/flexibleServers@2024-08-01' = {
  name: 'psql-${namePrefix}-${suffix}'
  location: location
  sku: { name: 'Standard_B1ms', tier: 'Burstable' }
  properties: {
    version: postgresVersion
    administratorLogin: postgresAdminLogin
    administratorLoginPassword: postgresAdminPassword
    storage: { storageSizeGB: 32 }
    backup: { backupRetentionDays: 7, geoRedundantBackup: 'Disabled' }
    highAvailability: { mode: 'Disabled' }
    network: { publicNetworkAccess: 'Enabled' }
    authConfig: { activeDirectoryAuth: 'Disabled', passwordAuth: 'Enabled' }
  }
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
          env: concat(backendEnv, [{ name: 'JAVA_TOOL_OPTIONS', value: '-XX:MaxRAMPercentage=60' }])
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

// ---------- outputs (no secrets) ----------

output registryLoginServer string = registry.properties.loginServer
output keyVaultName string = vault.name
output postgresHost string = postgres.properties.fullyQualifiedDomainName
output frontendUrl string = frontendUrl
