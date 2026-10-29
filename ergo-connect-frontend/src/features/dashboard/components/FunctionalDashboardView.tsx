import { Stack } from '@mui/material'
import type { FunctionalDashboard } from '../dashboardApi'
import { KpiTile } from './KpiTile'
import { DeviationSection, NcrSection, NpdSection, OrderSection, PartStatusSection, RfqSection } from './sections'

/** SRS §12.5: ERGO KAM/RFQ/NPD/OFP/Quality's own-module view. KAM gets every list populated across its assigned
 * accounts; the single-module roles only populate their own module's list - hideWhenEmpty drops the rest. */
export function FunctionalDashboardView({ view }: { view: FunctionalDashboard }) {
  return (
    <Stack spacing={3}>
      <Stack direction="row" spacing={2} useFlexGap sx={{ flexWrap: 'wrap' }}>
        <KpiTile label="Overdue" value={view.overdueCount} flagged />
        <KpiTile label="Awaiting me" value={view.awaitingMeCount} flagged />
        <KpiTile label="Awaiting external party" value={view.awaitingExternalCount} />
      </Stack>
      <RfqSection items={view.rfqItems} total={view.rfqTotal} viewAll="/rfqs" hideWhenEmpty />
      <PartStatusSection items={view.partItems} total={view.partTotal} viewAll="/rfqs" showSupplier hideWhenEmpty />
      <NpdSection items={view.npdItems} total={view.npdTotal} viewAll="/npd" showSupplier hideWhenEmpty />
      <OrderSection items={view.orderItems} total={view.orderTotal} viewAll="/ofp" showSupplier hideWhenEmpty />
      <NcrSection items={view.ncrItems} total={view.ncrTotal} viewAll="/quality/ncrs" showSupplier hideWhenEmpty />
      <DeviationSection items={view.deviationItems} total={view.deviationTotal} viewAll="/quality/deviations" showSupplier hideWhenEmpty />
    </Stack>
  )
}
