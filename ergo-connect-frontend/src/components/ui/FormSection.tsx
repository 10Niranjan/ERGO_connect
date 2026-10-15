import { Box, Card, CardContent, Typography } from '@mui/material'
import type { ReactNode } from 'react'

/** A titled card laying its fields out in three columns (one on phones). */
export function FormSection({ title, note, children }: { title: string; note?: string; children: ReactNode }) {
  return (
    <Card variant="outlined">
      <CardContent sx={{ display: 'grid', gap: 1.5 }}>
        <Typography variant="h6" component="h2">
          {title}
        </Typography>
        {note && (
          <Typography variant="body2" color="text.secondary">
            {note}
          </Typography>
        )}
        <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', md: 'repeat(3, 1fr)' } }}>{children}</Box>
      </CardContent>
    </Card>
  )
}
