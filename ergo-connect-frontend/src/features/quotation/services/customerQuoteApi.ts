import { api } from '@/services/baseApi'
import type { Named } from '@/features/rfq/rfqApi'

/** Flow doc §5: waiting for the customer, then its answer (on hold is answered again). */
export type ResponseStatus = 'WAITING' | 'ON_HOLD' | 'APPROVED' | 'REVISION_REQUESTED' | 'DECLINED'
export type CustomerResponse = 'APPROVED' | 'REVISION_REQUESTED' | 'DECLINED' | 'ON_HOLD'

/** What the customer sees of ERGO's terms for a part: ERGO's terms only, never a supplier or the margin. */
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

/** One part of the quotation as the customer received it. */
export interface CustomerLine {
  rfqItemId: number
  lineNo: number
  partNumber: string
  revision?: string
  description?: string
  ergoQuote: ErgoQuoteTerms
}

/** Exactly what the customer received, frozen when generated. */
export interface CustomerQuoteContent {
  remarks?: string
  lines: CustomerLine[]
}

/** ERGO's record of one part of a quotation: the supplier quote behind it and ERGO's margin. */
export interface ErgoLineBasis {
  quoteId: number
  rfqItemId: number
  partNumber: string
  supplier: Named
  supplierVersion: number
  marginAmount: number | null
  marginPercentage: number | null
}

export interface ErgoCustomerQuoteView {
  id: number
  version: number
  content: CustomerQuoteContent
  basis: ErgoLineBasis[]
  sentBy: string | null
  sentAt: string
  status: ResponseStatus
  respondedBy: string | null
  respondedAt: string | null
  responseComment: string | null
}

export interface CustomerQuoteView {
  id: number
  version: number
  sentAt: string
  content: CustomerQuoteContent
  status: ResponseStatus
  respondedBy: string | null
  respondedAt: string | null
  responseComment: string | null
  actions: ('APPROVE' | 'REQUEST_REVISION' | 'DECLINE' | 'PUT_ON_HOLD')[]
}

export type CustomerQuotesView =
  | { audience: 'ERGO'; quotations: ErgoCustomerQuoteView[] }
  | { audience: 'CUSTOMER'; quotations: CustomerQuoteView[] }

/** ERGO's own terms for one part of the quotation (prefilled from the part's accepted supplier quote). */
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

export interface GenerateLine {
  quoteId: number
  ergoQuote: ErgoQuoteForm
}

// Generating and answering move the RFQ and its parts: hence the Rfq and Quote tags.
const customerQuoteApi = api.enhanceEndpoints({ addTagTypes: ['Quote', 'CustomerQuote', 'Rfq'] }).injectEndpoints({
  endpoints: (build) => ({
    customerQuotes: build.query<CustomerQuotesView, number>({
      query: (rfqId) => `/rfqs/${rfqId}/customer-quotes`,
      providesTags: ['CustomerQuote'],
    }),
    generateCustomerQuote: build.mutation<CustomerQuotesView, { rfqId: number; lines: GenerateLine[]; remarks: string | null }>({
      query: ({ rfqId, ...body }) => ({ url: `/rfqs/${rfqId}/customer-quotes`, method: 'POST', body }),
      invalidatesTags: ['Quote', 'CustomerQuote', 'Rfq'],
    }),
    respondCustomerQuote: build.mutation<CustomerQuotesView, { id: number; response: CustomerResponse; comment: string | null }>({
      query: ({ id, ...body }) => ({ url: `/customer-quotes/${id}/respond`, method: 'POST', body }),
      invalidatesTags: ['Quote', 'CustomerQuote', 'Rfq'],
    }),
  }),
})

export const { useCustomerQuotesQuery, useGenerateCustomerQuoteMutation, useRespondCustomerQuoteMutation } = customerQuoteApi
