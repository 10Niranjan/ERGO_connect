import { Box, Card, CardContent, Typography } from '@mui/material'
import type { ReactNode } from 'react'

/** A titled card of label/value rows. A single row without a label shows just the text. */
export function Facts({ title, note, facts }: { title: string; note?: string; facts: [string, ReactNode][] }) {
  return (
    <Card variant="outlined">
      <CardContent>
        <Typography variant="h6" component="h2">
          {title}
        </Typography>
        {note && (
          <Typography variant="body2" color="text.secondary" gutterBottom>
            {note}
          </Typography>
        )}
        <Box sx={{ display: 'grid', gridTemplateColumns: facts.length === 1 && !facts[0][0] ? '1fr' : '170px 1fr', rowGap: 1, columnGap: 2, mt: 1 }}>
          {facts.map(([label, value], index) => (
            <Box key={label || index} sx={{ display: 'contents' }}>
              {label && <Typography color="text.secondary">{label}</Typography>}
              <Typography sx={{ whiteSpace: 'pre-wrap' }}>{value}</Typography>
            </Box>
          ))}
        </Box>
      </CardContent>
    </Card>
  )
}
