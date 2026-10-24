import type { ChipColor } from '@/types'
import type { InspectionStatus, MaterialStatus, OrderStatus, PackagingStatus, ProductionStatus } from '../services/ofpApi'
import type { ProductionPoStatus } from '../services/productionPoApi'

export const ORDER_STATUS: Record<OrderStatus, { label: string; color: ChipColor }> = {
  ORDER_CREATED: { label: 'Order Created', color: 'default' },
  MATERIAL_ORDERED: { label: 'Material Ordered', color: 'info' },
  MATERIAL_RECEIVED: { label: 'Material Received', color: 'info' },
  PRODUCTION_STARTED: { label: 'Production Started', color: 'primary' },
  IN_INSPECTION: { label: 'In Inspection', color: 'primary' },
  PACKAGING_IN_PROGRESS: { label: 'Packaging in Progress', color: 'primary' },
  DISPATCH_READY: { label: 'Dispatch Ready', color: 'secondary' },
  DISPATCHED: { label: 'Dispatched', color: 'secondary' },
  IN_TRANSIT: { label: 'In Transit', color: 'secondary' },
  DELIVERED: { label: 'Delivered', color: 'success' },
  CLOSED: { label: 'Closed', color: 'default' },
  ON_HOLD: { label: 'On Hold', color: 'error' },
}

/** The fixed forward path an order actually walks (OrderStatus.SEQUENCE plus its 4b/4c continuation); ON_HOLD is a
 * side-transition and never a position in this sequence. */
export const ORDER_STATUS_ORDER: OrderStatus[] = [
  'ORDER_CREATED',
  'MATERIAL_ORDERED',
  'MATERIAL_RECEIVED',
  'PRODUCTION_STARTED',
  'IN_INSPECTION',
  'PACKAGING_IN_PROGRESS',
  'DISPATCH_READY',
  'DISPATCHED',
  'IN_TRANSIT',
  'DELIVERED',
  'CLOSED',
]

/** Position in the fixed sequence — "how far along", matching NPD's own stageProgress precedent. On hold has no
 * position of its own (the backend never exposes what status it interrupted). */
export function statusProgress(status: OrderStatus): { label: string; percent: number } {
  if (status === 'ON_HOLD') return { label: 'On hold', percent: 0 }
  const position = ORDER_STATUS_ORDER.indexOf(status) + 1
  return { label: `${position} of ${ORDER_STATUS_ORDER.length}`, percent: Math.round((position / ORDER_STATUS_ORDER.length) * 100) }
}

/** An order is overdue once its due date has passed and it has not yet reached a shipped-or-later status. */
const NOT_YET_SHIPPED: OrderStatus[] = [
  'ORDER_CREATED',
  'MATERIAL_ORDERED',
  'MATERIAL_RECEIVED',
  'PRODUCTION_STARTED',
  'IN_INSPECTION',
  'PACKAGING_IN_PROGRESS',
  'DISPATCH_READY',
  'ON_HOLD',
]

export function isDelayed(dueDate: string | null, status: OrderStatus): boolean {
  return dueDate != null && NOT_YET_SHIPPED.includes(status) && new Date(`${dueDate}T00:00`) < new Date(new Date().toDateString())
}

export const MATERIAL_STATUS: Record<MaterialStatus, { label: string; color: ChipColor }> = {
  PENDING: { label: 'Pending', color: 'default' },
  ORDERED: { label: 'Ordered', color: 'info' },
  RECEIVED: { label: 'Received', color: 'success' },
  DELAYED: { label: 'Delayed', color: 'error' },
}

export const INSPECTION_STATUS: Record<InspectionStatus, { label: string; color: ChipColor }> = {
  PENDING: { label: 'Pending', color: 'default' },
  PASSED: { label: 'Passed', color: 'success' },
  FAILED: { label: 'Failed', color: 'error' },
}

export const PRODUCTION_STATUS: Record<ProductionStatus, { label: string; color: ChipColor }> = {
  NOT_STARTED: { label: 'Not started', color: 'default' },
  IN_PROGRESS: { label: 'In progress', color: 'info' },
  DONE: { label: 'Done', color: 'success' },
  BLOCKED: { label: 'Blocked', color: 'error' },
}

export const PACKAGING_STATUS: Record<PackagingStatus, { label: string; color: ChipColor }> = {
  PENDING: { label: 'Pending', color: 'default' },
  IN_PROGRESS: { label: 'In progress', color: 'info' },
  PACKED: { label: 'Packed', color: 'success' },
}

export const PRODUCTION_PO_STATUS: Record<ProductionPoStatus, { label: string; color: ChipColor }> = {
  SUBMITTED: { label: 'Submitted to KAM', color: 'info' },
  RETURNED: { label: 'Returned for correction', color: 'warning' },
  ACCEPTED: { label: 'Accepted', color: 'success' },
}
