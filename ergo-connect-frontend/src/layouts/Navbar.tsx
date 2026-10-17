import MenuIcon from '@mui/icons-material/Menu'
import SwapHorizIcon from '@mui/icons-material/SwapHoriz'
import {
  AppBar,
  Avatar,
  Box,
  Button,
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
import { DemoRoleModal } from '@/features/auth/components/DemoRoleModal'
import { useLogoutMutation, useSwitchRoleMutation } from '@/features/auth/services/authApi'
import type { Role, Session } from '@/features/auth/store/authSlice'
import { DEMO_STORAGE_KEY } from '@/features/auth/utils/demoAccounts'
import { NotificationsBell } from '@/features/notifications/components/NotificationsBell'

interface NavbarProps {
  session: Session
  onToggleMobile: () => void
}

export function Navbar({ session, onToggleMobile }: NavbarProps) {
  const [switchRole, switching] = useSwitchRoleMutation()
  const [logout] = useLogoutMutation()
  const [menuAnchor, setMenuAnchor] = useState<HTMLElement | null>(null)
  const [demoModalOpen, setDemoModalOpen] = useState(false)
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
    sessionStorage.removeItem(DEMO_STORAGE_KEY)
    void logout().then(() => navigate('/login', { replace: true }))
  }

  const initials = `${session.user.firstName[0] ?? ''}${session.user.lastName[0] ?? ''}`
  const roleName = session.roles.find((role) => role.code === session.activeRole)?.name ?? session.activeRole

  return (
    <>
      <AppBar position="sticky" elevation={0}>
        <Toolbar sx={{ gap: 2 }}>
          <IconButton
            color="inherit"
            aria-label="open navigation menu"
            edge="start"
            onClick={onToggleMobile}
            sx={{ mr: 1, display: { md: 'none' } }}
          >
            <MenuIcon />
          </IconButton>

          {/* Title / active role display */}
          <Typography variant="h6" component="div" sx={{ fontWeight: 700, display: { xs: 'none', sm: 'block' } }}>
            ERGO Connect
          </Typography>

          <Box sx={{ flexGrow: 1 }} />

          {/* Presenter's 1-Click Demo Role Switcher */}
          <Button
            variant="outlined"
            size="small"
            startIcon={<SwapHorizIcon />}
            onClick={() => setDemoModalOpen(true)}
            sx={{
              color: 'inherit',
              borderColor: 'rgba(255,255,255,0.6)',
              textTransform: 'none',
              fontWeight: 700,
              fontSize: '0.8rem',
              borderRadius: 2,
              px: 1.5,
              '&:hover': {
                borderColor: '#fff',
                bgcolor: 'rgba(255,255,255,0.1)',
              },
            }}
          >
            Switch Demo Role
          </Button>

          {session.roles.length > 1 ? (
            <Select
              size="small"
              value={session.activeRole}
              disabled={switching.isLoading}
              onChange={(event) => void changeRole(event.target.value as Role)}
              inputProps={{ 'aria-label': 'Active role' }}
              sx={{
                color: 'inherit',
                minWidth: 180,
                '.MuiOutlinedInput-notchedOutline': { borderColor: 'rgba(255,255,255,0.5)' },
                '.MuiSvgIcon-root': { color: 'inherit' },
              }}
            >
              {session.roles.map((role) => (
                <MenuItem key={role.code} value={role.code}>
                  {role.name}
                </MenuItem>
              ))}
            </Select>
          ) : (
            <Chip label={roleName} sx={{ color: 'inherit', borderColor: 'rgba(255,255,255,0.5)' }} variant="outlined" />
          )}

          <NotificationsBell />

          <IconButton aria-label="Account menu" onClick={(event) => setMenuAnchor(event.currentTarget)}>
            <Avatar sx={{ width: 32, height: 32, fontSize: 14 }}>{initials}</Avatar>
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

      {/* Demo Role Switcher Modal */}
      <DemoRoleModal open={demoModalOpen} onClose={() => setDemoModalOpen(false)} />
    </>
  )
}
