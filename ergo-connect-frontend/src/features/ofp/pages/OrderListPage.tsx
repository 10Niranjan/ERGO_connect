import {
  Button,
  Checkbox,
  Chip,
  FormControlLabel,
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
import { Link as RouterLink, useNavigate } from 'react-router'
import { useAppSelector } from '../../../app/store'
import { OFP_EDITORS } from '@/features/auth/utils/access'
import { formatDate, formatNumber } from '@/features/rfq/utils/labels'
import { ORDER_STATUS, isDelayed, statusProgress } from '@/features/ofp/utils/labels'
import { useOfpOrdersQuery, type OrderRow, type OrderStatus } from '@/features/ofp/services/ofpApi'
import { ProblemAlert } from '@/components/ui/ProblemAlert'

// SRS §12.9.1. OrderService.list() returns one shape per caller's own company type — ERGO sees both companies and
// the owner, a supplier only its own PO (never the customer), a customer only its own PO (never the supplier).
export function OrderListPage() {
  const companyType = useAppSelector((state) => state.auth.session?.user.companyType)
  const { data: rows = [], isLoading, error } = useOfpOrdersQuery()
  const [status, setStatus] = useState('')
  const [search, setSearch] = useState('')
  const [delayedOnly, setDelayedOnly] = useState(false)
  const canCreate = useAppSelector((state) => OFP_EDITORS.includes(state.auth.session?.activeRole ?? 'CUSTOMER_RFQ'))
  const navigate = useNavigate()

  const needle = search.trim().toLowerCase()
  const shown = rows.filter((r) => {
    if (status && r.currentStatus !== status) return false
    if (delayedOnly && !isDelayed(r.dueDate, r.currentStatus)) return false
    if (!needle) return true
    const haystack = [
      r.orderNumber,
      r.partNumber,
      'customer' in r ? r.customer : '',
      'supplier' in r ? r.supplier : '',
      'customerPoNo' in r ? r.customerPoNo : '',
      'supplierPoNo' in r ? r.supplierPoNo : '',
    ]
    return haystack.some((v) => (v ?? '').toLowerCase().includes(needle))
  })

  return (
    <Stack spacing={2}>
      <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center' }}>
        <Typography variant="h5" component="h1">
          Orders
        </Typography>
        {canCreate && (
          <Button component={RouterLink} to="/ofp/new" variant="contained">
            New order
          </Button>
        )}
      </Stack>
      <Stack direction="row" spacing={2} sx={{ alignItems: 'center', flexWrap: 'wrap' }} useFlexGap>
        <TextField size="small" label="Search" value={search} onChange={(e) => setSearch(e.target.value)} sx={{ minWidth: 260 }} />
        <TextField select size="small" label="Status" value={status} onChange={(e) => setStatus(e.target.value)} sx={{ minWidth: 220 }}>
          <MenuItem value="">All</MenuItem>
          {(Object.keys(ORDER_STATUS) as OrderStatus[]).map((s) => (
            <MenuItem key={s} value={s}>
              {ORDER_STATUS[s].label}
            </MenuItem>
          ))}
        </TextField>
        <FormControlLabel control={<Checkbox checked={delayedOnly} onChange={(e) => setDelayedOnly(e.target.checked)} />} label="Delayed only" />
      </Stack>
      {error && <ProblemAlert error={error} />}
      <TableContainer component={Paper} variant="outlined">
        {isLoading && <LinearProgress />}
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>Order</TableCell>
              {companyType === 'ERGO' && <TableCell>Customer</TableCell>}
              {companyType === 'ERGO' && <TableCell>Supplier</TableCell>}
              {companyType === 'CUSTOMER' && <TableCell>Customer PO</TableCell>}
              {companyType === 'SUPPLIER' && <TableCell>Supplier PO</TableCell>}
              <TableCell>Part</TableCell>
              <TableCell align="right">Quantity</TableCell>
              <TableCell>Due date</TableCell>
              <TableCell>Status</TableCell>
              {companyType === 'ERGO' && <TableCell>Owner</TableCell>}
            </TableRow>
          </TableHead>
          <TableBody>
            {shown.map((r) => {
              const status = ORDER_STATUS[r.currentStatus]
              const progress = statusProgress(r.currentStatus)
              const delayed = isDelayed(r.dueDate, r.currentStatus)
              return (
                <TableRow key={r.id} hover sx={{ cursor: 'pointer' }} onClick={() => navigate(`/ofp/${r.id}`)}>
                  <TableCell>{r.orderNumber}</TableCell>
                  {companyType === 'ERGO' && <TableCell>{(r as OrderRow).customer}</TableCell>}
                  {companyType === 'ERGO' && <TableCell>{(r as OrderRow).supplier}</TableCell>}
                  {'customerPoNo' in r && <TableCell>{r.customerPoNo ?? '—'}</TableCell>}
                  {'supplierPoNo' in r && <TableCell>{r.supplierPoNo ?? '—'}</TableCell>}
                  <TableCell>
                    {r.partNumber}
                    {r.revision ? ` rev ${r.revision}` : ''}
                  </TableCell>
                  <TableCell align="right">{formatNumber(r.orderedQuantity)}</TableCell>
                  <TableCell sx={delayed ? { color: 'error.main', fontWeight: 600 } : undefined}>{formatDate(r.dueDate)}</TableCell>
                  <TableCell>
                    <Stack spacing={0.5} sx={{ alignItems: 'flex-start' }}>
                      <Chip size="small" label={status.label} color={status.color} />
                      <Typography variant="caption" color="text.secondary">
                        {progress.label}
                      </Typography>
                    </Stack>
                  </TableCell>
                  {companyType === 'ERGO' && <TableCell>{(r as OrderRow).owner ?? '—'}</TableCell>}
                </TableRow>
              )
            })}
            {!isLoading && shown.length === 0 && (
              <TableRow>
                <TableCell colSpan={8} sx={{ color: 'text.secondary' }}>
                  No orders yet.
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </TableContainer>
    </Stack>
  )
}
