import {
  Button,
  Chip,
  LinearProgress,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material'
import type { User } from '../adminApi'
import { formatDateTime } from '@/utils/date'

interface Props {
  users: User[]
  loading?: boolean
  roleName: (code: string) => string
  /** Hide the Company column when the table already sits under one company. */
  showCompany?: boolean
  emptyText: string
  onEdit: (user: User) => void
  onResetPassword: (user: User) => void
  onToggleActive: (user: User) => void
  onDelete: (user: User) => void
}

/** The user rows and their per-row actions. Every button comes from the server's `user.actions`, not from the UI. */
export function UserTable({
  users,
  loading,
  roleName,
  showCompany = true,
  emptyText,
  onEdit,
  onResetPassword,
  onToggleActive,
  onDelete,
}: Props) {
  return (
    // Only the table scrolls sideways when it is wider than its space, never the page.
    <TableContainer sx={{ width: '100%', overflowX: 'auto' }}>
      {loading && <LinearProgress />}
      <Table size="small" sx={{ minWidth: 720 }}>
        <TableHead>
          <TableRow>
            <TableCell>Name</TableCell>
            <TableCell>Email</TableCell>
            {showCompany && <TableCell>Company</TableCell>}
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
              {showCompany && <TableCell>{user.companyName}</TableCell>}
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
                <Button size="small" onClick={() => onEdit(user)}>
                  Edit
                </Button>
                <Button size="small" onClick={() => onResetPassword(user)}>
                  Reset password
                </Button>
                {(user.actions.includes('DEACTIVATE') || user.actions.includes('ACTIVATE')) && (
                  <Button size="small" color={user.active ? 'error' : 'primary'} onClick={() => onToggleActive(user)}>
                    {user.active ? 'Deactivate' : 'Activate'}
                  </Button>
                )}
                {user.actions.includes('DELETE') && (
                  <Button size="small" color="error" onClick={() => onDelete(user)}>
                    Delete
                  </Button>
                )}
              </TableCell>
            </TableRow>
          ))}
          {!loading && users.length === 0 && (
            <TableRow>
              <TableCell colSpan={showCompany ? 7 : 6}>
                <Typography color="text.secondary" sx={{ py: 3, textAlign: 'center' }}>
                  {emptyText}
                </Typography>
              </TableCell>
            </TableRow>
          )}
        </TableBody>
      </Table>
    </TableContainer>
  )
}
