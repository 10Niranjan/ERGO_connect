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
  Stack,
  Toolbar,
  Typography,
} from '@mui/material'
import { useState } from 'react'
import { NavLink, Outlet, useNavigate } from 'react-router'
import { useAppSelector } from '../../app/store'
import {
  COMPANY_MANAGERS,
  EXTERNAL_ROLES,
  MASTER_DATA_VIEWERS,
  NPD_CUSTOMER_ROLES,
  NPD_SUPPLIER_ROLES,
  NPD_VIEWERS,
  OFP_CUSTOMER_ROLES,
  OFP_SUPPLIER_ROLES,
  OFP_VIEWERS,
  RFQ_VIEWERS,
  SUPPLIER_QUOTERS,
} from '../../auth/access'
import { useLogoutMutation, useSwitchRoleMutation } from '../../auth/authApi'
import type { Role } from '../../auth/authSlice'

// Menu entries per active role (SRS 12.15). Modules add their entries as they are built.
const NAV: { label: string; to: string; roles?: Role[] }[] = [
  { label: 'Dashboard', to: '/' },
  { label: 'RFQs', to: '/rfqs', roles: RFQ_VIEWERS },
  { label: 'Assigned RFQs', to: '/quotes', roles: SUPPLIER_QUOTERS },
  { label: 'NPD', to: '/npd', roles: NPD_VIEWERS },
  { label: 'NPD', to: '/npd/mine', roles: NPD_CUSTOMER_ROLES },
  { label: 'NPD Tasks', to: '/npd/mine', roles: NPD_SUPPLIER_ROLES },
  { label: 'Orders', to: '/ofp', roles: [...OFP_VIEWERS, ...OFP_SUPPLIER_ROLES, ...OFP_CUSTOMER_ROLES] },
  { label: 'Customers & suppliers', to: '/companies', roles: COMPANY_MANAGERS },
  { label: 'Company profile', to: '/company', roles: EXTERNAL_ROLES },
  { label: 'Users', to: '/admin/users', roles: ['ERGO_ADMIN'] },
  { label: 'Master data', to: '/admin/master-data', roles: MASTER_DATA_VIEWERS },
]

// SRS 12.2 layout: top bar with navigation, role switcher and user menu.
export function AppShell() {
  const session = useAppSelector((state) => state.auth.session)
  const [switchRole, switching] = useSwitchRoleMutation()
  const [logout] = useLogoutMutation()
  const [menuAnchor, setMenuAnchor] = useState<HTMLElement | null>(null)
  const navigate = useNavigate()
  if (!session) return null

  const changeRole = async (role: Role) => {
    try {
      await switchRole(role).unwrap()
      navigate('/') // the current page may not exist for the new role
    } catch {
      // keeps the current role
    }
  }

  const initials = `${session.user.firstName[0] ?? ''}${session.user.lastName[0] ?? ''}`
  const roleName = session.roles.find((role) => role.code === session.activeRole)?.name ?? session.activeRole

  return (
    <Box sx={{ minHeight: '100vh' }}>
      <AppBar position="sticky" elevation={0}>
        <Toolbar sx={{ gap: 2 }}>
          <Typography variant="h6" component="span" sx={{ mr: 2 }}>
            ERGO Connect
          </Typography>
          <Stack component="nav" direction="row" spacing={1} sx={{ flexGrow: 1 }}>
            {NAV.filter((item) => !item.roles || item.roles.includes(session.activeRole)).map((item) => (
              <Button
                key={item.to}
                component={NavLink}
                to={item.to}
                end={item.to === '/'}
                color="inherit"
                sx={{ '&.active': { bgcolor: 'rgba(255,255,255,0.16)' } }}
              >
                {item.label}
              </Button>
            ))}
          </Stack>
          {session.roles.length > 1 ? (
            <Select
              size="small"
              value={session.activeRole}
              disabled={switching.isLoading}
              onChange={(event) => void changeRole(event.target.value as Role)}
              inputProps={{ 'aria-label': 'Active role' }}
              sx={{
                color: 'inherit',
                minWidth: 200,
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
            <MenuItem onClick={() => void logout().then(() => navigate('/login', { replace: true }))}>
              Sign out
            </MenuItem>
          </Menu>
        </Toolbar>
      </AppBar>
      <Box component="main" sx={{ p: 3 }}>
        <Outlet />
      </Box>
    </Box>
  )
}
