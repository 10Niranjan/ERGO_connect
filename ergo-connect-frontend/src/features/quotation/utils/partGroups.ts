import type { PartRow, QuoteStatus } from '../services/quoteApi'

/** A part whose quote ERGO has selected (or the customer sent back) can be sent to the customer. */
export const READY: PartRow['status'][] = ['ACCEPTED', 'CUSTOMER_CORRECTION']

/** The order a part's overall status is read in when no quote is selected yet. */
const PROGRESS: QuoteStatus[] = ['SUBMITTED', 'RESUBMITTED', 'REVISION_REQUIRED', 'DRAFT_SAVED', 'NOT_STARTED']

export interface PartGroup {
  itemId: number
  part: PartRow['part']
  /** One row per supplier holding the part; empty when it has not been sent. */
  rows: PartRow[]
}

/** The server returns one row per (part, supplier); the screens want one group per part. */
export function groupByPart(parts: PartRow[]): PartGroup[] {
  const groups = new Map<number, PartGroup>()
  for (const row of parts) {
    const group = groups.get(row.part.itemId) ?? { itemId: row.part.itemId, part: row.part, rows: [] }
    if (row.quoteId !== null) group.rows.push(row)
    groups.set(row.part.itemId, group)
  }
  return [...groups.values()]
}

/** Where the part stands: the selected quote's status, else the furthest-along one. */
export function overallStatus(rows: PartRow[]): QuoteStatus | null {
  const selected = rows.find((r) => r.selected)
  if (selected) return selected.status
  return PROGRESS.find((s) => rows.some((r) => r.status === s)) ?? (rows.length > 0 ? 'CLOSED' : null)
}
