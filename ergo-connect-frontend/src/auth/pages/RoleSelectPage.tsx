import { Alert, List, ListItemButton, ListItemText } from '@mui/material'
import { useNavigate } from 'react-router'
import { useAppSelector } from '../../app/store'
import { CenteredCard } from '../../shared/components/CenteredCard'
import { problemMessage } from '../../shared/problem'
import { useSwitchRoleMutation } from '../authApi'
import type { Role } from '../authSlice'

// SRS 12.4.2: shown only to users with more than one role.
export function RoleSelectPage() {
  const session = useAppSelector((state) => state.auth.session)
  const [switchRole, { isLoading, error }] = useSwitchRoleMutation()
  const navigate = useNavigate()
  if (!session) return null

  const choose = async (role: Role) => {
    try {
      if (role !== session.activeRole) await switchRole(role).unwrap()
      navigate('/', { replace: true })
    } catch {
      // shown below
    }
  }

  return (
    <CenteredCard
      title="Choose your role"
      subtitle={`You work in one role at a time at ${session.user.companyName}. You can switch any time from the top bar.`}
    >
      <List disablePadding>
        {session.roles.map((role) => (
          <ListItemButton
            key={role.code}
            selected={role.code === session.activeRole}
            disabled={isLoading}
            onClick={() => void choose(role.code)}
          >
            <ListItemText primary={role.name} secondary={role.code === session.activeRole ? 'Default' : undefined} />
          </ListItemButton>
        ))}
      </List>
      {error && <Alert severity="error">{problemMessage(error)}</Alert>}
    </CenteredCard>
  )
}
