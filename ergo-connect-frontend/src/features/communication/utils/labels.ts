import type { ChipColor } from '@/types'
import type { CommentLinkedModule, CommentType } from '../services/communicationApi'

export const MODULE_LABEL: Record<CommentLinkedModule, string> = {
  rfq: 'RFQ',
  quotation: 'Supplier quote',
  npd: 'NPD project',
  ofp: 'Order',
  inspection: 'Inspection',
  ncr: 'NCR',
  deviation: 'Deviation',
}

/** What ERGO sees on a comment's audience tag (it must be unmistakable who can read what). */
export const TYPE_LABEL: Record<CommentType, { label: string; color: ChipColor }> = {
  INTERNAL: { label: 'Internal - ERGO only', color: 'warning' },
  EXTERNAL_CUSTOMER: { label: 'Shared with customer', color: 'info' },
  EXTERNAL_SUPPLIER: { label: 'Shared with supplier', color: 'secondary' },
}

/** An external viewer only ever sees its own side of a conversation, so the tag just says who else reads it. */
export const EXTERNAL_TYPE_LABEL = 'Shared with ERGO'

/** Which audiences each record kind supports (CommentService.Target): an RFQ is shared by several suppliers so it has
 * a customer side only; a supplier's quote has a supplier side only. Convenience only, the backend enforces it. */
export const SUPPORTED_TYPES: Record<CommentLinkedModule, CommentType[]> = {
  rfq: ['INTERNAL', 'EXTERNAL_CUSTOMER'],
  quotation: ['INTERNAL', 'EXTERNAL_SUPPLIER'],
  npd: ['INTERNAL', 'EXTERNAL_CUSTOMER', 'EXTERNAL_SUPPLIER'],
  ofp: ['INTERNAL', 'EXTERNAL_CUSTOMER', 'EXTERNAL_SUPPLIER'],
  inspection: ['INTERNAL', 'EXTERNAL_CUSTOMER', 'EXTERNAL_SUPPLIER'],
  ncr: ['INTERNAL', 'EXTERNAL_CUSTOMER', 'EXTERNAL_SUPPLIER'],
  deviation: ['INTERNAL', 'EXTERNAL_CUSTOMER', 'EXTERNAL_SUPPLIER'],
}

/** Where the commented record lives in the UI (the same routes the rest of the app uses). */
export function recordPath(module: CommentLinkedModule, id: number): string {
  switch (module) {
    case 'rfq':
      return `/rfqs/${id}`
    case 'quotation':
      return `/quotes/${id}`
    case 'npd':
      return `/npd/${id}`
    case 'ofp':
      return `/ofp/${id}`
    case 'inspection':
      return `/quality/${id}`
    case 'ncr':
      return `/quality/ncrs/${id}`
    case 'deviation':
      return `/quality/deviations/${id}`
  }
}

export const MAX_COMMENT_LENGTH = 4000
export const MAX_ATTACHMENTS = 5
export const MAX_MENTIONS = 20

export { formatDateTime } from '@/utils/date'
