import { api } from '../../app/store'

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

const qualityApi = api.enhanceEndpoints({ addTagTypes: ['Quality'] }).injectEndpoints({
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
} = qualityApi
