import { Alert, Button, Chip, LinearProgress, MenuItem, Paper, Stack, Table, TableBody, TableCell, TableContainer, TableHead, TablePagination, TableRow, TextField, Typography } from '@mui/material'
import { useEffect, useState } from 'react'
import { Link as RouterLink, useNavigate } from 'react-router'
import { useAppSelector } from '../../../app/store'
import { DEVIATION_CREATE, useCan } from '@/features/permissions'
import { problemMessage } from '../../../shared/problem'
import { formatDate } from '../../rfq/labels'
import { useDeviationsQuery, type DeviationRow, type DeviationStatus } from '../deviationApi'
import { DEVIATION_STATUS, LINKED_MODULE } from '../labels'

// SRS §10.8/§12.10.4. DeviationService.list() returns one shape per caller's own scope - ERGO sees both companies,
// a supplier only its own rows, a customer only its own customer-visible rows (same split as NcrListPage).
const PAGE_SIZES = [25, 50, 100]

export function DeviationListPage() {
  const companyType = useAppSelector((state) => state.auth.session?.user.companyType)
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(PAGE_SIZES[0])
  const [status, setStatus] = useState('')
  const [search, setSearch] = useState('')
  const [q, setQ] = useState('') // what the server is asked, a moment after typing stops
  const { data, isLoading, error } = useDeviationsQuery({ page, size, status, q })
  const shown = data?.items ?? []
  const total = data?.total ?? 0
  const canCreate = useCan(DEVIATION_CREATE)
  const navigate = useNavigate()

  useEffect(() => {
    const timer = window.setTimeout(() => {
      setQ(search.trim())
      setPage(0)
    }, 300)
    return () => window.clearTimeout(timer)
  }, [search])

  // If deviations vanished from under us (a filter, another user), fall back to the last page that still exists.
  useEffect(() => {
    if (data && data.items.length === 0 && data.total > 0 && page > 0) setPage(Math.max(Math.ceil(data.total / size) - 1, 0))
  }, [data, page, size])

  return (
    <Stack spacing={2}>
      <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center' }}>
        <Typography variant="h5" component="h1">
          Deviation approvals
        </Typography>
        {canCreate && (
          <Button component={RouterLink} to="/quality/deviations/new" variant="contained">
            New deviation
          </Button>
        )}
      </Stack>
      <Stack direction="row" spacing={2} sx={{ alignItems: 'center', flexWrap: 'wrap' }} useFlexGap>
        <TextField size="small" label="Search" value={search} onChange={(e) => setSearch(e.target.value)} sx={{ minWidth: 260 }} />
        <TextField select size="small" label="Status" value={status} onChange={(e) => {
            setStatus(e.target.value)
            setPage(0)
          }} sx={{ minWidth: 240 }}>
          <MenuItem value="">All</MenuItem>
          {(Object.keys(DEVIATION_STATUS) as DeviationStatus[]).map((s) => (
            <MenuItem key={s} value={s}>
              {DEVIATION_STATUS[s].label}
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
              <TableCell>Deviation no.</TableCell>
              <TableCell>Part number</TableCell>
              <TableCell>Linked module</TableCell>
              {companyType === 'ERGO' && <TableCell>Customer</TableCell>}
              {companyType === 'ERGO' && <TableCell>Supplier</TableCell>}
              <TableCell>Customer approval</TableCell>
              <TableCell>Validity end</TableCell>
              <TableCell>Status</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {shown.map((r) => {
              const s = DEVIATION_STATUS[r.currentStatus]
              return (
                <TableRow key={r.id} hover sx={{ cursor: 'pointer' }} onClick={() => navigate(`/quality/deviations/${r.id}`)}>
                  <TableCell>{r.deviationNumber}</TableCell>
                  <TableCell>{r.partNumber}</TableCell>
                  <TableCell>{LINKED_MODULE[r.linkedModule]}</TableCell>
                  {companyType === 'ERGO' && <TableCell>{(r as DeviationRow).customer ?? '—'}</TableCell>}
                  {companyType === 'ERGO' && <TableCell>{(r as DeviationRow).supplier ?? '—'}</TableCell>}
                  <TableCell>{r.customerApprovalRequiredFlag ? 'Required' : 'Not required'}</TableCell>
                  <TableCell>{formatDate(r.validityEndDate)}</TableCell>
                  <TableCell>
                    <Chip size="small" label={s.label} color={s.color} />
                  </TableCell>
                </TableRow>
              )
            })}
            {!isLoading && shown.length === 0 && (
              <TableRow>
                <TableCell colSpan={8} sx={{ color: 'text.secondary' }}>
                  No deviations found.
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
