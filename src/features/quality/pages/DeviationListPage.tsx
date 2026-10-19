import { Button, Chip, LinearProgress, MenuItem, Paper, Stack, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, TextField, Typography } from '@mui/material'
import { useState } from 'react'
import { Link as RouterLink, useNavigate } from 'react-router'
import { useAppSelector } from '../../../app/store'
import { DEVIATION_EDITORS } from '@/features/auth/utils/access'
import { formatDate } from '@/features/rfq/utils/labels'
import { useDeviationsQuery, type DeviationRow, type DeviationStatus } from '@/features/quality/services/deviationApi'
import { DEVIATION_STATUS, LINKED_MODULE } from '@/features/quality/utils/labels'
import { ProblemAlert } from '@/components/ui/ProblemAlert'

// SRS §10.8/§12.10.4. DeviationService.list() returns one shape per caller's own scope - ERGO sees both companies,
// a supplier only its own rows, a customer only its own customer-visible rows (same split as NcrListPage).
export function DeviationListPage() {
  const companyType = useAppSelector((state) => state.auth.session?.user.companyType)
  const { data: rows = [], isLoading, error } = useDeviationsQuery()
  const [status, setStatus] = useState('')
  const [search, setSearch] = useState('')
  const canCreate = useAppSelector((state) => DEVIATION_EDITORS.includes(state.auth.session?.activeRole ?? 'CUSTOMER_RFQ'))
  const navigate = useNavigate()

  const needle = search.trim().toLowerCase()
  const shown = rows.filter((r) => {
    if (status && r.currentStatus !== status) return false
    if (!needle) return true
    const haystack = [r.deviationNumber, r.partNumber, 'customer' in r ? r.customer : '', 'supplier' in r ? r.supplier : '']
    return haystack.some((v) => (v ?? '').toLowerCase().includes(needle))
  })

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
        <TextField select size="small" label="Status" value={status} onChange={(e) => setStatus(e.target.value)} sx={{ minWidth: 240 }}>
          <MenuItem value="">All</MenuItem>
          {(Object.keys(DEVIATION_STATUS) as DeviationStatus[]).map((s) => (
            <MenuItem key={s} value={s}>
              {DEVIATION_STATUS[s].label}
            </MenuItem>
          ))}
        </TextField>
      </Stack>
      {error && <ProblemAlert error={error} />}
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
                  No deviations yet.
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </TableContainer>
    </Stack>
  )
}
