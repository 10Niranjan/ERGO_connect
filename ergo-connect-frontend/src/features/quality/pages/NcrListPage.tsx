import { Alert, Button, Chip, LinearProgress, MenuItem, Paper, Stack, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, TextField, Typography } from '@mui/material'
import { useState } from 'react'
import { Link as RouterLink, useNavigate } from 'react-router'
import { useAppSelector } from '../../../app/store'
import { QUALITY_CREATE, useCan } from '@/features/permissions'
import { problemMessage } from '../../../shared/problem'
import { formatDate } from '../../rfq/labels'
import { LINKED_MODULE, NCR_STATUS } from '../labels'
import { useNcrsQuery, type LinkedModule, type NcrRow, type NcrStatus } from '../qualityApi'

// SRS §10.8/§12.10. NcrService.list() returns one shape per caller's own scope - ERGO sees both companies, a
// supplier only its own rows, a customer only its own customer-visible rows (same split as InspectionListPage).
export function NcrListPage() {
  const companyType = useAppSelector((state) => state.auth.session?.user.companyType)
  const { data: rows = [], isLoading, error } = useNcrsQuery()
  const [status, setStatus] = useState('')
  const [linkedModule, setLinkedModule] = useState('')
  const [search, setSearch] = useState('')
  const canCreate = useCan(QUALITY_CREATE)
  const navigate = useNavigate()

  const needle = search.trim().toLowerCase()
  const shown = rows.filter((r) => {
    if (status && r.currentStatus !== status) return false
    if (linkedModule && r.linkedModule !== linkedModule) return false
    if (!needle) return true
    const haystack = [r.ncrNumber, r.issueCategory, 'customer' in r ? r.customer : '', 'supplier' in r ? r.supplier : '']
    return haystack.some((v) => (v ?? '').toLowerCase().includes(needle))
  })

  return (
    <Stack spacing={2}>
      <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center' }}>
        <Typography variant="h5" component="h1">
          NCR / CAPA
        </Typography>
        {canCreate && (
          <Button component={RouterLink} to="/quality/ncrs/new" variant="contained">
            New NCR
          </Button>
        )}
      </Stack>
      <Stack direction="row" spacing={2} sx={{ alignItems: 'center', flexWrap: 'wrap' }} useFlexGap>
        <TextField size="small" label="Search" value={search} onChange={(e) => setSearch(e.target.value)} sx={{ minWidth: 260 }} />
        <TextField select size="small" label="Status" value={status} onChange={(e) => setStatus(e.target.value)} sx={{ minWidth: 240 }}>
          <MenuItem value="">All</MenuItem>
          {(Object.keys(NCR_STATUS) as NcrStatus[]).map((s) => (
            <MenuItem key={s} value={s}>
              {NCR_STATUS[s].label}
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
              <TableCell>NCR no.</TableCell>
              <TableCell>Issue category</TableCell>
              <TableCell>Linked module</TableCell>
              {companyType === 'ERGO' && <TableCell>Customer</TableCell>}
              {companyType === 'ERGO' && <TableCell>Supplier</TableCell>}
              <TableCell>Target closure</TableCell>
              <TableCell>Status</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {shown.map((r) => {
              const s = NCR_STATUS[r.currentStatus]
              return (
                <TableRow key={r.id} hover sx={{ cursor: 'pointer' }} onClick={() => navigate(`/quality/ncrs/${r.id}`)}>
                  <TableCell>{r.ncrNumber}</TableCell>
                  <TableCell>{r.issueCategory}</TableCell>
                  <TableCell>{LINKED_MODULE[r.linkedModule]}</TableCell>
                  {companyType === 'ERGO' && <TableCell>{(r as NcrRow).customer ?? '—'}</TableCell>}
                  {companyType === 'ERGO' && <TableCell>{(r as NcrRow).supplier ?? '—'}</TableCell>}
                  <TableCell>{formatDate(r.targetClosureDate)}</TableCell>
                  <TableCell>
                    <Chip size="small" label={s.label} color={s.color} />
                  </TableCell>
                </TableRow>
              )
            })}
            {!isLoading && shown.length === 0 && (
              <TableRow>
                <TableCell colSpan={7} sx={{ color: 'text.secondary' }}>
                  No NCRs yet.
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </TableContainer>
    </Stack>
  )
}
