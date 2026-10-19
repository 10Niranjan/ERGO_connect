import { Card, CardContent, Typography } from '@mui/material'

/** One KPI count tile (SRS §12.5 Management/Functional dashboards). */
export function KpiTile({ label, value, flagged }: { label: string; value: number; flagged?: boolean }) {
  return (
    <Card variant="outlined" sx={{ minWidth: 170, flex: '1 1 170px' }}>
      <CardContent>
        <Typography variant="h4" component="div" sx={flagged && value > 0 ? { color: 'error.main' } : undefined}>
          {value}
        </Typography>
        <Typography color="text.secondary" variant="body2">
          {label}
        </Typography>
      </CardContent>
    </Card>
  )
}
