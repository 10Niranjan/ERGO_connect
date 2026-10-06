import { Alert, LinearProgress, Stack, Typography } from '@mui/material'
import { useAppSelector } from '../../../app/store'
import { problemMessage } from '../../../shared/problem'
import { CustomerDashboardView } from '../components/CustomerDashboardView'
import { FunctionalDashboardView } from '../components/FunctionalDashboardView'
import { ManagementDashboardView } from '../components/ManagementDashboardView'
import { SupplierDashboardView } from '../components/SupplierDashboardView'
import { useDashboardQuery } from '../dashboardApi'

// SRS §12.5/§10.11: one endpoint, four shapes - the backend picks the shape from the caller's active role, this
// page just renders whichever one comes back. Every role has View only here, so there are no actions to gate.
export function DashboardPage() {
  const session = useAppSelector((state) => state.auth.session)
  const { data: view, isLoading, error } = useDashboardQuery()
  if (!session) return null
  const roleName = session.roles.find((role) => role.code === session.activeRole)?.name ?? session.activeRole

  return (
    <Stack spacing={3}>
      <Stack spacing={1}>
        <Typography variant="h5" component="h1">
          Welcome, {session.user.firstName}
        </Typography>
        <Typography color="text.secondary">
          You are working as {roleName} at {session.user.companyName}.
        </Typography>
      </Stack>
      {isLoading && <LinearProgress />}
      {error && <Alert severity="error">{problemMessage(error)}</Alert>}
      {view?.audience === 'MANAGEMENT' && <ManagementDashboardView view={view} />}
      {view?.audience === 'FUNCTIONAL' && <FunctionalDashboardView view={view} />}
      {view?.audience === 'CUSTOMER' && <CustomerDashboardView view={view} />}
      {view?.audience === 'SUPPLIER' && <SupplierDashboardView view={view} />}
    </Stack>
  )
}
