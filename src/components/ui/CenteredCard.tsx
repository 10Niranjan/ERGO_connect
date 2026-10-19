import { Box, Card, CardContent, Typography } from '@mui/material'
import type { ReactNode } from 'react'

/** Layout for the screens outside the app shell: sign-in, role selection, password change. */
export function CenteredCard({ title, subtitle, children }: { title: string; subtitle?: string; children: ReactNode }) {
  return (
    <Box sx={{ display: 'grid', placeItems: 'center', minHeight: '100vh', p: 2 }}>
      <Card sx={{ width: '100%', maxWidth: 420 }}>
        <CardContent sx={{ display: 'grid', gap: 2, p: 4 }}>
          <Typography variant="overline" color="primary">
            ERGO Connect
          </Typography>
          <Typography variant="h5" component="h1">
            {title}
          </Typography>
          {subtitle && <Typography color="text.secondary">{subtitle}</Typography>}
          {children}
        </CardContent>
      </Card>
    </Box>
  )
}
