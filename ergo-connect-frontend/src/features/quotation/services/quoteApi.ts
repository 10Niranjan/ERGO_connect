import { api } from '@/services/baseApi'
import type { Named, ProcessStep, StepView, Visibility } from '@/features/rfq/rfqApi'

/** The status of one part's quote (flow doc §3-5). */
export type QuoteStatus =
  | 'NOT_STARTED'
  | 'DRAFT_SAVED'
  | 'SUBMITTED'
  | 'REVISION_REQUIRED'
  | 'RESUBMITTED'
  | 'ACCEPTED'
  | 'WITH_CUSTOMER'
  | 'CUSTOMER_ON_HOLD'
  | 'CUSTOMER_CORRECTION'
  | 'CUSTOMER_APPROVED'
  | 'CUSTOMER_REJECTED'
  | 'RECALLED'

/** What a customer sees of a part: where its quotation stands, nothing about suppliers. */
export type CustomerPartStatus = 'IN_PROGRESS' | 'QUOTATION_RECEIVED' | 'ON_HOLD' | 'CORRECTION_REQUIRED' | 'APPROVED' | 'REJECTED'

/** Buttons the backend allows for this user, role and status right now. */
export type QuoteAction = 'EDIT' | 'SUBMIT' | 'REQUEST_REVISION' | 'ACCEPT' | 'RECALL' | 'UPLOAD'

export interface ToolingLine {
  toolName: string
  toolingCost: number
  remarks: string | null
}

/** A quote's content; also what each submitted version keeps. */
export interface QuoteData {
  quoteReference: string | null
  validityDate: string | null
  currency: Named | null
  incoterm: Named | null
  paymentTerm: Named | null
  toolingCostTotal: number | null
  tooling: ToolingLine[]
  ppapPiecePrice: number | null
  productionPiecePrice: number | null
  costs: {
    rawMaterialCost: number | null
    processingCost: number | null
    overheadCost: number | null
    packagingCost: number | null
    profit: number | null
  }
  moq: number | null
  materialLeadTimeWeeks: number | null
  toolingLeadTimeDays: number | null
  sampleLeadTimeDays: number | null
  productionLeadTimeDays: number | null
  processSteps: StepView[]
  assumptionsDeviations: string | null
  remarks: string | null
}

/** One step of the part's planned route as suppliers see it: names only; each supplier fills in the rest. */
export interface PlannedStep {
  processId: number | null
  processName: string | null
  stepName: string
}

/** What a supplier sees of one RFQ part sent to it: no customer, targets, notes or remarks, and no other part. */
export interface SupplierRfqItem {
  rfqId: number
  rfqNumber: string
  itemId: number
  partNumber: string
  revision: string | null
  description: string | null
  eau: number | null
  netWeightKg: number | null
  grossWeightKg: number | null
  material: string | null
  materialSpec: string | null
  coating: string | null
  coatingSpec: string | null
  processSteps: PlannedStep[]
  quotingOpen: boolean
}

/** The RFQ part a quote is about, as ERGO sees it. */
export interface PartRef {
  itemId: number
  lineNo: number
  partNumber: string
  revision: string | null
  description: string | null
}

interface QuoteViewBase {
  id: number
  responseDueDate: string
  subRfqNo: number
  actions: QuoteAction[]
}

export interface SupplierQuoteView extends QuoteViewBase {
  audience: 'SUPPLIER'
  status: QuoteStatus
  part: SupplierRfqItem
  /** The supplier's working copy (null until first saved). */
  quote: QuoteData | null
  version: number
  submittedAt: string | null
  /** What ERGO wants changed (flow doc §4: revision required). */
  revisionReason: string | null
}

export interface ErgoQuoteView extends QuoteViewBase {
  audience: 'ERGO'
  status: QuoteStatus
  rfqId: number
  rfqNumber: string
  supplier: Named
  part: PartRef
  invitedBy: string | null
  invitedAt: string
  /** The last submitted version; ERGO never sees unsent drafts. */
  quote: QuoteData | null
  version: number
  submittedAt: string | null
  revisionReason: string | null
  recallReason: string | null
}

/** A supplier role with RFQ access but none on Quotation: the assigned part only, no quote status or correspondence. */
export interface SupplierAssignedRfqView extends QuoteViewBase {
  audience: 'SUPPLIER_RFQ'
  part: SupplierRfqItem
}

export type QuoteView = SupplierQuoteView | ErgoQuoteView | SupplierAssignedRfqView

/** The supplier's "Assigned RFQs" list, one row per part sent to it; the quote columns are absent without Quotation access. */
export interface SupplierQuoteRow {
  id: number
  rfqNumber: string
  subRfqNo: number
  partNumber: string
  revision: string | null
  description: string | null
  responseDueDate: string
  status?: QuoteStatus
  version?: number
  submittedAt?: string | null
  quotingOpen: boolean
}

/**
 * One part of an RFQ on ERGO's quotes tab, with the supplier it is with and that supplier's last submitted figures.
 * `quoteId` is null while the part is not with a supplier.
 */
export interface PartRow {
  part: PartRef
  quoteId: number | null
  subRfqNo: number | null
  supplier: Named | null
  status: QuoteStatus | null
  responseDueDate: string | null
  version: number | null
  submittedAt: string | null
  currency: string | null
  productionPiecePrice: number | null
  toolingCostTotal: number | null
  moq: number | null
  productionLeadTimeDays: number | null
  actions: QuoteAction[]
}

export interface RfqQuotes {
  canDistribute: boolean
  parts: PartRow[]
}

/** A part's status: ERGO the full quote status (or UNASSIGNED), the RFQ's customer where its quotation stands. */
export interface PartStatusRow {
  itemId: number
  lineNo: number
  partNumber: string
  revision: string | null
  status: QuoteStatus | CustomerPartStatus | 'UNASSIGNED'
}

export interface VersionView {
  version: number
  submittedAt: string
  submittedBy: string | null
  /** Why ERGO sent the quote back before this version. */
  reopenReason: string | null
  reopenedBy: string | null
  reopenedAt: string | null
  quote: QuoteData
  /** The supplier's files submitted with this version. */
  files: string[]
}

/** RFQ = a file ERGO shared from the RFQ (suppliers only); QUOTE = a file attached to this quote. */
export interface QuoteDocument {
  id: number
  source: 'RFQ' | 'QUOTE'
  fileName: string
  size: number
  visibility: Visibility
  ownCompany: boolean
  canDelete: boolean
  createdAt: string
}

export interface QuoteForm {
  quoteReference: string | null
  validityDate: string | null
  currencyId: number | null
  incotermId: number | null
  paymentTermId: number | null
  /** Ignored when tooling lines are given: the total is their sum. */
  toolingCostTotal: number | null
  /** The backend rejects a line without a cost. */
  tooling: { toolName: string; toolingCost: number | null; remarks: string | null }[]
  ppapPiecePrice: number | null
  productionPiecePrice: number | null
  rawMaterialCost: number | null
  processingCost: number | null
  overheadCost: number | null
  packagingCost: number | null
  profit: number | null
  moq: number | null
  materialLeadTimeWeeks: number | null
  toolingLeadTimeDays: number | null
  sampleLeadTimeDays: number | null
  productionLeadTimeDays: number | null
  processSteps: ProcessStep[]
  assumptionsDeviations: string | null
  remarks: string | null
}

/** One sub-RFQ: the parts that go to one supplier. */
export interface SubRfqForm {
  supplierId: number
  itemIds: number[]
}

/** Workflow endpoints: submit takes nothing, accept nothing; revision (required) and recall (required) a comment. */
export type QuoteCommand = 'submit' | 'accept' | 'revision' | 'recall'

// Quote changes move the RFQ status (sent, partly/fully received), hence the Rfq tag.
const quoteApi = api.enhanceEndpoints({ addTagTypes: ['Quote', 'QuoteDocuments', 'Rfq'] }).injectEndpoints({
  endpoints: (build) => ({
    rfqQuotes: build.query<RfqQuotes, number>({ query: (rfqId) => `/rfqs/${rfqId}/quotes`, providesTags: ['Quote'] }),
    rfqParts: build.query<PartStatusRow[], number>({ query: (rfqId) => `/rfqs/${rfqId}/parts`, providesTags: ['Quote'] }),
    distributeRfq: build.mutation<RfqQuotes, { rfqId: number; subRfqs: SubRfqForm[]; responseDueDate: string }>({
      query: ({ rfqId, ...body }) => ({ url: `/rfqs/${rfqId}/quotes`, method: 'POST', body }),
      invalidatesTags: ['Quote', 'Rfq'],
    }),
    myQuotes: build.query<SupplierQuoteRow[], void>({ query: () => '/quotes', providesTags: ['Quote'] }),
    quote: build.query<QuoteView, number>({ query: (id) => `/quotes/${id}`, providesTags: ['Quote'] }),
    quoteVersions: build.query<VersionView[], number>({ query: (id) => `/quotes/${id}/versions`, providesTags: ['Quote'] }),
    saveQuote: build.mutation<QuoteView, { id: number; form: QuoteForm }>({
      query: ({ id, form }) => ({ url: `/quotes/${id}`, method: 'PUT', body: form }),
      invalidatesTags: ['Quote'],
    }),
    quoteCommand: build.mutation<QuoteView, { id: number; command: QuoteCommand; comment?: string }>({
      query: ({ id, command, comment }) =>
        command === 'submit' || command === 'accept'
          ? { url: `/quotes/${id}/${command}`, method: 'POST' }
          : { url: `/quotes/${id}/${command}`, method: 'POST', body: { comment: comment ?? null } },
      invalidatesTags: ['Quote', 'Rfq'],
    }),
    quoteDocuments: build.query<QuoteDocument[], number>({
      query: (id) => `/quotes/${id}/documents`,
      providesTags: (_r, _e, id) => [{ type: 'QuoteDocuments', id }],
    }),
    uploadQuoteDocument: build.mutation<QuoteDocument, { id: number; file: File }>({
      query: ({ id, file }) => {
        const body = new FormData()
        body.append('file', file)
        return { url: `/quotes/${id}/documents`, method: 'POST', body }
      },
      invalidatesTags: (_r, _e, { id }) => [{ type: 'QuoteDocuments', id }],
    }),
    deleteQuoteDocument: build.mutation<void, { id: number; documentId: number }>({
      query: ({ id, documentId }) => ({ url: `/quotes/${id}/documents/${documentId}`, method: 'DELETE' }),
      invalidatesTags: (_r, _e, { id }) => [{ type: 'QuoteDocuments', id }],
    }),
    /** Resolves to an object URL (the caller saves it, then revokes it). */
    downloadQuoteDocument: build.mutation<string, { id: number; documentId: number; source: QuoteDocument['source'] }>({
      query: ({ id, documentId, source }) => ({
        url: `/quotes/${id}/documents/${documentId}/content?source=${source}`,
        responseHandler: async (response) =>
          response.ok ? URL.createObjectURL(await response.blob()) : response.json(),
      }),
    }),
  }),
})

export const {
  useRfqQuotesQuery,
  useRfqPartsQuery,
  useDistributeRfqMutation,
  useMyQuotesQuery,
  useQuoteQuery,
  useQuoteVersionsQuery,
  useSaveQuoteMutation,
  useQuoteCommandMutation,
  useQuoteDocumentsQuery,
  useUploadQuoteDocumentMutation,
  useDeleteQuoteDocumentMutation,
  useDownloadQuoteDocumentMutation,
} = quoteApi
