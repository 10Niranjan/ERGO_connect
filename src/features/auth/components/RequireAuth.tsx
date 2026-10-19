import { Box, CircularProgress } from '@mui/material'
import { useEffect } from 'react'
import { Navigate, Outlet, useLocation } from 'react-router'
import { useAppDispatch } from '@/hooks/useAppDispatch'
import { useAppSelector } from '@/hooks/useAppSelector'
import { useRefreshMutation } from '../services/authApi'
import { sessionStarted } from '../store/authSlice'
import { DEMO_STORAGE_KEY } from '../utils/demoAccounts'

/** Restores the session after a page load (from demo session or HttpOnly cookie), then keeps everything behind sign-in. */
export function RequireAuth() {
  const { session, checked } = useAppSelector((state) => state.auth)
  const dispatch = useAppDispatch()
  const [refresh] = useRefreshMutation()
  const location = useLocation()

  useEffect(() => {
    if (!checked) {
      const demoSaved = sessionStorage.getItem(DEMO_STORAGE_KEY)
      if (demoSaved) {
        try {
          const parsed = JSON.parse(demoSaved)
          if (parsed && parsed.activeRole) {
            dispatch(sessionStarted(parsed))
            return
          }
        } catch {
          sessionStorage.removeItem(DEMO_STORAGE_KEY)
        }
      }
      void refresh()
    }
  }, [checked, dispatch, refresh])

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
