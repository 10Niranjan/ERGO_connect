// Load test of the endpoints every user hits all day (Phase 8o): login, the dashboard, the RFQ list, the notification
// bell (the frontend polls the unread count every 45 s per open tab), notifications and the document library.
//
// The users are the seeded demo users (dev profile) - one per audience - so the numbers cover ERGO, customer and
// supplier scoping. Run seed.js first, or the lists are nearly empty and the result means little.
//
// Local:
//   java -jar app.jar        (dev profile: seeds the demo users)
//   docker run --rm -i -e BASE_URL=http://host.docker.internal:8081 grafana/k6@sha256:… run - < loadtest/hot-paths.js
// Staging: the demo users do not exist there (prod profile). Create dedicated load-test accounts (a throwaway test customer
// and test supplier, ERGO accounts on a domain ERGO controls, a generated password), pass them in USERS / SUPPLIER_USERS and
// the password in PASSWORD, set TARGET_CONFIRM to the exact BASE_URL, and deactivate the accounts afterwards. Never production.
// The login limiter stays on: successful logins hand their slot back, so this run does not need it relaxed.
//
// Env: BASE_URL, PASSWORD, USERS (comma list, one per audience; default the demo users), SUPPLIER_USERS (the ones in USERS that
// are suppliers), TARGET_CONFIRM, PEAK_VUS (default 50), HOLD (default 2m), SKIP_LOGIN=1, SKIP_LIST=1 (to tell the lists' cost
// from everything else), THINK (seconds, default 1)
import http from 'k6/http'
import { check, fail, group, sleep } from 'k6'

const BASE = __ENV.BASE_URL || 'http://localhost:8080'
const PASSWORD = __ENV.PASSWORD || 'ErgoDev#2026'

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
const PEAK = parseInt(__ENV.PEAK_VUS || '50')
const HOLD = __ENV.HOLD || '2m'
const THINK = parseFloat(__ENV.THINK || '1')
// Each audience has its own work list: suppliers are (by design) refused on /api/rfqs and use their assigned quotes.
const USERS = (__ENV.USERS || 'management@ergo.dev,kam@ergo.dev,buyer@acme.dev,sales@precision.dev').split(',')
const SUPPLIERS = (__ENV.SUPPLIER_USERS || 'sales@precision.dev').split(',')
const JSON_HEADERS = { 'Content-Type': 'application/json' }

const scenarios = {
  browse: {
    executor: 'ramping-vus', exec: 'browse', startVUs: 0,
    stages: [{ duration: '30s', target: PEAK }, { duration: HOLD, target: PEAK }, { duration: '15s', target: 0 }],
  },
}
if (!__ENV.SKIP_LOGIN) {
  // bcrypt is deliberately slow and CPU-bound, so login capacity is tested separately, at a fixed arrival rate.
  scenarios.login = { executor: 'constant-arrival-rate', exec: 'login', rate: 5, timeUnit: '1s', duration: '45s', preAllocatedVUs: 20, maxVUs: 60, startTime: '5s' }
}

export const options = {
  scenarios,
  thresholds: {
    'http_req_failed{scenario:browse}': ['rate<0.01'],
    'http_req_duration{scenario:browse}': ['p(95)<800'],
    'http_req_duration{name:work-list}': ['p(95)<1000'],
    'http_req_duration{name:dashboard}': ['p(95)<1000'],
    'http_req_duration{name:unread-count}': ['p(95)<300'],
    'http_req_failed{scenario:login}': ['rate<0.01'],
    'http_req_duration{scenario:login}': ['p(95)<1500'],
  },
}

export function setup() {
  guardTarget()
  return USERS.map((email) => {
    const r = http.post(`${BASE}/api/auth/login`, JSON.stringify({ email, password: PASSWORD }), { headers: JSON_HEADERS })
    check(r, { [`setup login ${email}`]: (x) => x.status === 200 })
    return r.status === 200 ? r.json('accessToken') : null
  })
}

export function browse(tokens) {
  const idx = (__VU - 1) % tokens.length
  const token = tokens[idx]
  if (!token) return
  const listPath = SUPPLIERS.includes(USERS[idx]) ? '/api/quotes' : '/api/rfqs'
  const headers = { Authorization: `Bearer ${token}` }
  const get = (path, name) => check(http.get(`${BASE}${path}`, { headers, tags: { name } }), { [`${name} 200`]: (r) => r.status === 200 })
  group('open the app', () => {
    get('/api/dashboard', 'dashboard')
    get('/api/notifications/unread-count', 'unread-count')
  })
  sleep(THINK)
  group('work a list', () => {
    if (!__ENV.SKIP_LIST) get(listPath, 'work-list')
    get('/api/documents', 'documents')
    get('/api/notifications', 'notifications')
  })
  sleep(THINK)
  get('/api/notifications/unread-count', 'unread-count')
  sleep(THINK)
}

export function login() {
  const email = USERS[Math.floor(Math.random() * USERS.length)]
  const r = http.post(`${BASE}/api/auth/login`, JSON.stringify({ email, password: PASSWORD }), { headers: JSON_HEADERS, tags: { name: 'login' } })
  check(r, { 'login 200': (x) => x.status === 200 })
}
