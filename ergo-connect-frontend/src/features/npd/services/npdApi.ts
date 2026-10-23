import { api } from '@/services/baseApi'
import type { CompanyType } from '@/features/auth/store/authSlice'
import type { AuditEntry, Named } from '@/features/rfq/rfqApi'

/** Buttons the backend allows for this user, role and record right now (project-, activity-, issue- or approval-level). */
export type NpdAction =
  | 'CONVERT'
  | 'UPDATE_PROJECT'
  | 'CLOSE_STAGE'
  | 'ADD_ACTIVITY'
  | 'EDIT_ACTIVITY'
  | 'MARK_BLOCKED'
  | 'UNBLOCK'
  | 'UPDATE_OWN_ACTIVITY'
  | 'MANAGE_PROMPT_CHECKLIST'
  | 'RESPOND_TO_PROMPT'
  | 'RAISE_ISSUE'
  | 'UPDATE_ISSUE'
  | 'REQUEST_APPROVAL'
  | 'DECIDE_APPROVAL'

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

/** BLOCKED is never set through ActivityForm/ActivityProgressForm; only markBlocked/unblock toggle it, via `blockedFlag`. */
export type ActivityStatus = 'NOT_STARTED' | 'IN_PROGRESS' | 'DONE' | 'BLOCKED'

export type PromptResponseType = 'TEXT' | 'YES_NO' | 'FILE'
export type IssueRiskType = 'ISSUE' | 'RISK'
export type IssueRiskStatus = 'OPEN' | 'IN_PROGRESS' | 'CLOSED'
export type ApprovalType =
  | 'STAGE_GATE'
  | 'QUALITY_GATE'
  | 'CUSTOMER_SAMPLE_APPROVAL'
  | 'CUSTOMER_MILESTONE_ACKNOWLEDGMENT'
  | 'CUSTOMER_QUALITY_ACCEPTANCE'
export type ApprovalStatus = 'PENDING' | 'APPROVED' | 'REJECTED'

/** SRS §12.8.2: a customer-approved RFQ part waiting for manual conversion, not yet converted. */
export interface ConversionQueueRow {
  rfqId: number
  /** The approved part: each approved part of an RFQ converts on its own (flow doc §5). */
  rfqItemId: number
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
  rfqNumber: string | null
  customerName: string
  supplierName: string
  partNumber: string
  stage: NpdStage
  targetSampleDate: string | null
  delayDays: number | null
  owner: string | null
  riskLevel: RiskLevel | null
}

/** SRS §12.15's customer "NPD" / supplier "NPD Tasks" nav entry: the caller's own company's projects only, with no
 * field naming the other company (safe for both audiences). Feeds `/npd/mine` -> reach a project by id. */
export interface NpdOwnProjectRow {
  id: number
  npdNumber: string
  partNumber: string
  revision: string | null
  stage: NpdStage
  targetSampleDate: string | null
  delayDays: number | null
}

/** Full replace of these 4 fields (not a patch): resend current values for anything unchanged. */
export interface NpdProjectForm {
  ownerUserId: number | null
  riskLevel: RiskLevel | null
  targetSampleDate: string | null
  actualSampleSubmissionDate: string | null
}

/** Create/edit an activity, ERGO Admin/NPD only. `status` must never be BLOCKED here — the backend 422s (block/
 * unblock have their own endpoints). `ownerOrganizationType`/`ownerCompanyId` say who the activity is assigned to:
 * for CUSTOMER/SUPPLIER, `ownerCompanyId` must equal this project's own customer/supplier company id.
 * `requiredApprovalType` must be non-null when `approvalRequired` is true and null when it is false — the backend
 * rejects any other pairing (Close Stage's gate needs to know exactly which approval type it demands). */
export interface ActivityForm {
  activityName: string
  activityDescription: string | null
  ownerOrganizationType: CompanyType
  ownerCompanyId: number | null
  responsibleUserId: number | null
  plannedDate: string | null
  baselineDate: string | null
  actualDate: string | null
  dependencyActivityId: number | null
  status: ActivityStatus
  mandatory: boolean
  riskFlag: boolean
  issueFlag: boolean
  approvalRequired: boolean
  requiredApprovalType: ApprovalType | null
}

/** The owning company's own narrower progress update (SRS §17.4) — customer/supplier use only this, never
 * ActivityForm: never the name, owner, mandatory/approval flags or dependency. `status` may not be BLOCKED here either. */
export interface ActivityProgressForm {
  status: ActivityStatus
  actualDate: string | null
  issueFlag: boolean
  riskFlag: boolean
  remarks: string | null
}

export interface PromptChecklistView {
  id: number
  questionText: string
  responseType: PromptResponseType
  mandatoryFlag: boolean
  sequenceNo: number
  responseValue: string | null
  responseRemarks: string | null
  respondedBy: string | null
  respondedAt: string | null
}

/** Author (create/edit) a checklist question, ERGO only. `activityId` is required on create; the backend ignores it
 * on edit (a checklist's own activity never moves) but this codebase always sends the current value regardless. */
export interface PromptChecklistForm {
  activityId: number | null
  questionText: string
  responseType: PromptResponseType
  mandatory: boolean
  sequenceNo: number
}

/** Answer one question; upserts the one current answer. */
export interface PromptResponseForm {
  responseValue: string
  remarks: string | null
}

/** Raise an issue/risk, ERGO only. `ownerCompanyId` is required unless `ownerOrganizationType` is ERGO (then it is
 * server-derived); `linkedActivityId` must belong to the same project. */
export interface IssueRiskForm {
  type: IssueRiskType
  title: string
  description: string | null
  severity: RiskLevel | null
  ownerOrganizationType: CompanyType
  ownerCompanyId: number | null
  targetDate: string | null
  linkedActivityId: number | null
}

/** The narrower status-only update the owning company (or ERGO) may make; never the classification fields.
 * `comment` is optional except the backend requires it (audited as the reason) when this reopens a closed issue
 * (previous status CLOSED, new status not) — the same reopen-needs-a-reason rule used elsewhere in this app. */
export interface IssueStatusForm {
  status: IssueRiskStatus
  comment: string | null
}

export interface IssueRiskView {
  id: number
  type: IssueRiskType
  title: string
  description: string | null
  severity: RiskLevel | null
  ownerOrganizationType: CompanyType
  ownerCompanyName: string | null
  linkedActivityId: number | null
  targetDate: string | null
  status: IssueRiskStatus
  closureDate: string | null
  actions: NpdAction[]
}

/** Request an approval, ERGO only; no `approverRole` field — the backend computes it from `approvalType`, never
 * accepted from the client. */
export interface ApprovalForm {
  approvalType: ApprovalType
  linkedActivityId: number | null
}

/** Approve or reject a pending approval; `comments` is required on reject (enforced client-side too), optional on
 * approve — the backend is the real gate. */
export interface ApprovalDecisionForm {
  approved: boolean
  comments: string | null
}

export interface ApprovalView {
  id: number
  approvalType: ApprovalType
  stageName: NpdStage
  linkedActivityId: number | null
  requestedBy: string | null
  requestedAt: string
  approverRole: string
  approvalStatus: ApprovalStatus
  approvedBy: string | null
  approvedAt: string | null
  approvalComments: string | null
  actions: NpdAction[]
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
  approvalRequiredFlag: boolean
  requiredApprovalType: ApprovalType | null
  remarks: string | null
  promptChecklists: PromptChecklistView[]
  actions: NpdAction[]
}

/** SRS §13.4.2: one row per stage move, oldest first. `previousStage` is null for the very first row. ERGO-only
 * (`ErgoNpdView`): `remarks` is whatever free text ERGO typed on convert/closeStage, which could name the other
 * party or a price — see `ExternalStageHistoryView` for the customer/supplier variant. */
export interface StageHistoryView {
  stage: NpdStage
  previousStage: NpdStage | null
  changedBy: string | null
  changedAt: string
  remarks: string | null
}

/** The customer/supplier-safe variant of `StageHistoryView`: no `remarks`. `changedBy` is still safe to show since
 * every stage transition is an ERGO-only action, so it is always an ERGO user's name, never the other external
 * party's. */
export interface ExternalStageHistoryView {
  stage: NpdStage
  previousStage: NpdStage | null
  changedBy: string | null
  changedAt: string
}

interface NpdViewBase {
  id: number
  npdNumber: string
  rfqId: number
  rfqNumber: string | null
  partNumber: string
  revision: string | null
  description: string | null
  stage: NpdStage
  targetSampleDate: string | null
  actualSampleSubmissionDate: string | null
  delayDays: number | null
  activities: ActivityView[]
  issuesRisks: IssueRiskView[]
  actions: NpdAction[]
  createdAt: string
  updatedAt: string
}

/** ERGO's unrestricted view: both customer and supplier, every activity/checklist/issue/approval. `riskLevel` is
 * ERGO's own internal project risk rating — never shown to the customer or supplier. */
export interface ErgoNpdView extends NpdViewBase {
  audience: 'ERGO'
  customer: Named
  supplier: Named
  quoteReference: string | null
  ownerUserId: number | null
  owner: string | null
  riskLevel: RiskLevel | null
  approvals: ApprovalView[]
  stageHistory: StageHistoryView[]
}

/** Never carries the supplier (§8.1 anonymity, extended to NPD), no `quoteReference` (free text the supplier
 * typed — showing it to the customer could leak supplier identity) and no `riskLevel` (ERGO's own internal rating).
 * `stageHistory` is the `ExternalStageHistoryView` variant (no `remarks`). */
export interface CustomerNpdView extends NpdViewBase {
  audience: 'CUSTOMER'
  customer: Named
  approvals: ApprovalView[]
  stageHistory: ExternalStageHistoryView[]
}

/** Never carries the customer, no `riskLevel`, and no `approvals` field at all — SRS §10.6 gives supplier roles
 * zero Approve rights. `stageHistory` is the `ExternalStageHistoryView` variant (no `remarks`). */
export interface SupplierNpdView extends NpdViewBase {
  audience: 'SUPPLIER'
  quoteReference: string | null
  stageHistory: ExternalStageHistoryView[]
}

export type NpdView = ErgoNpdView | CustomerNpdView | SupplierNpdView

// Converting removes the RFQ from the queue and creates a project: both the Npd tag (queue + list + detail) and Rfq
// (the way quotation's actions invalidate Rfq) are invalidated, the same cross-invalidation pattern as comparisonApi.
const npdApi = api.enhanceEndpoints({ addTagTypes: ['Npd', 'Rfq'] }).injectEndpoints({
  endpoints: (build) => ({
    conversionQueue: build.query<ConversionQueueRow[], void>({
      query: () => '/npd/conversion-queue',
      providesTags: ['Npd'],
    }),
    convertRfq: build.mutation<NpdView, { rfqItemId: number; comment?: string }>({
      query: ({ rfqItemId, comment }) => ({ url: `/npd/convert/${rfqItemId}`, method: 'POST', body: { comment: comment ?? null } }),
      invalidatesTags: ['Npd', 'Rfq'],
    }),
    npdList: build.query<NpdListRow[], void>({ query: () => '/npd', providesTags: ['Npd'] }),
    /** SRS §12.15's customer "NPD" / supplier "NPD Tasks" nav entry. */
    myNpdProjects: build.query<NpdOwnProjectRow[], void>({ query: () => '/npd/mine', providesTags: ['Npd'] }),
    npdProject: build.query<NpdView, number>({
      query: (id) => `/npd/${id}`,
      providesTags: (_r, _e, id) => [{ type: 'Npd', id }],
    }),
    npdHistory: build.query<AuditEntry[], number>({
      query: (id) => `/npd/${id}/history`,
      providesTags: (_r, _e, id) => [{ type: 'Npd', id }],
    }),
    updateNpdProject: build.mutation<NpdView, { id: number; form: NpdProjectForm }>({
      query: ({ id, form }) => ({ url: `/npd/${id}`, method: 'PUT', body: form }),
      invalidatesTags: ['Npd'],
    }),
    closeNpdStage: build.mutation<NpdView, { id: number; comment?: string }>({
      query: ({ id, comment }) => ({ url: `/npd/${id}/close-stage`, method: 'POST', body: { comment: comment ?? null } }),
      invalidatesTags: ['Npd'],
    }),
    addNpdActivity: build.mutation<NpdView, { id: number; form: ActivityForm }>({
      query: ({ id, form }) => ({ url: `/npd/${id}/activities`, method: 'POST', body: form }),
      invalidatesTags: ['Npd'],
    }),
    updateNpdActivity: build.mutation<NpdView, { activityId: number; form: ActivityForm }>({
      query: ({ activityId, form }) => ({ url: `/npd/activities/${activityId}`, method: 'PUT', body: form }),
      invalidatesTags: ['Npd'],
    }),
    /** The owning company's own progress update (SRS §17.4); never used by ERGO's fuller ActivityForm dialog. */
    updateOwnNpdActivity: build.mutation<NpdView, { activityId: number; form: ActivityProgressForm }>({
      query: ({ activityId, form }) => ({ url: `/npd/activities/${activityId}/progress`, method: 'POST', body: form }),
      invalidatesTags: ['Npd'],
    }),
    blockNpdActivity: build.mutation<NpdView, { activityId: number; comment: string }>({
      query: ({ activityId, comment }) => ({ url: `/npd/activities/${activityId}/block`, method: 'POST', body: { comment } }),
      invalidatesTags: ['Npd'],
    }),
    unblockNpdActivity: build.mutation<NpdView, { activityId: number; comment?: string }>({
      query: ({ activityId, comment }) => ({ url: `/npd/activities/${activityId}/unblock`, method: 'POST', body: { comment: comment ?? null } }),
      invalidatesTags: ['Npd'],
    }),
    addPromptChecklist: build.mutation<NpdView, { id: number; form: PromptChecklistForm }>({
      query: ({ id, form }) => ({ url: `/npd/${id}/prompt-checklists`, method: 'POST', body: form }),
      invalidatesTags: ['Npd'],
    }),
    updatePromptChecklist: build.mutation<NpdView, { checklistId: number; form: PromptChecklistForm }>({
      query: ({ checklistId, form }) => ({ url: `/npd/prompt-checklists/${checklistId}`, method: 'PUT', body: form }),
      invalidatesTags: ['Npd'],
    }),
    respondToPrompt: build.mutation<NpdView, { checklistId: number; form: PromptResponseForm }>({
      query: ({ checklistId, form }) => ({ url: `/npd/prompt-checklists/${checklistId}/response`, method: 'POST', body: form }),
      invalidatesTags: ['Npd'],
    }),
    raiseIssue: build.mutation<NpdView, { id: number; form: IssueRiskForm }>({
      query: ({ id, form }) => ({ url: `/npd/${id}/issues`, method: 'POST', body: form }),
      invalidatesTags: ['Npd'],
    }),
    updateIssueStatus: build.mutation<NpdView, { issueId: number; form: IssueStatusForm }>({
      query: ({ issueId, form }) => ({ url: `/npd/issues/${issueId}/status`, method: 'PUT', body: form }),
      invalidatesTags: ['Npd'],
    }),
    requestApproval: build.mutation<NpdView, { id: number; form: ApprovalForm }>({
      query: ({ id, form }) => ({ url: `/npd/${id}/approvals`, method: 'POST', body: form }),
      invalidatesTags: ['Npd'],
    }),
    decideApproval: build.mutation<NpdView, { approvalId: number; form: ApprovalDecisionForm }>({
      query: ({ approvalId, form }) => ({ url: `/npd/approvals/${approvalId}/decide`, method: 'POST', body: form }),
      invalidatesTags: ['Npd'],
    }),
  }),
})

export const {
  useConversionQueueQuery,
  useConvertRfqMutation,
  useNpdListQuery,
  useMyNpdProjectsQuery,
  useNpdProjectQuery,
  useNpdHistoryQuery,
  useUpdateNpdProjectMutation,
  useCloseNpdStageMutation,
  useAddNpdActivityMutation,
  useUpdateNpdActivityMutation,
  useUpdateOwnNpdActivityMutation,
  useBlockNpdActivityMutation,
  useUnblockNpdActivityMutation,
  useAddPromptChecklistMutation,
  useUpdatePromptChecklistMutation,
  useRespondToPromptMutation,
  useRaiseIssueMutation,
  useUpdateIssueStatusMutation,
  useRequestApprovalMutation,
  useDecideApprovalMutation,
} = npdApi
