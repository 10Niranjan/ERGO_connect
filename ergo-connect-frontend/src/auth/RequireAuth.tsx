import { Box, CircularProgress } from '@mui/material'
import { useEffect } from 'react'
import { Navigate, Outlet, useLocation } from 'react-router'
import { useAppSelector } from '../app/store'
import { useRefreshMutation } from './authApi'

/** Restores the session after a page load (from the HttpOnly cookie), then keeps everything behind sign-in. */
export function RequireAuth() {
  const { session, checked } = useAppSelector((state) => state.auth)
  const [refresh] = useRefreshMutation()
  const location = useLocation()

  useEffect(() => {
    if (!checked) void refresh()
  }, [checked, refresh])

  if (!checked) {
    return (
      <Box sx={{ display: 'grid', placeItems: 'center', minHeight: '100vh' }}>
        <CircularProgress aria-label="Loading" />
      </Box>
    )
  }
  if (!session) return <Navigate to="/login" replace state={{ from: location.pathname }} />
  if (session.mustChangePassword && location.pathname !== '/change-password') {
    return <Navigate to="/change-password" replace />
  }
  return <Outlet />
}
