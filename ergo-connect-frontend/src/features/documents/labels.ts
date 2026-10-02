import type { ChipColor } from '../rfq/labels'
import type { ApprovalStatus, DocumentLinkedModule, LockStatus } from './documentsApi'

export const LINKED_MODULE_LABEL: Record<DocumentLinkedModule, string> = {
  rfq: 'RFQ',
  quotation: 'Quotation',
  OFP_SHIPMENT: 'OFP (proof of delivery)',
  QUALITY_CAPA: 'Quality (CAPA evidence)',
  comment: 'Comment attachment',
}

export const APPROVAL_STATUS: Record<ApprovalStatus, { label: string; color: ChipColor }> = {
  PENDING: { label: 'Pending approval', color: 'default' },
  APPROVED: { label: 'Approved', color: 'success' },
  REJECTED: { label: 'Rejected', color: 'error' },
}

export const LOCK_STATUS: Record<LockStatus, string> = { UNLOCKED: 'Unlocked', LOCKED: 'Locked' }

export function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

/** Where the owning record lives in the UI. Proof-of-delivery and CAPA-evidence files are keyed by shipment/CAPA
 * action ids, which have no page of their own, so those two show the record without a link. */
export function linkedRecordPath(module: DocumentLinkedModule, id: number): string | null {
  if (module === 'rfq') return `/rfqs/${id}`
  if (module === 'quotation') return `/quotes/${id}`
  return null
}

export function linkedRecordLabel(module: DocumentLinkedModule, id: number): string {
  return `${LINKED_MODULE_LABEL[module]} #${id}`
}
