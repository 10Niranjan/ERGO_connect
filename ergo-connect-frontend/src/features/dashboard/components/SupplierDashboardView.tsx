import { Stack } from '@mui/material'
import type { SupplierDashboard } from '../dashboardApi'
import { NcrSection, NpdSection, OrderSection, QuoteSection } from './sections'

/** SRS §12.5: every Supplier role's own-company view. Customer identity IS visible here (only the reverse
 * direction - supplier-to-supplier and supplier-to-customer - is restricted), so no showSupplier gymnastics needed;
 * the "supplier" column elsewhere would just be this company's own name. */
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
