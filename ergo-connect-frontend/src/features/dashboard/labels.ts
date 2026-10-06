import type { ChipColor } from '../rfq/labels'
import type { Awaiting } from './dashboardApi'

export const AWAITING: Record<Awaiting, { label: string; color: ChipColor }> = {
  ERGO: { label: 'Awaiting ERGO', color: 'primary' },
  CUSTOMER: { label: 'Awaiting Customer', color: 'secondary' },
  SUPPLIER: { label: 'Awaiting Supplier', color: 'info' },
  NONE: { label: '—', color: 'default' },
}

/** Dashboard items carry plain status/stage strings spanning five different modules' own enums - humanize rather
 * than importing and casting into every module's own label map for what's just a read-only summary row. */
export function humanize(value: string): string {
  return value
    .split('_')
    .map((w) => w.charAt(0) + w.slice(1).toLowerCase())
    .join(' ')
}
