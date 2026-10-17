// Shared helpers for the demo seeder. Everything goes through the real REST API as the real demo personas, so
// workflow rules, audit rows and notifications fire exactly as in use. Dev profile only.
import { createHmac } from 'node:crypto'

export const API = process.env.API_URL ?? 'http://localhost:8080/api'
const SECRET = 'dev-only-jwt-secret-never-use-outside-local-dev' // application-dev.yml ergo.security.jwt-secret

/** user id, company id, company type, active role. Ids 1..20 are DevDataSeeder's, in order; 21+ are made by phase 1. */
export const PERSONA = {
  admin: { id: 1, cid: 1, ctype: 'ERGO', role: 'ERGO_ADMIN' },
  mgmt: { id: 2, cid: 1, ctype: 'ERGO', role: 'ERGO_MANAGEMENT' },
  rfq: { id: 3, cid: 1, ctype: 'ERGO', role: 'ERGO_RFQ' },
  npd: { id: 3, cid: 1, ctype: 'ERGO', role: 'ERGO_NPD' }, // rfq@ergo.dev holds both roles
  quality: { id: 4, cid: 1, ctype: 'ERGO', role: 'ERGO_QUALITY' },
  kam: { id: 5, cid: 1, ctype: 'ERGO', role: 'ERGO_KAM' },
  acmeBuyer: { id: 6, cid: 2, ctype: 'CUSTOMER', role: 'CUSTOMER_RFQ' },
  acmeQc: { id: 6, cid: 2, ctype: 'CUSTOMER', role: 'CUSTOMER_QUALITY' },
  bharatBuyer: { id: 7, cid: 3, ctype: 'CUSTOMER', role: 'CUSTOMER_RFQ' },
  precision: { id: 8, cid: 4, ctype: 'SUPPLIER', role: 'SUPPLIER_RFQ' },
  shakti: { id: 9, cid: 5, ctype: 'SUPPLIER', role: 'SUPPLIER_RFQ' },
  ofp: { id: 10, cid: 1, ctype: 'ERGO', role: 'ERGO_OFP' },
  npdLead: { id: 11, cid: 1, ctype: 'ERGO', role: 'ERGO_NPD' },
  acmeNpd: { id: 12, cid: 2, ctype: 'CUSTOMER', role: 'CUSTOMER_NPD' },
  acmeOfp: { id: 13, cid: 2, ctype: 'CUSTOMER', role: 'CUSTOMER_OFP' },
  acmeQuality: { id: 14, cid: 2, ctype: 'CUSTOMER', role: 'CUSTOMER_QUALITY' },
  acmeKam: { id: 15, cid: 2, ctype: 'CUSTOMER', role: 'CUSTOMER_KAM' },
  precNpd: { id: 16, cid: 4, ctype: 'SUPPLIER', role: 'SUPPLIER_NPD' },
  precOfp: { id: 17, cid: 4, ctype: 'SUPPLIER', role: 'SUPPLIER_OFP' },
  precQa: { id: 18, cid: 4, ctype: 'SUPPLIER', role: 'SUPPLIER_QUALITY' },
  precKam: { id: 19, cid: 4, ctype: 'SUPPLIER', role: 'SUPPLIER_KAM' },
  acmeMgmt: { id: 20, cid: 2, ctype: 'CUSTOMER', role: 'CUSTOMER_MANAGEMENT' },
}

const b64 = (o) => Buffer.from(JSON.stringify(o)).toString('base64url')
export function token(p) {
  const now = Math.floor(Date.now() / 1000)
  const body = `${b64({ alg: 'HS256', typ: 'JWT' })}.${b64({ iss: 'ergo-connect', sub: String(p.id), cid: p.cid, ctype: p.ctype, role: p.role, sid: 1, pwc: false, iat: now, exp: now + 7200 })}`
  return `${body}.${createHmac('sha256', SECRET).update(body).digest('base64url')}`
}

const stats = { calls: 0, failures: 0 }
export const failures = []

async function raw(who, method, path, body, form) {
  const p = typeof who === 'string' ? PERSONA[who] : who
  if (!p) throw new Error(`unknown persona ${who}`)
  stats.calls++
  const headers = { Authorization: `Bearer ${token(p)}` }
  if (!form) headers['Content-Type'] = 'application/json'
  const res = await fetch(API + path, { method, headers, body: form ?? (body === undefined ? undefined : JSON.stringify(body)) })
  const text = await res.text()
  let data = null
  try { data = text ? JSON.parse(text) : null } catch { data = text }
  return { ok: res.ok, status: res.status, data, text }
}

/** Throws on failure: for steps everything later depends on. */
export async function call(who, method, path, body) {
  const r = await raw(who, method, path, body)
  if (!r.ok) throw new Error(`${who} ${method} ${path} -> ${r.status} ${r.text.slice(0, 260)}`)
  return r.data
}

/** Never throws: returns the data or null, and records the failure so the final report lists every skipped step. */
export async function attempt(label, who, method, path, body, quiet = false) {
  const r = await raw(who, method, path, body)
  if (!r.ok && quiet) return null
  if (!r.ok) {
    stats.failures++
    failures.push(`${label}: ${who} ${method} ${path} -> ${r.status} ${String(r.text).slice(0, 200)}`)
    return null
  }
  return r.data ?? true
}

export async function upload(who, path, file, extra = {}, category = null) {
  const form = new FormData()
  form.append('file', new File([file.content], file.name, { type: file.type }))
  for (const [k, v] of Object.entries(extra)) form.append(k, v)
  const r = await raw(who, 'POST', path, undefined, form)
  if (!r.ok) {
    stats.failures++
    failures.push(`upload ${file.name} -> ${path}: ${r.status} ${String(r.text).slice(0, 160)}`)
    return null
  }
  // a library category can only be set while the parent record is still open, so do it right after the upload
  if (category && r.data && typeof r.data === 'object' && r.data.id && /\/(rfqs|quotes)\/\d+\/documents$/.test(path)) {
    const cats = (categoryCache ??= Object.fromEntries((await call('admin', 'GET', '/documents/categories')).map((c) => [c.name, c.id])))
    await attempt(`category ${file.name}`, who, 'PATCH', `/documents/${r.data.id}`, { documentCategoryId: cats[category] }, true)
  }
  return r.data
}
let categoryCache = null

export const get = (who, path) => call(who, 'GET', path)
export const stat = () => ({ ...stats })

// ------------------------------------------------------------------ dates
export const inDays = (n) => new Date(Date.now() + n * 86400000).toISOString().slice(0, 10)
export const monthsAgo = (n) => { const d = new Date(); d.setMonth(d.getMonth() - n); d.setDate(1); return d.toISOString().slice(0, 10) }

// ------------------------------------------------------------------ small real files (the library, downloads and
// the virus scan all see genuine bytes)
const pdf = (title, lines) => {
  const text = [title, ...lines].map((l, i) => `BT /F1 12 Tf 50 ${760 - i * 18} Td (${l.replace(/[()\\]/g, '')}) Tj ET`).join('\n')
  return `%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 595 842]/Contents 4 0 R/Resources<</Font<</F1 5 0 R>>>>>>endobj\n4 0 obj<</Length ${text.length}>>stream\n${text}\nendstream endobj\n5 0 obj<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>endobj\ntrailer<</Root 1 0 R/Size 6>>\n%%EOF\n`
}
const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64')

export const files = {
  drawing: (part, rev = 'A') => ({ name: `${part}_Rev${rev}_drawing.pdf`, type: 'application/pdf', content: pdf(`Drawing ${part} rev ${rev}`, ['General tolerance ISO 2768-m', 'Material and heat treatment as per specification', 'All dimensions in mm']) }),
  spec: (part) => ({ name: `${part}_material_spec.pdf`, type: 'application/pdf', content: pdf(`Material specification for ${part}`, ['Chemical composition and mechanical properties', 'Test certificate required per batch (EN 10204 3.1)']) }),
  ppap: (part) => ({ name: `${part}_PPAP_level3.pdf`, type: 'application/pdf', content: pdf(`PPAP submission ${part}`, ['Design records, control plan, PFMEA, MSA, dimensional results', 'Initial process study: Cpk >= 1.67']) }),
  costSheet: (part) => ({ name: `${part}_cost_breakup.csv`, type: 'text/csv', content: 'item,amount_inr\nraw material,410\nprocessing,252\noverhead,84\npackaging,21\nprofit,53\n' }),
  inspectionReport: (ref) => ({ name: `${ref}_inspection_report.pdf`, type: 'application/pdf', content: pdf(`Inspection report ${ref}`, ['Sampling plan: AQL 1.0, level II', 'Critical dimensions checked on CMM']) }),
  photo: (name) => ({ name: `${name}.png`, type: 'image/png', content: PNG }),
  note: (name, text) => ({ name: `${name}.txt`, type: 'text/plain', content: text }),
  pod: (ref) => ({ name: `${ref}_proof_of_delivery.pdf`, type: 'application/pdf', content: pdf(`Proof of delivery ${ref}`, ['Goods received in good condition', 'Signed and stamped by consignee']) }),
  capaEvidence: (ref) => ({ name: `${ref}_capa_evidence.pdf`, type: 'application/pdf', content: pdf(`CAPA evidence ${ref}`, ['Corrective action implemented and verified', 'Three consecutive lots inspected with zero findings']) }),
  report8d: (ref) => ({ name: `${ref}_8D_report.pdf`, type: 'application/pdf', content: pdf(`8D report ${ref}`, ['D1 Team, D2 Problem, D3 Containment, D4 Root cause', 'D5 Corrective action, D6 Verification, D7 Prevention, D8 Closure']) }),
}

export const log = (...a) => console.log(...a)
export const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

// ------------------------------------------------------------------ quality helper shared by NPD, orders and quality
/**
 * Raise an inspection and walk it to `outcome`: 'open' | 'inspecting' | 'pass' | 'reject' | 'deviation' | 'closed'.
 * `who` is the ERGO quality engineer by default; a supplier QA engineer can raise one for their own company.
 */
export async function inspection({ who = 'quality', module, recordId, part, batch, cust = null, sup = null, visible = true, checked = 200, rejected = 0, defects = [], outcome = 'pass', remarks = null, date = null }) {
  const have = (await call('admin', 'GET', '/quality/inspections')).find((i) => i.linkedModule === module && i.linkedRecordId === recordId && i.batchLotNo === batch)
  if (have) return { id: have.id } // re-runs must not stack duplicate inspections
  const created = await attempt(`inspection ${part}`, who, 'POST', '/quality/inspections', { linkedModule: module, linkedRecordId: recordId, partNumber: part, batchLotNo: batch, customerCompanyId: cust, supplierCompanyId: sup, customerVisible: visible })
  if (!created || typeof created !== 'object') return null
  const id = created.id
  if (outcome === 'open') return { id }
  const form = { partNumber: part, batchLotNo: batch, quantityChecked: checked, quantityRejected: rejected, inspectionDate: date ?? inDays(-3), remarks, customerVisible: visible, customerCompanyId: cust, supplierCompanyId: sup, defects }
  await attempt(`inspection ${id} submit`, who, 'POST', `/quality/inspections/${id}/submit`, form)
  if (outcome === 'inspecting') return { id }
  const decide = { pass: ['pass', { reason: 'All checked characteristics within drawing tolerance.' }], closed: ['pass', { reason: 'All checked characteristics within drawing tolerance.' }],
    reject: ['reject', { reason: 'Critical dimension out of tolerance; lot cannot be accepted.' }], deviation: ['release-with-deviation', { reason: 'Minor cosmetic findings; released under deviation after customer concurrence.' }] }[outcome]
  await attempt(`inspection ${id} ${decide[0]}`, 'mgmt', 'POST', `/quality/inspections/${id}/${decide[0]}`, decide[1])
  if (outcome === 'closed') await attempt(`inspection ${id} close`, 'mgmt', 'POST', `/quality/inspections/${id}/close`, { reason: 'Report filed and shared.' })
  return { id }
}
