import AltRouteIcon from '@mui/icons-material/AltRoute'
import AssignmentIcon from '@mui/icons-material/Assignment'
import EngineeringIcon from '@mui/icons-material/Engineering'
import HourglassTopIcon from '@mui/icons-material/HourglassTop'
import PendingActionsIcon from '@mui/icons-material/PendingActions'
import ReportProblemIcon from '@mui/icons-material/ReportProblem'
import RequestQuoteIcon from '@mui/icons-material/RequestQuote'
import TaskAltIcon from '@mui/icons-material/TaskAlt'
import { Alert, Box, Stack } from '@mui/material'
import type { ManagementDashboard } from '../dashboardApi'
import { KpiGrid, KpiTile } from './KpiTile'
import { NpdSection, OrderSection, PartStatusSection, SupplierPerformanceSection } from './sections'

/** The things only a person can unblock, in one line at the top. */
function AttentionStrip({ view }: { view: ManagementDashboard }) {
  const items = [
    [view.pendingApprovalsCount, 'pending approvals'],
    [view.quotesAwaitingCustomerApprovalCount, 'quotes awaiting customer approval'],
    [view.samplePosAwaitingCount, 'Sample POs awaiting KAM'],
    [view.openNcrCount, 'open NCRs'],
    [view.openDeviationCount, 'open deviations'],
  ].filter(([count]) => (count as number) > 0)
  if (items.length === 0) return <Alert severity="success">Nothing needs your attention right now.</Alert>
  return (
    <Alert severity="warning">
      Needs attention: {items.map(([count, label]) => `${(count as number).toLocaleString()} ${label}`).join(' · ')}
    </Alert>
  )
}

/** SRS §12.5: ERGO Admin/Management's cross-module view. */
export function ManagementDashboardView({ view }: { view: ManagementDashboard }) {
  return (
    <Stack spacing={3}>
      <AttentionStrip view={view} />
      <KpiGrid>
        <KpiTile label="Open RFQs" value={view.openRfqCount} icon={RequestQuoteIcon} to="/rfqs" />
        <KpiTile label="Quotes pending submission" value={view.quotesPendingSubmissionCount} icon={PendingActionsIcon} to="/rfqs" />
        <KpiTile label="Quotes awaiting customer approval" value={view.quotesAwaitingCustomerApprovalCount} icon={HourglassTopIcon} to="/rfqs" />
        <KpiTile label="Sample POs awaiting KAM" value={view.samplePosAwaitingCount} icon={AssignmentIcon} to="/npd/sample-pos" />
        <KpiTile label="Active NPD projects" value={view.activeNpdCount} icon={EngineeringIcon} to="/npd" />
        <KpiTile label="Open NCRs" value={view.openNcrCount} flagged icon={ReportProblemIcon} to="/quality/ncrs" />
        <KpiTile label="Open deviations" value={view.openDeviationCount} flagged icon={AltRouteIcon} to="/quality/deviations" />
        <KpiTile label="Pending approvals" value={view.pendingApprovalsCount} flagged icon={TaskAltIcon} />
      </KpiGrid>
      {/* Two panels across while they are empty; a section with rows takes the full width (see Section). */}
      <Box sx={{ display: 'grid', gap: 3, gridTemplateColumns: { xs: '1fr', md: 'repeat(2, minmax(0, 1fr))' } }}>
        <PartStatusSection items={view.partItems} total={view.partTotal} viewAll="/rfqs" showSupplier hideWhenEmpty />
        <NpdSection
          title="Delayed NPD projects"
          items={view.delayedNpdProjects}
          total={view.delayedNpdTotal}
          viewAll="/npd"
          showSupplier
          emptyMessage="Nothing is delayed. Every NPD project is on schedule."
        />
        <NpdSection
          title="High-risk NPD projects"
          items={view.highRiskNpdProjects}
          total={view.highRiskNpdTotal}
          viewAll="/npd"
          showSupplier
          emptyMessage="No NPD project is flagged high-risk."
        />
        <OrderSection
          title="Orders at risk"
          items={view.ordersAtRisk}
          total={view.ordersAtRiskTotal}
          viewAll="/ofp"
          showSupplier
          emptyMessage="No order is at risk of missing its dispatch date."
        />
        <SupplierPerformanceSection rows={view.supplierPerformance} />
      </Box>
    </Stack>
  )
}
