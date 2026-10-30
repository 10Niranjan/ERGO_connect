# Load testing (Phase 8o)

Tooling, a first local run, and what it found. **Status (2026-10-29): the first local run is done; the staging run is not (nothing is deployed). The thresholds below were
NOT met by the dashboard and the RFQ list (finding 1). A fix is built but UNCOMMITTED and not yet re-measured (see "Fix in
progress"). Do not loosen the thresholds to make them pass.**

## Tooling

`loadtest/` holds two [k6](https://k6.io) scripts, run from the pinned container so nothing is installed:

```bash
K6=grafana/k6@sha256:3e4186aea62e11a45560bfc51e19eeafe82fc07ce069c78aff44b7ff893d9a5e   # k6 v2.3.0
# 1. A throwaway backend (dev profile seeds the demo users):
java -jar app.jar
# 2. Volume: RFQs created through the real API (3 parts each, so every row and audit entry a real RFQ makes exists)
docker run --rm -i -e BASE_URL=http://host.docker.internal:8081 -e RFQS=5000 $K6 run --quiet - < loadtest/seed.js
# 3. The test
docker run --rm -i -e BASE_URL=http://host.docker.internal:8081 $K6 run --quiet - < loadtest/hot-paths.js
```

`hot-paths.js` runs two scenarios. **browse**: ramps to `PEAK_VUS` (50) users, one per audience (ERGO management, KAM,
customer, supplier), each opening the dashboard, polling the notification bell, working their list, opening the document
library and notifications, with 1 s think time. **login**: 5 logins a second for 45 s (bcrypt is deliberately slow and
CPU-bound, so it is measured on its own). `SKIP_LIST=1` and `SKIP_LOGIN=1` separate the costs. Thresholds (p95): dashboard
and lists 1 s, unread count 300 ms, whole browse scenario 800 ms, login 1.5 s, error rate under 1%.

**Never run it against production.** Both scripts refuse any target that is not localhost / `host.docker.internal` unless
`TARGET_CONFIRM` repeats the exact `BASE_URL`, and refuse the published demo password off localhost. The demo users exist only
under the dev profile, so staging needs **dedicated load-test accounts**: a throwaway test customer and test supplier, ERGO
accounts on a domain ERGO controls (not the demo `.dev` addresses, which belong to someone else and would receive any reset
or code mail), a generated password passed in `PASSWORD`, listed in `USERS` / `SUPPLIER_USERS` (and `BUYERS` for the seed).
Deactivate them after the run. The login limiter stays on: a successful login hands its slot back, so the 5-a-second login
scenario should not trip it; if it does, that is a finding, not a reason to loosen the limiter.

## First local run (2026-10-29)

One 8-core / 16 GB Windows laptop running **everything**: the backend (`-Xmx1g`), Postgres 17 and Redis in Docker, and k6 in
Docker. So the numbers are a floor for contention and a ceiling for hardware; they find code-level problems, they do not size
the Azure environment. Data: 5,020 RFQs with 15,060 parts, 35,000 audit rows, a 25 MB database.

| p95 | One request, idle | 20 users | 50 users | 50 users, lists skipped |
|---|---|---|---|---|
| Dashboard | 85-140 ms | 568 ms | 2.3 s | 1.4 s |
| Work list (RFQs / quotes) | 70-145 ms | 559 ms | 2.1 s | n/a |
| Unread-count poll | under 10 ms | 37 ms | 1.35 s | 0.62 s |
| Whole browse scenario | | 377 ms | 1.8 s | 0.97 s |
| Errors | 0 | 0% | 0% | 0% |
| Login, 5 a second for 45 s | | 145 ms, 226 of 226 ok | 0.74-1.0 s | |

All thresholds hold at 20 users and fail at 50: with these payloads the knee on this machine is somewhere between. The 20-user
run used the **default** login limiter (226 successful logins, none refused: a successful login hands its slot back).

Response sizes: dashboard **1.47 MB** (customer), 541 KB (KAM); RFQ list **1.16 MB** (ERGO management), 583 KB (KAM),
478 KB (customer). 1.2 GB were moved in the three-minute run.

## Findings

1. **The dashboard and the RFQ list are unbounded.** The dashboard returns every RFQ and every part the user can see
   (2,513 RFQs and 7,539 parts for one customer) and the frontend renders every row in a table. The management and
   functional DTOs carry KPI counts, but `DashboardService` computes them from those full lists; the customer and supplier
   DTOs carry no counts at all.
   Under load even the trivial unread-count poll slows to over a second, because the server is busy serialising megabytes.
   This is the big one. The fix is a product decision, so it is **not** made here: cap each section to the items that need
   attention (overdue and awaiting-me first), add a total to each section (computed with COUNT queries in each module's API,
   not from the lists), and link "view all" to the list screen; paginate `GET /api/rfqs`. Truncating on the server without totals would make the KPI tiles and counts silently wrong, which is
   worse than slow. Other lists were not measured with volume (NPD projects, OFP orders, NCRs, deviations, inspections; the
   document library and notifications ran on nearly empty data) and probably have the same shape: check each before go-live.
2. **Compression was off.** Added to nginx (`gzip` for JSON, JS, CSS, SVG): the dashboard goes from 1,466,490 to 96,106 bytes
   (15x), the RFQ list from 477 KB to 38 KB, the JS bundle from 1.16 MB to 389 KB. Verified through the built frontend image;
   security headers unchanged. This is the main gain for a real user on a real connection, but it does not remove the server
   work in finding 1.
3. **No errors under 50 users, and login is fine**: bcrypt at 5 logins a second stayed under the threshold even with the CPU
   contended. The rate limiters (login 20 per 5 minutes per IP) were switched off for the run, as they must be.
4. **Load-test mistake worth keeping:** suppliers are refused on `/api/rfqs` by design (they use `/api/quotes`); the first run
   sent them there and 30% of the list calls (4.7% of all requests) failed with 403, all of them the supplier's. The script now
   uses each audience's own list.

## Not measured, and what the staging run needs to cover

- Real hardware: backend 1 vCPU / 2 Gi, Postgres **Burstable B1ms** (CPU credits run out under sustained load; watch the
  credit metric), Azure Managed Redis `Balanced_B0`. Expect different numbers; the thresholds stay.
- The login limiter and the Container Apps ingress in the path (the nginx hop is plain HTTP inside the environment).
- The 45 s notification poll at the real number of open tabs (200 users is about 4.4 requests a second), alongside the
  browse load. Uploads (50 MB held in memory by design) and the malware scan: a few concurrent large uploads are the likely
  memory risk on a 2 Gi container; test them separately before go-live.
- Hikari pool (10 connections by default) and Tomcat threads were not tuned or instrumented. The dashboard holds a connection
  for its whole read-only transaction, so the unread-count slowdown may be pool starvation rather than CPU: expose
  `hikaricp_connections_pending` (actuator metrics are off) and look at it on the next run.
- Re-run after finding 1 is fixed: the thresholds should then hold with room to spare, and that is the real acceptance test.

## The fix (commit c958e0e, 2026-10-29)

Built after the first run, at the user's request (the product decision in finding 1 was delegated):

- **Dashboard.** Every list section returns at most `ergo.dashboard.max-rows` (default 25) rows, ranked by `AttentionOrder`:
  already overdue / at risk first, then waiting on the viewer's own side, then soonest due date, then newest. Each carries its true
  size in a sibling `...Total` field (`rfqTotal`, `partTotal`, `quoteTotal`, `delayedNpdTotal`, ...). KPI counts are computed from the
  full lists before capping, so they do not change. The screen says "Showing the N that need attention first, of M" and links "View
  all" to the module's list. The fields keep their names, so the JSON paths used by existing tests still hold.
- **RFQ list.** `GET /api/rfqs?page=&size=&status=&q=` returns `{ items, total, page, size }`: record scope in the query, status
  filter in the caller's own vocabulary (a customer sending an internal status gets 422), search over RFQ number, part number and, for
  ERGO only, customer name (via `Directory.companyIdsByName`), `%` `_` `\` treated literally, size capped at 100. The list screen has
  a page selector, a debounced search box and the status filter.
- **Tests added:** `AttentionOrderTest` (3), `DashboardCapTests` (3), `RfqListPagingTests` (12). Full `./mvnw verify`: 755 tests, 0 failures. `security-reviewer`: no High/Medium; four Low items fixed.

## Re-run after the fix (2026-10-29)

Same machine, same 5,020-RFQ database, same script, 50 users at peak, default login limiter. Backend jar built from the working tree.

| p95 | Before (50 users) | After (50 users) | Threshold |
|---|---|---|---|
| Dashboard | 2.3 s | **818 ms** | 1 s ok |
| Work list | 2.1 s | **318 ms** | 1 s ok |
| Unread-count poll | 1.35 s | **375 ms** | 300 ms **crossed** |
| Whole browse scenario | 1.8 s | **543 ms** | 800 ms ok |
| Login, 5 a second | 0.74-1.0 s | **256 ms**, 225 of 225 ok | 1.5 s ok |
| Errors | 0% | 0.01% (2 of 11,875) | 1% ok |

- The dashboard and list findings are fixed: the 50-user run now passes every threshold but one, with room to spare on the others.
- **Unread-count 375 ms vs 300 ms.** It is a tiny query (6.9 ms median), so the p95 is queueing on a laptop that is also running k6, Postgres, Redis and the JVM; it fell from 1.35 s. Not a code finding. Judge it on the staging run, not here.
- **Two failures** were `dial: i/o timeout` on the supplier's `/api/quotes` (connection level, none in the backend log). That list is still unbounded; watch it on the staging run.
- Still unmeasured: real hardware (see above) and the other unbounded lists.

**Still to do:** (1) the staging run; (2) the lists that are **still unbounded**, in the order they are likely to hurt: ~~the supplier quote list `/api/quotes`~~ (paged, done: `QuoteService.mine`, search through `RfqApi.matchingItems`; not yet re-measured), ~~OFP orders~~ (paged, done: `OrderService.list`, with a server-side `delayed` filter; not yet re-measured), ~~NPD projects~~ (paged, done: `NpdService.list`, ERGO list only; `/mine` stays small per company; not yet re-measured; a search still reads every project's RFQ item id once to ask `RfqApi.matchingItems`, ids only; the RFQ match also covers item description), ~~sample and production POs~~ (paged, done: `SamplePoService.list`, `ProductionPoService.list`; not yet re-measured), ~~NCRs~~ (paged, done: `NcrService.list`, one row shape per audience; not yet re-measured), ~~deviations~~ (paged, done: `DeviationService.list`; not yet re-measured), inspections. Use the RFQ list as the pattern (declare only `findAll(Specification, Pageable)` on the repository).
