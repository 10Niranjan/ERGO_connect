// One-off: records the real backend's GET responses for every demo persona/role into src/demo/fixtures/*.json,
// so the frontend-only demo build (VITE_DEMO_MOCK) can run without a server.
//   node scripts/capture-fixtures.mjs [http://localhost:8080]   (backend on dev profile, data seeded)
import { mkdirSync, rmSync, writeFileSync } from 'node:fs'

const BASE = (process.argv[2] ?? 'http://localhost:8080') + '/api'
const OUT = new URL('../src/demo/fixtures/', import.meta.url)
const EMAILS = `admin management kam rfq npd ofp quality`.split(' ').map((n) => `${n}@ergo.dev`)
  .concat(`buyer npd logistics quality account management`.split(' ').map((n) => `${n}@acme.dev`))
  .concat(`sales tooling planner qa lead`.split(' ').map((n) => `${n}@precision.dev`))
const MODULES = ['rfq', 'quotation', 'npd', 'ofp', 'inspection', 'ncr', 'deviation']

rmSync(OUT, { recursive: true, force: true })
mkdirSync(OUT, { recursive: true })

const call = async (path, token, init = {}) => {
  const res = await fetch(BASE + path, {
    ...init,
    headers: { 'content-type': 'application/json', ...(token && { authorization: `Bearer ${token}` }), 'x-ergo-client': 'web' },
  })
  return { status: res.status, body: res.headers.get('content-type')?.includes('json') ? await res.json() : null }
}

const rows = (d) => (Array.isArray(d) ? d : (d?.items ?? []))

async function capture(session) {
  const { accessToken: token } = session
  const out = {}
  const get = async (path) => {
    if (path in out) return out[path]?.__status ? null : out[path]
    const { status, body } = await call(path, token)
    out[path] = status < 300 ? body : { __status: status }
    return status < 300 ? body : null
  }
  const ids = {}
  const list = async (key, path) => {
    ids[key] = [...new Set([...(ids[key] ?? []), ...rows(await get(path)).map((r) => r.id).filter(Boolean)])]
  }
  for (const p of ['/users', '/roles', '/companies', '/master-data', '/master-data/exchange-rates', '/admin/permission-matrix',
    '/dashboard', '/documents/categories', '/notifications/unread-count', '/npd/conversion-queue', '/quotes']) await get(p)
  for (const l of rows(await get('/master-data'))) await get(`/master-data/${l.key ?? l.code ?? l.name ?? l}`)
  await get('/notifications?size=500')
  await get('/documents?size=500&page=0')
  await get('/comments/inbox?size=500&page=0')
  await list('rfq', '/rfqs'); await list('quote', '/quotes'); await list('npd', '/npd'); await list('npd', '/npd/mine')
  await list('ofp', '/ofp/orders'); await list('inspection', '/quality/inspections'); await list('ncr', '/quality/ncrs')
  await list('deviation', '/quality/deviations')
  for (const d of rows(out['/documents?size=500&page=0'])) (ids.document ??= []).push(d.id)
  for (const id of ids.rfq) for (const s of ['', '/history', '/documents', '/comparison', '/customer-quotes', '/quotes']) await get(`/rfqs/${id}${s}`)
  for (const id of ids.quote) for (const s of ['', '/versions', '/documents']) await get(`/quotes/${id}${s}`)
  for (const id of ids.npd) for (const s of ['', '/history']) await get(`/npd/${id}${s}`)
  for (const id of ids.ofp) for (const s of ['', '/history']) await get(`/ofp/orders/${id}${s}`)
  for (const id of ids.inspection) await get(`/quality/inspections/${id}`)
  for (const id of ids.ncr) for (const s of ['', '/history']) await get(`/quality/ncrs/${id}${s}`)
  for (const id of ids.deviation) await get(`/quality/deviations/${id}`)
  for (const id of ids.document ?? []) await get(`/documents/${id}`)
  const link = { rfq: ids.rfq, quotation: ids.quote, npd: ids.npd, ofp: ids.ofp, inspection: ids.inspection, ncr: ids.ncr, deviation: ids.deviation }
  for (const m of MODULES) for (const id of link[m] ?? []) {
    await get(`/comments?linkedModule=${m}&linkedRecordId=${id}`)
    await get(`/comments/mentionable?linkedModule=${m}&linkedRecordId=${id}`)
  }
  return out
}

for (const email of EMAILS) {
  const login = await call('/auth/login', '', { method: 'POST', body: JSON.stringify({ email, password: 'ErgoDev#2026' }) })
  if (login.body?.otpRequired) { console.error('skipped (2FA required):', email); continue }
  if (login.status !== 200) { console.error('login failed', email, login.status); continue }
  const sessions = [login.body]
  for (const r of login.body.roles.filter((r) => r.code !== login.body.activeRole)) {
    const sw = await call('/auth/switch-role', login.body.accessToken, { method: 'POST', body: JSON.stringify({ role: r.code }) })
    if (sw.status === 200) sessions.push(sw.body)
  }
  for (const s of sessions) {
    const data = await capture(s)
    writeFileSync(new URL(`${s.user.id}-${s.activeRole}.json`, OUT), JSON.stringify(data))
    console.log(email, s.activeRole, Object.keys(data).length, 'responses')
  }
}
