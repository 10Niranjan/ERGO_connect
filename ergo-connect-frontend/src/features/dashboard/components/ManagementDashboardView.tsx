import { Stack } from '@mui/material'
import type { ManagementDashboard } from '../dashboardApi'
import { KpiTile } from './KpiTile'
import { NpdSection, OrderSection, PartStatusSection, SupplierPerformanceSection } from './sections'

/** SRS §12.5: ERGO Admin/Management's cross-module view. */
export function ManagementDashboardView({ view }: { view: ManagementDashboard }) {
  return (
    <Stack spacing={3}>
      <Stack direction="row" spacing={2} useFlexGap sx={{ flexWrap: 'wrap' }}>
        <KpiTile label="Open RFQs" value={view.openRfqCount} />
        <KpiTile label="Quotes pending submission" value={view.quotesPendingSubmissionCount} />
        <KpiTile label="Quotes awaiting customer approval" value={view.quotesAwaitingCustomerApprovalCount} />
        <KpiTile label="Sample POs awaiting KAM" value={view.samplePosAwaitingCount} />
        <KpiTile label="Active NPD projects" value={view.activeNpdCount} />
        <KpiTile label="Open NCRs" value={view.openNcrCount} flagged />
        <KpiTile label="Open deviations" value={view.openDeviationCount} flagged />
        <KpiTile label="Pending approvals" value={view.pendingApprovalsCount} flagged />
      </Stack>
      <PartStatusSection items={view.partItems} total={view.partTotal} viewAll="/rfqs" showSupplier hideWhenEmpty />
      <NpdSection title="Delayed NPD projects" items={view.delayedNpdProjects} total={view.delayedNpdTotal} viewAll="/npd" showSupplier />
      <NpdSection title="High-risk NPD projects" items={view.highRiskNpdProjects} total={view.highRiskNpdTotal} viewAll="/npd" showSupplier />
      <OrderSection title="Orders at risk" items={view.ordersAtRisk} total={view.ordersAtRiskTotal} viewAll="/ofp" showSupplier />
      <SupplierPerformanceSection rows={view.supplierPerformance} />
    </Stack>
  )
}
