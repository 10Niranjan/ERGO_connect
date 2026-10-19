import {
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
import {
  useResetUserPasswordMutation,
  useRolesQuery,
  useSetUserActiveMutation,
  useUsersQuery,
  type User,
} from '@/features/admin/services/adminApi'
import { TemporaryPasswordDialog } from '../components/TemporaryPasswordDialog'
import { UserDialog } from '../components/UserDialog'
import { formatDateTime } from '@/utils/date'
import { useConfirm } from '@/components/ui/useConfirm'
import { ProblemAlert } from '@/components/ui/ProblemAlert'

// SRS 12.13.1 User Administration. ERGO Admin only (route guard here, enforced by the backend).
export function UsersPage() {
  const { confirm, dialog: confirmDialog } = useConfirm()
  const { data: users = [], isLoading, error } = useUsersQuery()
  const { data: roles = [] } = useRolesQuery()
  const [setUserActive] = useSetUserActiveMutation()
  const [resetUserPassword] = useResetUserPasswordMutation()
  const [editing, setEditing] = useState<User | 'new' | null>(null)
  const [issued, setIssued] = useState<{ email: string; password: string } | null>(null)
  const [actionError, setActionError] = useState<unknown>(null)

  const roleName = (code: string) => roles.find((role) => role.code === code)?.name ?? code

  async function toggleActive(user: User) {
    const verb = user.active ? 'Deactivate' : 'Activate'
    if (!(await confirm({ title: `${verb} user?`, message: `${verb} ${user.firstName} ${user.lastName}?`, confirmLabel: verb }))) return
    setActionError(null)
    await setUserActive({ id: user.id, active: !user.active }).unwrap().catch(setActionError)
  }

  async function resetPassword(user: User) {
    if (!(await confirm({ title: 'Reset password?', message: `Reset the password of ${user.email}? They will be signed out everywhere.`, confirmLabel: 'Reset' }))) return
    setActionError(null)
    try {
      const { temporaryPassword } = await resetUserPassword(user.id).unwrap()
      setIssued({ email: user.email, password: temporaryPassword })
    } catch (e) {
      setActionError(e)
    }
  }

  return (
    <Stack spacing={2}>
      <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center' }}>
        <Typography variant="h5" component="h1">
          Users
        </Typography>
        <Button variant="contained" onClick={() => setEditing('new')}>
          New user
        </Button>
      </Stack>
      {(error ?? actionError) != null && <ProblemAlert error={error ?? actionError} />}
      <TableContainer component={Paper} variant="outlined">
        {isLoading && <LinearProgress />}
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>Name</TableCell>
              <TableCell>Email</TableCell>
              <TableCell>Company</TableCell>
              <TableCell>Roles</TableCell>
              <TableCell>Status</TableCell>
              <TableCell>Last sign-in</TableCell>
              <TableCell align="right">Actions</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {users.map((user) => (
              <TableRow key={user.id} hover>
                <TableCell>
                  {user.firstName} {user.lastName}
                </TableCell>
                <TableCell>{user.email}</TableCell>
                <TableCell>{user.companyName}</TableCell>
                <TableCell>
                  <Stack direction="row" spacing={0.5} useFlexGap sx={{ flexWrap: 'wrap' }}>
                    {user.roles.map((role) => (
                      <Chip
                        key={role}
                        size="small"
                        label={roleName(role)}
                        variant={role === user.defaultRole ? 'filled' : 'outlined'}
                      />
                    ))}
                  </Stack>
                </TableCell>
                <TableCell>
                  <Chip size="small" color={user.active ? 'success' : 'default'} label={user.active ? 'Active' : 'Inactive'} />
                </TableCell>
                <TableCell>{user.lastLoginAt ? formatDateTime(user.lastLoginAt) : '—'}</TableCell>
                <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                  <Button size="small" onClick={() => setEditing(user)}>
                    Edit
                  </Button>
                  <Button size="small" onClick={() => void resetPassword(user)}>
                    Reset password
                  </Button>
                  <Button size="small" color={user.active ? 'error' : 'primary'} onClick={() => void toggleActive(user)}>
                    {user.active ? 'Deactivate' : 'Activate'}
                  </Button>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>
      {editing && (
        <UserDialog
          user={editing === 'new' ? null : editing}
          onClose={() => setEditing(null)}
          onCreated={(email, password) => {
            setEditing(null)
            setIssued({ email, password })
          }}
        />
      )}
      {issued && <TemporaryPasswordDialog {...issued} onClose={() => setIssued(null)} />}
      {confirmDialog}
    </Stack>
  )
}
