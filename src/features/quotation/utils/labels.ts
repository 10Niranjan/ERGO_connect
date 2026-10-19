import { formatDate, formatMoney, formatNumber } from '@/features/rfq/utils/labels'
import type { ChipColor } from '@/types'
import type { SourcingType } from '@/features/rfq/services/rfqApi'
import type { CustomerSupplierQuote, ErgoQuoteTerms, ResponseStatus } from '../services/comparisonApi'
import type { QuoteData, QuoteStatus } from '../services/quoteApi'

export const QUOTE_STATUS: Record<QuoteStatus, { label: string; color: ChipColor }> = {
  NOT_STARTED: { label: 'Not started', color: 'default' },
  DRAFT_SAVED: { label: 'Draft (not sent)', color: 'info' },
  CLARIFICATION_REQUESTED: { label: 'Clarification requested', color: 'warning' },
  SUBMITTED: { label: 'Submitted', color: 'success' },
  REOPENED: { label: 'Re-opened for revision', color: 'warning' },
  RESUBMITTED: { label: 'Resubmitted', color: 'success' },
  SHORTLISTED: { label: 'Shortlisted', color: 'primary' },
  SELECTED_FOR_CUSTOMER: { label: 'Selected for customer', color: 'success' },
  NOT_SELECTED: { label: 'Not selected', color: 'default' },
  REGRETTED: { label: 'Regretted', color: 'error' },
}

export const SOURCING: Record<SourcingType, string> = { INHOUSE: 'In-house', OUTSOURCED: 'Outsourced' }

/** SRS §14.4. `customer` is how the customer reads the same state. */
export const RESPONSE_STATUS: Record<ResponseStatus, { label: string; customer: string; color: ChipColor }> = {
  WAITING: { label: 'Waiting for the customer', customer: 'Waiting for your answer', color: 'info' },
  APPROVED: { label: 'Approved by the customer', customer: 'Approved', color: 'success' },
  REVISION_REQUESTED: { label: 'Revision requested', customer: 'Revision requested', color: 'warning' },
  DECLINED: { label: 'Declined by the customer', customer: 'Declined', color: 'error' },
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

/** A forwarded supplier quote as the customer sees it: name (shown separately), prices and cost breakdown only. */
export function customerSupplierQuoteSections(q: CustomerSupplierQuote): { title: string; facts: [string, string][] }[] {
  const money = (value: number | null) => formatMoney(value, q.currency?.code)
  return [
    {
      title: 'Commercial terms',
      facts: [
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
      facts: [['', q.processSteps.map((s, i) => `${i + 1}. ${s.stepName}${s.processName ? ` (${s.processName})` : ''}`).join('\n') || '—']],
    },
  ]
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
