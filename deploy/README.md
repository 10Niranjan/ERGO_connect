# Client demo deployment (one VM, Docker Compose)

Free path: an Oracle Cloud Always Free ARM VM (Ubuntu 22.04, 4 CPU / 24 GB), or any Docker host with 4 GB+ RAM.
Not the Azure production path (see `infra/`). This stack runs the **dev profile** so documents live in Postgres and
no Azure/SMTP secrets are needed. Only Caddy (80/443) is exposed.

## 1. Server (once)
1. Create the VM. In the cloud firewall / security list open TCP **22, 80, 443**; also on the VM: `sudo iptables -I INPUT -p tcp -m multiport --dports 80,443 -j ACCEPT` (Oracle images block them by default), then save the rules.
2. Point a hostname at the VM's public IP (own domain A record, or a free `name.duckdns.org`).
3. Install Docker: `curl -fsSL https://get.docker.com | sh && sudo usermod -aG docker $USER` (log out and in).
4. `git clone -b "sprint-5-(demo-branch-)" https://github.com/varadyadav02/ERGO_connect.git && cd ERGO_connect/deploy`

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
3. Upload a normal PDF to an RFQ: saves. Upload the EICAR test file (`X5O!P%@AP[4\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*` in a .txt): **rejected** by malware scanning.
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
