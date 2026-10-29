import {
  Alert,
  Button,
  Chip,
  LinearProgress,
  MenuItem,
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
import { useAppSelector } from '../../../app/store'
import { RFQ_CREATE, useCan } from '@/features/permissions'
import { problemMessage } from '../../../shared/problem'
import { CUSTOMER_STATUS_FILTER, ERGO_STATUS_FILTER, PRIORITIES, STATUS, formatDate } from '../labels'
import { useRfqsQuery } from '../rfqApi'

const PAGE_SIZES = [25, 50, 100]

// SRS §12.6.1. Rows are already limited to the caller's record scope by the backend, which also pages, filters and searches
// them (Phase 8o: the list used to ship every RFQ to the browser).
export function RfqListPage() {
  const session = useAppSelector((state) => state.auth.session)
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(PAGE_SIZES[0])
  const [status, setStatus] = useState('')
  const [search, setSearch] = useState('')
  const [q, setQ] = useState('') // what the server is asked, a moment after typing stops
  const { data, isLoading, isFetching, error } = useRfqsQuery({ page, size, status, q })
  const navigate = useNavigate()
  const isErgo = session?.user.companyType === 'ERGO'
  const canCreate = useCan(RFQ_CREATE)
  const rfqs = data?.items ?? []
  const total = data?.total ?? 0

  useEffect(() => {
    const timer = window.setTimeout(() => {
      setQ(search.trim())
      setPage(0)
    }, 300)
    return () => window.clearTimeout(timer)
  }, [search])

  // If RFQs vanished from under us (a filter, another user), fall back to the last page that still exists.
  useEffect(() => {
    if (data && data.items.length === 0 && data.total > 0 && page > 0) setPage(Math.max(Math.ceil(data.total / size) - 1, 0))
  }, [data, page, size])

  const statuses = isErgo ? ERGO_STATUS_FILTER : CUSTOMER_STATUS_FILTER
  const filtered = Boolean(status || q)

  return (
    <Stack spacing={2}>
      <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center' }}>
        <Typography variant="h5" component="h1">
          RFQs
        </Typography>
        {canCreate && (
          <Button variant="contained" onClick={() => navigate('/rfqs/new')}>
            New RFQ
          </Button>
        )}
      </Stack>
      <Stack direction="row" spacing={2}>
        <TextField
          size="small"
          label={isErgo ? 'Search RFQ no., part or customer' : 'Search RFQ no. or part'}
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          slotProps={{ htmlInput: { maxLength: 100 } }}
          sx={{ minWidth: 300 }}
        />
        <TextField
          select
          size="small"
          label="Status"
          value={status}
          onChange={(e) => {
            setStatus(e.target.value)
            setPage(0)
          }}
          sx={{ minWidth: 220 }}
        >
          <MenuItem value="">All</MenuItem>
          {statuses.map((s) => (
            <MenuItem key={s} value={s}>
              {STATUS[s].label}
            </MenuItem>
          ))}
        </TextField>
      </Stack>
      {error && <Alert severity="error">{problemMessage(error)}</Alert>}
      <TableContainer component={Paper} variant="outlined">
        {(isLoading || isFetching) && <LinearProgress />}
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>RFQ</TableCell>
              {isErgo && <TableCell>Customer</TableCell>}
              <TableCell>Parts</TableCell>
              <TableCell>Status</TableCell>
              <TableCell>Quote needed by</TableCell>
              <TableCell>Priority</TableCell>
              {isErgo && <TableCell>Owner</TableCell>}
              <TableCell>Created</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {rfqs.map((r) => (
              <TableRow key={r.id} hover sx={{ cursor: 'pointer' }} onClick={() => navigate(`/rfqs/${r.id}`)}>
                <TableCell>{r.number}</TableCell>
                {isErgo && <TableCell>{r.customer}</TableCell>}
                <TableCell sx={{ maxWidth: 320, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{r.parts.join(', ') || '—'}</TableCell>
                <TableCell>
                  <Chip size="small" label={STATUS[r.status].label} color={STATUS[r.status].color} />
                </TableCell>
                <TableCell>{formatDate(r.targetQuoteDate)}</TableCell>
                <TableCell>{PRIORITIES[r.priority]}</TableCell>
                {isErgo && <TableCell>{r.owner ?? '—'}</TableCell>}
                <TableCell>{formatDate(r.createdAt)}</TableCell>
              </TableRow>
            ))}
            {!isLoading && rfqs.length === 0 && (
              <TableRow>
                <TableCell colSpan={isErgo ? 8 : 6} sx={{ color: 'text.secondary' }}>
                  {filtered ? 'No RFQs match.' : 'No RFQs yet.'}
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
