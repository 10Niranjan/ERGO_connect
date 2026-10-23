import { api } from '@/services/baseApi'

export type RfqStatus =
  | 'DRAFT'
  | 'SUBMITTED'
  | 'UNDER_REVIEW'
  | 'CLARIFICATION_REQUIRED'
  | 'VALIDATED'
  | 'SENT_TO_SUPPLIERS'
  | 'QUOTES_PARTIAL'
  | 'QUOTES_COMPLETE'
  | 'UNDER_COMPARISON'
  | 'SENT_TO_CUSTOMER'
  | 'CUSTOMER_ON_HOLD'
  | 'CUSTOMER_REVISION_REQUESTED'
  | 'CUSTOMER_APPROVED'
  | 'CUSTOMER_DECLINED'
  | 'CLOSED'
  | 'CANCELLED'

/** What customers see (internal progress collapsed into IN_PROGRESS). */
export type CustomerRfqStatus =
  | 'DRAFT'
  | 'SUBMITTED'
  | 'UNDER_REVIEW'
  | 'CLARIFICATION_REQUIRED'
  | 'IN_PROGRESS'
  | 'QUOTE_RECEIVED'
  | 'ON_HOLD'
  | 'REVISION_REQUESTED'
  | 'APPROVED'
  | 'DECLINED'
  | 'CLOSED'
  | 'CANCELLED'

export type Priority = 'LOW' | 'NORMAL' | 'HIGH' | 'URGENT'
export type Visibility = 'INTERNAL' | 'CUSTOMER' | 'SUPPLIER' | 'ALL'
export type SourcingType = 'INHOUSE' | 'OUTSOURCED'

/** Buttons the backend allows for this user, role and status right now. */
export type RfqAction =
  | 'EDIT'
  | 'EDIT_NOTES'
  | 'SUBMIT'
  | 'START_REVIEW'
  | 'REQUEST_CLARIFICATION'
  | 'VALIDATE'
  | 'REOPEN'
  | 'CANCEL'
  | 'CLOSE'
  | 'CLONE'
  | 'UPLOAD'

export interface Named {
  id: number
  code: string
  name: string
}

export interface ProcessStep {
  processId: number | null
  stepName: string
  cycleTimeSeconds: number | null
  sourcingType: SourcingType | null
  outsourcedSupplierName: string | null
  remarks: string | null
}

export interface StepView extends Omit<ProcessStep, 'processId'> {
  process: Named | null
}

export interface PartView {
  partNumber: string
  revision: string | null
  description: string | null
  eau: number | null
  netWeightKg: number | null
  grossWeightKg: number | null
  material: Named | null
  materialSpec: string | null
  coating: Named | null
  coatingSpec: string | null
  targetQuoteDate: string | null
  priority: Priority
  processSteps: StepView[]
}

/** The customer's targets: ERGO and that customer only. */
export interface TargetsView {
  currency: Named
  targetToolingCost: number | null
  targetPpapPiecePrice: number | null
  targetProductionPiecePrice: number | null
  paymentTerm: Named | null
  materialLeadTimeWeeks: number | null
  toolingLeadTimeDays: number | null
  sampleLeadTimeDays: number | null
  productionLeadTimeDays: number | null
  moq: number | null
}

interface RfqViewBase {
  id: number
  number: string
  part: PartView
  targets: TargetsView
  customerRemarks: string | null
  clarificationQuestion: string | null
  clarificationAnswer: string | null
  actions: RfqAction[]
  createdAt: string
  updatedAt: string
}

export interface ErgoRfqView extends RfqViewBase {
  audience: 'ERGO'
  status: RfqStatus
  sourceType: 'CUSTOMER' | 'ERGO'
  customer: Named
  createdBy: string | null
  owner: string | null
  internalNotes: string | null
}

export interface CustomerRfqView extends RfqViewBase {
  audience: 'CUSTOMER'
  status: CustomerRfqStatus
}

export type RfqView = ErgoRfqView | CustomerRfqView

export interface RfqRow {
  id: number
  number: string
  status: RfqStatus | CustomerRfqStatus
  /** ERGO only */
  customer?: string
  /** ERGO only */
  owner?: string | null
  partNumber: string
  revision: string | null
  description: string | null
  targetQuoteDate: string | null
  priority: Priority
  createdAt: string
}

export interface RfqForm {
  customerCompanyId: number | null
  partNumber: string
  revision: string | null
  description: string | null
  eau: number | null
  netWeightKg: number | null
  grossWeightKg: number | null
  materialId: number | null
  materialSpec: string | null
  coatingId: number | null
  coatingSpec: string | null
  targetQuoteDate: string | null
  priority: Priority
  currencyId: number | null
  targetToolingCost: number | null
  targetPpapPiecePrice: number | null
  targetProductionPiecePrice: number | null
  paymentTermId: number | null
  materialLeadTimeWeeks: number | null
  toolingLeadTimeDays: number | null
  sampleLeadTimeDays: number | null
  productionLeadTimeDays: number | null
  moq: number | null
  processSteps: ProcessStep[]
  customerRemarks: string | null
  internalNotes: string | null
}

export interface DocumentView {
  id: number
  fileName: string
  size: number
  visibility: Visibility
  ownCompany: boolean
  canDelete: boolean
  createdAt: string
}

export interface AuditEntry {
  at: string
  user: string | null
  role: string | null
  event: string
  field: string | null
  oldValue: string | null
  newValue: string | null
  reason: string | null
  channel: string
}

/** Workflow endpoints that take an optional/required comment. */
export type RfqCommand = 'submit' | 'start-review' | 'request-clarification' | 'validate' | 'reopen' | 'cancel' | 'close'

export const rfqApi = api.enhanceEndpoints({ addTagTypes: ['Rfq', 'RfqDocuments'] }).injectEndpoints({
  endpoints: (build) => ({
    rfqs: build.query<RfqRow[], void>({ query: () => '/rfqs', providesTags: ['Rfq'] }),
    rfq: build.query<RfqView, number>({ query: (id) => `/rfqs/${id}`, providesTags: (_r, _e, id) => [{ type: 'Rfq', id }] }),
    rfqHistory: build.query<AuditEntry[], number>({
      query: (id) => `/rfqs/${id}/history`,
      providesTags: (_r, _e, id) => [{ type: 'Rfq', id }],
    }),
    createRfq: build.mutation<RfqView, RfqForm>({
      query: (body) => ({ url: '/rfqs', method: 'POST', body }),
      invalidatesTags: ['Rfq'],
    }),
    updateRfq: build.mutation<RfqView, { id: number; form: RfqForm }>({
      query: ({ id, form }) => ({ url: `/rfqs/${id}`, method: 'PUT', body: form }),
      invalidatesTags: ['Rfq'],
    }),
    updateRfqNotes: build.mutation<RfqView, { id: number; internalNotes: string | null }>({
      query: ({ id, internalNotes }) => ({ url: `/rfqs/${id}/internal-notes`, method: 'PUT', body: { internalNotes } }),
      invalidatesTags: ['Rfq'],
    }),
    rfqCommand: build.mutation<RfqView, { id: number; command: RfqCommand; comment?: string }>({
      query: ({ id, command, comment }) =>
        command === 'start-review'
          ? { url: `/rfqs/${id}/${command}`, method: 'POST' }
          : { url: `/rfqs/${id}/${command}`, method: 'POST', body: { comment: comment ?? null } },
      invalidatesTags: ['Rfq'],
    }),
    cloneRfq: build.mutation<RfqView, number>({
      query: (id) => ({ url: `/rfqs/${id}/clone`, method: 'POST' }),
      invalidatesTags: ['Rfq'],
    }),
    rfqDocuments: build.query<DocumentView[], number>({
      query: (id) => `/rfqs/${id}/documents`,
      providesTags: (_r, _e, id) => [{ type: 'RfqDocuments', id }],
    }),
    uploadRfqDocument: build.mutation<DocumentView, { id: number; file: File; visibility?: Visibility }>({
      query: ({ id, file, visibility }) => {
        const body = new FormData()
        body.append('file', file)
        if (visibility) body.append('visibility', visibility)
        return { url: `/rfqs/${id}/documents`, method: 'POST', body }
      },
      invalidatesTags: (_r, _e, { id }) => [{ type: 'RfqDocuments', id }],
    }),
    setRfqDocumentVisibility: build.mutation<DocumentView, { id: number; documentId: number; visibility: Visibility }>({
      query: ({ id, documentId, visibility }) => ({
        url: `/rfqs/${id}/documents/${documentId}/visibility`,
        method: 'PUT',
        body: { visibility },
      }),
      invalidatesTags: (_r, _e, { id }) => [{ type: 'RfqDocuments', id }],
    }),
    deleteRfqDocument: build.mutation<void, { id: number; documentId: number }>({
      query: ({ id, documentId }) => ({ url: `/rfqs/${id}/documents/${documentId}`, method: 'DELETE' }),
      invalidatesTags: (_r, _e, { id }) => [{ type: 'RfqDocuments', id }],
    }),
    /** Resolves to an object URL (the caller downloads it, then revokes it). */
    downloadRfqDocument: build.mutation<string, { id: number; documentId: number }>({
      query: ({ id, documentId }) => ({
        url: `/rfqs/${id}/documents/${documentId}/content`,
        responseHandler: async (response) =>
          response.ok ? URL.createObjectURL(await response.blob()) : response.json(),
      }),
    }),
  }),
})

export const {
  useRfqsQuery,
  useRfqQuery,
  useRfqHistoryQuery,
  useCreateRfqMutation,
  useUpdateRfqMutation,
  useUpdateRfqNotesMutation,
  useRfqCommandMutation,
  useCloneRfqMutation,
  useRfqDocumentsQuery,
  useUploadRfqDocumentMutation,
  useSetRfqDocumentVisibilityMutation,
  useDeleteRfqDocumentMutation,
  useDownloadRfqDocumentMutation,
} = rfqApi
