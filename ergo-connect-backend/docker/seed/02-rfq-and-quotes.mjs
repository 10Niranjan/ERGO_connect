// Phase 2: 15 RFQs at every status (draft -> closed), multi-supplier quotes, scoring, customer decisions, files.
import { PERSONA, attempt, call, failures, files, get, getAll, inDays, log, upload } from './lib.mjs'

const code = async (list) => Object.fromEntries((await get('admin', `/master-data/${list}`)).map((e) => [e.code, e.id]))

export async function run(ctx) {
  const mat = await code('materials')
  const proc = await code('processes')
  const coat = await code('coatings')
  const pay = await code('payment-terms')
  const inco = await code('incoterms')
  const cur = await code('currencies')
  const C = ctx.companyIds
  Object.assign(ctx, { mat, proc, coat, pay, inco, cur })

  const step = (p, name, sec, sourcing = 'INHOUSE', sub = null) => ({ processId: proc[p], stepName: name, cycleTimeSeconds: sec, sourcingType: sourcing, outsourcedSupplierName: sub, remarks: sec >= 100 ? 'Critical to quality: inspect every 25 pieces' : sourcing === 'OUTSOURCED' ? 'Approved sub-supplier, audit done' : null })

  // buyer persona, company, part ... plan = the journey to replay
  const SPECS = [
    { buyer: 'acmeBuyer', cust: C.acme, part: 'ACM-KNUCKLE-01', desc: 'Steering knuckle, forged and machined', mat: 'EN19', mspec: 'Customer spec CS-114 rev 3, max inclusion rating 1.5', coat: 'ZN-NI', eau: 48000, net: 3.1, gross: 3.9, tgt: [980, 940, 520000], steps: [step('FORGING', 'Hot forge', 42), step('HEAT-TREAT', 'Quench and temper', 3600), step('CNC-TURN', 'Turn bores', 95), step('MAGNAFLUX', 'Crack detection', 40, 'OUTSOURCED', 'Sigma NDT Services')], plan: ['submit', 'review', 'validate', 'invite:precision', 'quote:precision=905', 'select:precision', 'send:900', 'respond:APPROVED'] },
    { buyer: 'acmeBuyer', cust: C.acme, part: 'ACM-HOUSING-03', desc: 'Gearbox housing, pressure die-cast', mat: 'ADC12', coat: 'E-COAT', eau: 36000, net: 4.8, gross: 5.6, tgt: [760, 720, 1400000], steps: [step('DIE-CAST', 'Cast housing', 55), step('FETTLING', 'Trim and fettle', 70), step('CNC-MILL', 'Mill mounting faces', 160), step('VMC-DRILL', 'Drill and tap', 120)], plan: ['submit', 'review', 'validate', 'invite:precision,shakti,deccan', 'quote:precision=782,shakti=765,deccan=809', 'score', 'shortlist:shakti', 'select:precision', 'send:850', 'respond:REVISION_REQUESTED'] },
    { buyer: 'acmeBuyer', cust: C.acme, part: 'ACM-BRACKET-02', desc: 'Engine mount bracket, fabricated', mat: 'AISI316', coat: 'HDG', eau: 18000, net: 1.9, gross: 2.3, tgt: [310, 290, 90000], steps: [step('WELD-FAB', 'Weld assembly', 140), step('SHOT-BLAST', 'Shot blast', 35)], plan: ['submit'] },
    { buyer: 'acmeBuyer', cust: C.acme, part: 'ACM-LINK-06', desc: 'Suspension link arm', mat: 'C45', coat: 'PHOS-OIL', eau: 60000, net: 1.2, gross: 1.5, tgt: [215, 200, 160000], steps: [step('FORGING', 'Hot forge', 30), step('CNC-TURN', 'Turn ends', 55)], plan: ['submit', 'review'] },
    { buyer: 'acmeBuyer', cust: C.acme, part: 'ACM-CAMSHAFT-07', desc: 'Camshaft, induction hardened', mat: 'EN24', mspec: 'Vacuum degassed, ultrasonic tested bar', coat: 'BLACK-OX', eau: 30000, net: 2.2, gross: 2.9, tgt: [640, 600, 380000], steps: [step('FORGING', 'Forge blank', 38), step('CNC-TURN', 'Rough and finish turn', 150), step('GRIND', 'Grind journals', 210)], plan: ['submit', 'review', 'clarify:Please confirm the required surface hardness on the cam lobes (HRC) and the allowed runout.'] },
    { buyer: 'acmeBuyer', cust: C.acme, part: 'ACM-FLANGE-08', desc: 'Exhaust flange, ductile iron', mat: 'SG500', mspec: 'Nodularity above 85%, pearlite 40-60%', coat: null, eau: 24000, net: 2.6, gross: 3.2, tgt: [255, 240, 210000], steps: [step('SAND-CAST', 'Sand cast', 80), step('FETTLING', 'Fettle', 45), step('CNC-MILL', 'Face and drill', 110)], plan: ['submit', 'review', 'validate', 'invite:precision,deccan'] },
    { buyer: 'acmeBuyer', cust: C.acme, part: 'ACM-GEARBLANK-09', desc: 'Helical gear blank', mat: '20MNCR5', coat: 'PHOS-OIL', eau: 42000, net: 3.4, gross: 4.1, tgt: [590, 560, 330000], steps: [step('FORGING', 'Forge blank', 36), step('HEAT-TREAT', 'Normalise', 2800), step('CNC-TURN', 'Turn blank', 85)], plan: ['submit', 'review', 'validate', 'invite:precision,shakti', 'quote:precision=548,shakti=571', 'select:precision', 'send:605', 'respond:APPROVED'] },
    { buyer: 'bharatBuyer', cust: C.bharat, part: 'BHM-GEAR-04', desc: 'Transmission gear blank', mat: 'SAE8620', coat: 'PHOS-OIL', eau: 52000, net: 2.8, gross: 3.4, tgt: [470, 445, 260000], steps: [step('FORGING', 'Forge', 34), step('CNC-TURN', 'Turn', 75)], plan: [] },
    { buyer: 'bharatBuyer', cust: C.bharat, part: 'BHM-AXLE-16', desc: 'Rear axle shaft', mat: 'SAE4140', coat: 'ZN-PLATE', eau: 20000, net: 7.4, gross: 8.6, tgt: [940, 900, 640000], steps: [step('FORGING', 'Forge', 70), step('HEAT-TREAT', 'Induction harden', 900), step('GRIND', 'Grind bearing seats', 260)], plan: ['submit', 'review', 'clarify:What is the required case depth and the allowed shaft straightness after hardening?', 'answer:Case depth 1.8-2.2 mm; straightness 0.15 mm TIR. Updated drawing rev B attached.'] },
    { buyer: 'bharatBuyer', cust: C.bharat, part: 'BHM-SHAFT-05', desc: 'Drive shaft forging', mat: 'SAE4140', coat: 'ZN-PLATE', eau: 26000, net: 5.2, gross: 6.4, tgt: [810, 770, 480000], steps: [step('FORGING', 'Forge', 60), step('HEAT-TREAT', 'Quench and temper', 3600), step('GRIND', 'Grind splines', 240)], plan: ['submit'] },
    { buyer: 'bharatBuyer', cust: C.bharat, part: 'BHM-CRANK-10', desc: 'Crank pin', mat: 'EN24', coat: 'CHROME', eau: 22000, net: 1.4, gross: 1.8, tgt: [420, 395, 150000], steps: [step('FORGING', 'Forge', 28), step('GRIND', 'Grind and polish', 180)], plan: ['submit', 'review', 'validate', 'invite:precision,deccan,shakti', 'ask:shakti', 'quote:precision=401,shakti=388', 'reopen:precision=395', 'regret:deccan'] },
    { buyer: 'bharatBuyer', cust: C.bharat, part: 'BHM-PINION-11', desc: 'Differential pinion', mat: '20MNCR5', coat: 'BLACK-OX', eau: 34000, net: 0.9, gross: 1.2, tgt: [265, 250, 120000], steps: [step('FORGING', 'Forge', 22), step('BROACH', 'Broach bore', 40)], plan: ['submit', 'review', 'validate', 'invite:shakti', 'quote:shakti=288', 'select:shakti', 'send:310', 'respond:DECLINED'] },
    { buyer: 'bharatBuyer', cust: C.bharat, part: 'BHM-HUB-12', desc: 'Wheel hub, cast and machined', mat: 'FG260', coat: 'E-COAT', eau: 40000, net: 6.1, gross: 7.0, tgt: [690, 650, 720000], steps: [step('SAND-CAST', 'Cast', 90), step('CNC-TURN', 'Turn', 130), step('VMC-DRILL', 'Drill PCD holes', 90)], plan: ['submit', 'review', 'validate', 'invite:shakti,precision', 'quote:shakti=655,precision=672', 'select:shakti', 'send:720', 'respond:APPROVED'] },
    { buyer: 'sahyadriBuyer', cust: C.sahyadri, part: 'SAH-BRKT-13', desc: 'Alternator bracket', mat: 'AL6061', coat: 'ANODIZE', eau: 15000, net: 0.7, gross: 0.9, tgt: [180, 170, 70000], steps: [step('CNC-MILL', 'Mill profile', 120)], plan: ['submit', 'cancel'] },
    { buyer: 'sahyadriBuyer', cust: C.sahyadri, part: 'SAH-ARM-14', desc: 'Rocker arm', mat: 'C45', coat: 'ZN-PLATE', eau: 70000, net: 0.8, gross: 1.0, tgt: [150, 140, 95000], steps: [step('FORGING', 'Forge', 20), step('CNC-MILL', 'Mill pads', 60)], plan: ['submit', 'review', 'validate', 'invite:deccan,precision', 'quote:deccan=139,precision=146', 'close'] },
    { buyer: 'sahyadriBuyer', cust: C.sahyadri, part: 'SAH-SPRING-15', desc: 'Leaf spring eye bracket', mat: 'SAE4140', coat: 'E-COAT', eau: 28000, net: 2.4, gross: 3.0, tgt: [330, 310, 210000], steps: [step('FORGING', 'Forge', 32), step('CNC-MILL', 'Mill and drill', 100)], plan: ['submit', 'review', 'validate', 'invite:precision', 'quote:precision=298', 'select:precision', 'send:332', 'respond:APPROVED'] },
  ]
  const PERSONA_OF = { precision: 'precision', shakti: 'shakti', deccan: 'deccan' }
  const SUPPLIER_COMPANY = { 'Precision Castings': C.precision, 'Shakti Forgings': C.shakti, 'Deccan Machining': C.deccan }
  const SUPPLIER_NAME = { precision: 'Precision Castings', shakti: 'Shakti Forgings', deccan: 'Deccan Machining' }

  const form = (s) => ({
    customerCompanyId: s.cust, partNumber: s.part, revision: 'A', description: s.desc, eau: s.eau, netWeightKg: s.net, grossWeightKg: s.gross,
    materialId: mat[s.mat], materialSpec: s.mspec ?? null, coatingId: s.coat ? coat[s.coat] : null, coatingSpec: s.coat ? null : 'Raw finish, rust-preventive oil',
    targetQuoteDate: inDays(25), priority: s.part.endsWith('-01') || s.part.endsWith('-12') ? 'HIGH' : s.part.endsWith('-06') ? 'LOW' : 'NORMAL',
    currencyId: cur.INR, targetToolingCost: s.tgt[2], targetPpapPiecePrice: s.tgt[0], targetProductionPiecePrice: s.tgt[1], paymentTermId: pay.NET30 ?? Object.values(pay)[0],
    materialLeadTimeWeeks: 3, toolingLeadTimeDays: 60, sampleLeadTimeDays: 30, productionLeadTimeDays: 45, moq: 500,
    processSteps: s.steps, customerRemarks: 'Annual volume as per our current schedule; PPAP level 3 required before production.',
  })
  const quoteForm = (s, price) => {
    const c = (n) => Math.round(n * 100) / 100
    return {
      quoteReference: `Q-${s.part.split('-')[1]}-${price}`, validityDate: inDays(120), currencyId: cur.INR, incotermId: inco.EXW ?? null, paymentTermId: pay.NET30 ?? Object.values(pay)[0],
      toolingCostTotal: null, tooling: [{ toolName: `${s.desc.split(',')[0]} tooling`, toolingCost: Math.round(s.tgt[2] * 0.85), remarks: 'Includes die set and one spare insert' }],
      ppapPiecePrice: price + 60, productionPiecePrice: price, rawMaterialCost: c(price * 0.5), processingCost: c(price * 0.3), overheadCost: c(price * 0.1), packagingCost: c(price * 0.03), profit: c(price * 0.07),
      moq: 500, materialLeadTimeWeeks: 3, toolingLeadTimeDays: 55, sampleLeadTimeDays: 25, productionLeadTimeDays: 40,
      processSteps: s.steps.map((x) => ({ ...x, cycleTimeSeconds: Math.round(x.cycleTimeSeconds * 0.95) })), assumptionsDeviations: 'Pricing valid for the stated volume; material indexed to the quoted grade.', remarks: 'Sample lots can be delivered in 25 days from tooling approval.',
    }
  }
  const ergo = (price) => ({
    currencyId: cur.INR, productionPiecePrice: price, ppapPiecePrice: price + 55, toolingCost: 450000, paymentTermId: pay.NET30 ?? Object.values(pay)[0], incotermId: inco.DAP ?? null, moq: 500,
    materialLeadTimeWeeks: 3, toolingLeadTimeDays: 55, sampleLeadTimeDays: 25, productionLeadTimeDays: 40, validityDate: inDays(90),
  })

  const existing = await getAll('admin', '/rfqs')
  const byPart = (part) => existing.find((r) => r.partNumber === part)
  const status = async (id) => (await get('admin', `/rfqs/${id}`)).status
  let created = 0

  for (const s of SPECS) {
    if (byPart(s.part)) continue
    try {
    const buyer = PERSONA[s.buyer]
    if (!buyer) { log(`  skip ${s.part}: persona ${s.buyer} missing`); continue }
    const rfq = await call(s.buyer, 'POST', '/rfqs', form(s))
    created++
    // files the customer attaches while the RFQ is still a draft
    await upload(s.buyer, `/rfqs/${rfq.id}/documents`, files.drawing(s.part), { visibility: 'ALL' }, 'Drawing')
    if (s.steps.length > 2) await upload(s.buyer, `/rfqs/${rfq.id}/documents`, files.spec(s.part), { visibility: 'ALL' }, 'Other')

    let quotes = {} // supplier key -> quote id
    let selected = null
    let invitedQuotes = null
    for (const move of s.plan) {
      const [cmd, arg] = move.split(/:(.*)/s)
      if (cmd === 'submit') await attempt(`${s.part} submit`, s.buyer, 'POST', `/rfqs/${rfq.id}/submit`, { comment: 'Please quote against the attached drawing.' })
      else if (cmd === 'review') await attempt(`${s.part} review`, 'rfq', 'POST', `/rfqs/${rfq.id}/start-review`, { comment: null })
      else if (cmd === 'clarify') await attempt(`${s.part} clarify`, 'rfq', 'POST', `/rfqs/${rfq.id}/request-clarification`, { comment: arg })
      else if (cmd === 'validate') await attempt(`${s.part} validate`, 'rfq', 'POST', `/rfqs/${rfq.id}/validate`, { comment: 'Technical and commercial data checked.' })
      else if (cmd === 'answer') await attempt(`${s.part} answer`, s.buyer, 'POST', `/rfqs/${rfq.id}/submit`, { comment: arg })
      else if (cmd === 'cancel') await attempt(`${s.part} cancel`, s.buyer, 'POST', `/rfqs/${rfq.id}/cancel`, { comment: 'Programme postponed by the customer.' })
      else if (cmd === 'close') await attempt(`${s.part} close`, 'rfq', 'POST', `/rfqs/${rfq.id}/close`, { comment: 'Customer chose a different source; closing.' })
      else if (cmd === 'invite') {
        const names = arg.split(',').map((k) => SUPPLIER_NAME[k])
        const supplierIds = names.map((n) => SUPPLIER_COMPANY[n]).filter(Boolean)
        invitedQuotes = await attempt(`${s.part} invite`, 'rfq', 'POST', `/rfqs/${rfq.id}/quotes`, { supplierIds, responseDueDate: inDays(14) })
        for (const q of invitedQuotes?.quotes ?? []) quotes[Object.keys(SUPPLIER_NAME).find((k) => SUPPLIER_NAME[k] === q.supplier.name)] = q.id
      } else if (cmd === 'ask') {
        await attempt(`${s.part} ask`, PERSONA_OF[arg], 'POST', `/quotes/${quotes[arg]}/request-clarification`, { comment: 'Is the surface finish Ra 3.2 or better acceptable, or do you need Ra 1.6 on the seating faces?' })
        await attempt(`${s.part} answer ask`, 'rfq', 'POST', `/quotes/${quotes[arg]}/answer-clarification`, { comment: 'Ra 3.2 is acceptable everywhere except the bearing seats, which need Ra 1.6.' })
      } else if (cmd === 'reopen') {
        const [sup, price] = arg.split('=')
        await attempt(`${s.part} reopen`, 'rfq', 'POST', `/quotes/${quotes[sup]}/reopen`, { comment: 'Please revisit the tooling lead time; the customer needs samples two weeks earlier.' })
        await attempt(`${s.part} requote`, PERSONA_OF[sup], 'PUT', `/quotes/${quotes[sup]}`, { ...quoteForm(s, Number(price)), toolingLeadTimeDays: 42 })
        await attempt(`${s.part} resubmit`, PERSONA_OF[sup], 'POST', `/quotes/${quotes[sup]}/submit`)
      } else if (cmd === 'quote') {
        for (const pair of arg.split(',')) {
          const [sup, price] = pair.split('=')
          const qid = quotes[sup]
          if (!qid) continue
          await attempt(`${s.part} ${sup} draft`, PERSONA_OF[sup], 'PUT', `/quotes/${qid}`, quoteForm(s, Number(price)))
          if (sup === 'precision') await upload(PERSONA_OF[sup], `/quotes/${qid}/documents`, files.costSheet(s.part), {}, 'Quotation')
          await attempt(`${s.part} ${sup} submit`, PERSONA_OF[sup], 'POST', `/quotes/${qid}/submit`)
        }
      } else if (cmd === 'regret') await attempt(`${s.part} regret`, PERSONA_OF[arg], 'POST', `/quotes/${quotes[arg]}/regret`, { comment: 'Capacity fully committed for the next two quarters.' })
      else if (cmd === 'score') {
        for (const [sup, qid] of Object.entries(quotes)) {
          const k = sup.length
          await attempt(`${s.part} score ${sup}`, 'rfq', 'PUT', `/quotes/${qid}/evaluation`, { scores: { commercial: 6 + (k % 3), technical: 7 + (k % 2), quality: 7 + ((k + 1) % 3), risk: 6 + (k % 4) }, internalRemarks: `${SUPPLIER_NAME[sup]}: capable, delivery history ${k % 2 ? 'good' : 'excellent'}.` })
        }
        await attempt(`${s.part} summary`, 'rfq', 'PUT', `/rfqs/${rfq.id}/comparison`, { internalSummary: 'Three quotes received; all technically acceptable. Spread between lowest and highest is under 6%.', recommendationSummary: 'Recommend the supplier with the best combination of price and delivery reliability.' })
      } else if (cmd === 'shortlist') await attempt(`${s.part} shortlist`, 'rfq', 'POST', `/quotes/${quotes[arg]}/decision`, { decision: 'SHORTLIST', comment: null })
      else if (cmd === 'select') {
        selected = quotes[arg]
        await attempt(`${s.part} select`, 'rfq', 'POST', `/quotes/${selected}/decision`, { decision: 'SELECT_FOR_CUSTOMER', comment: null })
      }
      else if (cmd === 'send') {
        await attempt(`${s.part} send`, 'rfq', 'POST', `/rfqs/${rfq.id}/customer-quotes`, { quoteId: selected, type: 'ERGO_QUOTE', remarks: 'Pricing includes packing and delivery to your plant.', ergoQuote: ergo(Number(arg)) })
      } else if (cmd === 'respond') {
        const cq = await get(s.buyer, `/rfqs/${rfq.id}/customer-quotes`)
        const id = cq.quotes?.[0]?.id
        const comment = arg === 'APPROVED' ? 'Approved. Please proceed with tooling.' : arg === 'DECLINED' ? 'Pricing is above our budget for this programme.' : 'Please review the tooling cost and resubmit.'
        if (id) await attempt(`${s.part} respond`, s.buyer, 'POST', `/customer-quotes/${id}/respond`, { response: arg, comment })
      }
    }
    if (s.plan.includes('review') && !s.plan.some((m) => /^(close|cancel|respond:DECLINED)/.test(m))) await attempt(`${s.part} notes`, 'rfq', 'PUT', `/rfqs/${rfq.id}/internal-notes`, { internalNotes: `Customer is ${s.cust === C.acme ? 'a strategic account' : 'a growing account'}; target margin 8-10%. Check tooling amortisation before quoting.` })
    } catch (e) {
      failures.push(`${s.part}: ${e.message}`)
    }
  }
  log(`  RFQs: ${created} created (${SPECS.length} in the set)`)
  return {}
}
