// Phase 4: nine orders across the whole lifecycle with material status, production progress, packaging, shipments,
// proof of delivery, a hold, a delayed order and an NPD handover. Quality gates are satisfied through real inspections.
import { PERSONA, attempt, call, files, get, getAll, inDays, inspection, log, upload } from './lib.mjs'

const SEQUENCE = ['ORDER_CREATED', 'MATERIAL_ORDERED', 'MATERIAL_RECEIVED', 'PRODUCTION_STARTED', 'IN_INSPECTION', 'PACKAGING_IN_PROGRESS']
const SUPPLIER_OFP = { precision: 'precOfp', shakti: 'shaktiOfp' }

const ORDERS = [
  { part: 'ACM-KNUCKLE-01', rev: 'A', customer: 'acme', supplier: 'precision', qty: 2500, cpo: 'PO-ACM-4471', spo: 'PO-PC-2210', due: 60, to: 'PRODUCTION_STARTED', progress: 'midway', material: 'received' },
  { part: 'ACM-GEARBLANK-09', rev: 'A', customer: 'acme', supplier: 'precision', qty: 4000, cpo: 'PO-ACM-4502', spo: 'PO-PC-2234', due: 25, to: 'DISPATCH_READY', progress: 'done', material: 'received', packaging: 'packed', gate: true },
  { part: 'ACM-SHAFT-20', rev: 'C', customer: 'acme', supplier: 'precision', qty: 1800, cpo: 'PO-ACM-4388', spo: 'PO-PC-2191', due: 8, to: 'IN_TRANSIT', progress: 'done', material: 'received', packaging: 'packed', gate: true, ship: { mode: 'ROAD-FTL', transporter: 'VRL Logistics', forwarder: null, track: 'VRL-88231907', awb: null, eta: 3 } },
  { part: 'BHM-HUB-12', rev: 'A', customer: 'bharat', supplier: 'shakti', qty: 3200, cpo: 'PO-BHM-7710', spo: 'PO-SF-0912', due: -10, to: 'CLOSED', progress: 'done', material: 'received', packaging: 'packed', gate: true, ship: { mode: 'ROAD-FTL', transporter: 'Gati Express', forwarder: null, track: 'GATI-4471290', awb: null, eta: -12 }, pod: true },
  { part: 'SAH-SPRING-15', rev: 'A', customer: 'sahyadri', supplier: 'precision', qty: 5000, cpo: 'PO-SAH-1203', spo: 'PO-PC-2260', due: 75, to: 'ORDER_CREATED', handover: true },
  { part: 'ACM-BUSH-21', rev: 'B', customer: 'acme', supplier: 'precision', qty: 9000, cpo: 'PO-ACM-4519', spo: 'PO-PC-2247', due: 35, to: 'MATERIAL_ORDERED', material: 'delayed' },
  { part: 'BHM-FLYWHEEL-22', rev: 'A', customer: 'bharat', supplier: 'shakti', qty: 1200, cpo: 'PO-BHM-7734', spo: 'PO-SF-0925', due: 30, to: 'PRODUCTION_STARTED', progress: 'blocked', material: 'received', hold: 'Customer revised the drawing; production paused until the new revision is released.' },
  { part: 'ACM-GASKET-23', rev: 'A', customer: 'acme', supplier: 'precision', qty: 15000, cpo: 'PO-ACM-4333', spo: 'PO-PC-2170', due: 2, to: 'DISPATCHED', progress: 'done', material: 'received', packaging: 'packed', gate: true, ship: { mode: 'AIR', transporter: 'Blue Dart Aviation', forwarder: 'DHL Global Forwarding', track: 'BD-77120934', awb: '176-55120934', eta: 2 } },
  { part: 'SAH-LINK-24', rev: 'A', customer: 'sahyadri', supplier: 'precision', qty: 2200, cpo: 'PO-SAH-1166', spo: 'PO-PC-2202', due: -6, to: 'PRODUCTION_STARTED', progress: 'behind', material: 'received' },
]

export async function run(ctx) {
  const C = ctx.companyIds
  const co = { acme: C.acme, bharat: C.bharat, sahyadri: C.sahyadri, precision: C.precision, shakti: C.shakti }
  const modes = Object.fromEntries((await get('admin', '/master-data/shipment-modes')).map((m) => [m.code, m.id]))
  const npdList = await get('npdLead', '/npd')
  const out = []

  for (const o of ORDERS) {
    try {
      const existing = (await getAll('ofp', '/ofp/orders')).find((x) => x.partNumber === o.part)
      let id = existing?.id
      if (!id) {
        let detail
        if (o.handover) {
          const npd = npdList.find((n) => n.partNumber === o.part)
          detail = npd && (await attempt(`${o.part} handover`, 'ofp', 'POST', `/ofp/orders/handover/${npd.id}`, { orderedQuantity: o.qty, dueDate: inDays(o.due), customerPoNo: o.cpo, supplierPoNo: o.spo, ownerUserId: PERSONA.ofp.id }))
        }
        detail ||= await call('ofp', 'POST', '/ofp/orders', { customerCompanyId: co[o.customer], supplierCompanyId: co[o.supplier], customerPoNo: o.cpo, supplierPoNo: o.spo, partNumber: o.part, revision: o.rev, orderedQuantity: o.qty, dueDate: inDays(o.due), committedDispatchDate: inDays(o.due - 4), ownerUserId: PERSONA.ofp.id })
        id = detail.id
      } else if (existing.currentStatus !== 'ORDER_CREATED' && o.to !== 'ORDER_CREATED') {
        out.push(`${o.part}: exists`); continue
      }
      const supplierActor = SUPPLIER_OFP[o.supplier]
            const status = async () => (await get('ofp', `/ofp/orders/${id}`)).currentStatus
      const stepTo = async (target) => {
        for (let guard = 0; guard < 8; guard++) {
          const s = await status()
          if (s === target || SEQUENCE.indexOf(s) < 0 || SEQUENCE.indexOf(target) < 0 || SEQUENCE.indexOf(s) >= SEQUENCE.indexOf(target)) return
          await attempt(`${o.part} advance from ${s}`, 'ofp', 'POST', `/ofp/orders/${id}/advance`, { comment: null })
        }
      }

      // material first (the supplier reports it), then the order moves on
      if (o.material) {
        const m = { received: { materialStatus: 'RECEIVED', materialOrderDate: inDays(-40), expectedReceiptDate: inDays(-24), actualReceiptDate: inDays(-26), inspectionStatus: 'PASSED', remarks: 'Mill test certificate verified on receipt.' },
          delayed: { materialStatus: 'DELAYED', materialOrderDate: inDays(-14), expectedReceiptDate: inDays(-2), actualReceiptDate: null, inspectionStatus: 'PENDING', remarks: 'Mill delayed the rolling slot by two weeks; supplier expediting.' } }[o.material]
        await attempt(`${o.part} material`, 'ofp', 'POST', `/ofp/orders/${id}/material-status`, m)
      }
      await stepTo(o.to === 'ORDER_CREATED' ? 'ORDER_CREATED' : SEQUENCE.includes(o.to) ? o.to : 'PACKAGING_IN_PROGRESS')

      if (o.progress) {
        const q = o.qty
        const steps = {
          midway: [['Hot forging', q, q, q - 18, 18, 0, 'DONE'], ['Heat treatment', q, Math.round(q * 0.6), Math.round(q * 0.58), 6, 8, 'IN_PROGRESS'], ['CNC turning', q, 0, 0, 0, 0, 'NOT_STARTED']],
          done: [['Forging / casting', q, q, q - 22, 22, 0, 'DONE'], ['Machining', q, q, q - 31, 31, 6, 'DONE'], ['Final inspection and cleaning', q, q, q - 31, 0, 0, 'DONE']],
          blocked: [['Forging', q, Math.round(q * 0.4), Math.round(q * 0.4), 0, 0, 'BLOCKED'], ['Machining', q, 0, 0, 0, 0, 'NOT_STARTED']],
          behind: [['Forging', q, q, q - 12, 12, 0, 'DONE'], ['Machining', q, Math.round(q * 0.35), Math.round(q * 0.33), 14, 0, 'IN_PROGRESS']],
        }[o.progress]
        for (const [name, planned, done, accepted, rejected, rework, st] of steps) {
          await attempt(`${o.part} progress ${name}`, supplierActor ?? 'ofp', 'POST', `/ofp/orders/${id}/production-progress`, {
            processStepName: name, plannedQuantity: planned, completedQuantity: done, acceptedQuantity: accepted, rejectedQuantity: rejected, reworkQuantity: rework, status: st,
            blockerReason: st === 'BLOCKED' ? 'Awaiting released drawing revision from the customer' : null, revisedDispatchDate: o.progress === 'behind' ? inDays(10) : null,
          })
        }
      }
      if (o.packaging) {
        await attempt(`${o.part} packaging`, supplierActor ?? 'ofp', 'POST', `/ofp/orders/${id}/packaging-dispatch`, { packedQuantity: o.qty - 31, packagingStatus: 'PACKED', dispatchReadyFlag: true, dispatchDate: inDays(Math.max(o.due - 3, -14)), dispatchRemarks: 'Packed in returnable crates, labelled per customer standard.' })
      }
      if (o.gate) {
        await inspection({ module: 'OFP', recordId: id, part: o.part, batch: `LOT-${id}`, cust: co[o.customer], sup: co[o.supplier], checked: Math.min(o.qty, 315), rejected: 2, defects: [{ defectCategory: 'Surface', defectDescription: 'Minor handling marks, within cosmetic limit', defectQuantity: 2, severity: 'MINOR' }], outcome: 'pass', remarks: 'Final inspection per control plan.' })
        await attempt(`${o.part} dispatch ready`, 'ofp', 'POST', `/ofp/orders/${id}/dispatch-ready`, { comment: null })
      }
      if (['DISPATCHED', 'IN_TRANSIT', 'CLOSED'].includes(o.to)) {
        await attempt(`${o.part} dispatch`, 'ofp', 'POST', `/ofp/orders/${id}/dispatch`, { comment: 'Vehicle left the plant.' })
        const s = o.ship
        const cur = await get('ofp', `/ofp/orders/${id}`)
        let shipment = (cur.shipments ?? [])[0]
        if (!shipment) {
          const d = await attempt(`${o.part} shipment`, 'ofp', 'POST', `/ofp/orders/${id}/shipments`, { shipmentModeId: modes[s.mode], transporterName: s.transporter, forwarderName: s.forwarder, trackingNumber: s.track, awbBlNo: s.awb, eta: inDays(s.eta), actualDeliveryDate: null, status: 'DISPATCHED', receiptConfirmedByCustomerFlag: false })
          shipment = d?.shipments?.[0] ?? (await get('ofp', `/ofp/orders/${id}`)).shipments?.[0]
        }
        if (o.to === 'IN_TRANSIT' || o.to === 'CLOSED') {
          await attempt(`${o.part} in transit`, 'ofp', 'POST', `/ofp/orders/${id}/in-transit`, { comment: null })
          if (shipment) await attempt(`${o.part} shipment transit`, 'ofp', 'PUT', `/ofp/shipments/${shipment.id}`, { shipmentModeId: modes[s.mode], transporterName: s.transporter, forwarderName: s.forwarder, trackingNumber: s.track, awbBlNo: s.awb, eta: inDays(s.eta), actualDeliveryDate: null, status: 'IN_TRANSIT', receiptConfirmedByCustomerFlag: false })
        }
        if (o.pod && shipment) {
          await upload('ofp', `/ofp/shipments/${shipment.id}/pod`, files.pod(`${o.part}-${id}`))
          await attempt(`${o.part} delivered`, 'ofp', 'POST', `/ofp/orders/${id}/delivered`, { comment: 'Delivered and signed for.' })
          await attempt(`${o.part} shipment delivered`, 'ofp', 'PUT', `/ofp/shipments/${shipment.id}`, { shipmentModeId: modes[s.mode], transporterName: s.transporter, forwarderName: s.forwarder, trackingNumber: s.track, awbBlNo: s.awb, eta: inDays(s.eta), actualDeliveryDate: inDays(s.eta + 1), status: 'DELIVERED', receiptConfirmedByCustomerFlag: false })
          await attempt(`${o.part} receipt`, 'ofp', 'PUT', `/ofp/shipments/${shipment.id}`, { shipmentModeId: modes[s.mode], transporterName: s.transporter, forwarderName: s.forwarder, trackingNumber: s.track, awbBlNo: s.awb, eta: inDays(s.eta), actualDeliveryDate: inDays(s.eta + 1), status: 'DELIVERED', receiptConfirmedByCustomerFlag: true })
          if (o.to === 'CLOSED') await attempt(`${o.part} close`, 'ofp', 'POST', `/ofp/orders/${id}/close`, { comment: 'Receipt confirmed; order complete.' })
        }
      }
      if (o.hold) await attempt(`${o.part} hold`, 'ofp', 'POST', `/ofp/orders/${id}/hold`, { comment: o.hold })
      out.push(`${o.part}: ${await status()}`)
    } catch (e) {
      out.push(`${o.part}: FAILED ${e.message.slice(0, 140)}`)
    }
  }
  log(`  orders: ${out.join('; ')}`)
  return {}
}
