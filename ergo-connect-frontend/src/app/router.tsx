import { createBrowserRouter, Navigate } from 'react-router'
import { ChangePasswordPage } from '../auth/pages/ChangePasswordPage'
import { LoginPage } from '../auth/pages/LoginPage'
import { RoleSelectPage } from '../auth/pages/RoleSelectPage'
import { RequireAuth } from '../auth/RequireAuth'
import { RequireRole } from '../auth/RequireRole'
import { CompaniesPage } from '../features/admin/pages/CompaniesPage'
import { UsersPage } from '../features/admin/pages/UsersPage'
import { DashboardPage } from '../features/dashboard/pages/DashboardPage'
import { AppShell } from '../shared/layout/AppShell'

// Route guards only hide screens; the backend enforces every permission and record scope itself.
export const router = createBrowserRouter([
  { path: '/login', element: <LoginPage /> },
  {
    element: <RequireAuth />,
    children: [
      { path: '/change-password', element: <ChangePasswordPage /> },
      { path: '/select-role', element: <RoleSelectPage /> },
      {
        element: <AppShell />,
        children: [
          { index: true, element: <DashboardPage /> },
          {
            element: <RequireRole roles={['ERGO_ADMIN']} />,
            children: [
              { path: '/admin/users', element: <UsersPage /> },
              { path: '/admin/companies', element: <CompaniesPage /> },
            ],
          },
          { path: '*', element: <Navigate to="/" replace /> },
        ],
      },
    ],
  },
])
