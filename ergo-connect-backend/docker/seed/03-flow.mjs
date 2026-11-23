// Phase 3 (flow-aligned): takes the customer-approved parts through Sample PO -> KAM creates the project -> plan release ->
// supplier plan -> ERGO agreement -> execution -> dual quality gate -> customer final approval -> production PO -> OFP project.
// Three stories are left at different points so a demo can pick up live:
//   ACM-KNUCKLE-01   (Acme / Precision)  complete: sample approved, production PO accepted, OFP plan agreed, in execution
//   BHM-HUB-12       (Bharat / Shakti)   plan submitted by the supplier, waiting for ERGO NPD to agree it
//   SAH-SPRING-15    (Sahyadri)          Sample PO raised, waiting for the KAM / Management to create the project
import { PERSONA, attempt, call, get, getAll, inDays, inspection, log } from './lib.mjs'

const post = (who, path, body = {}) => call(who, 'POST', path, body)

const STEPS = [
  ['Raw material availability timeline', 'Material ordered; mill test certificates to be shared before the first trial.'],
  ['Tooling readiness timeline', 'Tool design freeze, build, tryout and handover dates.'],
  ['Sample manufacturing plan date', 'Date the sample lot is produced and inspected in-house.'],
  ['Packaging design', 'Returnable packaging proposal for the sample lot.'],
]

async function addSteps(who, projectId, supplierCompanyId, names = STEPS) {
  let day = 12
  for (const [name, description] of names) {
    await call(who, 'POST', `/npd/${projectId}/activities`, {
      activityName: name, activityDescription: description, status: 'NOT_STARTED', mandatory: false, riskFlag: false, issueFlag: false,
      ownerOrganizationType: 'SUPPLIER', ownerCompanyId: supplierCompanyId, approvalRequired: false, plannedDate: inDays(day),
    })
    day += 7
  }
}

async function itemId(part) {
  const r = (await getAll('admin', '/rfqs')).find((x) => (x.parts ?? []).includes(part))
  if (!r) throw new Error(`no RFQ for ${part}`)
  const view = await get('admin', `/rfqs/${r.id}`)
  return view.items.find((i) => i.partNumber === part).id
}

async function raise(who, part, poNumber, quantity) {
  const po = await post(who, '/npd/sample-pos', { rfqItemId: await itemId(part), customerPoNumber: poNumber, quantity, requiredDate: inDays(45), remarks: 'Sample lot for fitment and functional trials.' })
  return po.id
}

async function drive(projectId, c) {
  for (let guard = 0; guard < 12; guard++) {
    const view = await get('npdLead', `/npd/${projectId}`)
    if (view.stage === 'APPROVED_FOR_PRODUCTION_HANDOVER') return
    if (view.stage === 'FINAL_SAMPLE_READINESS') {
      await inspection({ module: 'NPD', recordId: projectId, part: c.part, batch: 'SAMPLE-01', cust: c.custId, sup: c.supId, outcome: 'pass', remarks: 'Final sample inspection against the customer drawing.' })
      await post(c.supQa, `/npd/${projectId}/validation/submit`)
      await post('quality', `/npd/${projectId}/validation/decide`, { approved: true, comment: 'Dimensional and material reports verified.' })
      await post(c.supQa2, `/npd/${projectId}/validation/decide`, { approved: true, comment: 'Reviewed by a second supplier quality engineer.' })
    } else if (view.stage === 'CUSTOMER_FEEDBACK') {
      await post(c.custNpd, `/npd/${projectId}/validation/decide`, { approved: true, comment: 'Samples approved for fitment.' })
      await post(c.custQa, `/npd/${projectId}/validation/decide`, { approved: true, comment: 'Quality records accepted.' })
    }
    await post('npdLead', `/npd/${projectId}/close-stage`, { comment: `${view.stage.replaceAll('_', ' ').toLowerCase()} complete.` })
  }
}

export async function run(ctx) {
  const C = ctx.companyIds

  // ---- 1. ACM-KNUCKLE-01: the whole journey, left in OFP execution
  try {
    const po = await raise('acmeNpd', 'ACM-KNUCKLE-01', 'ACM-SPO-4101', 25)
    const prj = (await post('kam', `/npd/sample-pos/${po}/create-project`, { ergoNpdUserId: PERSONA.npdLead.id, ergoQualityUserId: PERSONA.quality.id, comment: 'Please plan with Precision; tooling is the long pole.' })).id
    await addSteps('npdLead', prj, C.precision)
    await post('npdLead', `/npd/${prj}/plan/assign`)
    await post('precNpd', `/npd/${prj}/plan/submit`)
    await post('npdLead', `/npd/${prj}/plan/agree`)
    await drive(prj, { part: 'ACM-KNUCKLE-01', custId: C.acme, supId: C.precision, supQa: 'precQa', supQa2: 'precQa2', custNpd: 'acmeNpd', custQa: 'acmeQuality' })
    const ppo = (await post('acmeOfp', '/ofp/production-pos', { sourceProjectId: prj, customerPoNumber: 'ACM-PPO-8801', quantity: 12000, requiredDate: inDays(75), remarks: 'First production lot, delivery in two tranches.' })).id
    const ofp = (await post('kam', `/ofp/production-pos/${ppo}/create-project`, { ergoOfpUserId: PERSONA.ofp.id, ergoQualityUserId: PERSONA.quality.id })).id
    await addSteps('ofp', ofp, C.precision, [['Production capacity confirmation', 'Line and shift plan for the first lot.'], ['Packaging and dispatch plan', 'Pack standard, labelling and dispatch schedule.']])
    await post('ofp', `/npd/${ofp}/plan/assign`)
    await post('precOfp', `/npd/${ofp}/plan/submit`)
    await post('ofp', `/npd/${ofp}/plan/agree`)
    log('  ACM-KNUCKLE-01: sample approved, production PO accepted, OFP project in execution')
  } catch (e) { log('  ACM-KNUCKLE-01 stopped: ' + e.message.slice(0, 240)) }

  // ---- 2. BHM-HUB-12: supplier plan submitted, ERGO NPD still to agree
  try {
    const po = await raise('bharatNpd', 'BHM-HUB-12', 'BHM-SPO-2207', 40)
    const prj = (await post('mgmt', `/npd/sample-pos/${po}/create-project`, { ergoNpdUserId: PERSONA.npdLead.id, ergoQualityUserId: PERSONA.quality.id, comment: null })).id
    await addSteps('npdLead', prj, C.shakti)
    await post('npdLead', `/npd/${prj}/plan/assign`)
    await post('shaktiNpd', `/npd/${prj}/plan/submit`)
    log('  BHM-HUB-12: plan submitted, waiting for ERGO NPD to agree')
  } catch (e) { log('  BHM-HUB-12 stopped: ' + e.message.slice(0, 240)) }

  // ---- 3. SAH-SPRING-15: Sample PO waiting for the KAM
  try {
    await raise('sahyadriNpd', 'SAH-SPRING-15', 'SAH-SPO-0933', 15)
    log('  SAH-SPRING-15: Sample PO raised, waiting for the KAM / Management')
  } catch (e) { log('  SAH-SPRING-15 stopped: ' + e.message.slice(0, 240)) }
  return {}
}
