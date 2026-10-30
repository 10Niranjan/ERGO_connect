import { api } from '@/services/baseApi'

/** SRS §12.5/§10.11: who the other party still has to act. */
export type Awaiting = 'ERGO' | 'CUSTOMER' | 'SUPPLIER' | 'NONE'

export interface RfqDashboardItem {
  id: number
  number: string
  customerName: string | null // ERGO only: null for customers and suppliers
  status: string
  awaiting: Awaiting | null // ERGO-internal: null for customers and suppliers
  targetQuoteDate: string | null
  overdue: boolean
  priority: string
  /** The RFQ's part numbers. */
  parts: string[]
}

export interface NpdDashboardItem {
  id: number
  npdNumber: string
  customerName: string | null // ERGO only: null for customers and suppliers
  supplierName: string | null
  stage: string
  awaiting: Awaiting | null // ERGO-internal: null for customers and suppliers
  targetSampleDate: string | null
  delayDays: number | null
  riskLevel: string | null
}

export interface OrderDashboardItem {
  id: number
  orderNumber: string
  customerName: string | null // ERGO only: null for customers and suppliers
  supplierName: string | null
  status: string
  awaiting: Awaiting | null // ERGO-internal: null for customers and suppliers
  committedDispatchDate: string | null
  atRisk: boolean
}

export interface NcrDashboardItem {
  id: number
  ncrNumber: string
  customerName: string | null // ERGO only: null for customers and suppliers
  supplierName: string | null
  status: string
  awaiting: Awaiting | null // ERGO-internal: null for customers and suppliers
  targetClosureDate: string | null
  overdue: boolean
}

export interface DeviationDashboardItem {
  id: number
  deviationNumber: string
  customerName: string | null // ERGO only: null for customers and suppliers
  supplierName: string | null
  status: string
  validityEndDate: string | null
  awaiting: Awaiting | null // ERGO-internal: null for customers and suppliers
}

export interface SupplierQuoteItem {
  invitationId: number
  rfqNumber: string
  partNumber: string
  status: string
  responseDueDate: string | null
  overdue: boolean
}

/** Where one part of an RFQ stands (flow doc §4): ERGO the full quote status and the supplier, a customer the collapsed status. */
export interface PartStatusItem {
  rfqId: number
  rfqNumber: string
  rfqItemId: number
  partNumber: string
  status: string
  supplierName: string | null
}

export interface SupplierPerformanceRow {
  supplierCompanyId: number
  supplierName: string
  openOrderCount: number
  openNcrCount: number
  dispatchedOrderCount: number
  onTimeDispatchedCount: number
}

/**
 * Every list below is capped to the rows that need attention first; its sibling `...Total` is the true size, so a screen can
 * say "25 of 2,513" and link to the module's own list (Phase 8o). KPI counts are never capped.
 */
export interface ManagementDashboard {
  audience: 'MANAGEMENT'
  openRfqCount: number
  quotesPendingSubmissionCount: number
  quotesAwaitingCustomerApprovalCount: number
  samplePosAwaitingCount: number
  activeNpdCount: number
  delayedNpdProjects: NpdDashboardItem[]
  delayedNpdTotal: number
  highRiskNpdProjects: NpdDashboardItem[]
  highRiskNpdTotal: number
  ordersAtRisk: OrderDashboardItem[]
  ordersAtRiskTotal: number
  openNcrCount: number
  openDeviationCount: number
  supplierPerformance: SupplierPerformanceRow[]
  pendingApprovalsCount: number
  partItems: PartStatusItem[]
  partTotal: number
}

export interface FunctionalDashboard {
  audience: 'FUNCTIONAL'
  rfqItems: RfqDashboardItem[]
  rfqTotal: number
  npdItems: NpdDashboardItem[]
  npdTotal: number
  orderItems: OrderDashboardItem[]
  orderTotal: number
  ncrItems: NcrDashboardItem[]
  ncrTotal: number
  deviationItems: DeviationDashboardItem[]
  deviationTotal: number
  overdueCount: number
  awaitingMeCount: number
  awaitingExternalCount: number
  partItems: PartStatusItem[]
  partTotal: number
}

export interface CustomerDashboard {
  audience: 'CUSTOMER'
  rfqItems: RfqDashboardItem[]
  rfqTotal: number
  npdItems: NpdDashboardItem[]
  npdTotal: number
  orderItems: OrderDashboardItem[]
  orderTotal: number
  ncrItems: NcrDashboardItem[]
  ncrTotal: number
  partItems: PartStatusItem[]
  partTotal: number
}

export interface SupplierDashboard {
  audience: 'SUPPLIER'
  quoteItems: SupplierQuoteItem[]
  quoteTotal: number
  npdItems: NpdDashboardItem[]
  npdTotal: number
  orderItems: OrderDashboardItem[]
  orderTotal: number
  ncrItems: NcrDashboardItem[]
  ncrTotal: number
}

export type DashboardView = ManagementDashboard | FunctionalDashboard | CustomerDashboard | SupplierDashboard

const dashboardApi = api.injectEndpoints({
  endpoints: (build) => ({
    // SRS §10.11: every role has View only; the backend picks the shape from the caller's active role.
    dashboard: build.query<DashboardView, void>({ query: () => '/dashboard' }),
  }),
})

export const { useDashboardQuery } = dashboardApi
