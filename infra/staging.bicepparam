// Parameters for the staging environment (Phase 8n). Deploy with:
//
//   export PG_PASSWORD=… JWT_SECRET=… MAIL_PASSWORD=… MAIL_HOST=… MAIL_USERNAME=… MAIL_FROM=… ALERT_EMAIL=…
//   az deployment group what-if -g rg-ergo-staging --parameters infra/staging.bicepparam
//
// Everything that is not a secret lives HERE, in git, so that a value like postgresServerName cannot be forgotten on the
// command line: after a point-in-time restore the data lives on a new server, and a deploy that omits the new name would
// point the backend back at the old, stale one (docs/BACKUP_AND_RECOVERY.md). Secrets and per-run values come from the
// environment; the build fails if a required one is unset.
using './main.bicep'

param namePrefix = 'ergo-staging'

// Empty = the server this template names itself. Set to the restored server's name after a point-in-time restore.
param postgresServerName = ''
// 7 is the minimum. Use 35 (the maximum) and a matching documentRetentionDays for anything holding real data.
param postgresBackupRetentionDays = 7
// Create-time only: changing it later means restoring into a new server.
param postgresGeoRedundantBackup = false
param documentRetentionDays = 7
param lockDataStores = true

param mailHost = readEnvironmentVariable('MAIL_HOST')
param mailUsername = readEnvironmentVariable('MAIL_USERNAME')
param mailFrom = readEnvironmentVariable('MAIL_FROM')
param alertEmail = readEnvironmentVariable('ALERT_EMAIL')

param postgresAdminPassword = readEnvironmentVariable('PG_PASSWORD')
param jwtSecret = readEnvironmentVariable('JWT_SECRET')
param mailPassword = readEnvironmentVariable('MAIL_PASSWORD')

// One-time first-admin bootstrap (8e): set both for the first deploy only, then unset them.
param bootstrapAdminEmail = readEnvironmentVariable('BOOTSTRAP_ADMIN_EMAIL', '')
param bootstrapAdminPassword = readEnvironmentVariable('BOOTSTRAP_ADMIN_PASSWORD', '')

// Empty = infrastructure only. For a cutover deploy pass the CURRENT digests (az containerapp show, or the last
// deploy.yml run summary); leaving them empty would leave the apps as they are.
param backendImage = readEnvironmentVariable('BACKEND_IMAGE', '')
param frontendImage = readEnvironmentVariable('FRONTEND_IMAGE', '')
