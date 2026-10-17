// Phase 6: comment threads on every module, extra files, document-library actions, notification read-states, and
// timestamps spread over the past weeks (notifications and comments only; the audit trail stays append-only).
import { execFileSync } from 'node:child_process'
import { API, PERSONA, attempt, files, get, log, upload } from './lib.mjs'

export async function run(ctx) {
  const rfqs = Object.fromEntries((await get('admin', '/rfqs')).map((r) => [r.partNumber, r.id]))
  const npds = Object.fromEntries((await get('npdLead', '/npd')).map((n) => [n.partNumber, n.id]))
  const orders = Object.fromEntries((await get('ofp', '/ofp/orders')).map((o) => [o.partNumber, o.id]))
  const asc = (a, b) => a - b
  const insp = (await get('quality', '/quality/inspections')).map((i) => i.id).sort(asc)
  const ncrs = (await get('quality', '/quality/ncrs')).map((n) => n.id).sort(asc)
  const devs = (await get('quality', '/quality/deviations')).map((d) => d.id).sort(asc)
  // the ERGO persona who can actually see (and so resolve / escalate) a thread of each module
  const steward = { rfq: 'rfq', quotation: 'rfq', npd: 'npdLead', ofp: 'ofp', inspection: 'quality', ncr: 'quality', deviation: 'quality' }
  const quoteOf = async (part, supplierName) => (await get('rfq', `/rfqs/${rfqs[part]}/quotes`)).quotes?.find((q) => q.supplier.name === supplierName)?.id
  const id = (p) => PERSONA[p]?.id

  const existing = (await get('admin', '/comments/inbox?size=1')).totalElements ?? 0

  // each thread: module, record id, type, author, text, query?, mentions, replies [who, text], attach, end: resolve|escalate|null
  const T = [
    { m: 'rfq', r: rfqs['ACM-KNUCKLE-01'], type: 'EXTERNAL_CUSTOMER', by: 'acmeBuyer', text: 'Please confirm the quote covers the zinc-nickel coating and 720 h salt spray requirement.', q: true, replies: [['rfq', 'Confirmed. Coating and salt-spray test are included; test reports will be part of the PPAP package.']], end: 'resolve' },
    { m: 'rfq', r: rfqs['ACM-KNUCKLE-01'], type: 'INTERNAL', by: 'rfq', text: 'Precision has the best delivery record on forged knuckles; price is 3% under the next supplier.', mentions: ['kam'], replies: [['kam', 'Agreed. Acme asked for the PPAP date; we can commit to week 12.']] },
    { m: 'rfq', r: rfqs['ACM-HOUSING-03'], type: 'EXTERNAL_CUSTOMER', by: 'acmeBuyer', text: 'The tooling cost is higher than our budget. Is there room to amortise part of it in the piece price?', q: true, replies: [['rfq', 'We can offer a 12-month amortisation option; sending a revised quote shortly.'], ['acmeBuyer', 'Thank you, that works for us.']], end: 'resolve' },
    { m: 'rfq', r: rfqs['ACM-CAMSHAFT-07'], type: 'EXTERNAL_CUSTOMER', by: 'rfq', text: 'Waiting for your answer on the cam lobe hardness and the allowed runout.', q: true, end: 'escalate' },
    { m: 'rfq', r: rfqs['BHM-CRANK-10'], type: 'INTERNAL', by: 'rfq', text: 'Deccan declined; Shakti and Precision are within 3.5% of each other. Waiting for the third quote before scoring.' },
    { m: 'rfq', r: rfqs['SAH-ARM-14'], type: 'EXTERNAL_CUSTOMER', by: 'rfq', text: 'We are closing this RFQ as the customer sourced the part locally. Thank you for the opportunity.' },
    { m: 'quotation', r: await quoteOf('ACM-HOUSING-03', 'Shakti Forgings'), type: 'EXTERNAL_SUPPLIER', by: 'rfq', text: 'Can you confirm whether the die casting tool is included in your tooling cost?', q: true, replies: [['shakti', 'Yes, the full die set and one spare insert are included in the tooling cost.']], end: 'resolve' },
    { m: 'quotation', r: await quoteOf('ACM-HOUSING-03', 'Precision Castings'), type: 'EXTERNAL_SUPPLIER', by: 'precision', text: 'We can reduce the piece price by 1.5% if the annual volume is committed for two years.', replies: [['rfq', 'Noted. We will take this to the customer.']] },
    { m: 'npd', r: npds['ACM-KNUCKLE-01'], type: 'INTERNAL', by: 'npdLead', text: 'Trial run is two days behind; the gauge drawing revision is the blocker.', mentions: ['admin'], replies: [['admin', 'I have escalated with the customer. Expect the revised drawing by Friday.']], end: 'escalate' },
    { m: 'npd', r: npds['ACM-KNUCKLE-01'], type: 'EXTERNAL_SUPPLIER', by: 'precNpd', text: 'First-off pieces are ready for CMM. Layout report will be uploaded tomorrow.', attach: files.inspectionReport('KNUCKLE-FIRSTOFF') },
    { m: 'npd', r: npds['ACM-KNUCKLE-01'], type: 'EXTERNAL_CUSTOMER', by: 'acmeNpd', text: 'Please share the heat-treatment batch records with the sample report.', q: true, replies: [['npdLead', 'Will do; they are attached to the PPAP package.']] },
    { m: 'npd', r: npds['BHM-HUB-12'], type: 'EXTERNAL_CUSTOMER', by: 'bharatNpd', text: 'Our line trial is booked for the 20th. Can the samples reach us by the 17th?', q: true, replies: [['npdLead', 'Yes, dispatch is planned for the 14th by road.']], end: 'resolve' },
    { m: 'npd', r: npds['SAH-SPRING-15'], type: 'INTERNAL', by: 'npdLead', text: 'Customer approved the sample. Handover to production completed, order created.' },
    { m: 'ofp', r: orders['ACM-KNUCKLE-01'], type: 'EXTERNAL_SUPPLIER', by: 'precOfp', text: 'Heat treatment furnace is running at 90% load; forging output unaffected.' },
    { m: 'ofp', r: orders['ACM-KNUCKLE-01'], type: 'EXTERNAL_CUSTOMER', by: 'acmeOfp', text: 'Can we pull the dispatch forward by 3 days? Our line stock is lower than planned.', q: true, replies: [['ofp', 'Checking with the supplier; will confirm by tomorrow.']], end: 'escalate' },
    { m: 'ofp', r: orders['ACM-BUSH-21'], type: 'EXTERNAL_SUPPLIER', by: 'ofp', text: 'Raw material delayed by the mill. Expected receipt in 5 days; production start moves accordingly.', mentions: ['precOfp'] },
    { m: 'ofp', r: orders['BHM-FLYWHEEL-22'], type: 'EXTERNAL_CUSTOMER', by: 'bharatOfp', text: 'We released drawing revision B yesterday. Please resume production.', q: true },
    { m: 'ofp', r: orders['ACM-GASKET-23'], type: 'EXTERNAL_CUSTOMER', by: 'acmeOfp', text: 'AWB received. Thank you; please share the packing list as well.', replies: [['ofp', 'Packing list attached to the shipment record.']], attach: files.note('packing_list_ACM-GASKET-23', 'Packing list: 15 crates, 1000 pcs per crate, gross weight 1,240 kg.') },
    { m: 'ofp', r: orders['BHM-HUB-12'], type: 'EXTERNAL_CUSTOMER', by: 'bharatOfp', text: 'Goods received in good condition. Receipt confirmed in the system.', q: true, replies: [['ofp', 'Thank you for confirming.']], end: 'resolve' },
    { m: 'inspection', r: insp[0], type: 'INTERNAL', by: 'quality', text: 'Hardness results are within spec for all 12 bars tested.', attach: files.inspectionReport('INSP-001') },
    { m: 'inspection', r: insp[2], type: 'EXTERNAL_SUPPLIER', by: 'quality', text: 'Please investigate the chatter marks on machining line 2 and respond with containment actions.', q: true, replies: [['precQa', 'Spindle bearing replaced; 100% sorting of the affected lots in progress.']], mentions: ['precQa'], end: 'escalate' },
    { m: 'ncr', r: ncrs[0], type: 'EXTERNAL_CUSTOMER', by: 'acmeQuality', text: 'Please share the 8D report for this NCR before we release the lot.', q: true, replies: [['quality', '8D report attached. All actions are verified effective.']], attach: files.report8d('NCR-2026-00001'), end: 'resolve' },
    { m: 'ncr', r: ncrs[1], type: 'EXTERNAL_SUPPLIER', by: 'quality', text: 'Please upload the corrective action evidence so we can close this NCR.', q: true, replies: [['precQa', 'Evidence uploaded against both CAPA actions.']] },
    { m: 'ncr', r: ncrs[2], type: 'INTERNAL', by: 'quality', text: 'Seam depth measured at 0.3 mm; mill has been informed and will credit the lot.', mentions: ['mgmt'], replies: [['mgmt', 'Approved. Please also request a corrective action report from the mill.']] },
    { m: 'ncr', r: ncrs[3], type: 'EXTERNAL_SUPPLIER', by: 'quality', text: 'Furnace logs for the affected batch are needed for the root cause analysis.', q: true },
    { m: 'deviation', r: devs[0], type: 'EXTERNAL_CUSTOMER', by: 'acmeQuality', text: 'We accept the flash limit for the trial lot only. Subsequent lots must be flash-free.', q: true, replies: [['quality', 'Understood and recorded on the deviation.']], end: 'resolve' },
    { m: 'deviation', r: devs[2], type: 'INTERNAL', by: 'quality', text: 'Alternate grade EN19 test certificates received; waiting for management review.', mentions: ['mgmt'] },
  ]

  let made = 0
  if (existing < 5) {
    for (const t of T) {
      if (!t.r) continue
      const top = await attempt(`comment ${t.m} ${t.r}`, t.by, 'POST', '/comments', { linkedModule: t.m, linkedRecordId: t.r, commentType: t.type, text: t.text, query: !!t.q, mentionedUserIds: (t.mentions ?? []).map(id).filter(Boolean) })
      if (!top || typeof top !== 'object') continue
      made++
      if (t.attach) await upload(t.by, `/comments/${top.id}/attachments`, t.attach)
      for (const [who, text] of t.replies ?? []) {
        await attempt(`reply ${top.id}`, who, 'POST', '/comments', { linkedModule: t.m, linkedRecordId: t.r, parentCommentId: top.id, text, query: false, mentionedUserIds: [] })
      }
      if (t.end === 'resolve') await attempt(`resolve ${top.id}`, steward[t.m], 'POST', `/comments/${top.id}/resolve`, { reason: 'Answered.' })
      if (t.end === 'escalate') await attempt(`escalate ${top.id}`, steward[t.m], 'POST', `/comments/${top.id}/escalate`, { reason: 'Needs management attention.' })
    }
  }
  log(`  comments: ${made} threads (with replies, mentions, queries, attachments)`)

  // ---------------------------------------------------------------- more files and library actions
  const categories = Object.fromEntries((await get('admin', '/documents/categories')).map((c) => [c.name, c.id]))
  let extra = 0
  for (const part of ['ACM-KNUCKLE-01', 'ACM-GEARBLANK-09', 'BHM-HUB-12', 'SAH-SPRING-15']) {
    if (!rfqs[part]) continue
    const r = await upload('rfq', `/rfqs/${rfqs[part]}/documents`, files.ppap(part), { visibility: 'INTERNAL' }, 'PPAP')
    if (r) extra++
  }
  const page = await get('admin', '/documents?size=200')
  const rows = page.items ?? []
  const byName = (re) => rows.filter((r) => re.test(r.fileName ?? r.name ?? ''))
  const setCat = async (list, cat) => { for (const d of list) await attempt(`category ${d.id}`, 'admin', 'PATCH', `/documents/${d.id}`, { documentCategoryId: categories[cat] }, true) }
  await setCat(byName(/drawing/i), 'Drawing')
  await setCat(byName(/cost_breakup/i), 'Quotation')
  await setCat(byName(/PPAP/i), 'PPAP')
  await setCat(byName(/inspection_report/i), 'Inspection Report')
  await setCat(byName(/capa_evidence|8D/i), 'NCR/CAPA Evidence')
  await setCat(byName(/proof_of_delivery/i), 'Shipment/POD')
  const drawings = byName(/drawing/i)
  const costs = byName(/cost_breakup/i)
  for (const d of drawings.slice(0, 7)) await attempt(`approve ${d.id}`, 'admin', 'POST', `/documents/${d.id}/approve`, { reason: 'Reviewed against the latest revision.' })
  for (const d of byName(/PPAP/i).slice(0, 2)) await attempt(`approve ${d.id}`, 'admin', 'POST', `/documents/${d.id}/approve`, { reason: 'PPAP package complete.' })
  if (drawings[8]) await attempt('reject doc', 'admin', 'POST', `/documents/${drawings[8].id}/reject`, { reason: 'Revision on the drawing does not match the RFQ.' })
  if (drawings[0]) await attempt('lock doc', 'admin', 'POST', `/documents/${drawings[0].id}/lock`, undefined, true)
  if (costs[0]) await upload('admin', `/documents/${costs[0].id}/replace`, { name: 'ACM-KNUCKLE-01_cost_breakup_v2.csv', type: 'text/csv', content: 'item,amount_inr\nraw material,402\nprocessing,248\noverhead,82\npackaging,21\nprofit,54\n' })
  log(`  documents: +${extra} internal PPAP files; categories set; approvals, a rejection, a lock and a new version applied`)

  // real sign-ins for the seeded personas, so the last sign-in column is filled in on the Users screen
  let logins = 0
  const EMAILS = ['admin', 'management', 'rfq', 'quality', 'kam', 'ofp', 'npd'].map((n) => `${n}@ergo.dev`)
    .concat(['buyer', 'npd', 'logistics', 'quality', 'account', 'management'].map((n) => `${n}@acme.dev`))
    .concat(['sales', 'tooling', 'planner', 'qa', 'lead'].map((n) => `${n}@precision.dev`), ['buyer@bharat.dev', 'sales@shakti.dev'])
  for (const email of EMAILS) {
    const res = await fetch(`${API}/auth/login`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ email, password: 'ErgoDev#2026' }) }).catch(() => null)
    if (res?.ok) logins++
  }
  log(`  sign-ins: ${logins}/${EMAILS.length} personas signed in once`)

  // ---------------------------------------------------------------- spread timestamps, then mark most notifications read
  const container = process.env.DB_CONTAINER ?? 'ergo-pg'
  const sql = (q) => execFileSync('docker', ['exec', container, 'psql', '-U', 'ergo', '-d', 'ergo_connect', '-At', '-c', q], { encoding: 'utf8' })
  try {
    sql("update notifications set sent_at = now() - ((select max(notification_id) from notifications) - notification_id) * (interval '26 days') / greatest((select max(notification_id) from notifications), 1) - interval '2 hours'")
    sql("update comments set created_at = now() - ((select max(comment_id) from comments) - comment_id) * (interval '18 days') / greatest((select max(comment_id) from comments), 1) - interval '3 hours', updated_at = created_at where resolved_at is null and escalated_at is null")
    log('  timestamps: notifications spread over the last 4 weeks, comments over the last 2.5 weeks')
  } catch {
    log('  timestamps: skipped (needs `docker` and the database container; set DB_CONTAINER if it is not called ergo-pg)')
  }
  const personas = ['admin', 'mgmt', 'rfq', 'quality', 'kam', 'ofp', 'npdLead', 'acmeBuyer', 'bharatBuyer', 'sahyadriBuyer', 'acmeNpd', 'acmeOfp', 'acmeQuality', 'acmeKam', 'precision', 'shakti', 'deccan', 'precNpd', 'precOfp', 'precQa']
  let read = 0
  for (const p of personas) {
    if (!PERSONA[p]) continue
    const list = await get(p, '/notifications?size=200')
    const items = list.items ?? []
    // the newest few stay unread (a believable inbox); older ones are mostly read
    for (const n of items.slice(3)) if (!n.read && n.id % 4 !== 0) { if (await attempt('read', p, 'POST', `/notifications/${n.id}/read`)) read++ }
  }
  log(`  notifications: ${read} marked read across ${personas.length} inboxes (newest stay unread)`)
  return {}
}
