# Production operations checklist (interim stack: Oracle Micro VM + Neon)

Written 2026-11-06. No secrets here: the live values are in the owner's private files (`~/.ergo-prod-micro.env`,
`~/ERGO-prod-credentials.txt`, `~/ERGO-backup-key.txt`) and on the VM in `~/ergo-prod/.env`.

## What runs where
| Piece | Where | Notes |
|---|---|---|
| App (backend, frontend, Redis, Caddy HTTPS) | Oracle Always Free Micro VM, `ubuntu@130.210.58.133`, files in `~/ergo-prod/` | compose: `deploy/docker-compose.micro-prod.yml`; 1 GB RAM + 2 GB swap |
| Database and uploaded documents | Neon project `ergo-prod`, db `ergo_connect` (free, 1 GB shared) | roles `ergo_owner` (migrations) / `ergo_app` (app); TLS verify-full |
| Email (login/reset notices) | Brevo SMTP, sender `ergo.administrator@gmail.com` | Brevo only accepts SMTP from authorized IPs (Security > Authorized IPs) |
| Backups | cron on the VM, 21:30 UTC nightly, encrypted, to Oracle bucket `ergo-prod-backups` | private key is NOT on the VM |
| Address | `https://130-210-58-133.sslip.io` | ephemeral IP: lost if the VM is deleted; see "Changing the address" |

## Dates to remember
| When | What | How |
|---|---|---|
| ~2026-11-11 | GitHub token on the VM (`read:packages`, `~/.docker/config.json`) expires. The running site is unaffected; pulling a NEW version fails until it is replaced. | The repo owner creates a new classic token with only `read:packages`, then on the VM `docker login ghcr.io -u <owner>`. Alternative: make the two image packages public (they hold the app code, decide first). |
| 2027-11-05 | Backup upload link (Oracle pre-authenticated request) expires; backups would fail from then on. | `oci os preauth-request create --namespace-name bmb8p8yllowc --bucket-name ergo-prod-backups --name ergo-server-upload-2 --access-type AnyObjectWrite --time-expires <date>`; put `https://objectstorage.ap-mumbai-1.oraclecloud.com` + the returned `access-uri` into `BACKUP_PAR_URL` in the VM `.env`. |
| 2027-11-06 | Brevo SMTP key `ergo-connect-prod` expires; all email stops (reset links, admin notices). | Brevo > SMTP & API > generate a key (an emailed code confirms it), update `MAIL_PASSWORD` in the VM `.env`, `docker compose -f docker-compose.micro-prod.yml up -d`. |
| Before real customers | Turn the admin emailed code back on. | Delete the `ERGO_SECURITY_ADMIN_2FA_REQUIRED` line from the compose file on the VM, then `up -d`. The admin then needs the code from the Brevo email at each login. |

## Regular checks
- **Weekly:** `ssh ubuntu@130.210.58.133 'tail -5 ~/ergo-backup.log'` shows last night's "uploaded off the machine" line. No alert exists yet (a free healthchecks.io check on `BACKUP_PING_URL` would add one).
- **Weekly:** Neon console > storage, under 70% of 1 GB. Writes fail at 100% (uploads, audit rows, everything). Raise a plan or move documents out before then.
- **Monthly:** redeploy the latest green build so image CVE fixes land (below). Dependabot opens the version bumps; CI's Trivy gate blocks vulnerable builds.
- **Quarterly:** restore drill. Copy the newest object from the bucket, `gpg --decrypt` with the private key, `pg_restore` into a scratch Postgres, check `flyway_schema_history` and the user count.
- **After any VM replacement, IP change or move:** see below.

## Deploying a new version
1. Push to `sprint-5-demo`; wait for the "Demo images" workflow to go green (tests + Trivy scans).
2. Put the full commit sha in `IMAGE_TAG` in the VM `.env`.
3. On the VM: `cd ~/ergo-prod && ./backup.sh` (migrations cannot be undone), then
   `docker compose -f docker-compose.micro-prod.yml pull backend frontend && docker compose -f docker-compose.micro-prod.yml up -d`.
4. The backend needs ~2 minutes to start on this VM. `docker logs ergo-prod-backend-1 | grep Started`, then open the site.

## Changing the address (real domain, or a replaced VM)
1. Point the new name's DNS A record at the VM's public IP (use a reserved IP so it survives a rebuild).
2. Set `DOMAIN=` in the VM `.env`; `up -d` (Caddy gets a new certificate by itself; ports 80 and 443 must be open).
3. Brevo > Security > Authorized IPs: add the new public IP (SMTP from any other IP is refused).
4. Anything that bookmarked the old name stops working: tell the users.

## If the VM is lost
Create a new Ubuntu VM, install Docker, copy `docker-compose.micro-prod.yml`, `Caddyfile` and the `.env` (from the owner's private copy), `docker login ghcr.io`, `up -d`. The database is in Neon, so nothing else is needed unless Neon is lost too: then restore the newest bucket backup into a new Neon database (roles as in `deploy/README.md`), with the private key.

## Known limits of this interim setup
Free-tier sizes (1 GB VM, 1 GB database), a single server with no failover, malware scanning off (client decision), and Oracle may reclaim an Always Free VM it considers idle for 7 days (CPU, network and memory all under 20%; this VM uses ~60% of its memory). The target remains the Oracle Ampere A1 stack (`deploy/docker-compose.oracle.yml`) when capacity allows.
