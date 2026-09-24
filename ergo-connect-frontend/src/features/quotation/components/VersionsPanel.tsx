import {
  Accordion,
  AccordionDetails,
  AccordionSummary,
  Alert,
  LinearProgress,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material'
import { problemMessage } from '../../../shared/problem'
import { quoteChanges } from '../labels'
import { useQuoteVersionsQuery } from '../quoteApi'
import { QuoteSummary } from './QuoteSummary'

const when = (value: string | null) => (value ? new Date(value).toLocaleString() : '—')
const fileList = (files: string[]) => files.join('\n') || '—'

/** SRS §12.7.3: every submitted version as it was sent, who re-opened it and why, and what changed. */
export function VersionsPanel({ quoteId }: { quoteId: number }) {
  const { data: versions = [], isLoading, error } = useQuoteVersionsQuery(quoteId)
  if (isLoading) return <LinearProgress />
  if (error) return <Alert severity="error">{problemMessage(error)}</Alert>
  if (versions.length === 0) return <Typography color="text.secondary">Nothing has been submitted yet.</Typography>

  return (
    <Stack spacing={1}>
      {[...versions].reverse().map((v, index) => {
        const previous = versions.find((p) => p.version === v.version - 1)
        const changes = previous ? quoteChanges(previous.quote, v.quote) : []
        if (previous && fileList(previous.files) !== fileList(v.files)) {
          changes.push({ field: 'Files', from: fileList(previous.files), to: fileList(v.files) })
        }
        return (
          <Accordion key={v.version} defaultExpanded={index === 0} variant="outlined" disableGutters>
            <AccordionSummary expandIcon={<span aria-hidden>▾</span>}>
              <Typography>
                <strong>Version {v.version}</strong> · submitted {when(v.submittedAt)} by {v.submittedBy ?? '—'}
                {previous && ` · ${changes.length} change${changes.length === 1 ? '' : 's'}`}
              </Typography>
            </AccordionSummary>
            <AccordionDetails sx={{ display: 'grid', gap: 2 }}>
              {v.reopenReason && (
                <Alert severity="info">
                  Re-opened by {v.reopenedBy ?? 'ERGO'} on {when(v.reopenedAt)}: {v.reopenReason}
                </Alert>
              )}
              {previous && changes.length > 0 && (
                <TableContainer component={Paper} variant="outlined">
                  <Table size="small" aria-label={`Changes in version ${v.version}`}>
                    <TableHead>
                      <TableRow>
                        <TableCell>Changed</TableCell>
                        <TableCell>Version {previous.version}</TableCell>
                        <TableCell>Version {v.version}</TableCell>
                      </TableRow>
                    </TableHead>
                    <TableBody>
                      {changes.map((c) => (
                        <TableRow key={c.field}>
                          <TableCell>{c.field}</TableCell>
                          <TableCell sx={{ whiteSpace: 'pre-wrap', color: 'text.secondary' }}>{c.from}</TableCell>
                          <TableCell sx={{ whiteSpace: 'pre-wrap' }}>{c.to}</TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </TableContainer>
              )}
              {previous && changes.length === 0 && <Typography color="text.secondary">Same content as version {previous.version}.</Typography>}
              <Typography>
                <strong>Files submitted:</strong> {v.files.join(', ') || 'none'}
              </Typography>
              <QuoteSummary quote={v.quote} />
            </AccordionDetails>
          </Accordion>
        )
      })}
    </Stack>
  )
}
