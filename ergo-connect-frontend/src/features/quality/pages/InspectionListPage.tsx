import { Alert, Button, Chip, LinearProgress, MenuItem, Paper, Stack, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, TextField, Typography } from '@mui/material'
import { useState } from 'react'
import { Link as RouterLink, useNavigate } from 'react-router'
import { useAppSelector } from '../../../app/store'
import { QUALITY_EDITORS } from '../../../auth/access'
import { problemMessage } from '../../../shared/problem'
import { formatDate } from '../../rfq/labels'
import { LINKED_MODULE, RESULT_STATUS } from '../labels'
import { useInspectionsQuery, type InspectionRow, type LinkedModule, type ResultStatus } from '../qualityApi'

// SRS §10.8/§12.10. InspectionService.list() returns one shape per caller's own scope - ERGO sees both companies,
// a supplier only its own rows, a customer only its own customer-visible rows.
export function InspectionListPage() {
  const companyType = useAppSelector((state) => state.auth.session?.user.companyType)
  const { data: rows = [], isLoading, error } = useInspectionsQuery()
  const [status, setStatus] = useState('')
  const [linkedModule, setLinkedModule] = useState('')
  const [search, setSearch] = useState('')
  const canCreate = useAppSelector((state) => QUALITY_EDITORS.includes(state.auth.session?.activeRole ?? 'CUSTOMER_RFQ'))
  const navigate = useNavigate()

  const needle = search.trim().toLowerCase()
  const shown = rows.filter((r) => {
    if (status && r.resultStatus !== status) return false
    if (linkedModule && r.linkedModule !== linkedModule) return false
    if (!needle) return true
    const haystack = [r.partNumber, r.batchLotNo, 'customer' in r ? r.customer : '', 'supplier' in r ? r.supplier : '']
    return haystack.some((v) => (v ?? '').toLowerCase().includes(needle))
  })

  return (
    <Stack spacing={2}>
      <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center' }}>
        <Typography variant="h5" component="h1">
          Inspections
        </Typography>
        {canCreate && (
          <Button component={RouterLink} to="/quality/new" variant="contained">
            New inspection
          </Button>
        )}
      </Stack>
      <Stack direction="row" spacing={2} sx={{ alignItems: 'center', flexWrap: 'wrap' }} useFlexGap>
        <TextField size="small" label="Search" value={search} onChange={(e) => setSearch(e.target.value)} sx={{ minWidth: 260 }} />
        <TextField select size="small" label="Result status" value={status} onChange={(e) => setStatus(e.target.value)} sx={{ minWidth: 220 }}>
          <MenuItem value="">All</MenuItem>
          {(Object.keys(RESULT_STATUS) as ResultStatus[]).map((s) => (
            <MenuItem key={s} value={s}>
              {RESULT_STATUS[s].label}
            </MenuItem>
          ))}
        </TextField>
        <TextField select size="small" label="Linked module" value={linkedModule} onChange={(e) => setLinkedModule(e.target.value)} sx={{ minWidth: 200 }}>
          <MenuItem value="">All</MenuItem>
          {(Object.keys(LINKED_MODULE) as LinkedModule[]).map((m) => (
            <MenuItem key={m} value={m}>
              {LINKED_MODULE[m]}
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
              <TableCell>Part</TableCell>
              <TableCell>Batch / lot</TableCell>
              <TableCell>Linked module</TableCell>
              {companyType === 'ERGO' && <TableCell>Customer</TableCell>}
              {companyType === 'ERGO' && <TableCell>Supplier</TableCell>}
              <TableCell>Inspection date</TableCell>
              <TableCell>Result</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {shown.map((r) => {
              const s = RESULT_STATUS[r.resultStatus]
              return (
                <TableRow key={r.id} hover sx={{ cursor: 'pointer' }} onClick={() => navigate(`/quality/${r.id}`)}>
                  <TableCell>{r.partNumber}</TableCell>
                  <TableCell>{r.batchLotNo ?? '—'}</TableCell>
                  <TableCell>{LINKED_MODULE[r.linkedModule]}</TableCell>
                  {companyType === 'ERGO' && <TableCell>{(r as InspectionRow).customer ?? '—'}</TableCell>}
                  {companyType === 'ERGO' && <TableCell>{(r as InspectionRow).supplier ?? '—'}</TableCell>}
                  <TableCell>{formatDate(r.inspectionDate)}</TableCell>
                  <TableCell>
                    <Chip size="small" label={s.label} color={s.color} />
                  </TableCell>
                </TableRow>
              )
            })}
            {!isLoading && shown.length === 0 && (
              <TableRow>
                <TableCell colSpan={7} sx={{ color: 'text.secondary' }}>
                  No inspections yet.
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </TableContainer>
    </Stack>
  )
}
