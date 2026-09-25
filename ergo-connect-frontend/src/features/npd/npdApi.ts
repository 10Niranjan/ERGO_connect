import { api } from '../../app/store'
import type { CompanyType } from '../../auth/authSlice'
import type { Named } from '../rfq/rfqApi'

/** Buttons the backend allows for this user, role and record right now (project-, activity- or queue-level). */
export type NpdAction = 'CONVERT' | 'UPDATE_PROJECT' | 'CLOSE_STAGE' | 'ADD_ACTIVITY' | 'EDIT_ACTIVITY' | 'MARK_BLOCKED' | 'UNBLOCK'

/** SRS §14.5's fixed, forward-only 9-stage sequence (PENDING_CONVERSION precedes the project existing at all). */
export type NpdStage =
  | 'PENDING_CONVERSION'
  | 'PLANNING'
  | 'COMMERCIAL_SOURCING_READINESS'
  | 'TOOLING_MATERIAL_RELEASE'
  | 'TRIAL_PREPARATION'
  | 'TRIAL_EXECUTION'
  | 'FINAL_SAMPLE_READINESS'
  | 'CUSTOMER_FEEDBACK'
  | 'APPROVED_FOR_PRODUCTION_HANDOVER'
  | 'CLOSED'

export type RiskLevel = 'LOW' | 'MEDIUM' | 'HIGH'

/** BLOCKED is never set through ActivityForm; only markBlocked/unblock toggle it, via `blockedFlag`. */
export type ActivityStatus = 'NOT_STARTED' | 'IN_PROGRESS' | 'DONE' | 'BLOCKED'

/** SRS §12.8.2: a customer-approved RFQ waiting for manual conversion, not yet converted. */
export interface ConversionQueueRow {
  rfqId: number
  rfqNumber: string
  customerName: string
  partNumber: string
  revision: string | null
  description: string | null
  approvedAt: string
  actions: NpdAction[]
}

/** SRS §12.8.1 NPD project list columns. */
export interface NpdListRow {
  id: number
  npdNumber: string
  rfqNumber: string
  customerName: string
  supplierName: string
  partNumber: string
  stage: NpdStage
  targetSampleDate: string | null
  delayDays: number | null
  owner: string | null
  riskLevel: RiskLevel | null
}

/** Full replace of these 4 fields (not a patch): resend current values for anything unchanged. */
export interface NpdProjectForm {
  ownerUserId: number | null
  riskLevel: RiskLevel | null
  targetSampleDate: string | null
  actualSampleSubmissionDate: string | null
}

/** Create/edit an activity. `status` must never be BLOCKED here — the backend 422s (block/unblock have their own endpoints). */
export interface ActivityForm {
  activityName: string
  activityDescription: string | null
  responsibleUserId: number | null
  plannedDate: string | null
  baselineDate: string | null
  actualDate: string | null
  dependencyActivityId: number | null
  status: ActivityStatus
  mandatory: boolean
  riskFlag: boolean
  issueFlag: boolean
}

export interface ActivityView {
  id: number
  phaseName: NpdStage
  activityName: string
  activityDescription: string | null
  ownerOrganizationType: CompanyType
  ownerCompanyName: string | null
  responsibleUserId: number | null
  responsibleUser: string | null
  plannedDate: string | null
  baselineDate: string | null
  actualDate: string | null
  dependencyActivityId: number | null
  status: ActivityStatus
  blockedFlag: boolean
  riskFlag: boolean
  issueFlag: boolean
  mandatoryFlag: boolean
  actions: NpdAction[]
}

/** SRS §13.4.2: one row per stage move, oldest first. `previousStage` is null for the very first row. */
export interface StageHistoryView {
  stage: NpdStage
  previousStage: NpdStage | null
  changedBy: string | null
  changedAt: string
  remarks: string | null
}

export interface NpdDetailView {
  id: number
  npdNumber: string
  rfqId: number
  rfqNumber: string
  customer: Named
  supplier: Named
  partNumber: string
  revision: string | null
  description: string | null
  quoteReference: string | null
  stage: NpdStage
  targetSampleDate: string | null
  actualSampleSubmissionDate: string | null
  delayDays: number | null
  ownerUserId: number | null
  owner: string | null
  riskLevel: RiskLevel | null
  activities: ActivityView[]
  stageHistory: StageHistoryView[]
  actions: NpdAction[]
  createdAt: string
  updatedAt: string
}

// Converting removes the RFQ from the queue and creates a project: both the Npd tag (queue + list + detail) and Rfq
// (the way quotation's actions invalidate Rfq) are invalidated, the same cross-invalidation pattern as comparisonApi.
const npdApi = api.enhanceEndpoints({ addTagTypes: ['Npd', 'Rfq'] }).injectEndpoints({
  endpoints: (build) => ({
    conversionQueue: build.query<ConversionQueueRow[], void>({
      query: () => '/npd/conversion-queue',
      providesTags: ['Npd'],
    }),
    convertRfq: build.mutation<NpdDetailView, { rfqId: number; comment?: string }>({
      query: ({ rfqId, comment }) => ({ url: `/npd/convert/${rfqId}`, method: 'POST', body: { comment: comment ?? null } }),
      invalidatesTags: ['Npd', 'Rfq'],
    }),
    npdList: build.query<NpdListRow[], void>({ query: () => '/npd', providesTags: ['Npd'] }),
    npdProject: build.query<NpdDetailView, number>({
      query: (id) => `/npd/${id}`,
      providesTags: (_r, _e, id) => [{ type: 'Npd', id }],
    }),
    updateNpdProject: build.mutation<NpdDetailView, { id: number; form: NpdProjectForm }>({
      query: ({ id, form }) => ({ url: `/npd/${id}`, method: 'PUT', body: form }),
      invalidatesTags: ['Npd'],
    }),
    closeNpdStage: build.mutation<NpdDetailView, { id: number; comment?: string }>({
      query: ({ id, comment }) => ({ url: `/npd/${id}/close-stage`, method: 'POST', body: { comment: comment ?? null } }),
      invalidatesTags: ['Npd'],
    }),
    addNpdActivity: build.mutation<NpdDetailView, { id: number; form: ActivityForm }>({
      query: ({ id, form }) => ({ url: `/npd/${id}/activities`, method: 'POST', body: form }),
      invalidatesTags: ['Npd'],
    }),
    updateNpdActivity: build.mutation<NpdDetailView, { activityId: number; form: ActivityForm }>({
      query: ({ activityId, form }) => ({ url: `/npd/activities/${activityId}`, method: 'PUT', body: form }),
      invalidatesTags: ['Npd'],
    }),
    blockNpdActivity: build.mutation<NpdDetailView, { activityId: number; comment: string }>({
      query: ({ activityId, comment }) => ({ url: `/npd/activities/${activityId}/block`, method: 'POST', body: { comment } }),
      invalidatesTags: ['Npd'],
    }),
    unblockNpdActivity: build.mutation<NpdDetailView, { activityId: number; comment?: string }>({
      query: ({ activityId, comment }) => ({ url: `/npd/activities/${activityId}/unblock`, method: 'POST', body: { comment: comment ?? null } }),
      invalidatesTags: ['Npd'],
    }),
  }),
})

export const {
  useConversionQueueQuery,
  useConvertRfqMutation,
  useNpdListQuery,
  useNpdProjectQuery,
  useUpdateNpdProjectMutation,
  useCloseNpdStageMutation,
  useAddNpdActivityMutation,
  useUpdateNpdActivityMutation,
  useBlockNpdActivityMutation,
  useUnblockNpdActivityMutation,
} = npdApi
