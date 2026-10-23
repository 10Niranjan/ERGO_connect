import MenuIcon from '@mui/icons-material/Menu'
import {
  AppBar,
  Avatar,
  Box,
  Chip,
  Divider,
  IconButton,
  ListItemText,
  Menu,
  MenuItem,
  Select,
  Toolbar,
  Typography,
} from '@mui/material'
import { useState } from 'react'
import { useNavigate } from 'react-router'
import { useLogoutMutation, useSwitchRoleMutation } from '@/features/auth/services/authApi'
import type { Role, Session } from '@/features/auth/store/authSlice'
import { NotificationsBell } from '@/features/notifications/components/NotificationsBell'

interface NavbarProps {
  session: Session
  onToggleMobile: () => void
}

export function Navbar({ session, onToggleMobile }: NavbarProps) {
  const [switchRole, switching] = useSwitchRoleMutation()
  const [logout] = useLogoutMutation()
  const [menuAnchor, setMenuAnchor] = useState<HTMLElement | null>(null)
  const navigate = useNavigate()

  const changeRole = async (role: Role) => {
    try {
      await switchRole(role).unwrap()
      navigate('/')
    } catch {
      // keeps the current role
    }
  }

  const handleSignOut = () => {
    void logout().then(() => navigate('/login', { replace: true }))
  }

  const initials = `${session.user.firstName[0] ?? ''}${session.user.lastName[0] ?? ''}`
  const roleName = session.roles.find((role) => role.code === session.activeRole)?.name ?? session.activeRole

  return (
    <>
      <AppBar position="sticky" elevation={0}>
        <Toolbar sx={{ gap: 2 }}>
          <IconButton
            aria-label="open navigation menu"
            edge="start"
            onClick={onToggleMobile}
            sx={{ mr: 1, display: { md: 'none' } }}
          >
            <MenuIcon />
          </IconButton>

          {/* Title / active role display */}
          <Typography variant="h6" component="div" sx={{ fontWeight: 600, display: { xs: 'none', sm: 'block' } }}>
            ERGO Connect
          </Typography>

          <Box sx={{ flexGrow: 1 }} />

          {session.roles.length > 1 ? (
            <Select
              size="small"
              value={session.activeRole}
              disabled={switching.isLoading}
              onChange={(event) => void changeRole(event.target.value as Role)}
              inputProps={{ 'aria-label': 'Active role' }}
              sx={{ minWidth: 180 }}
            >
              {session.roles.map((role) => (
                <MenuItem key={role.code} value={role.code}>
                  {role.name}
                </MenuItem>
              ))}
            </Select>
          ) : (
            <Chip label={roleName} color="primary" variant="outlined" sx={{ bgcolor: 'primary.light', borderColor: 'transparent' }} />
          )}

          <NotificationsBell />

          <IconButton aria-label="Account menu" onClick={(event) => setMenuAnchor(event.currentTarget)}>
            <Avatar sx={{ width: 32, height: 32, fontSize: 13, fontWeight: 600, bgcolor: 'primary.light', color: 'primary.main' }}>{initials}</Avatar>
          </IconButton>
          <Menu anchorEl={menuAnchor} open={Boolean(menuAnchor)} onClose={() => setMenuAnchor(null)}>
            <MenuItem disabled>
              <ListItemText
                primary={`${session.user.firstName} ${session.user.lastName}`}
                secondary={session.user.companyName}
              />
            </MenuItem>
            <Divider />
            <MenuItem onClick={() => navigate('/change-password')}>Change password</MenuItem>
            <MenuItem onClick={handleSignOut}>Sign out</MenuItem>
          </Menu>
        </Toolbar>
      </AppBar>
    </>
  )
}
