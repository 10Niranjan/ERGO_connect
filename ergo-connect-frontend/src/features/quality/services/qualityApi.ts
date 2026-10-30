import { api } from '@/services/baseApi'
import type { AuditEntry } from '@/features/rfq/rfqApi'

/** SRS §13.6.1 {@code inspections.linked_module}: mirrors the backend enum exactly. */
export type LinkedModule = 'NPD' | 'OFP' | 'INCOMING' | 'FINAL' | 'IN_PROCESS'

/** SRS §14.7 inspection workflow: OPEN -> IN_INSPECTION -> (PASSED | REJECTED | RELEASED_WITH_DEVIATION) -> CLOSED,
 * with CLOSED -> OPEN as the only reopen path. */
export type ResultStatus = 'OPEN' | 'IN_INSPECTION' | 'PASSED' | 'REJECTED' | 'RELEASED_WITH_DEVIATION' | 'CLOSED'

export type DefectSeverity = 'MINOR' | 'MAJOR' | 'CRITICAL'

/** Buttons the backend allows for this user, role and inspection right now (QualityDtos.InspectionAction). */
export type InspectionAction = 'EDIT' | 'SUBMIT' | 'PASS' | 'REJECT' | 'RELEASE_WITH_DEVIATION' | 'CLOSE' | 'REOPEN'

// ---------------------------------------------------------------- forms

/** Create (SRS §13.6.1). customerCompanyId/customerVisible are ERGO-only in effect: a supplier caller's values are
 * ignored server-side. */
export interface InspectionForm {
  linkedModule: LinkedModule
  linkedRecordId: number
  partNumber: string
  batchLotNo: string | null
  customerCompanyId: number | null
  supplierCompanyId: number | null
  customerVisible: boolean
}

export interface DefectForm {
  defectCategory: string
  defectDescription: string | null
  defectQuantity: number
  severity: DefectSeverity
}

/** Full replace of the header/result fields, used both for EDIT and SUBMIT (OPEN -> IN_INSPECTION). */
export interface InspectionUpdateForm {
  partNumber: string
  batchLotNo: string | null
  quantityChecked: number | null
  quantityRejected: number | null
  inspectionDate: string | null
  remarks: string | null
  customerVisible: boolean
  customerCompanyId: number | null
  supplierCompanyId: number | null
  defects: DefectForm[]
}

/** reason is required for REJECT/RELEASE_WITH_DEVIATION/REOPEN, optional (an audit comment) for PASS/CLOSE. */
export interface ReasonForm {
  reason: string | null
}

// ---------------------------------------------------------------- views

/** Shared by ERGO and the supplier: a defect line names no company either way, and both audiences author/need the
 * category and description text (security review M4 drops it for the customer - see CustomerInspectionDefectView). */
export interface InspectionDefectView {
  id: number
  defectCategory: string
  defectDescription: string | null
  defectQuantity: number
  severity: DefectSeverity
  createdAt: string
}

/** The customer's defect view (security review M4): no defectCategory/defectDescription - both are free text ERGO
 * or the supplier typed and could name the other party. */
export interface CustomerInspectionDefectView {
  id: number
  defectQuantity: number
  severity: DefectSeverity
  createdAt: string
}

/** ERGO's list row: both companies. */
export interface InspectionRow {
  id: number
  linkedModule: LinkedModule
  linkedRecordId: number
  customer: string | null
  supplier: string | null
  partNumber: string
  batchLotNo: string | null
  resultStatus: ResultStatus
  inspectionDate: string | null
  customerVisible: boolean
}

/** Two-way anonymity: no field names or identifies the customer, and no ERGO inspector name. */
export interface SupplierInspectionRow {
  id: number
  linkedModule: LinkedModule
  linkedRecordId: number
  partNumber: string
  batchLotNo: string | null
  resultStatus: ResultStatus
  inspectionDate: string | null
}

/** Two-way anonymity: no field names or identifies the supplier. */
export interface CustomerInspectionRow {
  id: number
  linkedModule: LinkedModule
  linkedRecordId: number
  partNumber: string
  batchLotNo: string | null
  resultStatus: ResultStatus
  inspectionDate: string | null
}

export type InspectionSummary = InspectionRow | SupplierInspectionRow | CustomerInspectionRow

/** ERGO's unrestricted detail view. */
export interface InspectionView {
  id: number
  linkedModule: LinkedModule
  linkedRecordId: number
  customerCompanyId: number | null
  customer: string | null
  supplierCompanyId: number | null
  supplier: string | null
  partNumber: string
  batchLotNo: string | null
  quantityChecked: number | null
  quantityRejected: number | null
  resultStatus: ResultStatus
  inspectedByUserId: number | null
  inspectedBy: string | null
  inspectionDate: string | null
  remarks: string | null
  customerVisible: boolean
  defects: InspectionDefectView[]
  actions: InspectionAction[]
  createdAt: string
  updatedAt: string
}

/** The supplier's detail view: no customer field, no ERGO inspector name, and no remarks (security review H1 - the
 * field is shared free text either side can write, and ERGO's own internal findings must never reach the supplier;
 * dropped entirely rather than nulled). */
export interface SupplierInspectionView {
  id: number
  linkedModule: LinkedModule
  linkedRecordId: number
  partNumber: string
  batchLotNo: string | null
  quantityChecked: number | null
  quantityRejected: number | null
  resultStatus: ResultStatus
  inspectionDate: string | null
  defects: InspectionDefectView[]
  actions: InspectionAction[]
  createdAt: string
  updatedAt: string
}

/** The customer's detail view: no supplier field, no remarks (free text that could name the other party), and
 * `actions` is always empty - Customer Quality is read-only in 5a. */
export interface CustomerInspectionView {
  id: number
  linkedModule: LinkedModule
  linkedRecordId: number
  partNumber: string
  batchLotNo: string | null
  quantityChecked: number | null
  quantityRejected: number | null
  resultStatus: ResultStatus
  inspectionDate: string | null
  defects: CustomerInspectionDefectView[]
  actions: InspectionAction[]
  createdAt: string
  updatedAt: string
}

export type InspectionDetail = InspectionView | SupplierInspectionView | CustomerInspectionView

// ================================================================== NCR / CAPA (SRS §10.8/§13.6.3/.5, §14.7)

/** SRS §14.7: OPEN -> CONTAINMENT_IN_PROGRESS -> ROOT_CAUSE_IN_PROGRESS -> CAPA_IN_PROGRESS ->
 * PENDING_CLOSURE_APPROVAL -> CLOSED, linear, plus SEND_BACK (PENDING_CLOSURE_APPROVAL -> CAPA_IN_PROGRESS) and
 * REOPEN (CLOSED -> OPEN) as the two reverse paths. */
export type NcrStatus = 'OPEN' | 'CONTAINMENT_IN_PROGRESS' | 'ROOT_CAUSE_IN_PROGRESS' | 'CAPA_IN_PROGRESS' | 'PENDING_CLOSURE_APPROVAL' | 'CLOSED'

export type CapaActionType = 'CORRECTIVE' | 'PREVENTIVE'

/** No SRS-enumerated workflow of its own: a simple per-action lifecycle underneath its parent NCR's own status. */
export type CapaActionStatus = 'OPEN' | 'IN_PROGRESS' | 'CLOSED'

/** Buttons the backend allows for this user, role and NCR right now (QualityDtos.NcrAction). CAPA actions have no
 * equivalent set - their own edit/close/upload rights track exactly the NCR's own EDIT action (NcrService
 * requireCapaEditable == allowed(EDIT, ...) by construction), so the UI derives CAPA editability from `has('EDIT')`. */
export type NcrAction = 'EDIT' | 'MOVE_TO_CONTAINMENT' | 'MOVE_TO_ROOT_CAUSE' | 'MOVE_TO_CAPA' | 'SUBMIT_FOR_CLOSURE' | 'CLOSE' | 'SEND_BACK' | 'REOPEN'

/** Create (SRS §13.6.3). customerCompanyId/supplierCompanyId/ownerUserId are ERGO-only in effect for anything other
 * than the supplier's own tag (NcrService.create) - a supplier caller's values are ignored server-side. NCR accepts
 * any LinkedModule (NcrService.createErgoNcr/NcrTests use INCOMING too), not only NPD/OFP - those two just get their
 * companies auto-derived from the linked record instead of the manual pickers below. */
export interface NcrForm {
  linkedModule: LinkedModule
  linkedRecordId: number
  issueCategory: string
  issueDescription: string
  targetClosureDate: string
  customerCompanyId: number | null
  supplierCompanyId: number | null
  ownerUserId: number | null
}

/** Full replace, used for both EDIT and every forward transition (mirrors InspectionUpdateForm covering EDIT and
 * SUBMIT). effectivenessStatus/ownerUserId/customerVisible/company reassignment are ERGO-only in effect - a
 * supplier's own edit leaves them untouched, but customerVisible is still a required primitive on every call. */
export interface NcrUpdateForm {
  issueCategory: string
  issueDescription: string
  targetClosureDate: string
  containmentAction: string | null
  rootCause: string | null
  correctiveAction: string | null
  preventiveAction: string | null
  effectivenessStatus: string | null
  ownerUserId: number | null
  customerCompanyId: number | null
  supplierCompanyId: number | null
  customerVisible: boolean
}

/** Create and full-replace edit for a CAPA action - one shape for both. */
export interface CapaActionForm {
  actionType: CapaActionType
  actionDescription: string
  targetDate: string
  ownerUserId: number | null
}

/** ERGO's CAPA action view: full detail including who owns it. */
export interface CapaActionView {
  id: number
  actionType: CapaActionType
  actionDescription: string
  ownerUserId: number | null
  owner: string | null
  targetDate: string
  status: CapaActionStatus
  evidenceDocumentId: number | null
  evidenceFileName: string | null
  closedAt: string | null
  createdAt: string
}

/** Supplier's CAPA action view: no ownerUserId/owner (staff identity stays internal). evidenceDocumentId/
 * evidenceFileName ARE shared - proof its own CAPA response was completed, the point of the NCR. */
export interface SupplierCapaActionView {
  id: number
  actionType: CapaActionType
  actionDescription: string
  targetDate: string
  status: CapaActionStatus
  evidenceDocumentId: number | null
  evidenceFileName: string | null
  closedAt: string | null
  createdAt: string
}

/** Customer's CAPA action view (security review H1): no evidenceDocumentId/evidenceFileName - a customer must never
 * download the other party's evidence file. evidenceAttached tells it whether proof exists without exposing it. */
export interface CustomerCapaActionView {
  id: number
  actionType: CapaActionType
  actionDescription: string
  targetDate: string
  status: CapaActionStatus
  evidenceAttached: boolean
  closedAt: string | null
  createdAt: string
}

/** ERGO's list row: both companies. */
export interface NcrRow {
  id: number
  ncrNumber: string
  linkedModule: LinkedModule
  linkedRecordId: number
  customer: string | null
  supplier: string | null
  issueCategory: string
  currentStatus: NcrStatus
  targetClosureDate: string
}

/** Two-way anonymity: no field names or identifies the other party. */
export interface SupplierNcrRow {
  id: number
  ncrNumber: string
  linkedModule: LinkedModule
  linkedRecordId: number
  issueCategory: string
  currentStatus: NcrStatus
  targetClosureDate: string
}

/** Two-way anonymity: no field names or identifies the other party. */
export interface CustomerNcrRow {
  id: number
  ncrNumber: string
  linkedModule: LinkedModule
  linkedRecordId: number
  issueCategory: string
  currentStatus: NcrStatus
  targetClosureDate: string
}

export type NcrSummary = NcrRow | SupplierNcrRow | CustomerNcrRow

/** One page of the NCR list; `total` counts every match, not just this page. */
export interface NcrPage {
  items: NcrSummary[]
  total: number
  page: number
  size: number
}

export interface NcrListArgs {
  page: number
  size: number
  status?: string
  linkedModule?: string
  q?: string
}

/** ERGO's unrestricted detail view: every field, including the ERGO-internal effectivenessStatus and owner, both
 * companies, and the full CapaActionView list. */
export interface NcrView {
  id: number
  ncrNumber: string
  linkedModule: LinkedModule
  linkedRecordId: number
  customerCompanyId: number | null
  customer: string | null
  supplierCompanyId: number | null
  supplier: string | null
  issueCategory: string
  issueDescription: string
  containmentAction: string | null
  rootCause: string | null
  correctiveAction: string | null
  preventiveAction: string | null
  ownerUserId: number | null
  owner: string | null
  targetClosureDate: string
  closureDate: string | null
  effectivenessStatus: string | null
  currentStatus: NcrStatus
  customerVisible: boolean
  capaActions: CapaActionView[]
  actions: NcrAction[]
  createdAt: string
  updatedAt: string
}

/** Supplier's detail view: the shared content fields (this is the point of an NCR raised against/by a supplier),
 * never effectivenessStatus/owner (ERGO-internal), never the customer. */
export interface SupplierNcrView {
  id: number
  ncrNumber: string
  linkedModule: LinkedModule
  linkedRecordId: number
  issueCategory: string
  issueDescription: string
  containmentAction: string | null
  rootCause: string | null
  correctiveAction: string | null
  preventiveAction: string | null
  targetClosureDate: string
  closureDate: string | null
  currentStatus: NcrStatus
  capaActions: SupplierCapaActionView[]
  actions: NcrAction[]
  createdAt: string
  updatedAt: string
}

/** Customer's detail view - same shape as SupplierNcrView except its CAPA actions use CustomerCapaActionView
 * (security review H1); `actions` is always empty - Customer Quality is view-only. */
export interface CustomerNcrView {
  id: number
  ncrNumber: string
  linkedModule: LinkedModule
  linkedRecordId: number
  issueCategory: string
  issueDescription: string
  containmentAction: string | null
  rootCause: string | null
  correctiveAction: string | null
  preventiveAction: string | null
  targetClosureDate: string
  closureDate: string | null
  currentStatus: NcrStatus
  capaActions: CustomerCapaActionView[]
  actions: NcrAction[]
  createdAt: string
  updatedAt: string
}

export type NcrDetail = NcrView | SupplierNcrView | CustomerNcrView

const qualityApi = api.enhanceEndpoints({ addTagTypes: ['Quality', 'Ncr'] }).injectEndpoints({
  endpoints: (build) => ({
    // One endpoint for every audience (same precedent as OFP's ofpOrders): InspectionService.list() returns the
    // right scoped, per-audience shape for whoever is calling.
    inspections: build.query<InspectionSummary[], void>({ query: () => '/quality/inspections', providesTags: ['Quality'] }),
    inspection: build.query<InspectionDetail, number>({
      query: (id) => `/quality/inspections/${id}`,
      providesTags: (_r, _e, id) => [{ type: 'Quality', id }],
    }),
    createInspection: build.mutation<InspectionDetail, InspectionForm>({
      query: (body) => ({ url: '/quality/inspections', method: 'POST', body }),
      invalidatesTags: ['Quality'],
    }),
    editInspection: build.mutation<InspectionDetail, { id: number; form: InspectionUpdateForm }>({
      query: ({ id, form }) => ({ url: `/quality/inspections/${id}`, method: 'PUT', body: form }),
      invalidatesTags: ['Quality'],
    }),
    submitInspection: build.mutation<InspectionDetail, { id: number; form: InspectionUpdateForm }>({
      query: ({ id, form }) => ({ url: `/quality/inspections/${id}/submit`, method: 'POST', body: form }),
      invalidatesTags: ['Quality'],
    }),
    passInspection: build.mutation<InspectionDetail, { id: number; reason?: string }>({
      query: ({ id, reason }) => ({ url: `/quality/inspections/${id}/pass`, method: 'POST', body: { reason: reason ?? null } }),
      invalidatesTags: ['Quality'],
    }),
    rejectInspection: build.mutation<InspectionDetail, { id: number; reason: string }>({
      query: ({ id, reason }) => ({ url: `/quality/inspections/${id}/reject`, method: 'POST', body: { reason } }),
      invalidatesTags: ['Quality'],
    }),
    releaseInspectionWithDeviation: build.mutation<InspectionDetail, { id: number; reason: string }>({
      query: ({ id, reason }) => ({ url: `/quality/inspections/${id}/release-with-deviation`, method: 'POST', body: { reason } }),
      invalidatesTags: ['Quality'],
    }),
    closeInspection: build.mutation<InspectionDetail, { id: number; reason?: string }>({
      query: ({ id, reason }) => ({ url: `/quality/inspections/${id}/close`, method: 'POST', body: { reason: reason ?? null } }),
      invalidatesTags: ['Quality'],
    }),
    reopenInspection: build.mutation<InspectionDetail, { id: number; reason: string }>({
      query: ({ id, reason }) => ({ url: `/quality/inspections/${id}/reopen`, method: 'POST', body: { reason } }),
      invalidatesTags: ['Quality'],
    }),

    // ---------------------------------------------------------------- NCR / CAPA
    // One page, newest first; filtered and searched by the server.
    ncrs: build.query<NcrPage, NcrListArgs>({
      query: ({ page, size, status, linkedModule, q }) => ({
        url: '/quality/ncrs',
        params: { page, size, ...(status ? { status } : {}), ...(linkedModule ? { linkedModule } : {}), ...(q ? { q } : {}) },
      }),
      providesTags: ['Ncr'],
    }),
    ncr: build.query<NcrDetail, number>({
      query: (id) => `/quality/ncrs/${id}`,
      providesTags: (_r, _e, id) => [{ type: 'Ncr', id }],
    }),
    ncrHistory: build.query<AuditEntry[], number>({
      query: (id) => `/quality/ncrs/${id}/history`,
      providesTags: (_r, _e, id) => [{ type: 'Ncr', id }],
    }),
    createNcr: build.mutation<NcrDetail, NcrForm>({
      query: (body) => ({ url: '/quality/ncrs', method: 'POST', body }),
      invalidatesTags: ['Ncr'],
    }),
    editNcr: build.mutation<NcrDetail, { id: number; form: NcrUpdateForm }>({
      query: ({ id, form }) => ({ url: `/quality/ncrs/${id}`, method: 'PUT', body: form }),
      invalidatesTags: ['Ncr'],
    }),
    moveNcrToContainment: build.mutation<NcrDetail, { id: number; form: NcrUpdateForm }>({
      query: ({ id, form }) => ({ url: `/quality/ncrs/${id}/move-to-containment`, method: 'POST', body: form }),
      invalidatesTags: ['Ncr'],
    }),
    moveNcrToRootCause: build.mutation<NcrDetail, { id: number; form: NcrUpdateForm }>({
      query: ({ id, form }) => ({ url: `/quality/ncrs/${id}/move-to-root-cause`, method: 'POST', body: form }),
      invalidatesTags: ['Ncr'],
    }),
    moveNcrToCapa: build.mutation<NcrDetail, { id: number; form: NcrUpdateForm }>({
      query: ({ id, form }) => ({ url: `/quality/ncrs/${id}/move-to-capa`, method: 'POST', body: form }),
      invalidatesTags: ['Ncr'],
    }),
    submitNcrForClosure: build.mutation<NcrDetail, { id: number; form: NcrUpdateForm }>({
      query: ({ id, form }) => ({ url: `/quality/ncrs/${id}/submit-for-closure`, method: 'POST', body: form }),
      invalidatesTags: ['Ncr'],
    }),
    closeNcr: build.mutation<NcrDetail, { id: number; reason?: string }>({
      query: ({ id, reason }) => ({ url: `/quality/ncrs/${id}/close`, method: 'POST', body: { reason: reason ?? null } }),
      invalidatesTags: ['Ncr'],
    }),
    sendBackNcr: build.mutation<NcrDetail, { id: number; reason: string }>({
      query: ({ id, reason }) => ({ url: `/quality/ncrs/${id}/send-back`, method: 'POST', body: { reason } }),
      invalidatesTags: ['Ncr'],
    }),
    reopenNcr: build.mutation<NcrDetail, { id: number; reason: string }>({
      query: ({ id, reason }) => ({ url: `/quality/ncrs/${id}/reopen`, method: 'POST', body: { reason } }),
      invalidatesTags: ['Ncr'],
    }),
    createCapaAction: build.mutation<NcrDetail, { id: number; form: CapaActionForm }>({
      query: ({ id, form }) => ({ url: `/quality/ncrs/${id}/capa-actions`, method: 'POST', body: form }),
      invalidatesTags: ['Ncr'],
    }),
    editCapaAction: build.mutation<NcrDetail, { id: number; capaActionId: number; form: CapaActionForm }>({
      query: ({ id, capaActionId, form }) => ({ url: `/quality/ncrs/${id}/capa-actions/${capaActionId}`, method: 'PUT', body: form }),
      invalidatesTags: ['Ncr'],
    }),
    closeCapaAction: build.mutation<NcrDetail, { id: number; capaActionId: number }>({
      query: ({ id, capaActionId }) => ({ url: `/quality/ncrs/${id}/capa-actions/${capaActionId}/close`, method: 'POST' }),
      invalidatesTags: ['Ncr'],
    }),
    uploadCapaEvidence: build.mutation<NcrDetail, { id: number; capaActionId: number; file: File }>({
      query: ({ id, capaActionId, file }) => {
        const body = new FormData()
        body.append('file', file)
        return { url: `/quality/ncrs/${id}/capa-actions/${capaActionId}/evidence`, method: 'POST', body }
      },
      invalidatesTags: ['Ncr'],
    }),
    /** Resolves to an object URL plus the file name recovered from the response's Content-Disposition header, same
     * pattern as OFP's downloadPod (never offered to a customer caller - see NcrDetailPage/labels). */
    downloadCapaEvidence: build.mutation<{ url: string; fileName: string }, { id: number; capaActionId: number }>({
      query: ({ id, capaActionId }) => ({
        url: `/quality/ncrs/${id}/capa-actions/${capaActionId}/evidence`,
        responseHandler: async (response) => {
          if (!response.ok) return response.json()
          const disposition = response.headers.get('content-disposition') ?? ''
          const match = /filename\*?=(?:UTF-8'')?"?([^;"]+)"?/i.exec(disposition)
          const fileName = match ? decodeURIComponent(match[1]) : 'evidence'
          return { url: URL.createObjectURL(await response.blob()), fileName }
        },
      }),
    }),
  }),
})

export const {
  useInspectionsQuery,
  useInspectionQuery,
  useCreateInspectionMutation,
  useEditInspectionMutation,
  useSubmitInspectionMutation,
  usePassInspectionMutation,
  useRejectInspectionMutation,
  useReleaseInspectionWithDeviationMutation,
  useCloseInspectionMutation,
  useReopenInspectionMutation,

  useNcrsQuery,
  useNcrQuery,
  useNcrHistoryQuery,
  useCreateNcrMutation,
  useEditNcrMutation,
  useMoveNcrToContainmentMutation,
  useMoveNcrToRootCauseMutation,
  useMoveNcrToCapaMutation,
  useSubmitNcrForClosureMutation,
  useCloseNcrMutation,
  useSendBackNcrMutation,
  useReopenNcrMutation,
  useCreateCapaActionMutation,
  useEditCapaActionMutation,
  useCloseCapaActionMutation,
  useUploadCapaEvidenceMutation,
  useDownloadCapaEvidenceMutation,
} = qualityApi
