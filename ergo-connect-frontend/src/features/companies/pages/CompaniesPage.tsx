import {
  Alert,
  Button,
  Chip,
  LinearProgress,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material'
import { useState } from 'react'
import { useAppSelector } from '../../../app/store'
import { COMPANY_CREATORS } from '@/features/auth/utils/access'
import { problemMessage } from '../../../shared/problem'
import {
  STATUS_LABELS,
  TYPE_LABELS,
  useCompaniesQuery,
  useResubmitCompanyMutation,
  useSetCompanyActiveMutation,
  type Company,
  type CompanyStatus,
} from '../companiesApi'
import { CompanyDialog } from '../components/CompanyDialog'
import { ReviewDialog } from '../components/ReviewDialog'

const STATUS_COLORS: Record<CompanyStatus, 'default' | 'success' | 'warning' | 'error'> = {
  PENDING_APPROVAL: 'warning',
  ACTIVE: 'success',
  INACTIVE: 'default',
  REJECTED: 'error',
}

// SRS §10.2 / §10.3 for ERGO roles. Which rows appear and which buttons show are decided by the backend.
export function CompaniesPage() {
  const role = useAppSelector((state) => state.auth.session?.activeRole)
  const { data: companies = [], isLoading, error } = useCompaniesQuery()
  const [resubmit] = useResubmitCompanyMutation()
  const [setActive] = useSetCompanyActiveMutation()
  const [editing, setEditing] = useState<Company | 'new' | null>(null)
  const [reviewing, setReviewing] = useState<{ company: Company; decision: 'approve' | 'reject' } | null>(null)
  const [actionError, setActionError] = useState<unknown>(null)

  const pending = companies.filter((company) => company.status === 'PENDING_APPROVAL').length
  const canAdd = role !== undefined && COMPANY_CREATORS.includes(role)

  async function run(action: () => Promise<unknown>, question?: string) {
    // ponytail: native confirm(); switch to an MUI dialog if these need more than a yes/no
    if (question && !window.confirm(question)) return
    setActionError(null)
    await action().catch(setActionError)
  }

  return (
    <Stack spacing={2}>
      <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center' }}>
        <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
          <Typography variant="h5" component="h1">
            Customers & suppliers
          </Typography>
          {pending > 0 && <Chip size="small" color="warning" label={`${pending} pending approval`} />}
        </Stack>
        {canAdd && (
          <Button variant="contained" onClick={() => setEditing('new')}>
            New company
          </Button>
        )}
      </Stack>
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
            {companies.map((company) => (
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
                  {company.actions.includes('APPROVE') && (
                    <Button size="small" onClick={() => setReviewing({ company, decision: 'approve' })}>
                      Approve
                    </Button>
                  )}
                  {company.actions.includes('REJECT') && (
                    <Button size="small" color="error" onClick={() => setReviewing({ company, decision: 'reject' })}>
                      Reject
                    </Button>
                  )}
                  {company.actions.includes('EDIT') && (
                    <Button size="small" onClick={() => setEditing(company)}>
                      Edit
                    </Button>
                  )}
                  {company.actions.includes('RESUBMIT') && (
                    <Button size="small" onClick={() => void run(() => resubmit(company.id).unwrap(), `Send ${company.profile.name} for approval again?`)}>
                      Resubmit
                    </Button>
                  )}
                  {company.actions.includes('DEACTIVATE') && (
                    <Button
                      size="small"
                      color="error"
                      onClick={() =>
                        void run(
                          () => setActive({ id: company.id, active: false }).unwrap(),
                          `Deactivate ${company.profile.name}? Its users will no longer be able to sign in.`,
                        )
                      }
                    >
                      Deactivate
                    </Button>
                  )}
                  {company.actions.includes('ACTIVATE') && (
                    <Button size="small" onClick={() => void run(() => setActive({ id: company.id, active: true }).unwrap())}>
                      Activate
                    </Button>
                  )}
                </TableCell>
              </TableRow>
            ))}
            {!isLoading && companies.length === 0 && (
              <TableRow>
                <TableCell colSpan={7} sx={{ color: 'text.secondary' }}>
                  {role === 'ERGO_KAM' ? 'No customer accounts are assigned to you yet.' : 'No companies yet.'}
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </TableContainer>
      {editing && (
        <CompanyDialog
          company={editing === 'new' ? null : editing}
          types={role === 'ERGO_KAM' ? ['CUSTOMER'] : ['CUSTOMER', 'SUPPLIER']}
          onClose={() => setEditing(null)}
        />
      )}
      {reviewing && <ReviewDialog {...reviewing} onClose={() => setReviewing(null)} />}
    </Stack>
  )
}
