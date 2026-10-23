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
      <RfqSection items={view.rfqItems} hideWhenEmpty />
      <PartStatusSection items={view.partItems} showSupplier hideWhenEmpty />
      <NpdSection items={view.npdItems} showSupplier hideWhenEmpty />
      <OrderSection items={view.orderItems} showSupplier hideWhenEmpty />
      <NcrSection items={view.ncrItems} showSupplier hideWhenEmpty />
      <DeviationSection items={view.deviationItems} showSupplier hideWhenEmpty />
    </Stack>
  )
}
