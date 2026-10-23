import { formatDate, formatMoney, formatNumber } from '@/features/rfq/utils/labels'
import type { ChipColor } from '@/types'
import type { SourcingType } from '@/features/rfq/rfqApi'
import type { ErgoQuoteTerms, ResponseStatus } from '../services/customerQuoteApi'
import type { CustomerPartStatus, PartStatusRow, QuoteData, QuoteStatus } from '../services/quoteApi'

/** The status of one part's quote (flow doc §3-5). */
export const QUOTE_STATUS: Record<QuoteStatus, { label: string; color: ChipColor }> = {
  NOT_STARTED: { label: 'Not started', color: 'default' },
  DRAFT_SAVED: { label: 'Draft saved', color: 'info' },
  SUBMITTED: { label: 'Submitted to ERGO', color: 'success' },
  REVISION_REQUIRED: { label: 'Revision required', color: 'warning' },
  RESUBMITTED: { label: 'Resubmitted', color: 'success' },
  ACCEPTED: { label: 'Accepted by ERGO', color: 'primary' },
  WITH_CUSTOMER: { label: 'Quotation with customer', color: 'secondary' },
  CUSTOMER_ON_HOLD: { label: 'On hold by customer', color: 'warning' },
  CUSTOMER_CORRECTION: { label: 'Correction required by customer', color: 'warning' },
  CUSTOMER_APPROVED: { label: 'Approved by customer', color: 'success' },
  CUSTOMER_REJECTED: { label: 'Rejected by customer', color: 'error' },
  RECALLED: { label: 'Taken back', color: 'default' },
}

/** What a customer reads for a part (no supplier, nothing before ERGO's quotation). */
export const CUSTOMER_PART_STATUS: Record<CustomerPartStatus, { label: string; color: ChipColor }> = {
  IN_PROGRESS: { label: 'Quotation in progress', color: 'primary' },
  QUOTATION_RECEIVED: { label: 'Quotation received', color: 'secondary' },
  ON_HOLD: { label: 'On hold', color: 'warning' },
  CORRECTION_REQUIRED: { label: 'Correction required', color: 'warning' },
  APPROVED: { label: 'Approved', color: 'success' },
  REJECTED: { label: 'Rejected', color: 'error' },
}

/** A part's status chip for either audience: ERGO's quote statuses, or the customer's collapsed ones. */
export function partStatusLabel(status: PartStatusRow['status']): { label: string; color: ChipColor } {
  if (status === 'UNASSIGNED') return { label: 'Not sent to a supplier', color: 'default' }
  return status in QUOTE_STATUS ? QUOTE_STATUS[status as QuoteStatus] : CUSTOMER_PART_STATUS[status as CustomerPartStatus]
}

export const SOURCING: Record<SourcingType, string> = { INHOUSE: 'In-house', OUTSOURCED: 'Outsourced' }

/** SRS §14.4. `customer` is how the customer reads the same state. */
export const RESPONSE_STATUS: Record<ResponseStatus, { label: string; customer: string; color: ChipColor }> = {
  WAITING: { label: 'Waiting for the customer', customer: 'Waiting for your answer', color: 'info' },
  ON_HOLD: { label: 'On hold by the customer', customer: 'On hold', color: 'warning' },
  APPROVED: { label: 'Approved by the customer', customer: 'Approved', color: 'success' },
  REVISION_REQUESTED: { label: 'Correction required', customer: 'Correction required', color: 'warning' },
  DECLINED: { label: 'Rejected by the customer', customer: 'Rejected', color: 'error' },
}

const named = (value: { code: string; name: string } | null) => (value ? `${value.code} – ${value.name}` : '—')

/** A quote as labelled sections of text. The read-only view and the version comparison both use it. */
export function quoteSections(q: QuoteData): { title: string; facts: [string, string][] }[] {
  const money = (value: number | null) => formatMoney(value, q.currency?.code)
  const costs = Object.values(q.costs)
  const costTotal = costs.every((c) => c != null) ? costs.reduce((sum, c) => sum + (c ?? 0), 0) : null
  return [
    {
      title: 'Commercial terms',
      facts: [
        ['Quote reference', q.quoteReference ?? '—'],
        ['Valid until', formatDate(q.validityDate)],
        ['Currency', named(q.currency)],
        ['Incoterm', named(q.incoterm)],
        ['Payment terms', q.paymentTerm?.name ?? '—'],
        ['MOQ', formatNumber(q.moq, ' pcs')],
      ],
    },
    {
      title: 'Prices',
      facts: [
        ['Production piece price', money(q.productionPiecePrice)],
        ['PPAP piece price', money(q.ppapPiecePrice)],
        ['Tooling cost', money(q.toolingCostTotal)],
        ['Tools', q.tooling.map((t) => `${t.toolName}: ${money(t.toolingCost)}${t.remarks ? ` (${t.remarks})` : ''}`).join('\n') || '—'],
      ],
    },
    {
      title: 'Cost breakdown per piece',
      facts: [
        ['Raw material', money(q.costs.rawMaterialCost)],
        ['Processing', money(q.costs.processingCost)],
        ['Overhead', money(q.costs.overheadCost)],
        ['Packaging', money(q.costs.packagingCost)],
        ['Profit', money(q.costs.profit)],
        ['Sum of breakdown', money(costTotal)],
      ],
    },
    {
      title: 'Lead times',
      facts: [
        ['Material', formatNumber(q.materialLeadTimeWeeks, ' weeks')],
        ['Tooling', formatNumber(q.toolingLeadTimeDays, ' days')],
        ['Samples', formatNumber(q.sampleLeadTimeDays, ' days')],
        ['Production from PO', formatNumber(q.productionLeadTimeDays, ' days')],
      ],
    },
    {
      title: 'Process route',
      facts: [
        [
          '',
          q.processSteps
            .map((s, i) =>
              [
                `${i + 1}. ${s.stepName}${s.process ? ` (${s.process.name})` : ''}`,
                s.cycleTimeSeconds != null && `${s.cycleTimeSeconds} s`,
                s.sourcingType && SOURCING[s.sourcingType] + (s.outsourcedSupplierName ? `: ${s.outsourcedSupplierName}` : ''),
                s.remarks,
              ]
                .filter(Boolean)
                .join(' · '),
            )
            .join('\n') || '—',
        ],
      ],
    },
    {
      title: 'Assumptions and remarks',
      facts: [
        ['Assumptions / deviations', q.assumptionsDeviations ?? '—'],
        ['Remarks', q.remarks ?? '—'],
      ],
    },
  ]
}

/** What changed from one submitted version to the next, field by field. */
export function quoteChanges(before: QuoteData, after: QuoteData): { field: string; from: string; to: string }[] {
  const flat = (q: QuoteData) => quoteSections(q).flatMap((s) => s.facts.map(([label, value]): [string, string] => [label || s.title, value]))
  const old = new Map(flat(before))
  return flat(after)
    .filter(([field, value]) => old.get(field) !== value)
    .map(([field, to]) => ({ field, from: old.get(field) ?? '—', to }))
}

/** ERGO's own quote as the customer sees it. */
export function ergoQuoteSections(q: ErgoQuoteTerms): { title: string; facts: [string, string][] }[] {
  const money = (value: number | null) => formatMoney(value, q.currency.code)
  return [
    {
      title: 'Prices',
      facts: [
        ['Production piece price', money(q.productionPiecePrice)],
        ['PPAP piece price', money(q.ppapPiecePrice)],
        ['Tooling cost', money(q.toolingCost)],
      ],
    },
    {
      title: 'Terms',
      facts: [
        ['Currency', named(q.currency)],
        ['Payment terms', q.paymentTerm?.name ?? '—'],
        ['Incoterm', named(q.incoterm)],
        ['MOQ', formatNumber(q.moq, ' pcs')],
        ['Valid until', formatDate(q.validityDate)],
      ],
    },
    {
      title: 'Lead times',
      facts: [
        ['Material', formatNumber(q.materialLeadTimeWeeks, ' weeks')],
        ['Tooling', formatNumber(q.toolingLeadTimeDays, ' days')],
        ['Samples', formatNumber(q.sampleLeadTimeDays, ' days')],
        ['Production from PO', formatNumber(q.productionLeadTimeDays, ' days')],
      ],
    },
  ]
}
