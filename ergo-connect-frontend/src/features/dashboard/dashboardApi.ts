import { api } from '../../app/store'

/** SRS §12.5/§10.11: who the other party still has to act. */
export type Awaiting = 'ERGO' | 'CUSTOMER' | 'SUPPLIER' | 'NONE'

export interface RfqDashboardItem {
  id: number
  number: string
  customerName: string
  status: string
  awaiting: Awaiting
  targetQuoteDate: string
  overdue: boolean
  priority: string
}

export interface NpdDashboardItem {
  id: number
  npdNumber: string
  customerName: string
  supplierName: string | null
  stage: string
  awaiting: Awaiting
  targetSampleDate: string
  delayDays: number | null
  riskLevel: string | null
}

export interface OrderDashboardItem {
  id: number
  orderNumber: string
  customerName: string
  supplierName: string | null
  status: string
  awaiting: Awaiting
  committedDispatchDate: string
  atRisk: boolean
}

export interface NcrDashboardItem {
  id: number
  ncrNumber: string
  customerName: string
  supplierName: string | null
  status: string
  awaiting: Awaiting
  targetClosureDate: string
  overdue: boolean
}

export interface DeviationDashboardItem {
  id: number
  deviationNumber: string
  customerName: string
  supplierName: string | null
  status: string
  validityEndDate: string
  awaiting: Awaiting
}

export interface SupplierQuoteItem {
  invitationId: number
  rfqNumber: string
  partNumber: string
  status: string
  responseDueDate: string
  overdue: boolean
}

export interface SupplierPerformanceRow {
  supplierCompanyId: number
  supplierName: string
  openOrderCount: number
  openNcrCount: number
  dispatchedOrderCount: number
  onTimeDispatchedCount: number
}

export interface ManagementDashboard {
  audience: 'MANAGEMENT'
  openRfqCount: number
  quotesPendingSubmissionCount: number
  quotesAwaitingCustomerApprovalCount: number
  approvedForConversionCount: number
  activeNpdCount: number
  delayedNpdProjects: NpdDashboardItem[]
  highRiskNpdProjects: NpdDashboardItem[]
  ordersAtRisk: OrderDashboardItem[]
  openNcrCount: number
  openDeviationCount: number
  supplierPerformance: SupplierPerformanceRow[]
  pendingApprovalsCount: number
}

export interface FunctionalDashboard {
  audience: 'FUNCTIONAL'
  rfqItems: RfqDashboardItem[]
  npdItems: NpdDashboardItem[]
  orderItems: OrderDashboardItem[]
  ncrItems: NcrDashboardItem[]
  deviationItems: DeviationDashboardItem[]
  overdueCount: number
  awaitingMeCount: number
  awaitingExternalCount: number
}

export interface CustomerDashboard {
  audience: 'CUSTOMER'
  rfqItems: RfqDashboardItem[]
  npdItems: NpdDashboardItem[]
  orderItems: OrderDashboardItem[]
  ncrItems: NcrDashboardItem[]
}

export interface SupplierDashboard {
  audience: 'SUPPLIER'
  quoteItems: SupplierQuoteItem[]
  npdItems: NpdDashboardItem[]
  orderItems: OrderDashboardItem[]
  ncrItems: NcrDashboardItem[]
}

export type DashboardView = ManagementDashboard | FunctionalDashboard | CustomerDashboard | SupplierDashboard

const dashboardApi = api.injectEndpoints({
  endpoints: (build) => ({
    // SRS §10.11: every role has View only; the backend picks the shape from the caller's active role.
    dashboard: build.query<DashboardView, void>({ query: () => '/dashboard' }),
  }),
})

export const { useDashboardQuery } = dashboardApi
