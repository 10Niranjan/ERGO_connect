import { createBrowserRouter } from 'react-router'
import { DashboardPage } from '../features/dashboard/pages/DashboardPage'
import { AppShell } from '../shared/layout/AppShell'
import { AccessDeniedPage } from '../shared/pages/AccessDeniedPage'

// Phase 1 wraps module routes in <RequireRole>. UI guards are convenience only — the backend enforces access.
export const router = createBrowserRouter([
  {
    element: <AppShell />,
    children: [
      { index: true, element: <DashboardPage /> },
      { path: 'access-denied', element: <AccessDeniedPage /> },
    ],
  },
])
