import { api } from '../../app/store'
import type { Named, ProcessStep, StepView, Visibility } from '../rfq/rfqApi'

/** SRS §14.3, plus REGRETTED (the supplier declined to quote, with a reason). */
export type QuoteStatus =
  | 'NOT_STARTED'
  | 'DRAFT_SAVED'
  | 'CLARIFICATION_REQUESTED'
  | 'SUBMITTED'
  | 'REOPENED'
  | 'RESUBMITTED'
  | 'SHORTLISTED'
  | 'SELECTED_FOR_CUSTOMER'
  | 'NOT_SELECTED'
  | 'REGRETTED'

/** Buttons the backend allows for this user, role and status right now. */
export type QuoteAction =
  | 'EDIT'
  | 'SUBMIT'
  | 'REGRET'
  | 'REQUEST_CLARIFICATION'
  | 'ANSWER_CLARIFICATION'
  | 'REOPEN'
  | 'UPLOAD'
  | 'EVALUATE'
  | 'SHORTLIST'
  | 'SELECT_FOR_CUSTOMER'
  | 'MARK_NOT_SELECTED'
  | 'CLEAR_DECISION'
  | 'SEND_TO_CUSTOMER'

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

/** One step of the RFQ's planned route as suppliers see it: names only; each supplier fills in the rest. */
export interface PlannedStep {
  processId: number | null
  processName: string | null
  stepName: string
}

/** What a supplier sees of an RFQ: no customer, targets, notes or remarks. */
export interface SupplierRfq {
  id: number
  number: string
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

interface QuoteViewBase {
  id: number
  status: QuoteStatus
  responseDueDate: string
  version: number
  submittedAt: string | null
  reopenReason: string | null
  clarificationQuestion: string | null
  clarificationAnswer: string | null
  regretReason: string | null
  actions: QuoteAction[]
}

export interface SupplierQuoteView extends QuoteViewBase {
  audience: 'SUPPLIER'
  rfq: SupplierRfq
  /** The supplier's working copy (null until first saved). */
  quote: QuoteData | null
}

export interface ErgoQuoteView extends QuoteViewBase {
  audience: 'ERGO'
  rfqId: number
  rfqNumber: string
  supplier: Named
  invitedBy: string | null
  invitedAt: string
  /** The last submitted version; ERGO never sees unsent drafts. */
  quote: QuoteData | null
}

export type QuoteView = SupplierQuoteView | ErgoQuoteView

/** The supplier's "Assigned RFQs" list. */
export interface SupplierQuoteRow {
  id: number
  rfqNumber: string
  partNumber: string
  revision: string | null
  description: string | null
  responseDueDate: string
  status: QuoteStatus
  version: number
  submittedAt: string | null
  quotingOpen: boolean
}

/** One supplier on an RFQ (ERGO); the figures are from its last submitted version. */
export interface ErgoQuoteRow {
  id: number
  supplier: Named
  status: QuoteStatus
  responseDueDate: string
  version: number
  submittedAt: string | null
  currency: string | null
  productionPiecePrice: number | null
  toolingCostTotal: number | null
  moq: number | null
  productionLeadTimeDays: number | null
}

export interface RfqSuppliers {
  canInvite: boolean
  quotes: ErgoQuoteRow[]
}

export interface VersionView {
  version: number
  submittedAt: string
  submittedBy: string | null
  /** Why ERGO re-opened the quote before this version. */
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

/** Workflow endpoints; all but submit take a comment. */
export type QuoteCommand = 'submit' | 'regret' | 'request-clarification' | 'answer-clarification' | 'reopen'

// Quote changes move the RFQ status (sent, partly/fully received), hence the Rfq tag.
const quoteApi = api.enhanceEndpoints({ addTagTypes: ['Quote', 'QuoteDocuments', 'Rfq'] }).injectEndpoints({
  endpoints: (build) => ({
    rfqSuppliers: build.query<RfqSuppliers, number>({ query: (rfqId) => `/rfqs/${rfqId}/quotes`, providesTags: ['Quote'] }),
    inviteSuppliers: build.mutation<RfqSuppliers, { rfqId: number; supplierIds: number[]; responseDueDate: string }>({
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
        command === 'submit'
          ? { url: `/quotes/${id}/submit`, method: 'POST' }
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
  useRfqSuppliersQuery,
  useInviteSuppliersMutation,
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
