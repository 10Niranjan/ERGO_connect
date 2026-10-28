# Backup and recovery (Phase 8n, SRS §21 "Backup and recovery plan ready")

What is backed up, how to get it back, and the one drill that has actually been run. **Status: authored. The database
drill and the scripts have been run locally; no Azure command below has been run (nothing is deployed yet), and a first
point-in-time restore on staging is still to do.** Read a procedure through to the end before running its first command.

## What protects what

| Data | Protection | Gap |
|---|---|---|
| **Postgres** (every record, audit trail, document metadata) | Azure automated backups, point-in-time restore (PITR). `postgresBackupRetentionDays` (7 default, **35 for real data**). `CanNotDelete` lock. | Locks are management-plane only (see below). No copy outside Azure's backup system. Geo-redundancy (`postgresGeoRedundantBackup`) can only be chosen when the server is created. |
| **Document files** (Blob `documents` container, blobs named `doc-<id>`) | Soft-delete for `documentRetentionDays` (7 default; **set it at least as long as the Postgres retention**, or an old restore brings back document rows whose files are already purged). `CanNotDelete` lock on the account. | No versioning, no second copy, `Standard_LRS`. |
| **Key Vault secrets** | 7-day soft-delete. | No purge protection (README, "Required before real data"). |
| **Redis** | Nothing needed: rate-limit counters with short TTLs. | |
| **Container images** | Rebuilt from git by the deploy pipeline; earlier digests stay in the registry. | |
| **Logs** (Log Analytics) | 30 days. Not a backup. | |
| **Deploy inputs** (Postgres admin password, JWT secret, mail password) | Not in the repo, by design. | **Keep them in the organisation's password manager.** Without them the infra cannot be redeployed. |

**What the locks do and do not do.** `lockDataStores` stops someone deleting the Postgres server (and with it its backups)
or the storage account by mistake. It does **not** stop anyone with Contributor from switching blob soft-delete off, reading
the storage keys and deleting blobs, or lowering the backup retention, and whoever deploys the template can remove the
locks. Treat it as a guard against accidents, not against a malicious or compromised admin.

## Targets (proposed, not yet agreed with the client)

The SRS names no figures. Proposed for sign-off: **RPO about 5 minutes** (Azure ships transaction logs roughly every 5
minutes; confirm in the first Azure restore) and **RTO 4 hours**. A geo-restore loses up to about an hour and is slower.

## Handling a logical dump (`DUMP_OUT`)

A dump is the only copy that survives losing the Azure subscription's backups, and it holds **every competitor quote, target price,
hidden margin, password hash and 2FA secret.** Proposed rules (the client must confirm owners and retention):

- Create it only with `infra/ops/restore-drill.sh` and `DUMP_OUT=…`: it is encrypted (AES-256) as it is written; no plaintext
  is ever on disk, and the script refuses a path inside a git work tree. (`.gitignore` also blocks `*.gpg`, `*.dump`, `*.part`
  and `infra/*.json`, the compiled parameter file with its secrets.) The passphrase is chosen at that moment, kept in the organisation's password manager, known to at least
  two named people, and never stored next to the dump.
- Store it only in a restricted location outside the production resource group (ideally another subscription, immutable
  storage). Not on laptops, chat or email. Keep it 90 days, then delete.
- Decrypting it for a restore is a recorded action. Decrypt straight into `pg_restore`, never to a file
  (`gpg --no-symkey-cache -d …`). The incident holding container (`documents-incident-<date>`, section B) follows the same rules.

## Restore procedures

Always keep an incident log: who decided, the restore point, why. **Everything the app wrote after the restore point is
lost, including its audit-trail rows** (procedure A keeps a copy of them). Azure restores into a **new server**; it cannot
restore over the old one. Use a terminal where the secrets are in the environment, not on the command line.

### A. Bad data, wrong delete, bad deploy (within retention): point-in-time restore

```bash
RG=rg-ergo-staging; SRC=<old server name>; NEW=${SRC}-r1; ST=<storage account>
```

0. **Freeze writes and the pipeline first.** Test this on staging before you need it.
   - `gh workflow disable deploy.yml`. A pipeline run during the incident would create a revision pointing at the old
     database; after a bad migration it would also rebuild the code that still contains it.
   - Deactivate the backend's active revision and check that nothing is left running:
     ```bash
     REV=$(az containerapp revision list -n backend -g $RG --query "[?properties.active].name" -o tsv)
     az containerapp revision deactivate -n backend -g $RG --revision $REV
     az containerapp replica list -n backend -g $RG --revision $REV    # must be empty
     ```
     (Not scaling to zero: Azure rejects `--max-replicas 0`, and `--min-replicas 0` alone lets any request wake the backend,
     and the daily scheduled jobs keep writing.) Users now get errors from the API instead of quietly losing work against a
     database that is about to be replaced. Note the time and tell the business owner. The cutover deploy (step 5) creates a
     new revision, which unfreezes; if it somehow does not, `az containerapp revision activate`.
1. **Restore** (add `--geo-redundant-backup Enabled` if `postgresGeoRedundantBackup` is on, otherwise the new server
   silently has none), then re-create the firewall rules the restore does not copy:
   ```bash
   az postgres flexible-server restore -g $RG -n $NEW --source-server $SRC --restore-time "2026-10-29T10:15:00+00:00"
   az postgres flexible-server firewall-rule create -g $RG -n $NEW --rule-name AllowAllAzureServicesAndResourcesWithinAzureIps \
     --start-ip-address 0.0.0.0 --end-ip-address 0.0.0.0
   az postgres flexible-server firewall-rule create -g $RG -n $NEW --rule-name operator --start-ip-address <your IP> --end-ip-address <your IP>
   ```
2. **Verify the restored server directly.** Do not run the drill on it: the drill compares a database with a copy of itself
   and passes whatever was restored, and it would put a full second copy on the production server. Check with `psql` (the
   pinned client in `restore-drill.sh`): `select max(changed_at) from audit_logs` is just before the restore point; the bad
   change is absent; the row counts of the affected tables match what you expect from the old server; the number of rows in
   `flyway_schema_history` matches the release you are going back to.
3. **Keep what the old server holds.** It has the only copy of everything written after the restore point, including the
   audit trail of the incident itself.
   - Take an encrypted dump of it (`DUMP_OUT`, above) and check it: `gpg --no-symkey-cache -d <dump> | docker run --rm -i
     <pinned client> pg_restore -l | head` lists the tables.
   - Its `CanNotDelete` lock also covers its firewall rules, so to close the old server: `az lock delete --name no-delete -g $RG
     --resource-name $SRC --resource-type Microsoft.DBforPostgreSQL/flexibleServers`, delete **all** its firewall rules (your IP
     and the all-Azure rule), then stop it. **Azure starts a stopped Flexible Server again after 7 days**, so schedule its
     deletion inside that week (step 7).
4. **Move the orphaned document files out of the way** (section B): `infra/ops/prune-orphan-blobs.sh`. Dry run first. It
   copies the files uploaded after the restore point into a holding container before removing them from `documents`.
5. **Cut over with the template, not by hand.**
   - First delete your IP rule from the NEW server (`az postgres flexible-server firewall-rule delete … --rule-name operator`):
     the deploy below puts a lock on it, and a lock also blocks deleting its child resources.
   - Edit `infra/staging.bicepparam`: `postgresServerName = '<NEW>'`, and commit it. The value must live in git: a deploy that
     omits it points the backend back at the OLD server's stale data and everything still "works".
   - Export the current image digests in `BACKEND_IMAGE` / `FRONTEND_IMAGE` (`az containerapp show`, or the last `deploy.yml`
     run summary). **If the cause was a bad deploy, use the PREVIOUS backend digest**: the current image carries the migration
     that did the damage, and Flyway would simply run it again on the restored server.
   - `az deployment group what-if … --parameters infra/staging.bicepparam` must show **no Create of a `flexibleServers`
     resource**. Anything else: stop. (what-if may print `DB_URL` as an unresolved expression, so also check after the deploy.)
   - Deploy. This also gives the backend a new revision (unfreeze). Then confirm: `az containerapp show -n backend -g $RG
     --query "properties.template.containers[?name=='backend'].env[] | [?name=='DB_URL'].value | [0]" -o tsv` contains `$NEW`,
     and the latest revision is the ready one.
6. Run `.github/scripts/smoke.sh`, log in as each role family, open a recent record. `az postgres flexible-server firewall-rule
   list` on **both** servers must show no operator IP. If the cause was a migration, keep the pipeline disabled until the
   migration is fixed in git: the next run would apply it again. Replace or correct the offending `V<n>` file (an exception to
   "migrations are immutable once committed": the database that held it is gone) and record it in ARCHITECTURE.md §8. Then
   `gh workflow enable deploy.yml`.
7. Delete the old server (the lock is already off), once the new one has run cleanly and the step 3 dump and the holding
   container are stored under the dump rules, and within 7 days of stopping it.

### B. Document files

- **Deleted blob, within the retention:** `az storage blob undelete --account-name $ST --container-name documents --name doc-<id> --account-key …`
  (find candidates with `az storage blob list … --include d`). The app deletes a blob only in the "wrong file" window
  (`DocumentStore.delete`); versions are new blobs and never overwrite.
- **After any restore of the database to an earlier point (procedure A, C or D):** the ID counter rewinds, but the blobs
  created after the restore point still exist, and the app uploads with overwrite **off**. Each upload that lands on one of
  those ids fails (and uses the id up), so uploads keep failing until the counter has passed them all. Those blobs are real
  work that the restore rolled back (supplier quotes, drawings, quality evidence), so they are **copied, not just deleted**.
  With the backend **frozen or not yet started**:
  `ST=$ST PGHOST=<new fqdn> PGUSER=<admin> bash infra/ops/prune-orphan-blobs.sh` (dry run: counts and the first names), then
  the same with `APPLY=1`. Each orphan is copied to `documents-incident-<date>` and removed from `documents` only once the
  copy has completed; the script refuses to run if it cannot read the counter or if it would move every blob. The holding
  container follows the dump rules (restricted, kept 90 days, then deleted). A soft-deleted original is **not** a way back:
  once the app reuses the name, a plain undelete no longer restores the old content. Also undelete any blob that was deleted
  after the restore point and is referenced by a restored `documents` row.

### C. The server (or the whole resource group) was deleted

Do **not** redeploy the infra first. The resource names are derived from the resource group, so a rebuilt group recreates
the same storage account name, after which the deleted account (with every document) can no longer be recovered, and the
Key Vault name collides with the soft-deleted vault.

1. **Stop everything that could write.** `gh workflow disable deploy.yml`; if the Container Apps still exist, freeze the backend
   (procedure A, step 0).
2. Postgres only: Azure keeps a dropped server's backups for **5 days**.
   `az postgres flexible-server revive-dropped -g $RG -n $NEW -l <region> --source-server <resource id from the Activity Log>`
   (add `--geo-redundant-backup Enabled` if it was on). Microsoft documents this as best effort; if it fails, open a support
   ticket immediately, because the 5 days run out.
3. Whole resource group: recreate **only the empty group, with the same name.** Recover the Key Vault
   (`az keyvault list-deleted`, `az keyvault recover --name …`) and the storage account (Portal → Storage accounts → Manage
   deleted accounts; there is no CLI command) **before any deployment.** Then revive Postgres as in 2.
4. Only if the revive is impossible (more than 5 days, or it failed): deploy the infra **with no image variables set** (so no
   backend exists to migrate the empty database or seed an admin into it), then restore the newest encrypted dump into the
   empty `ergo_connect`: `gpg --no-symkey-cache -d <dump>.gpg | docker run --rm -i <pinned client> pg_restore --no-owner --no-acl
   --exit-on-error -d ergo_connect …`. Everything since that dump is lost. **Until a scheduled dump exists (open decision 1)
   take one by hand before go-live and before every release.**
5. In every case, before any backend starts: check the restored data (procedure A, step 2), run the section B cleanup (a dry
   run reports no orphans when nothing was lost), then deploy with the image digests (`postgresServerName` set to the revived
   or new server) and re-enable the pipeline.

### D. Region outage

Only if `postgresGeoRedundantBackup` was on when the server was created:
`az postgres flexible-server geo-restore -g <rg> -n <new> --source-server <old resource id> --location <paired region>`,
then, as in C.5, check the data, run the section B cleanup, and deploy the infra and apps in that region with
`postgresServerName=<new>`. Otherwise: new infra plus the latest encrypted dump (C.4). The documents are lost with the
region (`Standard_LRS`).

### E. Lost secrets

`az keyvault recover --name <kv>` (and `az keyvault secret recover`) within 7 days. Otherwise redeploy the infra with the
inputs from the password manager. Rotating `jwtSecret` only signs everyone out.

## Before every production deploy

Take an on-demand restore point: `az postgres flexible-server backup create -g <rg> --name <server> --backup-name pre-<release>`
(the CLI is changing which of `--name` and `--backup-name` means which; check `--help`). Flyway only moves forward: a bad
migration is undone by procedure A back to just before the deploy **with the previous backend image**, never by rolling the
image back alone.

## Drill

`infra/ops/restore-drill.sh` streams a dump of a database straight into a fresh `*_drill` database (nothing is written to local disk) and checks every table's row
count, the Flyway history (checksums), sequence positions, every trigger on every table, and that `audit_logs` still refuses
UPDATE/DELETE/TRUNCATE with its own error. It fails on any query error or empty result, refuses any copy name that does not
end in `_drill`, connects with `verify-full` TLS by default (set `PGSSLMODE=disable` only for a local scratch server) and
asks for the password instead of taking it from your history. Run it against a copy of production data before go-live and
after any major schema change, **not** against the live server. For a fuller check, point a backend at the restored copy and
confirm Flyway reports "up to date".

| Date | Where | Result |
|---|---|---|
| 2026-10-29 | Local Postgres 17, dev data after the browser walk | **Passed.** 64 tables, 173 audit rows, 39 migrations, sequences and triggers identical; backend booted on the copy ("Successfully validated 39 migrations … up to date"), health 200, a seeded user logged in and got a token. Also checked: wrong password and bad copy names fail; a failed encrypted dump leaves no file; the encrypted dump decrypts back to a valid dump; the blob pruning logic with a stand-in `az`. |
| | Azure PITR on staging | **Not yet run.** Do this on the first deploy; it also measures the real RTO, and proves the freeze, the cutover deploy and the lock behaviour. |

## Teardown of a staging environment

The locks stop `az group delete`. Remove them first:
`az lock delete --name no-delete -g <rg> --resource-name <psql…> --resource-type Microsoft.DBforPostgreSQL/flexibleServers`
and the same with `--resource-name <st…> --resource-type Microsoft.Storage/storageAccounts`. Do not use a `ReadOnly` lock:
it blocks `listKeys` and the template's own writes.

## Open decisions

1. **Scheduled logical dump** (a Container Apps job running the encrypted dump to an immutable store outside the main
   resource group). Not built: it needs the retention and ownership decisions in "Handling a logical dump".
2. **RPO/RTO, retention and geo-redundancy** (above): the client should confirm. Geo-redundancy must be decided before the
   production server is created.
3. **A second copy of the documents** (`Standard_GRS` or object replication, blob versioning), if losing a file past the
   soft-delete window is not acceptable.
4. **Uploads after a restore.** The manual blob cleanup (section B) exists because uploads use overwrite off; a code change
   could remove the step. Left as is: overwrite off protects against ever mixing two documents' contents.
