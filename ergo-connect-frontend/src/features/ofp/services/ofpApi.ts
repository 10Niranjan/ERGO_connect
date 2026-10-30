import { api } from '@/services/baseApi'
import type { AuditEntry } from '@/features/rfq/rfqApi'

/** SRS §13.5.1/§14.6 order lifecycle. {@link OrderStatus} mirrors the backend enum exactly. ON_HOLD is a side
 * transition reachable from any non-terminal status; the status it interrupted is not exposed to the frontend
 * (recovered server-side from the audit trail only). */
export type OrderStatus =
  | 'ORDER_CREATED'
  | 'MATERIAL_ORDERED'
  | 'MATERIAL_RECEIVED'
  | 'PRODUCTION_STARTED'
  | 'IN_INSPECTION'
  | 'PACKAGING_IN_PROGRESS'
  | 'DISPATCH_READY'
  | 'DISPATCHED'
  | 'IN_TRANSIT'
  | 'DELIVERED'
  | 'CLOSED'
  | 'ON_HOLD'

/** Buttons the backend allows for this user, role and order right now (OfpDtos.OrderAction). */
export type OrderAction =
  | 'EDIT'
  | 'ADVANCE'
  | 'HOLD'
  | 'RELEASE'
  | 'UPDATE_MATERIAL_STATUS'
  | 'UPDATE_PRODUCTION_PROGRESS'
  | 'UPDATE_PACKAGING_DISPATCH'
  | 'MARK_DISPATCH_READY'
  | 'CREATE_SHIPMENT'
  | 'UPDATE_SHIPMENT'
  | 'DISPATCH'
  | 'MARK_IN_TRANSIT'
  | 'MARK_DELIVERED'
  | 'CLOSE_ORDER'

export type MaterialStatus = 'PENDING' | 'ORDERED' | 'RECEIVED' | 'DELAYED'
export type InspectionStatus = 'PENDING' | 'PASSED' | 'FAILED'
export type ProductionStatus = 'NOT_STARTED' | 'IN_PROGRESS' | 'DONE' | 'BLOCKED'
export type PackagingStatus = 'PENDING' | 'IN_PROGRESS' | 'PACKED'

// ---------------------------------------------------------------- forms

/** Direct standalone create (repeat/running orders): both companies given explicitly, no NPD/RFQ link. */
export interface OrderForm {
  customerCompanyId: number
  supplierCompanyId: number
  customerPoNo: string | null
  supplierPoNo: string | null
  partNumber: string
  revision: string | null
  orderedQuantity: number
  dueDate: string | null
  committedDispatchDate: string | null
  ownerUserId: number | null
}

/** NPD's "Handover to OFP" action: customer/supplier/part/revision come from the NPD project itself. */
export interface OrderHandoverForm {
  orderedQuantity: number
  dueDate: string | null
  customerPoNo: string | null
  supplierPoNo: string | null
  ownerUserId: number | null
}

/** Full replace of the header fields still open to change after creation. */
export interface OrderUpdateForm {
  customerPoNo: string | null
  supplierPoNo: string | null
  dueDate: string | null
  committedDispatchDate: string | null
  ownerUserId: number | null
}

export interface MaterialStatusForm {
  materialStatus: MaterialStatus
  materialOrderDate: string | null
  expectedReceiptDate: string | null
  actualReceiptDate: string | null
  inspectionStatus: InspectionStatus
  remarks: string | null
}

export interface ProductionProgressForm {
  processStepName: string
  plannedQuantity: number | null
  completedQuantity: number | null
  acceptedQuantity: number | null
  rejectedQuantity: number | null
  reworkQuantity: number | null
  status: ProductionStatus
  blockerReason: string | null
  revisedDispatchDate: string | null
}

/** The assigned supplier (or ERGO OFP/Admin) fills this in. `dispatchReadyFlag` is a readiness claim only — it
 * never moves the order's own status by itself; only ERGO's separate Mark Dispatch Ready action does that. */
export interface PackagingDispatchForm {
  packedQuantity: number | null
  packagingStatus: PackagingStatus
  dispatchReadyFlag: boolean
  dispatchDate: string | null
  dispatchRemarks: string | null
}

/** ERGO OFP/Admin only. `status` must be DISPATCHED/IN_TRANSIT/DELIVERED (null defaults to DISPATCHED). No
 * `podDocumentId` — a POD is set only via the dedicated upload endpoint, never a plain client-supplied id. */
export interface ShipmentForm {
  shipmentModeId: number | null
  transporterName: string | null
  forwarderName: string | null
  trackingNumber: string | null
  awbBlNo: string | null
  eta: string | null
  actualDeliveryDate: string | null
  status: OrderStatus | null
  receiptConfirmedByCustomerFlag: boolean
}

// ---------------------------------------------------------------- views

/** ERGO's material-status row, with internal free-text remarks. */
export interface MaterialStatusView {
  id: number
  materialStatus: MaterialStatus
  materialOrderDate: string | null
  expectedReceiptDate: string | null
  actualReceiptDate: string | null
  inspectionStatus: InspectionStatus
  remarks: string | null
  createdAt: string
  updatedAt: string
}

/** The supplier's and customer's material-status row: same as {@link MaterialStatusView} minus `remarks` (could
 * name the other party). */
export interface SupplierMaterialStatusView {
  id: number
  materialStatus: MaterialStatus
  materialOrderDate: string | null
  expectedReceiptDate: string | null
  actualReceiptDate: string | null
  inspectionStatus: InspectionStatus
  createdAt: string
  updatedAt: string
}

/** Shared by ERGO and the supplier (the party that actually enters production data). */
export interface ProductionProgressView {
  id: number
  processStepName: string
  plannedQuantity: number | null
  completedQuantity: number
  acceptedQuantity: number
  rejectedQuantity: number
  reworkQuantity: number
  status: ProductionStatus
  blockerReason: string | null
  revisedDispatchDate: string | null
  updatedBy: string | null
  updatedAt: string
}

/** The customer's production-progress row: no `blockerReason` (a supplier's free text) and no `updatedBy` (a
 * supplier employee's name). */
export interface CustomerProductionProgressView {
  id: number
  processStepName: string
  plannedQuantity: number | null
  completedQuantity: number
  acceptedQuantity: number
  rejectedQuantity: number
  reworkQuantity: number
  status: ProductionStatus
  revisedDispatchDate: string | null
  updatedAt: string
}

export interface PackagingDispatchView {
  id: number
  packedQuantity: number
  packagingStatus: PackagingStatus
  dispatchReadyFlag: boolean
  dispatchDate: string | null
  dispatchRemarks: string | null
  updatedBy: string | null
  updatedAt: string
}

/** ERGO-only: carries the POD reference and who last updated the row. A POD's file name routinely names the
 * consignee, so this is never sent to the supplier or the customer — see {@link ExternalShipmentView}. */
export interface ShipmentView {
  id: number
  shipmentModeId: number | null
  transporterName: string | null
  forwarderName: string | null
  trackingNumber: string | null
  awbBlNo: string | null
  eta: string | null
  actualDeliveryDate: string | null
  podDocumentId: number | null
  podFileName: string | null
  receiptConfirmedByCustomerFlag: boolean
  receiptConfirmedAt: string | null
  status: OrderStatus
  updatedBy: string | null
  updatedAt: string
}

/** The supplier's and customer's shipment view: no POD reference, no `updatedBy` (always an ERGO employee). */
export interface ExternalShipmentView {
  id: number
  shipmentModeId: number | null
  transporterName: string | null
  forwarderName: string | null
  trackingNumber: string | null
  awbBlNo: string | null
  eta: string | null
  actualDeliveryDate: string | null
  receiptConfirmedByCustomerFlag: boolean
  receiptConfirmedAt: string | null
  status: OrderStatus
  updatedAt: string
}

// ---------------------------------------------------------------- per-audience list rows and detail views
//
// The backend serializes a sealed interface (OfpDtos.OrderSummary/OrderDetail) with no discriminant field (unlike
// NpdDtos, which adds an explicit `audience` string) — the three shapes are told apart structurally. `customer` is
// unique to the ERGO row/view; after excluding that, `supplierPoNo`/`customerPoNo` tell the other two apart.

/** ERGO's list row: both companies, owner. */
export interface OrderRow {
  id: number
  orderNumber: string
  customer: string
  supplier: string
  partNumber: string
  revision: string | null
  orderedQuantity: number
  dueDate: string | null
  currentStatus: OrderStatus
  owner: string | null
}

/** Two-way anonymity: no field names or identifies the customer, and no ERGO owner. */
export interface SupplierOrderRow {
  id: number
  orderNumber: string
  supplierPoNo: string | null
  partNumber: string
  revision: string | null
  orderedQuantity: number
  dueDate: string | null
  currentStatus: OrderStatus
}

/** Two-way anonymity extended to the customer: no field names or identifies the supplier, and no ERGO owner. */
export interface CustomerOrderRow {
  id: number
  orderNumber: string
  customerPoNo: string | null
  partNumber: string
  revision: string | null
  orderedQuantity: number
  dueDate: string | null
  currentStatus: OrderStatus
}

export type OrderSummary = OrderRow | SupplierOrderRow | CustomerOrderRow

/** ERGO's unrestricted detail view. */
export interface OrderView {
  id: number
  orderNumber: string
  customer: string
  supplier: string
  linkedNpdProjectId: number | null
  linkedRfqId: number | null
  customerPoNo: string | null
  supplierPoNo: string | null
  partNumber: string
  revision: string | null
  orderedQuantity: number
  dueDate: string | null
  committedDispatchDate: string | null
  actualDispatchDate: string | null
  currentStatus: OrderStatus
  ownerUserId: number | null
  owner: string | null
  materialStatus: MaterialStatusView[]
  productionProgress: ProductionProgressView[]
  packagingDispatch: PackagingDispatchView[]
  shipments: ShipmentView[]
  actions: OrderAction[]
  createdAt: string
  updatedAt: string
}

/** The supplier's detail view: no customer name/id, no customerPoNo, no linked RFQ/NPD id, no ERGO owner, no
 * material-status remarks. */
export interface SupplierOrderView {
  id: number
  orderNumber: string
  supplierPoNo: string | null
  partNumber: string
  revision: string | null
  orderedQuantity: number
  dueDate: string | null
  committedDispatchDate: string | null
  actualDispatchDate: string | null
  currentStatus: OrderStatus
  materialStatus: SupplierMaterialStatusView[]
  productionProgress: ProductionProgressView[]
  packagingDispatch: PackagingDispatchView[]
  shipments: ExternalShipmentView[]
  actions: OrderAction[]
  createdAt: string
  updatedAt: string
}

/** The customer's detail view: no supplier name/id, no ERGO owner, no linked RFQ/NPD id, no packaging/dispatch
 * field at all (Customer OFP has no business reason to see it), and `actions` is always empty (pure read-only). */
export interface CustomerOrderView {
  id: number
  orderNumber: string
  customerPoNo: string | null
  partNumber: string
  revision: string | null
  orderedQuantity: number
  dueDate: string | null
  committedDispatchDate: string | null
  actualDispatchDate: string | null
  currentStatus: OrderStatus
  materialStatus: SupplierMaterialStatusView[]
  productionProgress: CustomerProductionProgressView[]
  shipments: ExternalShipmentView[]
  actions: OrderAction[]
  createdAt: string
  updatedAt: string
}

export interface OrderPage {
  items: OrderSummary[]
  /** Every order that matches, not just this page. */
  total: number
  page: number
  size: number
}

export interface OrderListArgs {
  page: number
  size: number
  status?: string
  q?: string
  delayed?: boolean
}

export type OrderDetail = OrderView | SupplierOrderView | CustomerOrderView

const ofpApi = api.enhanceEndpoints({ addTagTypes: ['Ofp', 'Npd'] }).injectEndpoints({
  endpoints: (build) => ({
    // One page, newest first; filtered and searched by the server.
    ofpOrders: build.query<OrderPage, OrderListArgs>({
      query: ({ page, size, status, q, delayed }) => ({
        url: '/ofp/orders',
        params: { page, size, ...(status ? { status } : {}), ...(q ? { q } : {}), ...(delayed ? { delayed: true } : {}) },
      }),
      providesTags: ['Ofp'],
    }),
    ofpOrder: build.query<OrderDetail, number>({
      query: (id) => `/ofp/orders/${id}`,
      providesTags: (_r, _e, id) => [{ type: 'Ofp', id }],
    }),
    ofpOrderHistory: build.query<AuditEntry[], number>({
      query: (id) => `/ofp/orders/${id}/history`,
      providesTags: (_r, _e, id) => [{ type: 'Ofp', id }],
    }),
    createOrder: build.mutation<OrderDetail, OrderForm>({
      query: (body) => ({ url: '/ofp/orders', method: 'POST', body }),
      invalidatesTags: ['Ofp'],
    }),
    /** NPD's "Handover to OFP" action; also invalidates Npd since it flips the source project's handover flag. */
    handoverOrder: build.mutation<OrderDetail, { npdProjectId: number; form: OrderHandoverForm }>({
      query: ({ npdProjectId, form }) => ({ url: `/ofp/orders/handover/${npdProjectId}`, method: 'POST', body: form }),
      invalidatesTags: ['Ofp', 'Npd'],
    }),
    updateOrder: build.mutation<OrderDetail, { id: number; form: OrderUpdateForm }>({
      query: ({ id, form }) => ({ url: `/ofp/orders/${id}`, method: 'PUT', body: form }),
      invalidatesTags: ['Ofp'],
    }),
    advanceOrder: build.mutation<OrderDetail, { id: number; comment?: string }>({
      query: ({ id, comment }) => ({ url: `/ofp/orders/${id}/advance`, method: 'POST', body: { comment: comment ?? null } }),
      invalidatesTags: ['Ofp'],
    }),
    holdOrder: build.mutation<OrderDetail, { id: number; comment: string }>({
      query: ({ id, comment }) => ({ url: `/ofp/orders/${id}/hold`, method: 'POST', body: { comment } }),
      invalidatesTags: ['Ofp'],
    }),
    releaseOrder: build.mutation<OrderDetail, { id: number; comment?: string }>({
      query: ({ id, comment }) => ({ url: `/ofp/orders/${id}/release`, method: 'POST', body: { comment: comment ?? null } }),
      invalidatesTags: ['Ofp'],
    }),
    addMaterialStatus: build.mutation<OrderDetail, { id: number; form: MaterialStatusForm }>({
      query: ({ id, form }) => ({ url: `/ofp/orders/${id}/material-status`, method: 'POST', body: form }),
      invalidatesTags: ['Ofp'],
    }),
    updateMaterialStatus: build.mutation<OrderDetail, { statusId: number; form: MaterialStatusForm }>({
      query: ({ statusId, form }) => ({ url: `/ofp/material-status/${statusId}`, method: 'PUT', body: form }),
      invalidatesTags: ['Ofp'],
    }),
    addProductionProgress: build.mutation<OrderDetail, { id: number; form: ProductionProgressForm }>({
      query: ({ id, form }) => ({ url: `/ofp/orders/${id}/production-progress`, method: 'POST', body: form }),
      invalidatesTags: ['Ofp'],
    }),
    updateProductionProgress: build.mutation<OrderDetail, { progressId: number; form: ProductionProgressForm }>({
      query: ({ progressId, form }) => ({ url: `/ofp/production-progress/${progressId}`, method: 'PUT', body: form }),
      invalidatesTags: ['Ofp'],
    }),
    addPackagingDispatch: build.mutation<OrderDetail, { id: number; form: PackagingDispatchForm }>({
      query: ({ id, form }) => ({ url: `/ofp/orders/${id}/packaging-dispatch`, method: 'POST', body: form }),
      invalidatesTags: ['Ofp'],
    }),
    updatePackagingDispatch: build.mutation<OrderDetail, { dispatchId: number; form: PackagingDispatchForm }>({
      query: ({ dispatchId, form }) => ({ url: `/ofp/packaging-dispatch/${dispatchId}`, method: 'PUT', body: form }),
      invalidatesTags: ['Ofp'],
    }),
    markDispatchReady: build.mutation<OrderDetail, { id: number; comment?: string }>({
      query: ({ id, comment }) => ({ url: `/ofp/orders/${id}/dispatch-ready`, method: 'POST', body: { comment: comment ?? null } }),
      invalidatesTags: ['Ofp'],
    }),
    createShipment: build.mutation<OrderDetail, { id: number; form: ShipmentForm }>({
      query: ({ id, form }) => ({ url: `/ofp/orders/${id}/shipments`, method: 'POST', body: form }),
      invalidatesTags: ['Ofp'],
    }),
    updateShipment: build.mutation<OrderDetail, { shipmentId: number; form: ShipmentForm }>({
      query: ({ shipmentId, form }) => ({ url: `/ofp/shipments/${shipmentId}`, method: 'PUT', body: form }),
      invalidatesTags: ['Ofp'],
    }),
    uploadPod: build.mutation<OrderDetail, { shipmentId: number; file: File }>({
      query: ({ shipmentId, file }) => {
        const body = new FormData()
        body.append('file', file)
        return { url: `/ofp/shipments/${shipmentId}/pod`, method: 'POST', body }
      },
      invalidatesTags: ['Ofp'],
    }),
    /** Resolves to an object URL plus the file name recovered from the response's Content-Disposition header (the
     * customer's own view carries no POD file name field to pass in here — see ExternalShipmentView). */
    downloadPod: build.mutation<{ url: string; fileName: string }, number>({
      query: (shipmentId) => ({
        url: `/ofp/shipments/${shipmentId}/pod`,
        responseHandler: async (response) => {
          if (!response.ok) return response.json()
          const disposition = response.headers.get('content-disposition') ?? ''
          const match = /filename\*?=(?:UTF-8'')?"?([^;"]+)"?/i.exec(disposition)
          const fileName = match ? decodeURIComponent(match[1]) : 'proof-of-delivery'
          return { url: URL.createObjectURL(await response.blob()), fileName }
        },
      }),
    }),
    dispatchOrder: build.mutation<OrderDetail, { id: number; comment?: string }>({
      query: ({ id, comment }) => ({ url: `/ofp/orders/${id}/dispatch`, method: 'POST', body: { comment: comment ?? null } }),
      invalidatesTags: ['Ofp'],
    }),
    markInTransit: build.mutation<OrderDetail, { id: number; comment?: string }>({
      query: ({ id, comment }) => ({ url: `/ofp/orders/${id}/in-transit`, method: 'POST', body: { comment: comment ?? null } }),
      invalidatesTags: ['Ofp'],
    }),
    markDelivered: build.mutation<OrderDetail, { id: number; comment?: string }>({
      query: ({ id, comment }) => ({ url: `/ofp/orders/${id}/delivered`, method: 'POST', body: { comment: comment ?? null } }),
      invalidatesTags: ['Ofp'],
    }),
    closeOrder: build.mutation<OrderDetail, { id: number; comment?: string }>({
      query: ({ id, comment }) => ({ url: `/ofp/orders/${id}/close`, method: 'POST', body: { comment: comment ?? null } }),
      invalidatesTags: ['Ofp'],
    }),
  }),
})

export const {
  useOfpOrdersQuery,
  useOfpOrderQuery,
  useOfpOrderHistoryQuery,
  useCreateOrderMutation,
  useHandoverOrderMutation,
  useUpdateOrderMutation,
  useAdvanceOrderMutation,
  useHoldOrderMutation,
  useReleaseOrderMutation,
  useAddMaterialStatusMutation,
  useUpdateMaterialStatusMutation,
  useAddProductionProgressMutation,
  useUpdateProductionProgressMutation,
  useAddPackagingDispatchMutation,
  useUpdatePackagingDispatchMutation,
  useMarkDispatchReadyMutation,
  useCreateShipmentMutation,
  useUpdateShipmentMutation,
  useUploadPodMutation,
  useDownloadPodMutation,
  useDispatchOrderMutation,
  useMarkInTransitMutation,
  useMarkDeliveredMutation,
  useCloseOrderMutation,
} = ofpApi
