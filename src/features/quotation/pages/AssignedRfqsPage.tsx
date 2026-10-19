import {
  Chip,
  LinearProgress,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TextField,
  Typography,
} from '@mui/material'
import { useState } from 'react'
import { useNavigate } from 'react-router'
import { formatDate } from '@/features/rfq/utils/labels'
import { QUOTE_STATUS } from '@/features/quotation/utils/labels'
import { useMyQuotesQuery, type QuoteStatus } from '@/features/quotation/services/quoteApi'
import { ProblemAlert } from '@/components/ui/ProblemAlert'

const OPEN: QuoteStatus[] = ['NOT_STARTED', 'DRAFT_SAVED', 'CLARIFICATION_REQUESTED', 'REOPENED']

/** SRS §12.15: the supplier's "Assigned RFQs". The backend returns only this supplier's invitations. */
export function AssignedRfqsPage() {
  const { data: rows = [], isLoading, error } = useMyQuotesQuery()
  const [search, setSearch] = useState('')
  const navigate = useNavigate()
  const today = new Date().toLocaleDateString('en-CA')

  const needle = search.trim().toLowerCase()
  const shown = rows.filter((r) => !needle || [r.rfqNumber, r.partNumber, r.description].some((v) => v?.toLowerCase().includes(needle)))

  return (
    <Stack spacing={2}>
      <Typography variant="h5" component="h1">
        Assigned RFQs
      </Typography>
      <TextField size="small" label="Search" value={search} onChange={(e) => setSearch(e.target.value)} sx={{ maxWidth: 260 }} />
      {error && <ProblemAlert error={error} />}
      <TableContainer component={Paper} variant="outlined">
        {isLoading && <LinearProgress />}
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>RFQ</TableCell>
              <TableCell>Part</TableCell>
              <TableCell>Description</TableCell>
              <TableCell>Your quote</TableCell>
              <TableCell>Quote due</TableCell>
              <TableCell>Version</TableCell>
              <TableCell>Last submitted</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {shown.map((r) => {
              const closed = !r.quotingOpen && OPEN.includes(r.status)
              const overdue = !closed && OPEN.includes(r.status) && r.responseDueDate < today
              const status = closed ? { label: 'RFQ closed', color: 'default' as const } : QUOTE_STATUS[r.status]
              return (
                <TableRow key={r.id} hover sx={{ cursor: 'pointer' }} onClick={() => navigate(`/quotes/${r.id}`)}>
                  <TableCell>{r.rfqNumber}</TableCell>
                  <TableCell>
                    {r.partNumber}
                    {r.revision ? ` rev ${r.revision}` : ''}
                  </TableCell>
                  <TableCell sx={{ maxWidth: 280, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{r.description ?? '—'}</TableCell>
                  <TableCell>
                    <Chip size="small" label={status.label} color={status.color} />
                  </TableCell>
                  <TableCell sx={overdue ? { color: 'error.main' } : undefined}>
                    {formatDate(r.responseDueDate)}
                    {overdue && ' (overdue)'}
                  </TableCell>
                  <TableCell>{r.version || '—'}</TableCell>
                  <TableCell>{formatDate(r.submittedAt)}</TableCell>
                </TableRow>
              )
            })}
            {!isLoading && shown.length === 0 && (
              <TableRow>
                <TableCell colSpan={7} sx={{ color: 'text.secondary' }}>
                  No RFQs assigned to your company yet.
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </TableContainer>
    </Stack>
  )
}
