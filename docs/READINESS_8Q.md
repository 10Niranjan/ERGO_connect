# 8q: final readiness check against SRS §21 (2026-11-06)

SRS §21 lists six conditions "before go-live". This is the evidence for each, on the deployed system
(`https://130-210-58-133.sslip.io`, commit `c477b11` when tested; production now runs `9409ef0` with the 8p fixes, Oracle Micro VM + Neon). **This report is the evidence; the sign-off
itself belongs to the owner and the client.** Status: **Ready**, **Partly** or **Open**.

| # | SRS §21 condition | Status | Evidence | What is missing |
|---|---|---|---|---|
| 1 | Role access validated | **Ready** (automated), not yet on live users | 17 of the 18 roles are active (`CUSTOMER_MANAGEMENT` is inactive for the MVP by SRS §9.2). 774 backend tests, including the adversarial campaign for every role, company scope, hidden-field and approval rules (`docs/PERMISSIONS.md`, QA campaign). ZAP scans signed in as customer, supplier and each ERGO role: 0 FAIL (`docs/SECURITY_PASS_8P.md`). Live: one login per role was created and tested on 2026-11-06, then removed; every probed API endpoint refuses anonymous and forged tokens. | A role-by-role walk with real users on the production instance. |
| 2 | Workflow tested end-to-end | **Partly** | The six flow slices (On Hold decision, multi-item RFQ and quotes, Sample PO and plan release, plan agreement and execution gate, dual quality gate and final approval, production PO) are covered by automated tests, and a full browser walk of the UI was completed and its findings closed on 2026-11-01 (dev data). | Never run end-to-end on the **production** instance: it has no pilot users or catalog data yet (master data holds only starter entries). |
| 3 | Supplier and customer pilot tested | **Open** | No pilot has taken place. | Create named pilot users (one customer company, one supplier company, ERGO staff), run a scripted RFQ-to-closure with them, record issues. Only people can do this. |
| 4 | Audit logs verified | **Ready** | `audit_logs` rejects UPDATE, DELETE and TRUNCATE even for the schema-owner login (proved on the live database); every action of 2026-11-06 is in it (bootstrap, user create/role/delete with reasons, master-data adds, passwordless admin sign-ins); the real client IP is recorded; automated tests cover audit and versioning per module. | Nothing blocking. Audit export/retention policy is not defined. |
| 5 | Data security tested | **Ready** | `docs/SECURITY_PASS_8P.md` live run: TLS 1.2/1.3 only, HSTS, secure cookies, no exposed paths or internals, CORS closed, login rate limiting and lockout proven, least-privilege database logins, Trivy-scanned images (14 CVEs found and fixed), server hardened (key-only SSH, firewall, auto security updates). Passive ZAP: 0 FAIL, 59 PASS. Findings F1-F5 fixed and live since 2026-11-07 (full CSP, self-hosted font, header and server hardening). | Active scanning of production and a third-party penetration test were not done. |
| 6 | Backup and recovery plan ready | **Ready** | Nightly encrypted dump (03:00 IST) uploaded off-site to a retention-locked Oracle bucket; **restore drill passed on 2026-11-06** (decrypted on a separate machine and restored: 65 tables, schema v43); runbooks in `docs/OPS_CHECKLIST.md`, `docs/BACKUP_AND_RECOVERY.md`, `deploy/README.md`. | No failure alert yet (needs a monitoring account); the private backup key must be copied off the owner's PC; recovery point is 24 h; one region, one VM. |

## Recommendation
- **Ready for a controlled pilot** with named users (conditions 1, 4, 5, 6 are met; this is what condition 3 needs).
- **Not yet ready for open go-live.** Before real customers are onboarded:
  1. Run the pilot (conditions 2 and 3) on this instance, and fix what it shows.
  2. Re-enable the ERGO Admin emailed code (`ERGO_SECURITY_ADMIN_2FA_REQUIRED` line, ARCHITECTURE.md §8 2026-11-06).
  3. ~~Close security findings F1-F5~~ Done 2026-11-07 (`37b3b39`, live).
  4. Load the client's real materials, processes, coatings and payment terms, and current exchange rates.
  5. Move to a real domain on a reserved IP, and add a backup failure alert.
  6. Copy the backup key off the PC.

## Known limits of the interim platform
Free-tier sizes (1 GB VM, 1 GB shared database), one server with no failover, malware scanning off (client decision), and the
target Oracle Ampere A1 stack not yet provisioned (no capacity in Mumbai). None of these blocks a pilot; the 1 GB database is
the first to hit a limit if users upload many drawings and models.
