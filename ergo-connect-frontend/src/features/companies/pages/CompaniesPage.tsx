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
import { STATUS_COLORS, STATUS_LABELS, TYPE_LABELS, useCompaniesQuery, type Company } from '../companiesApi'
import { CompanyDialog } from '../components/CompanyDialog'
import { CompanyRowActions } from '../components/CompanyRowActions'

// A supplier is on the approved list only once ERGO Management / Admin has approved it (status ACTIVE).
const TABS = {
  all: { label: 'All', matches: () => true },
  customers: { label: 'Customers', matches: (c: Company) => c.type === 'CUSTOMER' },
  approvedSuppliers: { label: 'Approved suppliers', matches: (c: Company) => c.type === 'SUPPLIER' && c.status === 'ACTIVE' },
  pending: { label: 'Pending approval', matches: (c: Company) => c.status === 'PENDING_APPROVAL' },
  rejected: { label: 'Rejected', matches: (c: Company) => c.status === 'REJECTED' },
}
type TabKey = keyof typeof TABS

// SRS §10.2 / §10.3 for ERGO roles. Which rows appear and which buttons show are decided by the backend.
export function CompaniesPage() {
  const role = useAppSelector((state) => state.auth.session?.activeRole)
  const { data: companies = [], isLoading, error } = useCompaniesQuery()
  const [adding, setAdding] = useState(false)
  const [tab, setTab] = useState<TabKey>('all')
  const [actionError, setActionError] = useState<unknown>(null)

  const canAdd = role !== undefined && COMPANY_CREATORS.includes(role)
  const shown = companies.filter(TABS[tab].matches)

  return (
    <Stack spacing={2}>
      <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center' }}>
        <Typography variant="h5" component="h1">
          Customers & suppliers
        </Typography>
        {canAdd && (
          <Button variant="contained" onClick={() => setAdding(true)}>
            New company
          </Button>
        )}
      </Stack>
      <Tabs value={tab} onChange={(_event, value: TabKey) => setTab(value)} variant="scrollable" scrollButtons="auto">
        {(Object.keys(TABS) as TabKey[]).map((key) => {
          const count = companies.filter(TABS[key].matches).length
          return (
            <Tab
              key={key}
              value={key}
              label={
                <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                  <span>{TABS[key].label}</span>
                  {key === 'pending' && count > 0 ? <Chip size="small" color="warning" label={count} /> : null}
                </Stack>
              }
            />
          )
        })}
      </Tabs>
      {(error ?? actionError) != null && <Alert severity="error">{problemMessage(error ?? actionError)}</Alert>}
      <TableContainer component={Paper} variant="outlined">
        {isLoading && <LinearProgress />}
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>Code</TableCell>
              <TableCell>Name</TableCell>
              <TableCell>Type</TableCell>
              <TableCell>City</TableCell>
              <TableCell>Primary contact</TableCell>
              <TableCell>Status</TableCell>
              <TableCell align="right">Actions</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {shown.map((company) => (
              <TableRow key={company.id} hover>
                <TableCell>{company.code}</TableCell>
                <TableCell>{company.profile.name}</TableCell>
                <TableCell>{TYPE_LABELS[company.type]}</TableCell>
                <TableCell>{company.profile.city ?? '—'}</TableCell>
                <TableCell>{company.profile.primaryContactName ?? '—'}</TableCell>
                <TableCell>
                  <Chip size="small" color={STATUS_COLORS[company.status]} label={STATUS_LABELS[company.status]} />
                </TableCell>
                <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                  <CompanyRowActions company={company} onError={setActionError} />
                </TableCell>
              </TableRow>
            ))}
            {!isLoading && shown.length === 0 && (
              <TableRow>
                <TableCell colSpan={7} sx={{ color: 'text.secondary' }}>
                  {role === 'ERGO_KAM' ? 'No customer accounts are assigned to you yet.' : 'No companies here.'}
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </TableContainer>
      {adding && (
        <CompanyDialog company={null} types={role === 'ERGO_KAM' ? ['CUSTOMER'] : ['CUSTOMER', 'SUPPLIER']} onClose={() => setAdding(false)} />
      )}
    </Stack>
  )
}
