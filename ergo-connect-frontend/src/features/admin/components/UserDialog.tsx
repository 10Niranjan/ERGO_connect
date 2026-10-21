import {
  Alert,
  Button,
  Checkbox,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControl,
  FormControlLabel,
  FormGroup,
  FormHelperText,
  FormLabel,
  MenuItem,
  Stack,
  TextField,
} from '@mui/material'
import { useState, type FormEvent } from 'react'
import type { Role } from '../../../auth/authSlice'
import { problemMessage } from '../../../shared/problem'
import { useCompaniesQuery } from '../../companies/companiesApi'
import {
  useCreateUserMutation,
  useRolesQuery,
  useSetUserAccountsMutation,
  useSetUserRolesMutation,
  useUpdateUserMutation,
  type User,
} from '../adminApi'

interface Props {
  /** null = create a new user */
  user: User | null
  /** Creating from a company's row: that company is fixed instead of chosen (ERGO RFQ adds supplier accounts this way). */
  fixedCompanyId?: number
  onClose: () => void
  onCreated: (email: string, temporaryPassword: string) => void
}

// SRS 12.13.1: create user, map company, assign role(s).
export function UserDialog({ user, fixedCompanyId, onClose, onCreated }: Props) {
  const { data: companies = [] } = useCompaniesQuery()
  const { data: roleOptions = [] } = useRolesQuery()
  const [createUser, creating] = useCreateUserMutation()
  const [updateUser, updating] = useUpdateUserMutation()
  const [setUserRoles, settingRoles] = useSetUserRolesMutation()
  const [setUserAccounts, settingAccounts] = useSetUserAccountsMutation()
  const [companyId, setCompanyId] = useState<number | ''>(user?.companyId ?? fixedCompanyId ?? '')
  const [roles, setRoles] = useState<Role[]>(user?.roles ?? [])
  const [defaultRole, setDefaultRole] = useState<Role | ''>(user?.defaultRole ?? '')
  const [accountIds, setAccountIds] = useState<number[]>(user?.accountIds ?? [])

  // A company's users can only hold roles of that company type (also enforced by the backend and the database).
  const companyType = user?.companyType ?? companies.find((company) => company.id === companyId)?.type
  const available = roleOptions.filter((role) => role.companyType === companyType)
  const chosenDefault = defaultRole && roles.includes(defaultRole) ? defaultRole : null
  const error = creating.error ?? updating.error ?? settingRoles.error ?? settingAccounts.error
  // SRS §13.2.5: an ERGO Key Account Manager sees only the customer accounts assigned here
  const isKam = companyType === 'ERGO' && roles.includes('ERGO_KAM')
  const customers = companies.filter((company) => company.type === 'CUSTOMER' && company.status !== 'REJECTED')

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    const profile = {
      firstName: String(form.get('firstName')),
      lastName: String(form.get('lastName')),
      email: String(form.get('email')),
      mobile: String(form.get('mobile')) || null,
    }
    try {
      if (user) {
        await updateUser({ id: user.id, ...profile }).unwrap()
        await setUserRoles({ id: user.id, roles, defaultRole: chosenDefault }).unwrap()
        if (companyType === 'ERGO') await setUserAccounts({ id: user.id, customerIds: isKam ? accountIds : [] }).unwrap()
        onClose()
      } else {
        const created = await createUser({ ...profile, companyId: Number(companyId), roles, defaultRole: chosenDefault }).unwrap()
        if (isKam && accountIds.length > 0) await setUserAccounts({ id: created.user.id, customerIds: accountIds }).unwrap()
        onCreated(created.user.email, created.temporaryPassword)
      }
    } catch {
      // shown below
    }
  }

  const toggleRole = (role: Role, checked: boolean) =>
    setRoles(checked ? [...roles, role] : roles.filter((r) => r !== role))

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <form onSubmit={handleSubmit}>
        <DialogTitle>{user ? 'Edit user' : 'New user'}</DialogTitle>
        <DialogContent sx={{ display: 'grid', gap: 2, pt: '8px !important' }}>
          {user || fixedCompanyId !== undefined ? (
            <TextField
              label="Company"
              value={user?.companyName ?? companies.find((company) => company.id === fixedCompanyId)?.profile.name ?? ''}
              disabled
              helperText="Users always stay in their company"
            />
          ) : (
            <TextField
              select
              label="Company"
              value={companyId}
              required
              helperText="Only active (approved) companies can get users"
              onChange={(event) => {
                setCompanyId(Number(event.target.value))
                setRoles([])
                setDefaultRole('')
              }}
            >
              {companies
                .filter((company) => company.status === 'ACTIVE')
                .map((company) => (
                  <MenuItem key={company.id} value={company.id}>
                    {company.profile.name} ({company.type.toLowerCase()})
                  </MenuItem>
                ))}
            </TextField>
          )}
          <Stack direction="row" spacing={2}>
            <TextField name="firstName" label="First name" defaultValue={user?.firstName} required fullWidth slotProps={{ htmlInput: { maxLength: 100 } }} />
            <TextField name="lastName" label="Last name" defaultValue={user?.lastName} required fullWidth slotProps={{ htmlInput: { maxLength: 100 } }} />
          </Stack>
          <TextField name="email" type="email" label="Email" defaultValue={user?.email} required slotProps={{ htmlInput: { maxLength: 254 } }} />
          <TextField name="mobile" label="Mobile" defaultValue={user?.mobile ?? ''} slotProps={{ htmlInput: { maxLength: 30 } }} />
          <FormControl component="fieldset" required>
            <FormLabel component="legend">Roles</FormLabel>
            {!companyType && <FormHelperText>Choose a company first.</FormHelperText>}
            <FormGroup>
              {available.map((role) => (
                <FormControlLabel
                  key={role.code}
                  label={role.name}
                  control={
                    <Checkbox
                      checked={roles.includes(role.code)}
                      onChange={(event) => toggleRole(role.code, event.target.checked)}
                    />
                  }
                />
              ))}
            </FormGroup>
          </FormControl>
          {roles.length > 1 && (
            <TextField
              select
              label="Default role"
              helperText="Opens after sign-in. The user can switch roles at any time."
              value={chosenDefault ?? ''}
              onChange={(event) => setDefaultRole(event.target.value as Role)}
            >
              {available
                .filter((role) => roles.includes(role.code))
                .map((role) => (
                  <MenuItem key={role.code} value={role.code}>
                    {role.name}
                  </MenuItem>
                ))}
            </TextField>
          )}
          {isKam && (
            <TextField
              select
              label="Customer accounts"
              helperText="As Key Account Manager this user sees only these customers."
              value={accountIds}
              onChange={(event) => setAccountIds(event.target.value as unknown as number[])}
              slotProps={{
                select: {
                  multiple: true,
                  renderValue: (selected) =>
                    (selected as number[])
                      .map((id) => customers.find((company) => company.id === id)?.profile.name ?? `#${id}`)
                      .join(', '),
                },
              }}
            >
              {customers.map((company) => (
                <MenuItem key={company.id} value={company.id}>
                  <Checkbox size="small" checked={accountIds.includes(company.id)} sx={{ p: 0, mr: 1 }} />
                  {company.profile.name}
                </MenuItem>
              ))}
            </TextField>
          )}
          {error && <Alert severity="error">{problemMessage(error)}</Alert>}
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            type="submit"
            variant="contained"
            disabled={roles.length === 0}
            loading={creating.isLoading || updating.isLoading || settingRoles.isLoading || settingAccounts.isLoading}
          >
            {user ? 'Save' : 'Create user'}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  )
}
