import { Stack, Typography } from '@mui/material'
import { useAppSelector } from '../../../app/store'

// Role-specific dashboards (SRS 12.5) land in Phase 7.
export function DashboardPage() {
  const session = useAppSelector((state) => state.auth.session)
  if (!session) return null
  const roleName = session.roles.find((role) => role.code === session.activeRole)?.name ?? session.activeRole

  return (
    <Stack spacing={1}>
      <Typography variant="h5" component="h1">
        Welcome, {session.user.firstName}
      </Typography>
      <Typography color="text.secondary">
        You are working as {roleName} at {session.user.companyName}.
      </Typography>
    </Stack>
  )
}
