import { Box, CircularProgress } from '@mui/material'
import { Outlet } from 'react-router'
import { useAppSelector } from '@/hooks/useAppSelector'
import { AccessDeniedPage } from '@/features/auth/pages/AccessDeniedPage'
import { allows, type Rule } from './permissions'

/**
 * Route guard: renders the page only if the signed-in role meets one of the rules. Hides screens only; the API
 * refuses the same request regardless.
 */
export function RequirePermission({ rules }: { rules: Rule[] }) {
  const session = useAppSelector((state) => state.auth.session)
  if (!session?.access) {
    return (
      <Box sx={{ display: 'grid', placeItems: 'center', minHeight: '40vh' }}>
        <CircularProgress aria-label="Loading permissions" />
      </Box>
    )
  }
  return allows(session.access, session.user.companyType, rules) ? <Outlet /> : <AccessDeniedPage />
}
