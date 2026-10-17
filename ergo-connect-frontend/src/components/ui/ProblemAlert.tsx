import { Alert, Box } from '@mui/material'
import { problemMessage } from '@/utils/problem'

const COMPLETE = 'Please complete: '

/** Splits on the commas between items, not the ones inside a "(raw material, processing, …)" note. */
const items = (list: string) => list.split(/,\s*(?![^()]*\))/).map((s) => s.trim().replace(/\.$/, ''))

/**
 * An RTK Query error as an alert. The backend's "Please complete: a, b, c" list of missing fields is shown as a
 * checklist instead of one long sentence.
 */
export function ProblemAlert({ error }: { error: unknown }) {
  const message = problemMessage(error)
  if (!message.startsWith(COMPLETE)) return <Alert severity="error">{message}</Alert>
  return (
    <Alert severity="error">
      Please complete the following:
      <Box component="ul" sx={{ m: 0, mt: 0.5, pl: 2.5 }}>
        {items(message.slice(COMPLETE.length)).map((item) => (
          <li key={item}>{item}</li>
        ))}
      </Box>
    </Alert>
  )
}
