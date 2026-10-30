import { Stack } from '@mui/material'
import type { SupplierDashboard } from '../dashboardApi'
import { NcrSection, NpdSection, OrderSection, QuoteSection } from './sections'

/** SRS §12.5: every Supplier role's own-company view. A supplier never sees the customer (nor another supplier): the
 * server sends customerName, supplierName and awaiting as null on every row, so those columns never appear here. */
export function SupplierDashboardView({ view }: { view: SupplierDashboard }) {
  return (
    <Stack spacing={3}>
      <QuoteSection items={view.quoteItems} total={view.quoteTotal} viewAll="/quotes" />
      <NpdSection items={view.npdItems} total={view.npdTotal} viewAll="/npd/mine" />
      <OrderSection items={view.orderItems} total={view.orderTotal} viewAll="/ofp" />
      <NcrSection items={view.ncrItems} total={view.ncrTotal} viewAll="/quality/ncrs" />
    </Stack>
  )
}
