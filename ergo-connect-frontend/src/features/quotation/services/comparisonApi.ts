import { api } from '@/services/baseApi'
import type { Named } from '@/features/rfq/services/rfqApi'
import type { QuoteAction, QuoteData, QuoteStatus } from './quoteApi'

/** 0-10, higher is better (risk 10 = lowest risk). */
export interface Scores {
  commercial: number | null
  technical: number | null
  quality: number | null
  risk: number | null
}

/** The quote's prices in the RFQ currency; all null when ERGO has no exchange rate for the pair. */
export interface Converted {
  rate: number | null
  productionPiecePrice: number | null
  ppapPiecePrice: number | null
  toolingCost: number | null
}

/** No sub-supplier names or free-text remarks: the supplier wrote those for ERGO, not for the customer. */
export interface CustomerProcessStep {
  processName: string | null
  stepName: string
  cycleTimeSeconds: number | null
  sourcingType: string | null
}

export interface TargetsView {
  toolingCost: number | null
  ppapPiecePrice: number | null
  productionPiecePrice: number | null
  paymentTerm: Named | null
  moq: number | null
  materialLeadTimeWeeks: number | null
  toolingLeadTimeDays: number | null
  sampleLeadTimeDays: number | null
  productionLeadTimeDays: number | null
}

export interface ComparisonLine {
  quoteId: number
  supplier: Named
  status: QuoteStatus
  version: number
  submittedAt: string
  /** The last submitted version. */
  quote: QuoteData
  converted: Converted
  scores: Scores | null
  totalScore: number | null
  internalRemarks: string | null
  actions: QuoteAction[]
}

/** ERGO only (SRS §12.7.1). */
export interface ComparisonView {
  rfqId: number
  rfqNumber: string
  currency: Named
  targets: TargetsView
  internalSummary: string | null
  recommendationSummary: string | null
  preparedBy: string | null
  preparedAt: string | null
  canEdit: boolean
  lines: ComparisonLine[]
}

export type QuoteType = 'SUPPLIER_QUOTE' | 'ERGO_QUOTE'
export type ResponseStatus = 'WAITING' | 'APPROVED' | 'REVISION_REQUESTED' | 'DECLINED'
export type CustomerResponse = 'APPROVED' | 'REVISION_REQUESTED' | 'DECLINED'
export type Decision = 'SHORTLIST' | 'SELECT_FOR_CUSTOMER' | 'MARK_NOT_SELECTED' | 'CLEAR_DECISION'

/** What the customer sees of an ERGO quote: ERGO's terms only. */
export interface ErgoQuoteTerms {
  currency: Named
  productionPiecePrice: number
  ppapPiecePrice: number | null
  toolingCost: number | null
  paymentTerm: Named | null
  incoterm: Named | null
  moq: number | null
  materialLeadTimeWeeks: number | null
  toolingLeadTimeDays: number | null
  sampleLeadTimeDays: number | null
  productionLeadTimeDays: number | null
  validityDate: string
}

/**
 * What the customer sees of a forwarded supplier quote: name, prices and cost breakdown (client decision) — narrower
 * than the shared `QuoteData`, on purpose (no quote reference, assumptions, remarks or sub-supplier names).
 */
export interface CustomerSupplierQuote {
  validityDate: string | null
  currency: Named | null
  incoterm: Named | null
  paymentTerm: Named | null
  toolingCostTotal: number | null
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
  processSteps: CustomerProcessStep[]
}

/** Exactly what the customer received: a supplier's quote as-is, or ERGO's terms. */
export interface CustomerQuoteContent {
  type: QuoteType
  supplierName?: string
  supplierQuote?: CustomerSupplierQuote
  ergoQuote?: ErgoQuoteTerms
  remarks?: string
}

export interface ErgoCustomerQuoteView {
  id: number
  type: QuoteType
  quoteId: number
  supplier: Named
  supplierVersion: number
  content: CustomerQuoteContent
  /** ERGO quotes only; empty without an exchange rate. */
  marginAmount: number | null
  marginPercentage: number | null
  sentBy: string | null
  sentAt: string
  status: ResponseStatus
  respondedBy: string | null
  respondedAt: string | null
  responseComment: string | null
}

export interface CustomerQuoteView {
  id: number
  sentAt: string
  content: CustomerQuoteContent
  status: ResponseStatus
  respondedBy: string | null
  respondedAt: string | null
  responseComment: string | null
  actions: ('APPROVE' | 'REQUEST_REVISION' | 'DECLINE')[]
}

export type CustomerQuotesView =
  | { audience: 'ERGO'; quotes: ErgoCustomerQuoteView[] }
  | { audience: 'CUSTOMER'; quotes: CustomerQuoteView[] }

export interface ErgoQuoteForm {
  currencyId: number | null
  productionPiecePrice: number | null
  ppapPiecePrice: number | null
  toolingCost: number | null
  paymentTermId: number | null
  incotermId: number | null
  moq: number | null
  materialLeadTimeWeeks: number | null
  toolingLeadTimeDays: number | null
  sampleLeadTimeDays: number | null
  productionLeadTimeDays: number | null
  validityDate: string | null
}

// Comparison actions move the RFQ (under comparison, sent to customer, the customer's answer): hence the Rfq tag.
const comparisonApi = api.enhanceEndpoints({ addTagTypes: ['Quote', 'CustomerQuote', 'Rfq', 'ExchangeRate'] }).injectEndpoints({
  endpoints: (build) => ({
    comparison: build.query<ComparisonView, number>({
      query: (rfqId) => `/rfqs/${rfqId}/comparison`,
      providesTags: ['Quote', 'ExchangeRate'],
    }),
    saveComparisonSummary: build.mutation<ComparisonView, { rfqId: number; internalSummary: string | null; recommendationSummary: string | null }>({
      query: ({ rfqId, ...body }) => ({ url: `/rfqs/${rfqId}/comparison`, method: 'PUT', body }),
      invalidatesTags: ['Quote', 'Rfq'],
    }),
    evaluateQuote: build.mutation<ComparisonView, { quoteId: number; scores: Scores; internalRemarks: string | null }>({
      query: ({ quoteId, ...body }) => ({ url: `/quotes/${quoteId}/evaluation`, method: 'PUT', body }),
      invalidatesTags: ['Quote', 'Rfq'],
    }),
    decideQuote: build.mutation<unknown, { quoteId: number; decision: Decision; comment?: string }>({
      query: ({ quoteId, decision, comment }) => ({ url: `/quotes/${quoteId}/decision`, method: 'POST', body: { decision, comment: comment ?? null } }),
      invalidatesTags: ['Quote', 'Rfq'],
    }),
    customerQuotes: build.query<CustomerQuotesView, number>({
      query: (rfqId) => `/rfqs/${rfqId}/customer-quotes`,
      providesTags: ['CustomerQuote'],
    }),
    sendCustomerQuote: build.mutation<CustomerQuotesView, { rfqId: number; quoteId: number; type: QuoteType; ergoQuote: ErgoQuoteForm | null; remarks: string | null }>({
      query: ({ rfqId, ...body }) => ({ url: `/rfqs/${rfqId}/customer-quotes`, method: 'POST', body }),
      invalidatesTags: ['Quote', 'CustomerQuote', 'Rfq'],
    }),
    respondCustomerQuote: build.mutation<CustomerQuotesView, { id: number; response: CustomerResponse; comment: string | null }>({
      query: ({ id, ...body }) => ({ url: `/customer-quotes/${id}/respond`, method: 'POST', body }),
      invalidatesTags: ['CustomerQuote', 'Rfq'],
    }),
  }),
})

export const {
  useComparisonQuery,
  useSaveComparisonSummaryMutation,
  useEvaluateQuoteMutation,
  useDecideQuoteMutation,
  useCustomerQuotesQuery,
  useSendCustomerQuoteMutation,
  useRespondCustomerQuoteMutation,
} = comparisonApi
