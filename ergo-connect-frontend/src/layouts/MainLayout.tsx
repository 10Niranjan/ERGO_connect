import { Box } from '@mui/material'
import { useState } from 'react'
import { Outlet } from 'react-router'
import { useAppSelector } from '@/hooks/useAppSelector'
import { useAccessQuery } from '@/features/auth/services/authApi'
import { ImpersonationBanner } from './ImpersonationBanner'
import { Navbar } from './Navbar'
import { Sidebar } from './Sidebar'

export function MainLayout() {
  const session = useAppSelector((state) => state.auth.session)
  const [mobileOpen, setMobileOpen] = useState(false)
  // A restored demo session carries no permissions; every real session response does. Fetch them once if missing.
  useAccessQuery(undefined, { skip: !session || session.access !== undefined })

  if (!session) return null

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', minHeight: '100vh', bgcolor: 'background.default' }}>
      {session.impersonation && <ImpersonationBanner session={session} />}
      <Box sx={{ display: 'flex', flexGrow: 1, minHeight: 0 }}>
      {/* Sidebar Navigation */}
      <Sidebar
        session={session}
        mobileOpen={mobileOpen}
        onCloseMobile={() => setMobileOpen(false)}
      />

      {/* Main app area */}
      <Box sx={{ display: 'flex', flexDirection: 'column', flexGrow: 1, minWidth: 0 }}>
        <Navbar
          session={session}
          onToggleMobile={() => setMobileOpen((prev) => !prev)}
        />

        <Box component="main" sx={{ p: 3, flexGrow: 1 }}>
          <Outlet />
        </Box>
      </Box>
      </Box>
    </Box>
  )
}

// Export alias for backwards compatibility
export const AppShell = MainLayout
