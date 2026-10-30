// Phase 5: inspections in every result state, NCRs at every stage with CAPA actions and evidence, and deviations
// through the ERGO -> customer approval chain. Maker-checker rules respected (Management decides, creators never do).
import { PERSONA, attempt, files, get, getAll, inDays, inspection, log, upload } from './lib.mjs'

export async function run(ctx) {
  const C = ctx.companyIds
  const orders = Object.fromEntries((await getAll('ofp', '/ofp/orders')).map((o) => [o.partNumber, o.id]))
  const npds = Object.fromEntries((await get('npdLead', '/npd')).map((n) => [n.partNumber, n.id]))
  const cust = { 'ACM-KNUCKLE-01': C.acme, 'ACM-GEARBLANK-09': C.acme, 'ACM-SHAFT-20': C.acme, 'ACM-BUSH-21': C.acme, 'ACM-GASKET-23': C.acme, 'BHM-HUB-12': C.bharat, 'BHM-FLYWHEEL-22': C.bharat, 'SAH-SPRING-15': C.sahyadri, 'SAH-LINK-24': C.sahyadri }
  const sup = (part) => (part.startsWith('BHM') ? C.shakti : C.precision)
  const defect = (category, description, quantity, severity) => ({ defectCategory: category, defectDescription: description, defectQuantity: quantity, severity })

  // ---------------------------------------------------------------- inspections
  const INSPECTIONS = [
    { who: 'quality', module: 'INCOMING', recordId: 101, part: 'ACM-KNUCKLE-01', batch: 'HEAT-88142', outcome: 'pass', checked: 120, rejected: 0, remarks: 'Bar stock chemistry and hardness verified against the mill certificate.' },
    { who: 'quality', module: 'FINAL', recordId: orders['ACM-GEARBLANK-09'], part: 'ACM-GEARBLANK-09', batch: 'FINAL-A', outcome: 'closed', checked: 315, rejected: 3, defects: [defect('Dimensional', 'Tooth profile at upper tolerance limit', 3, 'MINOR')], remarks: 'Pre-shipment audit sample.' },
    { who: 'quality', module: 'IN_PROCESS', recordId: orders['ACM-SHAFT-20'], part: 'ACM-SHAFT-20', batch: 'IP-0417', outcome: 'reject', checked: 80, rejected: 14, defects: [defect('Surface', 'Chatter marks on journal surface', 9, 'MAJOR'), defect('Dimensional', 'Journal diameter 0.04 mm undersize', 5, 'CRITICAL')], remarks: 'Machining line 2 - in-process audit.' },
    { who: 'quality', module: 'INCOMING', recordId: 102, part: 'BHM-FLYWHEEL-22', batch: 'HEAT-90410', outcome: 'inspecting', checked: 60, rejected: 1, defects: [defect('Material', 'One bar with surface seam', 1, 'MINOR')], remarks: 'Waiting for the hardness test lab report.' },
    { who: 'precQa', module: 'IN_PROCESS', recordId: orders['ACM-KNUCKLE-01'], part: 'ACM-KNUCKLE-01', batch: 'SELF-INSP-01', outcome: 'inspecting', checked: 50, rejected: 2, defects: [defect('Dimensional', 'Bore diameter near the upper limit on 2 pieces', 2, 'MINOR')], remarks: 'Supplier self-inspection after forging.' },
    { who: 'quality', module: 'INCOMING', recordId: 103, part: 'SAH-LINK-24', batch: 'HEAT-77310', outcome: 'open', remarks: null },
    { who: 'quality', module: 'OFP', recordId: orders['SAH-LINK-24'], part: 'SAH-LINK-24', batch: 'LOT-DEV', outcome: 'deviation', checked: 200, rejected: 6, defects: [defect('Surface', 'Light scale on non-functional surface', 6, 'MINOR')], remarks: 'Cosmetic only; customer accepted a limited concession.' },
    { who: 'precQa', module: 'FINAL', recordId: orders['ACM-GASKET-23'], part: 'ACM-GASKET-23', batch: 'SUP-FINAL-09', outcome: 'pass', checked: 500, rejected: 0, remarks: 'Supplier final audit before dispatch.' },
    { who: 'quality', module: 'FINAL', recordId: orders['BHM-HUB-12'], part: 'BHM-HUB-12', batch: 'FINAL-HUB-B', outcome: 'closed', checked: 200, rejected: 1, defects: [defect('Casting', 'Minor porosity within accepted level', 1, 'MINOR')], remarks: 'Pre-shipment inspection.' },
  ]
  let ins = 0
  for (const i of INSPECTIONS) {
    if (!i.recordId) continue
    const r = await inspection({ ...i, cust: cust[i.part], sup: sup(i.part), visible: true })
    if (r) ins++
  }
  log(`  inspections: ${ins} raised or already present`)

  // ---------------------------------------------------------------- NCRs
  const ncrs = await getAll('quality', '/quality/ncrs')
  const NCRS = [
    { part: 'ACM-KNUCKLE-01', module: 'NPD', rec: npds['ACM-KNUCKLE-01'], cat: 'Dimensional', desc: 'Bore diameter 0.05 mm over tolerance on 3 trial pieces.', stage: 'CLOSED' },
    { part: 'ACM-SHAFT-20', module: 'OFP', rec: orders['ACM-SHAFT-20'], cat: 'Surface finish', desc: 'Chatter marks found on journal surfaces during in-process audit.', stage: 'PENDING_CLOSURE' },
    { part: 'BHM-FLYWHEEL-22', module: 'OFP', rec: orders['BHM-FLYWHEEL-22'], cat: 'Material', desc: 'Surface seams found on incoming bar stock from the mill.', stage: 'CAPA' },
    { part: 'SAH-LINK-24', module: 'OFP', rec: orders['SAH-LINK-24'], cat: 'Heat treatment', desc: 'Hardness below specification on 6 pieces after quench and temper.', stage: 'ROOT_CAUSE' },
    { part: 'BHM-HUB-12', module: 'OFP', rec: orders['BHM-HUB-12'], cat: 'Packaging', desc: 'Corrosion staining on parts in two crates after transit.', stage: 'OPEN' },
  ]
  let nc = 0
  for (const n of NCRS) {
    if (!n.rec || ncrs.some((x) => x.linkedRecordId === n.rec && x.issueCategory === n.cat)) continue
    const created = await attempt(`ncr ${n.part}`, 'quality', 'POST', '/quality/ncrs', { linkedModule: n.module, linkedRecordId: n.rec, issueCategory: n.cat, issueDescription: n.desc, targetClosureDate: inDays(n.stage === 'CLOSED' ? -5 : 21), customerCompanyId: cust[n.part], supplierCompanyId: sup(n.part), ownerUserId: PERSONA.quality.id })
    if (!created || typeof created !== 'object') continue
    nc++
    const id = created.id
    const f = { issueCategory: n.cat, issueDescription: n.desc, targetClosureDate: inDays(n.stage === 'CLOSED' ? -5 : 21), containmentAction: null, rootCause: null, correctiveAction: null, preventiveAction: null, effectivenessStatus: null, ownerUserId: PERSONA.quality.id, customerCompanyId: cust[n.part], supplierCompanyId: sup(n.part), customerVisible: true }
    const stage = n.stage
    if (stage === 'OPEN') continue
    f.containmentAction = 'All suspect stock quarantined and tagged; 100% sorting of the affected lots started.'
    await attempt(`ncr ${id} containment`, 'quality', 'POST', `/quality/ncrs/${id}/move-to-containment`, f)
    await attempt(`ncr ${id} root cause stage`, 'quality', 'POST', `/quality/ncrs/${id}/move-to-root-cause`, f)
    if (stage === 'ROOT_CAUSE') continue
    f.rootCause = '5-why analysis: worn tooling and an unmonitored process parameter allowed the drift to reach the customer lot.'
    await attempt(`ncr ${id} capa stage`, 'quality', 'POST', `/quality/ncrs/${id}/move-to-capa`, f)
    const actions = [['CORRECTIVE', 'Replace the worn tool and re-validate the first-off piece.', -2], ['PREVENTIVE', 'Add tool-life counter and an SPC alarm on the critical dimension.', 14]]
    for (const [type, text, days] of actions) await attempt(`ncr ${id} capa action`, 'quality', 'POST', `/quality/ncrs/${id}/capa-actions`, { actionType: type, actionDescription: text, targetDate: inDays(days), ownerUserId: PERSONA.quality.id })
    if (stage === 'CAPA') continue
    const detail = await get('quality', `/quality/ncrs/${id}`)
    const list = detail.capaActions ?? []
    for (const a of list) {
      await upload('quality', `/quality/ncrs/${id}/capa-actions/${a.id}/evidence`, files.capaEvidence(`${detail.ncrNumber ?? id}-${a.id}`))
      await attempt(`ncr ${id} close action`, 'quality', 'POST', `/quality/ncrs/${id}/capa-actions/${a.id}/close`)
    }
    f.correctiveAction = 'Worn tooling replaced, process re-validated, three consecutive lots passed with zero findings.'
    f.preventiveAction = 'Tool-life counters and SPC alarms rolled out to all similar machining lines.'
    f.effectivenessStatus = 'Effective - no recurrence in 30 days'
    await attempt(`ncr ${id} submit closure`, 'quality', 'POST', `/quality/ncrs/${id}/submit-for-closure`, f)
    if (stage === 'CLOSED') await attempt(`ncr ${id} close`, 'mgmt', 'POST', `/quality/ncrs/${id}/close`, { reason: 'CAPA verified effective.' })
  }
  log(`  NCRs: ${nc} created`)

  // ---------------------------------------------------------------- deviations
  const devs = await getAll('quality', '/quality/deviations')
  const linkedInspections = await getAll('quality', '/quality/inspections') // a deviation may cite an inspection of the same NPD/OFP record
  const DEVIATIONS = [
    { who: 'quality', part: 'ACM-KNUCKLE-01', module: 'NPD', rec: npds['ACM-KNUCKLE-01'], desc: 'Accept forgings with parting-line flash up to 0.6 mm for the trial lot.', qi: 'None on fit or function.', ti: 'Flash is removed in the next machining step.', cust: true, qty: 400, flow: 'closed' },
    { who: 'precQa', part: 'ACM-GEARBLANK-09', module: 'OFP', rec: orders['ACM-GEARBLANK-09'], desc: 'Supplier requests acceptance of gear blanks with 0.1 mm extra stock on the rim.', qi: 'None; stock is removed in machining.', ti: 'Slightly longer cycle time at the customer.', cust: false, qty: 600, flow: 'requested' },
    { who: 'quality', part: 'BHM-FLYWHEEL-22', module: 'OFP', rec: orders['BHM-FLYWHEEL-22'], desc: 'Use alternate steel grade EN19 for the first 300 pieces until the mill delivery arrives.', qi: 'Equivalent strength; hardness range overlaps.', ti: 'Mass differs by 0.4%.', cust: true, qty: 300, flow: 'review' },
    { who: 'quality', part: 'BHM-HUB-12', module: 'OFP', rec: orders['BHM-HUB-12'], desc: 'Waive the second coating pass on the inner bore.', qi: 'Corrosion protection reduced.', ti: 'Does not meet the drawing coating thickness.', cust: false, qty: 800, flow: 'rejected' },
    { who: 'acmeQuality', part: 'ACM-SHAFT-20', module: 'OFP', rec: orders['ACM-SHAFT-20'], desc: 'Customer asks to ship the lot with chatter marks outside the functional area.', qi: 'Cosmetic only.', ti: 'None.', cust: true, qty: 250, flow: 'sent-back' },
  ]
  let dv = 0
  for (const d of DEVIATIONS) {
    if (!d.rec || devs.some((x) => x.linkedRecordId === d.rec && x.partNumber === d.part && x.linkedModule === d.module)) continue
    const created = await attempt(`deviation ${d.part}`, d.who, 'POST', '/quality/deviations', { linkedModule: d.module, linkedRecordId: d.rec, inspectionId: linkedInspections.find((i) => i.linkedModule === d.module && i.linkedRecordId === d.rec)?.id ?? null, partNumber: d.part, deviationDescription: d.desc, qualityImpact: d.qi, technicalImpact: d.ti, customerApprovalRequiredFlag: d.cust, validityQuantity: d.qty, validityEndDate: inDays(45) })
    if (!created || typeof created !== 'object') continue
    dv++
    const id = created.id
    if (d.flow === 'requested') continue
    await attempt(`deviation ${id} review`, 'quality', 'POST', `/quality/deviations/${id}/start-review`)
    if (d.flow === 'review') continue
    if (d.flow === 'rejected') { await attempt(`deviation ${id} reject`, 'mgmt', 'POST', `/quality/deviations/${id}/reject`, { reason: 'The drawing coating requirement is a safety-relevant corrosion control and cannot be waived.' }); continue }
    if (d.flow === 'sent-back') { await attempt(`deviation ${id} send back`, 'mgmt', 'POST', `/quality/deviations/${id}/send-back`, { reason: 'Please provide measurements of the affected pieces and a photo of the chatter marks.' }); continue }
    await attempt(`deviation ${id} approve`, 'mgmt', 'POST', `/quality/deviations/${id}/approve`, { reason: 'Technically acceptable for the stated quantity.' })
    if (d.cust) await attempt(`deviation ${id} customer approve`, 'acmeQuality', 'POST', `/quality/deviations/${id}/approve`, { reason: 'Customer accepts the deviation for this lot only.' })
    if (d.flow === 'closed') await attempt(`deviation ${id} close`, 'quality', 'POST', `/quality/deviations/${id}/close`, { reason: 'Lot shipped and validity quantity consumed.' })
  }
  log(`  deviations: ${dv} created`)
  return {}
}
