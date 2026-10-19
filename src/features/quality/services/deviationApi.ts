import { api } from '@/services/baseApi'
import type { Role } from '@/features/auth/store/authSlice'
import type { LinkedModule } from './qualityApi'

// A separate file from qualityApi.ts (same split as the backend's DeviationDtos vs QualityDtos), purely for size.
// SRS §10.8/§13.6.4/§14.7, Phase 5c.

/** SRS §14.7 deviation workflow: REQUESTED -> UNDER_REVIEW -> [CUSTOMER_APPROVAL_PENDING, only when
 * customerApprovalRequiredFlag is set] -> APPROVED | REJECTED. APPROVED -> EXPIRED automatically once
 * validityEndDate passes; APPROVED/EXPIRED -> CLOSED manually. SEND_BACK is a decision on an approval row, not a
 * status of its own - it routes currentStatus back to REQUESTED or UNDER_REVIEW (DeviationStatus's own javadoc). */
export type DeviationStatus = 'REQUESTED' | 'UNDER_REVIEW' | 'CUSTOMER_APPROVAL_PENDING' | 'APPROVED' | 'REJECTED' | 'EXPIRED' | 'CLOSED'

export type DeviationDecision = 'PENDING' | 'APPROVED' | 'REJECTED' | 'SENT_BACK'

/** Buttons the backend allows for this user, role and deviation right now (DeviationDtos.DeviationAction).
 * START_REVIEW/APPROVE/REJECT/SEND_BACK/CLOSE take no body of their own besides an optional/required reason - see
 * ReasonForm (reused from qualityApi.ts, same {reason} shape) - except START_REVIEW, which takes none at all. */
export type DeviationAction = 'EDIT' | 'START_REVIEW' | 'APPROVE' | 'REJECT' | 'SEND_BACK' | 'CLOSE'

// ---------------------------------------------------------------- forms

/** Create (SRS §13.6.4). linkedModule must be NPD or OFP (DeviationService.create). customerCompanyId/
 * supplierCompanyId are never sent - both are always derived from the linked NPD/OFP record server-side.
 * customerApprovalRequiredFlag is locked after creation (DeviationUpdateForm carries no such field). */
export interface DeviationForm {
  linkedModule: Extract<LinkedModule, 'NPD' | 'OFP'>
  linkedRecordId: number
  inspectionId: number | null
  partNumber: string
  deviationDescription: string
  qualityImpact: string | null
  technicalImpact: string | null
  customerApprovalRequiredFlag: boolean
  validityQuantity: number
  validityEndDate: string
}

/** Full replace, used for EDIT only. customerVisible/supplierVisible are ERGO-only in effect (a non-ERGO editor's
 * value is ignored server-side), same convention as NcrUpdateForm/InspectionUpdateForm. Both must always be sent:
 * the backend deserializes a missing boolean as false, so omitting supplierVisible on a PUT would silently hide
 * the deviation from its own supplier. */
export interface DeviationUpdateForm {
  partNumber: string
  deviationDescription: string
  qualityImpact: string | null
  technicalImpact: string | null
  validityQuantity: number
  validityEndDate: string
  customerVisible: boolean
  supplierVisible: boolean
}

// ---------------------------------------------------------------- views

/** ERGO's own approval-row view: full detail including who decided it. */
export interface ErgoDeviationApprovalView {
  approverRole: Role
  decision: DeviationDecision
  approverUserId: number | null
  approverName: string | null
  comment: string | null
  decidedAt: string | null
  createdAt: string
}

/** Shared by both external audiences: no approver identity (ERGO/customer staff stays internal). */
export interface ExternalDeviationApprovalView {
  approverRole: Role
  decision: DeviationDecision
  comment: string | null
  decidedAt: string | null
  createdAt: string
}

/** ERGO's list row: both companies. */
export interface DeviationRow {
  id: number
  deviationNumber: string
  linkedModule: LinkedModule
  linkedRecordId: number
  customer: string | null
  supplier: string | null
  partNumber: string
  currentStatus: DeviationStatus
  customerApprovalRequiredFlag: boolean
  validityEndDate: string
}

/** Two-way anonymity: no field names or identifies the customer. */
export interface SupplierDeviationRow {
  id: number
  deviationNumber: string
  linkedModule: LinkedModule
  linkedRecordId: number
  partNumber: string
  currentStatus: DeviationStatus
  customerApprovalRequiredFlag: boolean
  validityEndDate: string
}

/** Two-way anonymity: no field names or identifies the supplier. */
export interface CustomerDeviationRow {
  id: number
  deviationNumber: string
  linkedModule: LinkedModule
  linkedRecordId: number
  partNumber: string
  currentStatus: DeviationStatus
  customerApprovalRequiredFlag: boolean
  validityEndDate: string
}

export type DeviationSummary = DeviationRow | SupplierDeviationRow | CustomerDeviationRow

/** ERGO's unrestricted detail view: every field, both companies, both approval rows with approver identity. */
export interface DeviationView {
  id: number
  deviationNumber: string
  linkedModule: LinkedModule
  linkedRecordId: number
  inspectionId: number | null
  customerCompanyId: number | null
  customer: string | null
  supplierCompanyId: number | null
  supplier: string | null
  partNumber: string
  deviationDescription: string
  requestedByUserId: number | null
  requestedBy: string | null
  qualityImpact: string | null
  technicalImpact: string | null
  customerApprovalRequiredFlag: boolean
  validityQuantity: number
  validityEndDate: string
  currentStatus: DeviationStatus
  customerVisible: boolean
  supplierVisible: boolean
  approvals: ErgoDeviationApprovalView[]
  actions: DeviationAction[]
  createdAt: string
  updatedAt: string
}

/** The supplier's detail view: no customer field, no approver identity. */
export interface SupplierDeviationView {
  id: number
  deviationNumber: string
  linkedModule: LinkedModule
  linkedRecordId: number
  partNumber: string
  deviationDescription: string
  qualityImpact: string | null
  technicalImpact: string | null
  customerApprovalRequiredFlag: boolean
  validityQuantity: number
  validityEndDate: string
  currentStatus: DeviationStatus
  approvals: ExternalDeviationApprovalView[]
  actions: DeviationAction[]
  createdAt: string
  updatedAt: string
}

/** The customer's detail view: no supplier field, no approver identity. */
export interface CustomerDeviationView {
  id: number
  deviationNumber: string
  linkedModule: LinkedModule
  linkedRecordId: number
  partNumber: string
  deviationDescription: string
  qualityImpact: string | null
  technicalImpact: string | null
  customerApprovalRequiredFlag: boolean
  validityQuantity: number
  validityEndDate: string
  currentStatus: DeviationStatus
  approvals: ExternalDeviationApprovalView[]
  actions: DeviationAction[]
  createdAt: string
  updatedAt: string
}

export type DeviationDetail = DeviationView | SupplierDeviationView | CustomerDeviationView

const deviationApi = api.enhanceEndpoints({ addTagTypes: ['Deviation'] }).injectEndpoints({
  endpoints: (build) => ({
    deviations: build.query<DeviationSummary[], void>({ query: () => '/quality/deviations', providesTags: ['Deviation'] }),
    deviation: build.query<DeviationDetail, number>({
      query: (id) => `/quality/deviations/${id}`,
      providesTags: (_r, _e, id) => [{ type: 'Deviation', id }],
    }),
    createDeviation: build.mutation<DeviationDetail, DeviationForm>({
      query: (body) => ({ url: '/quality/deviations', method: 'POST', body }),
      invalidatesTags: ['Deviation'],
    }),
    editDeviation: build.mutation<DeviationDetail, { id: number; form: DeviationUpdateForm }>({
      query: ({ id, form }) => ({ url: `/quality/deviations/${id}`, method: 'PUT', body: form }),
      invalidatesTags: ['Deviation'],
    }),
    startReviewDeviation: build.mutation<DeviationDetail, { id: number }>({
      query: ({ id }) => ({ url: `/quality/deviations/${id}/start-review`, method: 'POST' }),
      invalidatesTags: ['Deviation'],
    }),
    approveDeviation: build.mutation<DeviationDetail, { id: number; reason?: string }>({
      query: ({ id, reason }) => ({ url: `/quality/deviations/${id}/approve`, method: 'POST', body: { reason: reason ?? null } }),
      invalidatesTags: ['Deviation'],
    }),
    rejectDeviation: build.mutation<DeviationDetail, { id: number; reason: string }>({
      query: ({ id, reason }) => ({ url: `/quality/deviations/${id}/reject`, method: 'POST', body: { reason } }),
      invalidatesTags: ['Deviation'],
    }),
    sendBackDeviation: build.mutation<DeviationDetail, { id: number; reason: string }>({
      query: ({ id, reason }) => ({ url: `/quality/deviations/${id}/send-back`, method: 'POST', body: { reason } }),
      invalidatesTags: ['Deviation'],
    }),
    closeDeviation: build.mutation<DeviationDetail, { id: number; reason?: string }>({
      query: ({ id, reason }) => ({ url: `/quality/deviations/${id}/close`, method: 'POST', body: { reason: reason ?? null } }),
      invalidatesTags: ['Deviation'],
    }),
  }),
})

export const {
  useDeviationsQuery,
  useDeviationQuery,
  useCreateDeviationMutation,
  useEditDeviationMutation,
  useStartReviewDeviationMutation,
  useApproveDeviationMutation,
  useRejectDeviationMutation,
  useSendBackDeviationMutation,
  useCloseDeviationMutation,
} = deviationApi
