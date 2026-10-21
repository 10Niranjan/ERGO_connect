import {
  Alert,
  Button,
  Chip,
  LinearProgress,
  Paper,
  Stack,
  Tab,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Tabs,
  Typography,
} from '@mui/material'
import { useState } from 'react'
import { useAppSelector } from '../../../app/store'
import { COMPANY_CREATORS } from '@/features/auth/utils/access'
import { problemMessage } from '../../../shared/problem'
import { STATUS_COLORS, STATUS_LABELS, useCompaniesQuery, type CompanyStatus } from '../companiesApi'
import { CompanyDialog } from '../components/CompanyDialog'
import { CompanyRowActions } from '../components/CompanyRowActions'

// The approved supplier list is the ACTIVE tab: a supplier appears there only after it has been approved. Every other
// tab is the approval queue and its history. Rows and buttons come from the backend, already limited to the caller.
const TABS: { status: CompanyStatus; label: string; empty: string }[] = [
  { status: 'ACTIVE', label: 'Approved suppliers', empty: 'No approved suppliers yet.' },
  { status: 'PENDING_APPROVAL', label: 'Pending approval', empty: 'No suppliers are waiting for approval.' },
  { status: 'REJECTED', label: 'Rejected', empty: 'No rejected suppliers.' },
  { status: 'INACTIVE', label: 'Inactive', empty: 'No inactive suppliers.' },
]

export function SuppliersPage() {
  const role = useAppSelector((state) => state.auth.session?.activeRole)
  const [status, setStatus] = useState<CompanyStatus>('ACTIVE')
  const { data: suppliers = [], isLoading, error } = useCompaniesQuery({ type: 'SUPPLIER', status })
  const { data: pending = [] } = useCompaniesQuery({ type: 'SUPPLIER', status: 'PENDING_APPROVAL' })
  const [adding, setAdding] = useState(false)
  const [actionError, setActionError] = useState<unknown>(null)

  const canAdd = role !== undefined && role !== 'ERGO_KAM' && COMPANY_CREATORS.includes(role)
  const tab = TABS.find((t) => t.status === status) ?? TABS[0]

  return (
    <Stack spacing={2}>
      <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center' }}>
        <Typography variant="h5" component="h1">
          Suppliers
        </Typography>
        {canAdd && (
          <Button variant="contained" onClick={() => setAdding(true)}>
            New supplier
          </Button>
        )}
      </Stack>
      <Tabs value={status} onChange={(_event, value: CompanyStatus) => setStatus(value)} variant="scrollable" scrollButtons="auto">
        {TABS.map((t) => (
          <Tab
            key={t.status}
            value={t.status}
            label={
              <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                <span>{t.label}</span>
                {t.status === 'PENDING_APPROVAL' && pending.length > 0 ? <Chip size="small" color="warning" label={pending.length} /> : null}
              </Stack>
            }
          />
        ))}
      </Tabs>
      {(error ?? actionError) != null && <Alert severity="error">{problemMessage(error ?? actionError)}</Alert>}
      <TableContainer component={Paper} variant="outlined">
        {isLoading && <LinearProgress />}
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>Code</TableCell>
              <TableCell>Name</TableCell>
              <TableCell>City</TableCell>
              <TableCell>Primary contact</TableCell>
              <TableCell>Status</TableCell>
              <TableCell align="right">Actions</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {suppliers.map((supplier) => (
              <TableRow key={supplier.id} hover>
                <TableCell>{supplier.code}</TableCell>
                <TableCell>{supplier.profile.name}</TableCell>
                <TableCell>{supplier.profile.city ?? '—'}</TableCell>
                <TableCell>{supplier.profile.primaryContactName ?? '—'}</TableCell>
                <TableCell>
                  <Chip size="small" color={STATUS_COLORS[supplier.status]} label={STATUS_LABELS[supplier.status]} />
                </TableCell>
                <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                  <CompanyRowActions company={supplier} onError={setActionError} />
                </TableCell>
              </TableRow>
            ))}
            {!isLoading && suppliers.length === 0 && (
              <TableRow>
                <TableCell colSpan={6} sx={{ color: 'text.secondary' }}>
                  {tab.empty}
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </TableContainer>
      {adding && <CompanyDialog company={null} types={['SUPPLIER']} onClose={() => setAdding(false)} />}
    </Stack>
  )
}
