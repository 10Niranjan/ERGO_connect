import { createBrowserRouter, Navigate } from 'react-router'
import { COMPANY_MANAGERS, EXTERNAL_ROLES, MASTER_DATA_VIEWERS } from '../auth/access'
import { ChangePasswordPage } from '../auth/pages/ChangePasswordPage'
import { LoginPage } from '../auth/pages/LoginPage'
import { RoleSelectPage } from '../auth/pages/RoleSelectPage'
import { RequireAuth } from '../auth/RequireAuth'
import { RequireRole } from '../auth/RequireRole'
import { MasterDataPage } from '../features/admin/pages/MasterDataPage'
import { UsersPage } from '../features/admin/pages/UsersPage'
import { CompaniesPage } from '../features/companies/pages/CompaniesPage'
import { MyCompanyPage } from '../features/companies/pages/MyCompanyPage'
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
            element: <RequireRole roles={COMPANY_MANAGERS} />,
            children: [{ path: '/companies', element: <CompaniesPage /> }],
          },
          { element: <RequireRole roles={EXTERNAL_ROLES} />, children: [{ path: '/company', element: <MyCompanyPage /> }] },
          { element: <RequireRole roles={['ERGO_ADMIN']} />, children: [{ path: '/admin/users', element: <UsersPage /> }] },
          {
            element: <RequireRole roles={MASTER_DATA_VIEWERS} />,
            children: [{ path: '/admin/master-data', element: <MasterDataPage /> }],
          },
          { path: '*', element: <Navigate to="/" replace /> },
        ],
      },
    ],
  },
])
