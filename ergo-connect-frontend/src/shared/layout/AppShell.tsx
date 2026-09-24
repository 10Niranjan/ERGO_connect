import { AppBar, Box, Toolbar, Typography } from '@mui/material'
import { Outlet } from 'react-router'

// SRS 12.2 layout. Role-based left nav, notifications and role switcher arrive with auth in Phase 1.
export function AppShell() {
  return (
    <Box sx={{ minHeight: '100vh' }}>
      <AppBar position="sticky" elevation={0}>
        <Toolbar>
          <Typography variant="h6" component="span">
            ERGO Connect
          </Typography>
        </Toolbar>
      </AppBar>
      <Box component="main" sx={{ p: 3 }}>
        <Outlet />
      </Box>
    </Box>
  )
}
