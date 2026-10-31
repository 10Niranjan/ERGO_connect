// Fills a TEST database with realistic volume so list and dashboard endpoints are measured against more than a handful
// of rows: RFQs created through the real API by the demo customers (so every table and audit row a real RFQ creates
// exists). Never point this at anything but a throwaway environment.
//
//   docker run --rm -i -e BASE_URL=http://host.docker.internal:8081 -e RFQS=3000 grafana/k6@sha256:… run - < loadtest/seed.js
// Also seeds the quality lists: NCRS and INSPECTIONS (standalone INCOMING/FINAL records, tagged to the demo customer and
// supplier so every audience has rows) and, only when NPD_ID is given (an existing NPD project id), DEVIATIONS linked to
// it. Orders, NPD projects and the Sample/Production POs need the whole RFQ-to-handover flow and are not seeded here:
// hot-paths.js still calls their lists, so they are measured on whatever the database already holds.
// SEED_VUS (default 4) must stay below the backend's DB pool (Hikari default 10): writes that notify need a second connection
// after commit, so as many concurrent writers as pool connections stall for 30 s (see docs/LOAD_TESTING.md).
// Env: BASE_URL, RFQS, NCRS, INSPECTIONS, DEVIATIONS, NPD_ID, QUALITY_USER, SUPPLIER_USER, PASSWORD, BUYERS (comma list of customer RFQ users; default the demo buyers), TARGET_CONFIRM (required
// for anything that is not localhost). See hot-paths.js for the rules about non-local targets.
import http from 'k6/http'
import { check, fail } from 'k6'
import exec from 'k6/execution'

const BASE = __ENV.BASE_URL || 'http://localhost:8080'
const RFQS = parseInt(__ENV.RFQS || '1000')
const NCRS = parseInt(__ENV.NCRS || '0')
const INSPECTIONS = parseInt(__ENV.INSPECTIONS || '0')
const DEVIATIONS = __ENV.NPD_ID ? parseInt(__ENV.DEVIATIONS || '0') : 0
const QUALITY_USER = __ENV.QUALITY_USER || 'quality@ergo.dev'
const SUPPLIER_USER = __ENV.SUPPLIER_USER || 'sales@precision.dev'
const PASSWORD = __ENV.PASSWORD || 'ErgoDev#2026'
const BUYERS = (__ENV.BUYERS || 'buyer@acme.dev,buyer@bharat.dev').split(',')

// Refuses to run against anything that is not a local throwaway unless the operator names the target again, and never
// with the published demo password off localhost: the demo accounts only exist under the dev profile, and creating them
// on an internet-facing environment would hand out a known password (Phase 8o security review).
const LOCAL = /^https?:\/\/(localhost|127\.0\.0\.1|host\.docker\.internal)(:\d+)?$/
const DEV_PASSWORD = 'ErgoDev#2026'
function guardTarget() {
  if (LOCAL.test(BASE)) return
  if (__ENV.TARGET_CONFIRM !== BASE) fail(`${BASE} is not a local address. To really run against it set TARGET_CONFIRM=${BASE}. Never production.`)
  if (!__ENV.PASSWORD || __ENV.PASSWORD === DEV_PASSWORD) fail('Off localhost, set PASSWORD to the generated password of dedicated load-test accounts, not the dev default.')
}

export const options = {
  scenarios: { seed: { executor: 'shared-iterations', vus: parseInt(__ENV.SEED_VUS || '4'), iterations: RFQS + NCRS + INSPECTIONS + DEVIATIONS, maxDuration: '20m' } },
  thresholds: { checks: ['rate>0.99'] },
}

function login(email) {
  const r = http.post(`${BASE}/api/auth/login`, JSON.stringify({ email, password: PASSWORD }), { headers: { 'Content-Type': 'application/json' } })
  if (r.status !== 200) fail(`login ${email}: ${r.status} ${r.body}`)
  return r
}

export function setup() {
  guardTarget()
  const buyers = BUYERS.map((email) => login(email).json('accessToken'))
  const quality = login(QUALITY_USER).json('accessToken')
  // the quality records are tagged to the first buyer's company and the supplier's, so customer and supplier lists have rows
  const customerCompanyId = login(BUYERS[0]).json('user.companyId')
  const supplierCompanyId = login(SUPPLIER_USER).json('user.companyId')
  return { buyers, quality, customerCompanyId, supplierCompanyId }
}

function post(path, body, token) {
  return http.post(`${BASE}${path}`, JSON.stringify(body), { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` } })
}

export default function (ctx) {
  // __ITER counts per VU; the kind of record is chosen by the iteration number across the whole run
  const it = exec.scenario.iterationInTest
  if (it >= RFQS) return quality(ctx, it - RFQS)
  const tokens = ctx.buyers
  const token = tokens[it % tokens.length]
  const due = new Date(Date.now() + 10 * 86400000).toISOString().slice(0, 10)
  const part = (n) => ({
    partNumber: `LT-${it}-${n}`, description: 'Bracket', materialSpec: 'EN8',
    targetProductionPiecePrice: 99.5, processSteps: [{ stepName: 'Casting' }],
  })
  const body = { targetQuoteDate: due, currencyId: 1, items: [part(1), part(2), part(3)] }
  const r = http.post(`${BASE}/api/rfqs`, JSON.stringify(body), { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` } })
  check(r, { 'rfq created': (x) => x.status === 201 || x.status === 200 })
}

function quality(ctx, n) {
  const day = (d) => new Date(Date.now() + d * 86400000).toISOString().slice(0, 10)
  if (n < NCRS) {
    const r = post('/api/quality/ncrs', {
      linkedModule: 'INCOMING', linkedRecordId: n + 1, issueCategory: 'Dimensional', issueDescription: `Load test NCR ${n}`,
      targetClosureDate: day(30), customerCompanyId: ctx.customerCompanyId, supplierCompanyId: ctx.supplierCompanyId,
    }, ctx.quality)
    return check(r, { 'ncr created': (x) => x.status === 201 })
  }
  n -= NCRS
  if (n < INSPECTIONS) {
    const r = post('/api/quality/inspections', {
      linkedModule: n % 2 ? 'FINAL' : 'INCOMING', linkedRecordId: n + 1, partNumber: `LT-INSP-${n}`, batchLotNo: `LOT-${n}`,
      customerCompanyId: ctx.customerCompanyId, supplierCompanyId: ctx.supplierCompanyId, customerVisible: n % 2 === 0,
    }, ctx.quality)
    return check(r, { 'inspection created': (x) => x.status === 201 })
  }
  n -= INSPECTIONS
  const r = post('/api/quality/deviations', {
    linkedModule: 'NPD', linkedRecordId: parseInt(__ENV.NPD_ID), partNumber: `LT-DEV-${n}`, deviationDescription: `Load test deviation ${n}`,
    customerApprovalRequiredFlag: n % 2 === 0, validityQuantity: 100, validityEndDate: day(60),
  }, ctx.quality)
  check(r, { 'deviation created': (x) => x.status === 201 })
}
