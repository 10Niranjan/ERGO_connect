# Client demo deployment (one VM, Docker Compose)

**Production is the last section, "Production on Oracle".** Everything above it is the demo.

Free path: an Oracle Cloud Always Free ARM VM (Ubuntu 22.04, 4 CPU / 24 GB), or any Docker host with 4 GB+ RAM.
Not the Azure production path (see `infra/`). This stack runs the **dev profile** so documents live in Postgres and
no Azure/SMTP secrets are needed. Only Caddy (80/443) is exposed.

## 1. Server (once)
1. Create the VM. In the cloud firewall / security list open TCP **22, 80, 443**; also on the VM: `sudo iptables -I INPUT -p tcp -m multiport --dports 80,443 -j ACCEPT` (Oracle images block them by default), then save the rules.
2. Hostname: no signup needed with sslip.io. For public IP 130.61.5.9 use `130-61-5-9.sslip.io` as DOMAIN (resolves to that IP; Caddy gets a real certificate for it). Or use your own domain / a `name.duckdns.org` pointing at the IP.
3. Install Docker: `curl -fsSL https://get.docker.com | sh && sudo usermod -aG docker $USER` (log out and in).
4. `git clone -b sprint-5-demo https://github.com/varadyadav02/ERGO_connect.git && cd ERGO_connect/deploy`

## 2. Configure and start
```bash
cp .env.example .env        # set DOMAIN, DB_PASSWORD, JWT_SECRET  (openssl rand -base64 36)
docker compose -f docker-compose.prod.yml up -d --build   # first build ~10 min; ClamAV needs 1-3 min to become healthy
docker compose -f docker-compose.prod.yml ps               # wait until clamav + backend show (healthy)
```
Flyway migrations and the 20 demo personas are created on first backend start.

## 3. Load the base data (once, after the backend is healthy)
Master data, 8 companies and ~30 users. (Seed phases 2-6, sample RFQs/NPD/orders, still target the pre-flow-alignment API and fail; do the RFQ -> order story live instead.)
```bash
docker compose -f docker-compose.prod.yml exec -T postgres psql -U ergo -d ergo_connect < ../ergo-connect-backend/docker/seed/00-sessions.sql
docker run --rm --network ergo-demo_default -e API_URL=http://backend:8080/api -e SEED_JWT_SECRET="$(grep ^JWT_SECRET .env | cut -d= -f2-)"   -v "$PWD/../ergo-connect-backend/docker:/s" -w /s node:22-alpine node seed-demo-data.mjs --only=1
```
Run `00-sessions.sql` before anything logs in. Logins: the demo users (`admin@ergo.dev`, `buyer@acme.dev`, `sales@precision.dev` ...), password `ErgoDev#2026`.

## 4. Smoke test (do this the day before, then reboot the VM and repeat 3-5)
1. `https://DOMAIN` loads the login page with a valid certificate.
2. Log in as admin, a customer buyer, and a supplier; each sees only its own data (supplier never sees the customer).
3. Upload a normal PDF to an RFQ: saves. Upload the EICAR test file (`X5O!P%@AP[4\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*` in a .txt): **rejected** by malware scanning. (Only with the full stack above. In Variant B scanning is OFF, so EICAR is accepted: skip this check.)
4. Walk RFQ -> quote -> customer approval -> NPD -> order -> quality, checking the dashboards and the notification bell.
5. `docker compose -f docker-compose.prod.yml logs --tail=100 backend` shows no ERROR lines.

## 5. Backup / reset / stop
```bash
docker compose -f docker-compose.prod.yml exec -T postgres pg_dump -U ergo ergo_connect > demo-clean.sql   # after the rehearsal
docker compose -f docker-compose.prod.yml down            # stop (data kept);  add -v to wipe everything
```

## Known limits (demo box, by design)
- Dev profile: the demo personas share the published password and 2FA is skipped. Share the URL only with the client, and wipe or `down` the stack after the demo.
- No password-reset email (no SMTP). ClamAV signatures are the ones bundled in the image.
- Redis/Postgres are not exposed; backups are manual (step 5).

## Clean start / reset to clean
A new VM is already clean: the Postgres volume is created empty, Flyway builds the schema, and the only rows are the 20 demo users (DevDataSeeder), base lookup lists, and whatever step 3 loads. No RFQs, quotes, orders or documents exist until someone creates them.
To get back to exactly that state at any time (this DELETES all data and uploaded files):
```bash
docker compose -f docker-compose.prod.yml down -v      # -v drops the database and certificate volumes
docker compose -f docker-compose.prod.yml up -d        # then repeat step 3
```
Dropping `caddy_data` forces a new certificate request (Let's Encrypt allows 5 per week per hostname); to keep the certificate, instead run `docker volume rm ergo-demo_pgdata` after `down`.

## Variant B: 1 GB VM (Oracle Always Free Micro) + Neon Postgres, malware scanning OFF
For when no 4 GB host is available. **Untested on a real Micro**: memory is tight (about 1 GB total) and the CPU is slow (first backend start runs every Flyway migration and may take minutes). Files: `docker-compose.micro.yml`, `micro.env.example`.
- **Scanning is OFF** (`ERGO_CLAMAV_ENABLED=false`, allowed only under the dev profile): uploaded files are not checked. Share the URL only with the client, wipe the data after, and never migrate this data into a real environment (nothing records which files went unscanned).
- Nothing is built on the VM: `.github/workflows/demo-images.yml` pushes `ghcr.io/<owner>/ergo-connect-{backend,frontend}:demo`. Both packages must be Public (GitHub > Packages > package settings), or run `docker login ghcr.io` on the VM first.
1. VM: ports 22/80/443 open (cloud list + `iptables`, as in section 1), Docker installed, and **2 GB swap**: `sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile && echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab`.
2. Neon: create a free project and use the **direct (non-pooled)** connection details. `DB_URL` is a JDBC URL with `?sslmode=require`.
3. `git clone -b sprint-5-demo https://github.com/varadyadav02/ERGO_connect.git && cd ERGO_connect/deploy && cp micro.env.example .env` and edit it (DOMAIN, DB_URL, DB_USERNAME, DB_PASSWORD, JWT_SECRET).
4. `docker compose -f docker-compose.micro.yml pull && docker compose -f docker-compose.micro.yml up -d`, then `docker compose -f docker-compose.micro.yml logs -f backend` until it reports started.
5. Base data (once, after the backend is healthy), against Neon and the running backend:
```bash
docker run --rm -i postgres:17-alpine psql "postgresql://USER:PASSWORD@NEON_HOST/DBNAME?sslmode=require" < ../ergo-connect-backend/docker/seed/00-sessions.sql
docker run --rm --network ergo-micro_default -e API_URL=http://backend:8080/api -e SEED_JWT_SECRET="$(grep ^JWT_SECRET .env | cut -d= -f2-)" -v "$PWD/../ergo-connect-backend/docker:/s" -w /s node:22-alpine node seed-demo-data.mjs --only=1
```
6. Smoke test as in section 4 (skip the EICAR check). Neon suspends when idle, so the first request after a quiet spell is slow. Check `docker stats --no-stream` for memory headroom.

## Production on Oracle (`docker-compose.oracle.yml`)
The production stack (ARCHITECTURE.md §8 2026-11-05): one Oracle VM, **prod profile** (no demo users, 2FA, reset emails), Postgres on the VM holding data and documents, **malware scanning OFF** (accepted risk: uploads are stored unscanned), app images from GHCR pinned to a commit, third-party images pinned by digest. Files: `docker-compose.oracle.yml`, `oracle.env.example`, `db-init/`, `backup.sh`. Not yet run on a real VM.

Three database logins (`db-init/10-roles.sh`, created on the first start): `postgres` (superuser, backups and admin only), `ergo_owner` (owns the schema, used only by Flyway) and `ergo_app` (what the app connects as: reads and writes rows, owns nothing, so it cannot drop tables or switch off the `audit_logs` append-only trigger). Postgres and Redis sit on an internal network that only the backend can reach.

1. **VM:** Ubuntu on the free-tier **Ampere A1** shape (arm64; e.g. 2 OCPU / 12 GB, the whole free A1 allowance) or any x86 shape with 2 GB+ RAM; the images are built for both. Not the 1 GB Micro (too small for Postgres + Redis + backend). A1 often reports "Out of host capacity": retry, or try another availability domain. Oracle can reclaim idle Always Free VMs; upgrading the account to Pay As You Go stops that and the free allowance stays free (set a budget alert). Ports 22/80/443 open (cloud list + `iptables`, as in section 1), Docker installed, 2 GB swap (Variant B step 1). Keep the VCN IPv4-only: with IPv6 every user would show up as one internal IP in the audit log and share one login rate limit. Point the domain's DNS A record at the VM's public IP.
2. **Images:** a push to `sprint-5-demo` runs CI (tests + dependency CVE check) and only then builds the images, tagged with the commit sha (`.github/workflows/demo-images.yml`). Use as `IMAGE_TAG` the sha of a run where the whole workflow is green. The packages are private: `docker login ghcr.io` on the VM with a `read:packages` token.
3. **Configure:** `git clone -b sprint-5-demo https://github.com/varadyadav02/ERGO_connect.git && cd ERGO_connect/deploy && cp oracle.env.example .env && chmod 600 .env`, then fill every value (secrets with `openssl rand`). SMTP is required: the app refuses to start without it.
4. **Start:** `docker compose -f docker-compose.oracle.yml pull && docker compose -f docker-compose.oracle.yml up -d`, then `docker compose -f docker-compose.oracle.yml logs -f backend` until `Started ErgoConnectApplication` (first start runs every migration). Log in as `BOOTSTRAP_ADMIN_EMAIL`, set a new password, then **delete both `BOOTSTRAP_ADMIN_*` lines from `.env`** and `docker compose -f docker-compose.oracle.yml up -d` again. Create companies and users from the Admin screens; do not run the demo seed here.
   **Smoke test:** the login page loads at `https://DOMAIN` with a valid certificate, and `curl -sI https://DOMAIN | grep -i strict-transport` shows HSTS. After logging in from the browser, the session must carry your real public IP, not a `172.x` address: `docker compose -f docker-compose.oracle.yml exec postgres psql -U postgres -d ergo_connect -c 'select ip_address, created_at from user_sessions order by session_id desc limit 3'`. Never add `trusted_proxies` to the Caddyfile: Caddy then stops replacing a client-supplied `X-Forwarded-For`, and anyone could fake their IP in the audit log. The bootstrap login's emailed code also proves that outbound SMTP works; if it never arrives, check the SMTP values and that the VM's egress rules allow port 587.
5. **Backups (once):**
   - **Key:** on your own machine (not the VM), run `gpg --quick-gen-key backups@<your-domain>`. Keep the private key safe and off the VM: losing it loses every backup. Run `gpg --export --armor backups@<your-domain> > backup-public.asc`, copy that file to the VM, run `gpg --import backup-public.asc` there, and set `BACKUP_GPG_RECIPIENT` in `.env`.
   - **Bucket:** create a private Object Storage bucket (ideally in another region). Add a **retention rule** (for example 30 days, then lock it) so nothing can delete or overwrite a backup during that time, plus a lifecycle rule that deletes objects after, for example, 90 days.
   - **Upload-only identity:** create a dedicated IAM user (not your own) in a group whose only policy is
     `Allow group ergo-backup-writers to manage objects in compartment <compartment> where all {target.bucket.name='<bucket>', any {request.permission='OBJECT_CREATE', request.permission='OBJECT_INSPECT'}}`.
     Give that user a Customer Secret Key, then on the VM run `sudo apt install rclone && rclone config` (type `s3`, provider `Other`, endpoint `https://<namespace>.compat.objectstorage.<region>.oraclecloud.com`, that key, and in the advanced options `no_check_bucket = true`, since this user may not look up or create buckets). Set `BACKUP_RCLONE_REMOTE=<remote>:<bucket>` in `.env`. Someone who takes over the VM can then add backups but cannot read or delete the existing ones.
   - **Alert on missed backups:** create a check at a cron monitor (for example healthchecks.io) that emails you when a daily ping is missed, and put its URL in `BACKUP_PING_URL`. Add a plain uptime check on `https://DOMAIN` there too: nothing else on this path alerts.
   - Run `./backup.sh` once by hand and **check in the Oracle console that the object appeared in the bucket** and that the monitor received the ping. Then add it to cron (the line is at the top of `backup.sh`). It keeps 14 days on the VM, and refuses to report success while `BACKUP_RCLONE_REMOTE` is empty.
   - Keep a copy of the VM's `.env` in a password manager: it exists nowhere else.
6. **Upgrade:** run `./backup.sh` first (new migrations cannot be undone). Then set the new `IMAGE_TAG` in `.env` and run `docker compose -f docker-compose.oracle.yml pull backend frontend && docker compose -f docker-compose.oracle.yml up -d`. Bump the pinned Postgres/Redis/Caddy digests only on purpose, one at a time.
7. **Restore.** Restore into a new database and swap it in, so the live database is never half-overwritten. On the VM, in `deploy/`, with `DC="docker compose -f docker-compose.oracle.yml"`:
   1. Stop the app and back up its current state: `$DC stop backend && ./backup.sh`.
   2. Create an empty database: `$DC exec -T postgres psql -U postgres -c 'CREATE DATABASE ergo_restore OWNER ergo_owner'`.
   3. From the machine holding the private key (the plaintext is only streamed, never written):
      `gpg --decrypt ergo-<time>.dump.gpg | ssh ubuntu@<vm> 'cd ERGO_connect/deploy && docker compose -f docker-compose.oracle.yml exec -T postgres pg_restore -U ergo_owner -d ergo_restore --no-owner --single-transaction --exit-on-error' && echo RESTORE OK`.
      Without `RESTORE OK`, stop: drop `ergo_restore` and start the backend again on the untouched database.
   4. Swap it in and reapply the grants:
      `$DC exec -T postgres psql -U postgres -v ON_ERROR_STOP=1 -c 'ALTER DATABASE ergo_connect RENAME TO ergo_connect_old' -c 'ALTER DATABASE ergo_restore RENAME TO ergo_connect'`
      (if the second rename fails, no `ergo_connect` exists: rename `ergo_connect_old` back before anything else), then
      `$DC exec -T postgres psql -U postgres -d ergo_connect -v ON_ERROR_STOP=1 -f /docker-entrypoint-initdb.d/grants.sql.txt`.
   5. End every login, one-time code and reset link restored from the backup: `$DC exec -T postgres psql -U postgres -d ergo_connect -c 'DELETE FROM user_sessions' -c 'DELETE FROM otp_codes' -c 'DELETE FROM password_reset_tokens'`, and set a new `JWT_SECRET` in `.env`.
   6. `$DC up -d backend`, log in and check the data. Once you are sure, `$DC exec -T postgres psql -U postgres -c 'DROP DATABASE ergo_connect_old'`.

   **VM lost:** build a new VM with steps 1-4 from your saved `.env`, with **no** `BOOTSTRAP_ADMIN_*` lines. Download the latest backup from the bucket (console or `rclone copy`) to the machine holding the key, then do steps 7.2-7.6. Rehearse a restore once on a spare VM before you need it.

**Known limits:**
- No malware scanning (client decision). Nothing records which files were stored unscanned, so turning ClamAV on later does not check old files.
- No built-in alerting, only the monitor from step 5.
- Flyway's `ergo_owner` password is in the backend's environment. Someone who could run code inside the backend could still alter the schema; SQL injection cannot, because it runs as `ergo_app`.
- One VM: losing it means restoring from the off-machine backup.
