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
  TableRow,
  TextField,
  Typography,
} from '@mui/material'
import { useState } from 'react'
import { useNavigate } from 'react-router'
import { useAppSelector } from '../../../app/store'
import { RFQ_CREATE, useCan } from '@/features/permissions'
import { problemMessage } from '../../../shared/problem'
import { PRIORITIES, STATUS, formatDate } from '../labels'
import { useRfqsQuery } from '../rfqApi'

// SRS §12.6.1. Rows are already limited to the caller's record scope by the backend.
// ponytail: filtering and search in the browser; move to the server when lists reach thousands of RFQs.
export function RfqListPage() {
  const session = useAppSelector((state) => state.auth.session)
  const { data: rfqs = [], isLoading, error } = useRfqsQuery()
  const [status, setStatus] = useState('')
  const [search, setSearch] = useState('')
  const navigate = useNavigate()
  const isErgo = session?.user.companyType === 'ERGO'
  const canCreate = useCan(RFQ_CREATE)

  const needle = search.trim().toLowerCase()
  const shown = rfqs.filter(
    (r) =>
      (!status || r.status === status) &&
      (!needle || [r.number, r.partNumber, r.description, r.customer].some((v) => v?.toLowerCase().includes(needle))),
  )
  const statuses = [...new Set(rfqs.map((r) => r.status))]

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
        <TextField size="small" label="Search" value={search} onChange={(e) => setSearch(e.target.value)} sx={{ minWidth: 260 }} />
        <TextField select size="small" label="Status" value={status} onChange={(e) => setStatus(e.target.value)} sx={{ minWidth: 220 }}>
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
        {isLoading && <LinearProgress />}
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>RFQ</TableCell>
              {isErgo && <TableCell>Customer</TableCell>}
              <TableCell>Part</TableCell>
              <TableCell>Description</TableCell>
              <TableCell>Status</TableCell>
              <TableCell>Quote needed by</TableCell>
              <TableCell>Priority</TableCell>
              {isErgo && <TableCell>Owner</TableCell>}
              <TableCell>Created</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {shown.map((r) => (
              <TableRow key={r.id} hover sx={{ cursor: 'pointer' }} onClick={() => navigate(`/rfqs/${r.id}`)}>
                <TableCell>{r.number}</TableCell>
                {isErgo && <TableCell>{r.customer}</TableCell>}
                <TableCell>
                  {r.partNumber}
                  {r.revision ? ` rev ${r.revision}` : ''}
                </TableCell>
                <TableCell sx={{ maxWidth: 280, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{r.description ?? '—'}</TableCell>
                <TableCell>
                  <Chip size="small" label={STATUS[r.status].label} color={STATUS[r.status].color} />
                </TableCell>
                <TableCell>{formatDate(r.targetQuoteDate)}</TableCell>
                <TableCell>{PRIORITIES[r.priority]}</TableCell>
                {isErgo && <TableCell>{r.owner ?? '—'}</TableCell>}
                <TableCell>{formatDate(r.createdAt)}</TableCell>
              </TableRow>
            ))}
            {!isLoading && shown.length === 0 && (
              <TableRow>
                <TableCell colSpan={isErgo ? 9 : 7} sx={{ color: 'text.secondary' }}>
                  No RFQs yet.
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </TableContainer>
    </Stack>
  )
}
