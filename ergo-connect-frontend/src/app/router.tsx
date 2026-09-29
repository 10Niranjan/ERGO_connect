import { createBrowserRouter, Navigate } from 'react-router'
import {
  COMPANY_MANAGERS,
  EXTERNAL_ROLES,
  MASTER_DATA_VIEWERS,
  NPD_CUSTOMER_ROLES,
  NPD_DETAIL_VIEWERS,
  NPD_SUPPLIER_ROLES,
  NPD_VIEWERS,
  OFP_CUSTOMER_ROLES,
  OFP_DETAIL_VIEWERS,
  OFP_EDITORS,
  OFP_SUPPLIER_ROLES,
  OFP_VIEWERS,
  QUOTE_VIEWERS,
  RFQ_CREATORS,
  RFQ_VIEWERS,
  SUPPLIER_QUOTERS,
} from '../auth/access'
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
import { ConversionQueuePage } from '../features/npd/pages/ConversionQueuePage'
import { MyNpdProjectsPage } from '../features/npd/pages/MyNpdProjectsPage'
import { NpdDetailPage } from '../features/npd/pages/NpdDetailPage'
import { NpdListPage } from '../features/npd/pages/NpdListPage'
import { OrderCreatePage } from '../features/ofp/pages/OrderCreatePage'
import { OrderDetailPage } from '../features/ofp/pages/OrderDetailPage'
import { OrderListPage } from '../features/ofp/pages/OrderListPage'
import { AssignedRfqsPage } from '../features/quotation/pages/AssignedRfqsPage'
import { QuotePage } from '../features/quotation/pages/QuotePage'
import { RfqDetailPage } from '../features/rfq/pages/RfqDetailPage'
import { RfqEditPage } from '../features/rfq/pages/RfqEditPage'
import { RfqListPage } from '../features/rfq/pages/RfqListPage'
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
            element: <RequireRole roles={RFQ_VIEWERS} />,
            children: [
              { path: '/rfqs', element: <RfqListPage /> },
              { path: '/rfqs/:id', element: <RfqDetailPage /> },
            ],
          },
          {
            element: <RequireRole roles={RFQ_CREATORS} />,
            children: [
              { path: '/rfqs/new', element: <RfqEditPage /> },
              { path: '/rfqs/:id/edit', element: <RfqEditPage /> },
            ],
          },
          { element: <RequireRole roles={SUPPLIER_QUOTERS} />, children: [{ path: '/quotes', element: <AssignedRfqsPage /> }] },
          { element: <RequireRole roles={QUOTE_VIEWERS} />, children: [{ path: '/quotes/:id', element: <QuotePage /> }] },
          {
            element: <RequireRole roles={NPD_VIEWERS} />,
            children: [
              { path: '/npd', element: <NpdListPage /> },
              { path: '/npd/conversion-queue', element: <ConversionQueuePage /> },
            ],
          },
          {
            element: <RequireRole roles={[...NPD_CUSTOMER_ROLES, ...NPD_SUPPLIER_ROLES]} />,
            children: [{ path: '/npd/mine', element: <MyNpdProjectsPage /> }],
          },
          {
            element: <RequireRole roles={NPD_DETAIL_VIEWERS} />,
            children: [{ path: '/npd/:id', element: <NpdDetailPage /> }],
          },
          {
            // /ofp's own single list/detail endpoints already scope by caller, so one route set serves every
            // audience (ERGO, the four supplier roles and Customer OFP) rather than NPD's separate ERGO/"mine" split.
            element: <RequireRole roles={[...OFP_VIEWERS, ...OFP_SUPPLIER_ROLES, ...OFP_CUSTOMER_ROLES]} />,
            children: [{ path: '/ofp', element: <OrderListPage /> }],
          },
          {
            element: <RequireRole roles={OFP_EDITORS} />,
            children: [{ path: '/ofp/new', element: <OrderCreatePage /> }],
          },
          {
            element: <RequireRole roles={OFP_DETAIL_VIEWERS} />,
            children: [{ path: '/ofp/:id', element: <OrderDetailPage /> }],
          },
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
