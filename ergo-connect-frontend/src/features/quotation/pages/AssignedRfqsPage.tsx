import {
  Alert,
  Chip,
  LinearProgress,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TablePagination,
  TableRow,
  TextField,
  Typography,
} from '@mui/material'
import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router'
import { problemMessage } from '../../../shared/problem'
import { formatDate } from '../../rfq/labels'
import { QUOTE_STATUS } from '../labels'
import { useMyQuotesQuery, type QuoteStatus } from '../quoteApi'

const PAGE_SIZES = [25, 50, 100]
const OPEN: QuoteStatus[] = ['NOT_STARTED', 'DRAFT_SAVED', 'REVISION_REQUIRED']

/** SRS §12.15: the supplier's "Assigned RFQs". The backend returns only this supplier's invitations. */
export function AssignedRfqsPage() {
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(PAGE_SIZES[0])
  const [search, setSearch] = useState('')
  const [q, setQ] = useState('') // what the server is asked, a moment after typing stops
  const { data, isLoading, error } = useMyQuotesQuery({ page, size, q })
  const shown = data?.items ?? []
  const total = data?.total ?? 0
  const navigate = useNavigate()
  const today = new Date().toLocaleDateString('en-CA')

  useEffect(() => {
    const timer = window.setTimeout(() => {
      setQ(search.trim())
      setPage(0)
    }, 300)
    return () => window.clearTimeout(timer)
  }, [search])

  // If parts vanished from under us (a recall, a search), fall back to the last page that still exists.
  useEffect(() => {
    if (data && data.items.length === 0 && data.total > 0 && page > 0) setPage(Math.max(Math.ceil(data.total / size) - 1, 0))
  }, [data, page, size])

  return (
    <Stack spacing={2}>
      <Typography variant="h5" component="h1">
        Assigned RFQs
      </Typography>
      <TextField size="small" label="Search" value={search} onChange={(e) => setSearch(e.target.value)} sx={{ maxWidth: 260 }} />
      {error && <Alert severity="error">{problemMessage(error)}</Alert>}
      <TableContainer component={Paper} variant="outlined">
        {isLoading && <LinearProgress />}
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>RFQ</TableCell>
              <TableCell>Sub-RFQ</TableCell>
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
              const st = r.status
              const closed = st !== undefined && !r.quotingOpen && OPEN.includes(st)
              const overdue = st !== undefined && !closed && OPEN.includes(st) && r.responseDueDate < today
              const status = st === undefined ? null : closed ? { label: 'RFQ closed', color: 'default' as const } : QUOTE_STATUS[st]
              return (
                <TableRow key={r.id} hover sx={{ cursor: 'pointer' }} onClick={() => navigate(`/quotes/${r.id}`)}>
                  <TableCell>{r.rfqNumber}</TableCell>
                  <TableCell>{r.subRfqNo}</TableCell>
                  <TableCell>
                    {r.partNumber}
                    {r.revision ? ` rev ${r.revision}` : ''}
                  </TableCell>
                  <TableCell sx={{ maxWidth: 280, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{r.description ?? '—'}</TableCell>
                  <TableCell>
                    {status ? <Chip size="small" label={status.label} color={status.color} /> : '—'}
                  </TableCell>
                  <TableCell sx={overdue ? { color: 'error.main' } : undefined}>
                    {formatDate(r.responseDueDate)}
                    {overdue && ' (overdue)'}
                  </TableCell>
                  <TableCell>{r.version || '—'}</TableCell>
                  <TableCell>{formatDate(r.submittedAt ?? null)}</TableCell>
                </TableRow>
              )
            })}
            {!isLoading && shown.length === 0 && (
              <TableRow>
                <TableCell colSpan={8} sx={{ color: 'text.secondary' }}>
                  No RFQs assigned to your company yet.
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
        <TablePagination
          component="div"
          count={total}
          page={page}
          rowsPerPage={size}
          rowsPerPageOptions={PAGE_SIZES}
          onPageChange={(_e, p) => setPage(p)}
          onRowsPerPageChange={(e) => {
            setSize(Number(e.target.value))
            setPage(0)
          }}
        />
      </TableContainer>
    </Stack>
  )
}
