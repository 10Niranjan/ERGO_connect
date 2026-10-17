import { Box } from '@mui/material'
import { Facts } from '@/components/ui/Facts'
import { quoteSections } from '@/features/quotation/utils/labels'
import type { QuoteData } from '@/features/quotation/services/quoteApi'

/** A quote read-only: a submitted version, or the supplier's own quote once locked. */
export function QuoteSummary({ quote }: { quote: QuoteData }) {
  return (
    <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', md: '1fr 1fr' } }}>
      {quoteSections(quote).map((section) => (
        <Facts key={section.title} title={section.title} facts={section.facts} />
      ))}
    </Box>
  )
}
