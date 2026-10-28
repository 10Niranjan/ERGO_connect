// Fills a TEST database with realistic volume so list and dashboard endpoints are measured against more than a handful
// of rows: RFQs created through the real API by the demo customers (so every table and audit row a real RFQ creates
// exists). Never point this at anything but a throwaway environment.
//
//   docker run --rm -i -e BASE_URL=http://host.docker.internal:8081 -e RFQS=3000 grafana/k6@sha256:… run - < loadtest/seed.js
// Env: BASE_URL, RFQS, PASSWORD, BUYERS (comma list of customer RFQ users; default the demo buyers), TARGET_CONFIRM (required
// for anything that is not localhost). See hot-paths.js for the rules about non-local targets.
import http from 'k6/http'
import { check, fail } from 'k6'

const BASE = __ENV.BASE_URL || 'http://localhost:8080'
const RFQS = parseInt(__ENV.RFQS || '1000')
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
  scenarios: { seed: { executor: 'shared-iterations', vus: 10, iterations: RFQS, maxDuration: '20m' } },
  thresholds: { checks: ['rate>0.99'] },
}

export function setup() {
  guardTarget()
  return BUYERS.map((email) => {
    const r = http.post(`${BASE}/api/auth/login`, JSON.stringify({ email, password: PASSWORD }), { headers: { 'Content-Type': 'application/json' } })
    if (r.status !== 200) fail(`login ${email}: ${r.status} ${r.body}`)
    return r.json('accessToken')
  })
}

export default function (tokens) {
  const token = tokens[__ITER % tokens.length]
  const due = new Date(Date.now() + 10 * 86400000).toISOString().slice(0, 10)
  const part = (n) => ({
    partNumber: `LT-${__ITER}-${n}`, description: 'Bracket', materialSpec: 'EN8',
    targetProductionPiecePrice: 99.5, processSteps: [{ stepName: 'Casting' }],
  })
  const body = { targetQuoteDate: due, currencyId: 1, items: [part(1), part(2), part(3)] }
  const r = http.post(`${BASE}/api/rfqs`, JSON.stringify(body), { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` } })
  check(r, { 'rfq created': (x) => x.status === 201 || x.status === 200 })
}
