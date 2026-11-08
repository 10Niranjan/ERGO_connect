import { alpha, Box, Card, CardActionArea, CardContent, Skeleton, Typography } from '@mui/material'
import { Children, type ElementType, type ReactNode } from 'react'
import { Link as RouterLink } from 'react-router'

/** One KPI count tile (SRS §12.5 Management/Functional dashboards). `flagged` turns the accent red while the count is above 0.
 * `to` makes the whole tile a link to the module's list; the list's own role and scope checks still apply. */
export function KpiTile({
  label,
  value,
  flagged,
  icon: Icon,
  to,
}: {
  label: string
  value: number
  flagged?: boolean
  icon?: ElementType
  to?: string
}) {
  const alert = Boolean(flagged) && value > 0
  const body = (
    <CardContent sx={{ display: 'flex', alignItems: 'center', gap: 2 }}>
      {Icon && (
        <Box
          sx={{
            width: 44,
            height: 44,
            flexShrink: 0,
            borderRadius: '50%',
            display: 'grid',
            placeItems: 'center',
            color: alert ? 'error.main' : 'primary.main',
            bgcolor: (t) => alpha(alert ? t.palette.error.main : t.palette.primary.main, 0.1),
          }}
        >
          <Icon fontSize="small" />
        </Box>
      )}
      <Box sx={{ minWidth: 0 }}>
        <Typography variant="h4" component="div" sx={{ lineHeight: 1.1, color: alert ? 'error.main' : undefined }}>
          {value.toLocaleString()}
        </Typography>
        <Typography color="text.secondary" variant="body2">
          {label}
        </Typography>
      </Box>
    </CardContent>
  )
  return (
    <Card variant="outlined" sx={{ height: '100%' }}>
      {to ? (
        <CardActionArea component={RouterLink} to={to} sx={{ height: '100%' }}>
          {body}
        </CardActionArea>
      ) : (
        body
      )}
    </Card>
  )
}

/** Equal-width tiles: two across on a phone, up to four on a desktop, so the last row is never a pair of stretched cards. */
export function KpiGrid({ children }: { children: ReactNode }) {
  const columns = Math.min(Children.count(children), 4)
  return (
    <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: 'repeat(2, 1fr)', md: `repeat(${columns}, 1fr)` } }}>
      {children}
    </Box>
  )
}

/** Shown while the dashboard loads, in the shape of the tiles. */
export function KpiSkeleton({ count = 8 }: { count?: number }) {
  return (
    <KpiGrid>
      {Array.from({ length: count }, (_, i) => (
        <Skeleton key={i} variant="rounded" height={84} />
      ))}
    </KpiGrid>
  )
}
