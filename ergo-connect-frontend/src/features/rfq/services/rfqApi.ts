import { api } from '@/services/baseApi'
import type { AuthState } from '@/features/auth/store/authSlice'
import { uploadWithProgress } from '@/utils/uploadWithProgress'

export type RfqStatus =
  | 'DRAFT'
  | 'SUBMITTED'
  | 'UNDER_REVIEW'
  | 'CLARIFICATION_REQUIRED'
  | 'SENT_TO_SUPPLIERS'
  | 'QUOTES_PARTIAL'
  | 'QUOTES_COMPLETE'
  | 'SENT_TO_CUSTOMER'
  | 'CUSTOMER_ON_HOLD'
  | 'CUSTOMER_REVISION_REQUESTED'
  | 'CUSTOMER_APPROVED'
  | 'CUSTOMER_DECLINED'
  | 'CLOSED'
  | 'CANCELLED'

/** What customers see: Draft, Submitted, Under Review (clarification and supplier progress included), Quotation Submitted, then their own decision. */
export type CustomerRfqStatus =
  | 'DRAFT'
  | 'SUBMITTED'
  | 'UNDER_REVIEW'
  | 'QUOTE_RECEIVED'
  | 'ON_HOLD'
  | 'REVISION_REQUESTED'
  | 'APPROVED'
  | 'DECLINED'
  | 'CLOSED'
  | 'CANCELLED'

export type Priority = 'LOW' | 'NORMAL' | 'HIGH' | 'URGENT'
export type Visibility = 'INTERNAL' | 'CUSTOMER' | 'SUPPLIER' | 'ALL'
/** The four attachment slots of a part in the customer's RFQ table. */
export type FileKind = 'DRAWING_2D' | 'MODEL_3D' | 'SPECIFICATION' | 'ADDITIONAL'

/** A file in one of a part's slots. */
export interface ItemFile {
  id: number
  kind: FileKind
  fileName: string
  size: number
  ownCompany: boolean
  canDelete: boolean
  createdAt: string
}

/** Allowed extensions (lower case, no dot) and size limit of one slot; the backend is the source, read once. */
export interface AttachmentRule {
  kind: FileKind
  extensions: string[]
  maxBytes: number
}

export type SourcingType = 'INHOUSE' | 'OUTSOURCED'

/** Buttons the backend allows for this user, role and status right now. */
export type RfqAction = 'EDIT' | 'EDIT_NOTES' | 'SUBMIT' | 'START_REVIEW' | 'REQUEST_CLARIFICATION' | 'CANCEL' | 'CLOSE' | 'CLONE' | 'UPLOAD'

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

/** The customer's targets for a part: ERGO and that customer only. */
export interface TargetsView {
  targetToolingCost: number | null
  targetPpapPiecePrice: number | null
  targetProductionPiecePrice: number | null
  materialLeadTimeWeeks: number | null
  toolingLeadTimeDays: number | null
  sampleLeadTimeDays: number | null
  productionLeadTimeDays: number | null
  moq: number | null
}

/** One part (item) of an RFQ with its technical data, targets and process flow. */
export interface ItemView {
  id: number
  lineNo: number
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
  targets: TargetsView
  processSteps: StepView[]
  remarks: string | null
  files: ItemFile[]
}

/** A part as its own customer sees it: only what the customer entered, plus its files. */
export interface CustomerItemView {
  id: number
  lineNo: number
  partNumber: string
  description: string | null
  eau: number | null
  targetPrice: number | null
  remarks: string | null
  files: ItemFile[]
}

interface RfqViewBase {
  id: number
  number: string
  title: string | null
  description: string | null
  specialInstructions: string | null
  targetQuoteDate: string | null
  currency: Named
  customerRemarks: string | null
  clarificationQuestion: string | null
  clarificationAnswer: string | null
  actions: RfqAction[]
  createdAt: string
  updatedAt: string
}

export interface ErgoRfqView extends RfqViewBase {
  audience: 'ERGO'
  /** The reference suppliers see instead of the number (the number carries the customer's prefix). */
  supplierRef: string
  status: RfqStatus
  sourceType: 'CUSTOMER' | 'ERGO'
  customer: Named
  createdBy: string | null
  owner: string | null
  internalNotes: string | null
  priority: Priority
  paymentTerm: Named | null
  items: ItemView[]
}

export interface CustomerRfqView extends RfqViewBase {
  audience: 'CUSTOMER'
  status: CustomerRfqStatus
  items: CustomerItemView[]
}

export type RfqView = ErgoRfqView | CustomerRfqView

export interface RfqRow {
  id: number
  number: string
  title: string | null
  status: RfqStatus | CustomerRfqStatus
  /** ERGO only */
  customer?: string
  /** ERGO only */
  owner?: string | null
  /** The part numbers of the RFQ's items, in order. */
  parts: string[]
  targetQuoteDate: string | null
  /** ERGO only */
  priority?: Priority
  createdAt: string
}

/** One part in the form. `id` is set for a part that already exists, so its quotes and history stay with it. */
export interface ItemForm {
  id: number | null
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
  targetToolingCost: number | null
  targetPpapPiecePrice: number | null
  targetProductionPiecePrice: number | null
  materialLeadTimeWeeks: number | null
  toolingLeadTimeDays: number | null
  sampleLeadTimeDays: number | null
  productionLeadTimeDays: number | null
  moq: number | null
  processSteps: ProcessStep[]
  remarks: string | null
}

export interface RfqForm {
  customerCompanyId: number | null
  title: string | null
  description: string | null
  specialInstructions: string | null
  targetQuoteDate: string | null
  priority: Priority
  currencyId: number | null
  paymentTermId: number | null
  customerRemarks: string | null
  internalNotes: string | null
  items: ItemForm[]
}

export interface DocumentView {
  id: number
  fileName: string
  size: number
  visibility: Visibility
  ownCompany: boolean
  canDelete: boolean
  createdAt: string
  /** ERGO only: the parts a file shared with suppliers is shared for. */
  itemIds: number[]
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

export interface RfqPage {
  items: RfqRow[]
  /** Every RFQ the caller can see that matches the filter, not just this page. */
  total: number
  page: number
  size: number
}

/** `status` is the caller's own vocabulary (an internal status for ERGO, the collapsed one for a customer). */
export interface RfqListArgs {
  page: number
  size: number
  status?: string
  q?: string
}

/** Workflow endpoints that take an optional/required comment. */
export type RfqCommand = 'submit' | 'start-review' | 'request-clarification' | 'cancel' | 'close'

export const rfqApi = api.enhanceEndpoints({ addTagTypes: ['Rfq', 'RfqDocuments'] }).injectEndpoints({
  endpoints: (build) => ({
    // One page, newest first; filtered and searched by the server (Phase 8o).
    rfqs: build.query<RfqPage, RfqListArgs>({
      query: ({ page, size, status, q }) => ({ url: '/rfqs', params: { page, size, ...(status ? { status } : {}), ...(q ? { q } : {}) } }),
      providesTags: ['Rfq'],
    }),
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
    attachmentRules: build.query<AttachmentRule[], void>({ query: () => '/rfqs/attachment-rules' }),
    /**
     * Files into one slot of one part; resolves to the stored files. Goes through XMLHttpRequest (not the shared fetch
     * base query) so the page can show real upload progress. A cheap request through the shared base query comes
     * first: it renews an expired access token and follows permission changes exactly as every other call does.
     */
    uploadItemFiles: build.mutation<
      ItemFile[],
      { id: number; itemId: number; kind: FileKind; files: File[]; onProgress?: (loaded: number, total: number) => void }
    >({
      async queryFn({ id, itemId, kind, files, onProgress }, api, _extra, baseQuery) {
        await baseQuery({ url: '/auth/access' })
        const token = (api.getState() as { auth: AuthState }).auth?.session?.accessToken
        const body = new FormData()
        files.forEach((file) => body.append('files', file))
        return uploadWithProgress<ItemFile[]>(`/rfqs/${id}/items/${itemId}/files?kind=${encodeURIComponent(kind)}`, body, token, onProgress)
      },
      invalidatesTags: (_r, _e, { id }) => [{ type: 'RfqDocuments', id }, { type: 'Rfq', id }],
    }),
    uploadRfqDocument: build.mutation<DocumentView, { id: number; file: File; visibility?: Visibility; itemIds?: number[] }>({
      query: ({ id, file, visibility, itemIds }) => {
        const body = new FormData()
        body.append('file', file)
        if (visibility) body.append('visibility', visibility)
        itemIds?.forEach((itemId) => body.append('itemIds', String(itemId)))
        return { url: `/rfqs/${id}/documents`, method: 'POST', body }
      },
      invalidatesTags: (_r, _e, { id }) => [{ type: 'RfqDocuments', id }, { type: 'Rfq', id }],
    }),
    setRfqDocumentVisibility: build.mutation<DocumentView, { id: number; documentId: number; visibility: Visibility; itemIds?: number[] }>({
      query: ({ id, documentId, visibility, itemIds }) => ({
        url: `/rfqs/${id}/documents/${documentId}/visibility`,
        method: 'PUT',
        body: { visibility, itemIds: itemIds ?? [] },
      }),
      invalidatesTags: (_r, _e, { id }) => [{ type: 'RfqDocuments', id }, { type: 'Rfq', id }],
    }),
    deleteRfqDocument: build.mutation<void, { id: number; documentId: number }>({
      query: ({ id, documentId }) => ({ url: `/rfqs/${id}/documents/${documentId}`, method: 'DELETE' }),
      invalidatesTags: (_r, _e, { id }) => [{ type: 'RfqDocuments', id }, { type: 'Rfq', id }],
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
  useAttachmentRulesQuery,
  useUploadItemFilesMutation,
  useUploadRfqDocumentMutation,
  useSetRfqDocumentVisibilityMutation,
  useDeleteRfqDocumentMutation,
  useDownloadRfqDocumentMutation,
} = rfqApi
