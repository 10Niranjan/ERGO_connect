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
import {
  useCompaniesQuery,
  useCreateUserMutation,
  useRolesQuery,
  useSetUserRolesMutation,
  useUpdateUserMutation,
  type User,
} from '../adminApi'

interface Props {
  /** null = create a new user */
  user: User | null
  onClose: () => void
  onCreated: (email: string, temporaryPassword: string) => void
}

// SRS 12.13.1: create user, map company, assign role(s).
export function UserDialog({ user, onClose, onCreated }: Props) {
  const { data: companies = [] } = useCompaniesQuery()
  const { data: roleOptions = [] } = useRolesQuery()
  const [createUser, creating] = useCreateUserMutation()
  const [updateUser, updating] = useUpdateUserMutation()
  const [setUserRoles, settingRoles] = useSetUserRolesMutation()
  const [companyId, setCompanyId] = useState<number | ''>(user?.companyId ?? '')
  const [roles, setRoles] = useState<Role[]>(user?.roles ?? [])
  const [defaultRole, setDefaultRole] = useState<Role | ''>(user?.defaultRole ?? '')

  // A company's users can only hold roles of that company type (also enforced by the backend and the database).
  const companyType = user?.companyType ?? companies.find((company) => company.id === companyId)?.type
  const available = roleOptions.filter((role) => role.companyType === companyType)
  const chosenDefault = defaultRole && roles.includes(defaultRole) ? defaultRole : null
  const error = creating.error ?? updating.error ?? settingRoles.error

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
        onClose()
      } else {
        const created = await createUser({ ...profile, companyId: Number(companyId), roles, defaultRole: chosenDefault }).unwrap()
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
          <TextField
            select
            label="Company"
            value={companyId}
            required
            disabled={Boolean(user)}
            helperText={user ? 'Users always stay in their company' : undefined}
            onChange={(event) => {
              setCompanyId(Number(event.target.value))
              setRoles([])
              setDefaultRole('')
            }}
          >
            {companies
              .filter((company) => company.active || company.id === companyId)
              .map((company) => (
                <MenuItem key={company.id} value={company.id}>
                  {company.name} ({company.type.toLowerCase()})
                </MenuItem>
              ))}
          </TextField>
          <Stack direction="row" spacing={2}>
            <TextField name="firstName" label="First name" defaultValue={user?.firstName} required fullWidth />
            <TextField name="lastName" label="Last name" defaultValue={user?.lastName} required fullWidth />
          </Stack>
          <TextField name="email" type="email" label="Email" defaultValue={user?.email} required />
          <TextField name="mobile" label="Mobile" defaultValue={user?.mobile ?? ''} />
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
          {error && <Alert severity="error">{problemMessage(error)}</Alert>}
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            type="submit"
            variant="contained"
            disabled={roles.length === 0}
            loading={creating.isLoading || updating.isLoading || settingRoles.isLoading}
          >
            {user ? 'Save' : 'Create user'}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  )
}
