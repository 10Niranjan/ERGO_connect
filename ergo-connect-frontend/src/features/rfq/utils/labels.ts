import type { CustomerRfqStatus, Priority, RfqStatus, Visibility } from '../services/rfqApi'
import type { ChipColor } from '@/types'

export { type ChipColor }

export const STATUS: Record<RfqStatus | CustomerRfqStatus, { label: string; color: ChipColor }> = {
  DRAFT: { label: 'Draft', color: 'default' },
  SUBMITTED: { label: 'Submitted', color: 'info' },
  UNDER_REVIEW: { label: 'Under ERGO review', color: 'info' },
  CLARIFICATION_REQUIRED: { label: 'Clarification required', color: 'warning' },
  VALIDATED: { label: 'Validated', color: 'primary' },
  SENT_TO_SUPPLIERS: { label: 'Sent to suppliers', color: 'primary' },
  QUOTES_PARTIAL: { label: 'Quotes partly received', color: 'primary' },
  QUOTES_COMPLETE: { label: 'Quotes received', color: 'primary' },
  UNDER_COMPARISON: { label: 'Under comparison', color: 'primary' },
  SENT_TO_CUSTOMER: { label: 'Sent to customer', color: 'secondary' },
  CUSTOMER_ON_HOLD: { label: 'On hold by customer', color: 'warning' },
  CUSTOMER_REVISION_REQUESTED: { label: 'Correction required', color: 'warning' },
  CUSTOMER_APPROVED: { label: 'Approved', color: 'success' },
  CUSTOMER_DECLINED: { label: 'Rejected', color: 'error' },
  CLOSED: { label: 'Closed', color: 'default' },
  CANCELLED: { label: 'Cancelled', color: 'default' },
  IN_PROGRESS: { label: 'Quotation in progress', color: 'primary' },
  QUOTE_RECEIVED: { label: 'Quote received', color: 'secondary' },
  ON_HOLD: { label: 'On hold', color: 'warning' },
  REVISION_REQUESTED: { label: 'Correction required', color: 'warning' },
  APPROVED: { label: 'Approved', color: 'success' },
  DECLINED: { label: 'Rejected', color: 'error' },
}

export const PRIORITIES: Record<Priority, string> = { LOW: 'Low', NORMAL: 'Normal', HIGH: 'High', URGENT: 'Urgent' }

export const VISIBILITY: Record<Visibility, string> = {
  INTERNAL: 'ERGO only',
  CUSTOMER: 'ERGO + customer',
  SUPPLIER: 'ERGO + suppliers',
  ALL: 'Customer and suppliers',
}

/** Date-only values (due dates, validity) are calendar days, not instants: they are read as local midnight. */
export function formatDate(value: string | null): string {
  return value ? new Date(value.length === 10 ? `${value}T00:00` : value).toLocaleDateString() : '—'
}

export function formatNumber(value: number | null, suffix = ''): string {
  return value == null ? '—' : `${value.toLocaleString()}${suffix}`
}

/** Money always shows its currency (CLAUDE.md non-negotiable). */
export function formatMoney(value: number | null, currency: string | undefined): string {
  return value == null ? '—' : `${currency ?? ''} ${value.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 4 })}`.trim()
}
