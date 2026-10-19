import { Outlet } from 'react-router'
import { useAppSelector } from '@/hooks/useAppSelector'
import { AccessDeniedPage } from '../pages/AccessDeniedPage'
import type { Role } from '../store/authSlice'

/** Hides screens the active role cannot use. Convenience only: the backend enforces every permission itself. */
export function RequireRole({ roles }: { roles: Role[] }) {
  const activeRole = useAppSelector((state) => state.auth.session?.activeRole)
  return activeRole && roles.includes(activeRole) ? <Outlet /> : <AccessDeniedPage />
}
