// Phase 3: four NPD projects at different stages with activities (ERGO, supplier and customer owned), prompt
// checklists, issues/risks and approvals, advanced through the real stage-gate rules.
import { PERSONA, attempt, call, get, inDays, inspection, log } from './lib.mjs'

const STAGES = ['PLANNING', 'COMMERCIAL_SOURCING_READINESS', 'TOOLING_MATERIAL_RELEASE', 'TRIAL_PREPARATION', 'TRIAL_EXECUTION', 'FINAL_SAMPLE_READINESS', 'CUSTOMER_FEEDBACK', 'APPROVED_FOR_PRODUCTION_HANDOVER']

// [name, owner (ERGO|SUPPLIER|CUSTOMER), mandatory, approvalType|null, description]
const ACTIVITIES = {
  PLANNING: [
    ['Kick-off meeting', 'ERGO', true, null, 'Align ERGO, supplier and customer on scope, milestones and responsibilities.'],
    ['Project charter and timeline', 'ERGO', true, null, 'Baseline schedule with the nine stage gates and sample dates.'],
    ['Feasibility study', 'SUPPLIER', true, null, 'Supplier confirms manufacturability, tolerances and capacity.'],
  ],
  COMMERCIAL_SOURCING_READINESS: [
    ['Supplier contract and PO terms', 'ERGO', true, null, 'Agreement on commercial terms, tooling ownership and payment milestones.'],
    ['Customer drawing freeze', 'CUSTOMER', true, 'CUSTOMER_MILESTONE_ACKNOWLEDGMENT', 'Customer acknowledges the frozen drawing revision.'],
    ['Packaging standard agreed', 'CUSTOMER', false, null, 'Returnable or one-way packaging, labelling format.'],
  ],
  TOOLING_MATERIAL_RELEASE: [
    ['Tooling design review', 'ERGO', true, null, 'Review die/fixture design for manufacturability.'],
    ['Tool manufacturing', 'SUPPLIER', true, null, 'Tool built, tried out and handed over to production.'],
    ['Raw material release', 'SUPPLIER', true, null, 'Material ordered and test certificates received.'],
  ],
  TRIAL_PREPARATION: [
    ['Trial plan and control plan', 'ERGO', true, null, 'Sampling plan, control plan and PFMEA reviewed.'],
    ['Gauges and fixtures ready', 'SUPPLIER', true, null, 'Gauge R&R complete for critical dimensions.'],
  ],
  TRIAL_EXECUTION: [
    ['First-off trial run', 'SUPPLIER', true, null, 'Trial lot produced on production tooling.'],
    ['Dimensional report (CMM)', 'SUPPLIER', true, null, 'Full layout inspection on the trial pieces.'],
    ['Trial review with customer', 'ERGO', false, null, 'Findings and corrective steps reviewed with the customer.'],
  ],
  FINAL_SAMPLE_READINESS: [
    ['Sample packing and labelling', 'SUPPLIER', true, null, 'Samples packed to the agreed standard with traceability labels.'],
    ['PPAP documentation', 'SUPPLIER', true, null, 'PPAP level 3 package compiled.'],
  ],
  CUSTOMER_FEEDBACK: [
    ['Customer sample approval', 'CUSTOMER', true, 'CUSTOMER_SAMPLE_APPROVAL', 'Customer evaluates and signs off the sample.'],
  ],
}

const PROJECTS = [
  { part: 'ACM-KNUCKLE-01', supplier: 'precision', customer: 'acme', target: 'TRIAL_EXECUTION', risk: 'HIGH', targetDays: -6, note: 'delayed' },
  { part: 'ACM-GEARBLANK-09', supplier: 'precision', customer: 'acme', target: 'COMMERCIAL_SOURCING_READINESS', risk: 'MEDIUM', targetDays: 40 },
  { part: 'BHM-HUB-12', supplier: 'shakti', customer: 'bharat', target: 'FINAL_SAMPLE_READINESS', risk: 'LOW', targetDays: 12, sampleSent: -3, finalInspection: 'inspecting' },
  { part: 'SAH-SPRING-15', supplier: 'precision', customer: 'sahyadri', target: 'APPROVED_FOR_PRODUCTION_HANDOVER', risk: 'LOW', targetDays: -20, sampleSent: -24, finalInspection: 'pass' },
]

export async function run(ctx) {
  const C = ctx.companyIds
  const companyOf = { precision: C.precision, shakti: C.shakti, acme: C.acme, bharat: C.bharat, sahyadri: C.sahyadri }
  const rfqs = await get('admin', '/rfqs')
  const existing = await get('npd', '/npd')
  const queue = await get('npd', '/npd/conversion-queue')
  const out = []

  for (const proj of PROJECTS) {
    try {
      let row = existing.find((n) => n.partNumber === proj.part)
      if (!row) {
        const rfq = rfqs.find((r) => r.partNumber === proj.part)
        const inQueue = queue.find((q) => q.partNumber === proj.part)
        if (!rfq || !inQueue) { log(`  skip ${proj.part}: RFQ not approved/convertible`); continue }
        const view = await call('npdLead', 'POST', `/npd/convert/${rfq.id}`, { comment: 'Customer approved; starting NPD.' })
        row = { id: view.id }
      }
      const id = row.id
      let view = await get('npdLead', `/npd/${id}`)
      const supplierId = companyOf[proj.supplier]
      const customerId = companyOf[proj.customer]
      const SUP_NPD = { precision: 'precNpd', shakti: 'shaktiNpd' }
      const CUS_NPD = { acme: 'acmeNpd', bharat: 'bharatNpd', sahyadri: 'sahyadriNpd' }
      const actorFor = (owner) => (owner === 'SUPPLIER' ? SUP_NPD[proj.supplier] : owner === 'CUSTOMER' ? CUS_NPD[proj.customer] : 'npdLead')
      const responsible = (owner) => PERSONA[actorFor(owner)]?.id ?? null

      await attempt(`${proj.part} project`, 'npdLead', 'PUT', `/npd/${id}`, { ownerUserId: 11, riskLevel: proj.risk, targetSampleDate: inDays(proj.targetDays), actualSampleSubmissionDate: proj.sampleSent != null ? inDays(proj.sampleSent) : null })

      const targetIdx = STAGES.indexOf(proj.target)
      let stageNow = STAGES.indexOf(view.stage)
      // work through the stages up to (not including) the target: create, complete, gate, close
      for (let i = Math.max(stageNow, 0); i < targetIdx; i++) {
        const stage = STAGES[i]
        view = await get('npdLead', `/npd/${id}`)
        if (view.stage !== stage) break
        let list = view.activities.filter((a) => a.phaseName === stage)
        if (list.length === 0) {
          for (const [name, owner, mandatory, approvalType, description] of ACTIVITIES[stage] ?? []) {
            await attempt(`${proj.part} activity ${name}`, 'npdLead', 'POST', `/npd/${id}/activities`, {
              activityName: name, activityDescription: description, ownerOrganizationType: owner, ownerCompanyId: owner === 'SUPPLIER' ? supplierId : owner === 'CUSTOMER' ? customerId : null,
              responsibleUserId: responsible(owner),
              plannedDate: inDays(-60 + i * 9), baselineDate: inDays(-60 + i * 9), actualDate: null, dependencyActivityId: null, status: 'NOT_STARTED',
              mandatory, riskFlag: false, issueFlag: false, approvalRequired: !!approvalType, requiredApprovalType: approvalType,
            })
          }
          view = await get('npdLead', `/npd/${id}`)
          list = view.activities.filter((a) => a.phaseName === stage)
        }
        for (const [idx, a] of list.entries()) {
          if (a.status !== 'DONE') {
            const owner = a.ownerOrganizationType
            if (owner === 'ERGO' || actorFor(owner) === 'npdLead') {
              await attempt(`${proj.part} done ${a.activityName}`, 'npdLead', 'PUT', `/npd/activities/${a.id}`, { activityName: a.activityName, activityDescription: a.activityDescription, ownerOrganizationType: owner, ownerCompanyId: owner === 'SUPPLIER' ? supplierId : owner === 'CUSTOMER' ? customerId : null, responsibleUserId: a.responsibleUserId, plannedDate: a.plannedDate, baselineDate: a.baselineDate, actualDate: a.plannedDate, dependencyActivityId: idx > 0 && list[idx - 1].ownerOrganizationType === 'ERGO' ? list[idx - 1].id : null, status: 'DONE', mandatory: a.mandatoryFlag, riskFlag: false, issueFlag: false, approvalRequired: a.approvalRequiredFlag, requiredApprovalType: a.requiredApprovalType ?? null })
            } else {
              await attempt(`${proj.part} progress ${a.activityName}`, actorFor(owner), 'POST', `/npd/activities/${a.id}/progress`, { status: 'DONE', actualDate: a.plannedDate, issueFlag: false, riskFlag: false, remarks: 'Completed and verified.' })
            }
          }
          if (a.approvalRequiredFlag) {
            const ap = await attempt(`${proj.part} approval ${a.activityName}`, 'npdLead', 'POST', `/npd/${id}/approvals`, { approvalType: a.requiredApprovalType, linkedActivityId: a.id })
            const fresh = (await get('npdLead', `/npd/${id}`)).approvals.find((x) => x.linkedActivityId === a.id && x.approvalStatus === 'PENDING')
            if (fresh) await attempt(`${proj.part} decide`, actorFor('CUSTOMER'), 'POST', `/npd/approvals/${fresh.id}/decide`, { approved: true, comments: 'Acknowledged.' })
          }
        }
        if (stage === 'FINAL_SAMPLE_READINESS') await inspection({ module: 'NPD', recordId: id, part: proj.part, batch: 'SAMPLE-01', cust: customerId, sup: supplierId, outcome: 'pass', remarks: 'Final sample inspection against drawing.' })
        await attempt(`${proj.part} close ${stage}`, 'npdLead', 'POST', `/npd/${id}/close-stage`, { comment: `${stage.replaceAll('_', ' ').toLowerCase()} complete.` })
      }

      // the stage the project is in now: leave it visibly mid-flight
      view = await get('npdLead', `/npd/${id}`)
      const stage = view.stage
      if (stage === proj.target && stage !== 'APPROVED_FOR_PRODUCTION_HANDOVER' && view.activities.filter((a) => a.phaseName === stage).length === 0) {
        const defs = ACTIVITIES[stage] ?? []
        for (const [k, [name, owner, mandatory, approvalType, description]] of defs.entries()) {
          await attempt(`${proj.part} activity ${name}`, 'npdLead', 'POST', `/npd/${id}/activities`, {
            activityName: name, activityDescription: description, ownerOrganizationType: owner, ownerCompanyId: owner === 'SUPPLIER' ? supplierId : owner === 'CUSTOMER' ? customerId : null,
            responsibleUserId: responsible(owner),
            plannedDate: inDays(proj.note === 'delayed' ? -10 + k * 3 : 7 + k * 6), baselineDate: inDays(proj.note === 'delayed' ? -14 + k * 3 : 7 + k * 6), actualDate: null, dependencyActivityId: null, status: 'NOT_STARTED',
            mandatory, riskFlag: k === 1, issueFlag: false, approvalRequired: !!approvalType, requiredApprovalType: approvalType,
          })
        }
        view = await get('npdLead', `/npd/${id}`)
        const cur = view.activities.filter((a) => a.phaseName === stage)
        // first done, second in progress (supplier-reported), third blocked on the delayed project
        if (cur[0]) await attempt('mid done', 'npdLead', 'PUT', `/npd/activities/${cur[0].id}`, { activityName: cur[0].activityName, activityDescription: cur[0].activityDescription, ownerOrganizationType: cur[0].ownerOrganizationType, ownerCompanyId: cur[0].ownerOrganizationType === 'SUPPLIER' ? supplierId : cur[0].ownerOrganizationType === 'CUSTOMER' ? customerId : null, responsibleUserId: cur[0].responsibleUserId, plannedDate: cur[0].plannedDate, baselineDate: cur[0].baselineDate, actualDate: inDays(-2), dependencyActivityId: null, status: 'DONE', mandatory: cur[0].mandatoryFlag, riskFlag: false, issueFlag: false, approvalRequired: cur[0].approvalRequiredFlag, requiredApprovalType: cur[0].requiredApprovalType ?? null })
        if (cur[1]) await attempt('mid progress', actorFor(cur[1].ownerOrganizationType) === 'npdLead' ? 'npdLead' : actorFor(cur[1].ownerOrganizationType), cur[1].ownerOrganizationType === 'ERGO' || actorFor(cur[1].ownerOrganizationType) === 'npdLead' ? 'PUT' : 'POST', cur[1].ownerOrganizationType === 'ERGO' || actorFor(cur[1].ownerOrganizationType) === 'npdLead' ? `/npd/activities/${cur[1].id}` : `/npd/activities/${cur[1].id}/progress`,
          cur[1].ownerOrganizationType === 'ERGO' || actorFor(cur[1].ownerOrganizationType) === 'npdLead'
            ? { activityName: cur[1].activityName, activityDescription: cur[1].activityDescription, ownerOrganizationType: cur[1].ownerOrganizationType, ownerCompanyId: cur[1].ownerOrganizationType === 'SUPPLIER' ? supplierId : cur[1].ownerOrganizationType === 'CUSTOMER' ? customerId : null, responsibleUserId: cur[1].responsibleUserId, plannedDate: cur[1].plannedDate, baselineDate: cur[1].baselineDate, actualDate: null, dependencyActivityId: null, status: 'IN_PROGRESS', mandatory: cur[1].mandatoryFlag, riskFlag: true, issueFlag: false, approvalRequired: cur[1].approvalRequiredFlag, requiredApprovalType: cur[1].requiredApprovalType ?? null }
            : { status: 'IN_PROGRESS', actualDate: null, issueFlag: false, riskFlag: true, remarks: 'On track; watching raw material delivery.' })
        if (proj.note === 'delayed' && cur[2]) await attempt('mid block', 'npdLead', 'POST', `/npd/activities/${cur[2].id}/block`, { comment: 'Waiting for the customer to release the revised gauge drawing.' })
        // a prompt checklist on the first supplier activity
        const sup = cur.find((a) => a.ownerOrganizationType === 'SUPPLIER')
        if (sup) {
          const questions = [['Is the control plan approved by the customer?', 'YES_NO'], ['Describe the in-process inspection frequency.', 'TEXT'], ['Is the PFMEA attached and current?', 'YES_NO']]
          for (const [q, [text, type]] of questions.entries()) await attempt('checklist', 'npdLead', 'POST', `/npd/${id}/prompt-checklists`, { activityId: sup.id, questionText: text, responseType: type, mandatory: q < 2, sequenceNo: q + 1 })
          view = await get('npdLead', `/npd/${id}`)
          const lists = (view.activities.find((a) => a.id === sup.id)?.promptChecklists) ?? []
          const responder = actorFor('SUPPLIER')
          for (const pc of lists.slice(0, 2)) await attempt('checklist answer', responder, 'POST', `/npd/prompt-checklists/${pc.id}/response`, { responseValue: pc.responseType === 'YES_NO' ? 'YES' : 'Dimensional check every 25 pieces, CMM layout every shift.', remarks: 'Evidence is in the PPAP folder, section 4.' })
        }
      }

      // issues and risks, with owners and statuses
      view = await get('npdLead', `/npd/${id}`)
      if ((view.issuesRisks ?? []).length === 0) {
        const linkId = view.activities.filter((a) => a.phaseName === view.stage)[0]?.id ?? null
        const issues = [
          ['ISSUE', 'Die insert wear above the allowed limit', 'Wear measured after the first 800 pieces exceeds the tool-life plan.', 'MEDIUM', 'SUPPLIER', supplierId, 'OPEN'],
          ['RISK', 'Raw material lead time may slip', 'Steel mill quoted 5 weeks against a 3 week plan.', 'HIGH', 'SUPPLIER', supplierId, 'IN_PROGRESS'],
          ['ISSUE', 'Drawing note 7 unclear', 'Surface finish callout conflicts with the coating specification.', 'LOW', 'CUSTOMER', customerId, 'CLOSED'],
        ]
        for (const [n, [type, title, description, severity, ownerType, ownerCompanyId, status]] of issues.entries()) {
          const r = await attempt(`${proj.part} issue`, 'npdLead', 'POST', `/npd/${id}/issues`, { type, title, description, severity, ownerOrganizationType: ownerType, ownerCompanyId, targetDate: inDays(14), linkedActivityId: n === 0 ? linkId : null })
          if (r && status !== 'OPEN') {
            const fresh = (await get('npdLead', `/npd/${id}`)).issuesRisks.find((x) => x.title === title)
            if (fresh) await attempt('issue status', 'npdLead', 'PUT', `/npd/issues/${fresh.id}/status`, { status, comment: status === 'CLOSED' ? 'Customer issued drawing revision B.' : 'Mitigation under way.' })
          }
        }
      }

      // an approval waiting on someone, and a gate that was approved
      view = await get('npdLead', `/npd/${id}`)
      if (!(view.approvals ?? []).some((a) => a.approvalStatus === 'PENDING') && proj.target !== 'APPROVED_FOR_PRODUCTION_HANDOVER') {
        await attempt(`${proj.part} gate approval`, 'npdLead', 'POST', `/npd/${id}/approvals`, { approvalType: 'STAGE_GATE', linkedActivityId: null })
        await attempt(`${proj.part} quality gate`, 'npdLead', 'POST', `/npd/${id}/approvals`, { approvalType: 'QUALITY_GATE', linkedActivityId: null })
        const fresh = (await get('npdLead', `/npd/${id}`)).approvals.find((a) => a.approvalType === 'STAGE_GATE' && a.approvalStatus === 'PENDING')
        if (fresh) await attempt('gate decide', 'admin', 'POST', `/npd/approvals/${fresh.id}/decide`, { approved: true, comments: 'Gate criteria met.' })
      }

      if (proj.finalInspection && proj.target === 'FINAL_SAMPLE_READINESS') {
        await inspection({ module: 'NPD', recordId: id, part: proj.part, batch: 'SAMPLE-01', cust: customerId, sup: supplierId, outcome: proj.finalInspection, remarks: 'Final sample inspection in progress.' })
      }
      out.push(`${proj.part}: ${(await get('npdLead', `/npd/${id}`)).stage}`)
    } catch (e) {
      out.push(`${proj.part}: FAILED ${e.message.slice(0, 160)}`)
    }
  }
  log(`  NPD projects: ${out.join('; ')}`)
  return {}
}
