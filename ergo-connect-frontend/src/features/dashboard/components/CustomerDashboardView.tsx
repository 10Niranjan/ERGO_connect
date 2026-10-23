import { Stack } from '@mui/material'
import type { CustomerDashboard } from '../dashboardApi'
import { NcrSection, NpdSection, OrderSection, PartStatusSection, RfqSection } from './sections'

/** SRS §12.5: every Customer role's own-company view. No supplier identity is ever shown here (the backend has
 * already scrubbed it from supplierName) and no awaitingMe/awaitingExternal tiles - those are functional-only. */
export function CustomerDashboardView({ view }: { view: CustomerDashboard }) {
  return (
    <Stack spacing={3}>
      <RfqSection items={view.rfqItems} />
      <PartStatusSection items={view.partItems} />
      <NpdSection items={view.npdItems} />
      <OrderSection items={view.orderItems} />
      <NcrSection items={view.ncrItems} />
    </Stack>
  )
}
