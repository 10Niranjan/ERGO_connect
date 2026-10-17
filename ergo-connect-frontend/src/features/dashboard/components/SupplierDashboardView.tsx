import { Stack } from '@mui/material'
import type { SupplierDashboard } from '@/features/dashboard/services/dashboardApi'
import { NcrSection, NpdSection, OrderSection, QuoteSection } from './sections'

/** SRS §12.5: every Supplier role's own-company view. Customer identity IS visible here (only the reverse
 * direction - supplier-to-supplier and supplier-to-customer - is restricted), so no showSupplier gymnastics needed;
 * the "supplier" column elsewhere would just be this company's own name. */
export function SupplierDashboardView({ view }: { view: SupplierDashboard }) {
  return (
    <Stack spacing={3}>
      <QuoteSection items={view.quoteItems} />
      <NpdSection items={view.npdItems} />
      <OrderSection items={view.orderItems} />
      <NcrSection items={view.ncrItems} />
    </Stack>
  )
}
