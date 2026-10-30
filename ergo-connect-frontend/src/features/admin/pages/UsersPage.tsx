import { Alert, Button, MenuItem, Paper, Stack, Tab, Tabs, TextField, Typography } from '@mui/material'
import { useMemo, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router'
import { problemMessage } from '../../../shared/problem'
import { STATUS_LABELS, useCompaniesQuery } from '../../companies/companiesApi'
import type { CompanyType } from '../../auth/store/authSlice'
import {
  useResetUserPasswordMutation,
  useRolesQuery,
  useSetUserActiveMutation,
  useUsersQuery,
  type User,
} from '../adminApi'
import { DeleteUserDialog } from '../components/DeleteUserDialog'
import { LoginAsDialog } from '../components/LoginAsDialog'
import { TemporaryPasswordDialog } from '../components/TemporaryPasswordDialog'
import { UserDialog } from '../components/UserDialog'
import { UserTable } from '../components/UserTable'
import { useConfirm } from '@/components/ui/useConfirm'

const TABS: { type: CompanyType; label: string; param: string }[] = [
  { type: 'ERGO', label: 'ERGO', param: 'ergo' },
  { type: 'CUSTOMER', label: 'Customers', param: 'customer' },
  { type: 'SUPPLIER', label: 'Suppliers', param: 'supplier' },
]

const byName = (a: User, b: User) =>
  `${a.firstName} ${a.lastName}`.localeCompare(`${b.firstName} ${b.lastName}`, undefined, { sensitivity: 'base' })

// SRS 12.13.1 User Administration. ERGO Admin only (route guard here, enforced by the backend).
// Three tabs by company type; customer and supplier tabs list the companies, each with its own employees.
export function UsersPage() {
  const { data: users = [], isLoading, error } = useUsersQuery()
  const { data: companies = [] } = useCompaniesQuery()
  const { data: roles = [] } = useRolesQuery()
  const [setUserActive] = useSetUserActiveMutation()
  const [resetUserPassword] = useResetUserPasswordMutation()
  const [params, setParams] = useSearchParams()
  const [selectedId, setSelectedId] = useState<number | null>(null)
  // 'new' creates anywhere; { companyId } creates inside that company; a User edits
  const [editing, setEditing] = useState<User | 'new' | { companyId: number } | null>(null)
  const [deleting, setDeleting] = useState<User | null>(null)
  const [viewing, setViewing] = useState<User | null>(null)
  const navigate = useNavigate()
  const [issued, setIssued] = useState<{ email: string; password: string } | null>(null)
  const [actionError, setActionError] = useState<unknown>(null)
  const [confirm, confirmDialog] = useConfirm()

  const tab = TABS.find((t) => t.param === params.get('tab')) ?? TABS[0]
  const roleName = (code: string) => roles.find((role) => role.code === code)?.name ?? code

  const typeUsers = useMemo(() => users.filter((u) => u.companyType === tab.type).sort(byName), [users, tab.type])

  // Companies of this tab, sorted by name, each with its employee count.
  const tabCompanies = useMemo(() => {
    return companies
      .filter((c) => c.type === tab.type)
      .map((c) => ({ company: c, count: users.filter((u) => u.companyId === c.id).length }))
      .sort((a, b) => a.company.profile.name.localeCompare(b.company.profile.name, undefined, { sensitivity: 'base' }))
  }, [companies, users, tab.type])

  const selected =
    tabCompanies.find((entry) => entry.company.id === selectedId) ?? (tabCompanies.length ? tabCompanies[0] : undefined)
  const employees = selected ? typeUsers.filter((u) => u.companyId === selected.company.id) : []

  function changeTab(param: string) {
    setParams({ tab: param })
    setSelectedId(null)
  }

  async function toggleActive(user: User) {
    if (!(await confirm(`${user.active ? 'Deactivate' : 'Activate'} ${user.firstName} ${user.lastName}?`, user.active ? 'Deactivate' : 'Activate'))) return
    setActionError(null)
    await setUserActive({ id: user.id, active: !user.active }).unwrap().catch(setActionError)
  }

  async function resetPassword(user: User) {
    if (!(await confirm(`Reset the password of ${user.email}? They will be signed out everywhere.`, 'Reset password'))) return
    setActionError(null)
    try {
      const { temporaryPassword } = await resetUserPassword(user.id).unwrap()
      setIssued({ email: user.email, password: temporaryPassword })
    } catch (e) {
      setActionError(e)
    }
  }

  const actions = {
    roleName,
    onEdit: (user: User) => setEditing(user),
    onResetPassword: (user: User) => void resetPassword(user),
    onToggleActive: (user: User) => void toggleActive(user),
    onDelete: (user: User) => setDeleting(user),
    onLoginAs: (user: User) => setViewing(user),
  }

  const dialogUser = editing && editing !== 'new' && 'id' in editing ? editing : null
  const fixedCompanyId = editing && typeof editing === 'object' && 'companyId' in editing && !('id' in editing) ? editing.companyId : undefined

  return (
    <Stack spacing={2}>
      <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center' }}>
        <Typography variant="h5" component="h1">
          Users
        </Typography>
        <Button variant="contained" onClick={() => setEditing(selected && tab.type !== 'ERGO' ? { companyId: selected.company.id } : 'new')}>
          New user
        </Button>
      </Stack>

      <Tabs value={tab.param} onChange={(_e, value: string) => changeTab(value)} aria-label="User type" sx={{ borderBottom: 1, borderColor: 'divider' }}>
        {TABS.map((t) => (
          <Tab
            key={t.param}
            value={t.param}
            label={`${t.label} (${users.filter((u) => u.companyType === t.type).length})`}
          />
        ))}
      </Tabs>

      {(error ?? actionError) != null && <Alert severity="error">{problemMessage(error ?? actionError)}</Alert>}

      {tab.type === 'ERGO' ? (
        <Paper variant="outlined" sx={{ minWidth: 0 }}>
          <UserTable users={typeUsers} loading={isLoading} showCompany={false} emptyText="No ERGO employees yet." {...actions} />
        </Paper>
      ) : (
        <Paper variant="outlined" sx={{ minWidth: 0 }}>
          <Stack
            direction={{ xs: 'column', sm: 'row' }}
            spacing={2}
            sx={{ p: 2, justifyContent: 'space-between', alignItems: { sm: 'center' } }}
          >
            <TextField
              select
              size="small"
              label={`Select ${tab.type === 'CUSTOMER' ? 'customer' : 'supplier'} company`}
              value={selected?.company.id ?? ''}
              onChange={(event) => setSelectedId(Number(event.target.value))}
              disabled={tabCompanies.length === 0}
              sx={{ minWidth: { sm: 340 } }}
              helperText={selected ? `${selected.count} ${selected.count === 1 ? 'employee' : 'employees'} · ${selected.company.code}` : undefined}
            >
              {tabCompanies.map(({ company, count }) => (
                <MenuItem key={company.id} value={company.id}>
                  <Stack direction="row" spacing={1} sx={{ width: '100%', alignItems: 'center', justifyContent: 'space-between' }}>
                    <span>{company.profile.name}</span>
                    <Typography variant="caption" color="text.secondary">
                      {company.status === 'ACTIVE' ? count : STATUS_LABELS[company.status]}
                    </Typography>
                  </Stack>
                </MenuItem>
              ))}
            </TextField>
            <Button
              variant="outlined"
              disabled={!selected || selected.company.status !== 'ACTIVE'}
              onClick={() => selected && setEditing({ companyId: selected.company.id })}
            >
              Add employee
            </Button>
          </Stack>
          {selected && selected.company.status !== 'ACTIVE' && (
            <Alert severity="info" sx={{ mx: 2, mb: 2 }}>
              {STATUS_LABELS[selected.company.status]}. Only an approved (active) company can have users.
            </Alert>
          )}
          <UserTable
            users={employees}
            loading={isLoading}
            showCompany={false}
            emptyText={selected ? 'This company has no employees yet.' : `No ${tab.label.toLowerCase()} yet.`}
            {...actions}
          />
        </Paper>
      )}

      {editing && (
        <UserDialog
          user={dialogUser}
          fixedCompanyId={fixedCompanyId}
          onClose={() => setEditing(null)}
          onCreated={(email, password) => {
            setEditing(null)
            setIssued({ email, password })
          }}
        />
      )}
      {deleting && <DeleteUserDialog user={deleting} onClose={() => setDeleting(null)} />}
      {viewing && <LoginAsDialog user={viewing} onClose={() => setViewing(null)} onStarted={() => void navigate('/')} />}
      {issued && <TemporaryPasswordDialog {...issued} onClose={() => setIssued(null)} />}
      {confirmDialog}
    </Stack>
  )
}
