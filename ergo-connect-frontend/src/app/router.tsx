import { createBrowserRouter, Navigate } from 'react-router'
import {
  COMMUNICATION_VIEWERS,
  COMPANY_MANAGERS,
  DEVIATION_EDITORS,
  DOCUMENT_VIEWERS,
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
  QUALITY_EDITORS,
  QUALITY_VIEWERS,
  QUOTE_VIEWERS,
  RFQ_CREATORS,
  RFQ_VIEWERS,
  SUPPLIER_QUOTERS,
} from '../auth/access'
import { ChangePasswordPage } from '../auth/pages/ChangePasswordPage'
import { ForgotPasswordPage } from '../auth/pages/ForgotPasswordPage'
import { LoginPage } from '../auth/pages/LoginPage'
import { ResetPasswordPage } from '../auth/pages/ResetPasswordPage'
import { RoleSelectPage } from '../auth/pages/RoleSelectPage'
import { VerifyOtpPage } from '../auth/pages/VerifyOtpPage'
import { RequireAuth } from '../auth/RequireAuth'
import { RequireRole } from '../auth/RequireRole'
import { MasterDataPage } from '../features/admin/pages/MasterDataPage'
import { PermissionMatrixPage } from '../features/admin/pages/PermissionMatrixPage'
import { UsersPage } from '../features/admin/pages/UsersPage'
import { CompaniesPage } from '../features/companies/pages/CompaniesPage'
import { MyCompanyPage } from '../features/companies/pages/MyCompanyPage'
import { CommunicationCenterPage } from '../features/communication/pages/CommunicationCenterPage'
import { DashboardPage } from '../features/dashboard/pages/DashboardPage'
import { DocumentDetailPage } from '../features/documents/pages/DocumentDetailPage'
import { DocumentLibraryPage } from '../features/documents/pages/DocumentLibraryPage'
import { ConversionQueuePage } from '../features/npd/pages/ConversionQueuePage'
import { MyNpdProjectsPage } from '../features/npd/pages/MyNpdProjectsPage'
import { NpdDetailPage } from '../features/npd/pages/NpdDetailPage'
import { NpdListPage } from '../features/npd/pages/NpdListPage'
import { OrderCreatePage } from '../features/ofp/pages/OrderCreatePage'
import { OrderDetailPage } from '../features/ofp/pages/OrderDetailPage'
import { OrderListPage } from '../features/ofp/pages/OrderListPage'
import { DeviationCreatePage } from '../features/quality/pages/DeviationCreatePage'
import { DeviationDetailPage } from '../features/quality/pages/DeviationDetailPage'
import { DeviationListPage } from '../features/quality/pages/DeviationListPage'
import { InspectionCreatePage } from '../features/quality/pages/InspectionCreatePage'
import { InspectionDetailPage } from '../features/quality/pages/InspectionDetailPage'
import { InspectionListPage } from '../features/quality/pages/InspectionListPage'
import { NcrCreatePage } from '../features/quality/pages/NcrCreatePage'
import { NcrDetailPage } from '../features/quality/pages/NcrDetailPage'
import { NcrListPage } from '../features/quality/pages/NcrListPage'
import { AssignedRfqsPage } from '../features/quotation/pages/AssignedRfqsPage'
import { QuotePage } from '../features/quotation/pages/QuotePage'
import { RfqDetailPage } from '../features/rfq/pages/RfqDetailPage'
import { RfqEditPage } from '../features/rfq/pages/RfqEditPage'
import { RfqListPage } from '../features/rfq/pages/RfqListPage'
import { AppShell } from '../shared/layout/AppShell'

// Route guards only hide screens; the backend enforces every permission and record scope itself.
export const router = createBrowserRouter([
  { path: '/login', element: <LoginPage /> },
  { path: '/verify-otp', element: <VerifyOtpPage /> },
  { path: '/forgot-password', element: <ForgotPasswordPage /> },
  { path: '/reset-password', element: <ResetPasswordPage /> },
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
            // /quality's own single list/detail endpoints already scope by caller (InspectionService.list()/get()),
            // same pattern as /ofp above.
            element: <RequireRole roles={QUALITY_VIEWERS} />,
            children: [{ path: '/quality', element: <InspectionListPage /> }, { path: '/quality/:id', element: <InspectionDetailPage /> }],
          },
          {
            element: <RequireRole roles={QUALITY_EDITORS} />,
            children: [{ path: '/quality/new', element: <InspectionCreatePage /> }],
          },
          {
            // NCR/CAPA: same per-caller-scoped list/detail endpoints and role sets as Inspection above (NcrController's
            // VIEWERS/EDITORS are identical to InspectionController's).
            element: <RequireRole roles={QUALITY_VIEWERS} />,
            children: [{ path: '/quality/ncrs', element: <NcrListPage /> }, { path: '/quality/ncrs/:id', element: <NcrDetailPage /> }],
          },
          {
            element: <RequireRole roles={QUALITY_EDITORS} />,
            children: [{ path: '/quality/ncrs/new', element: <NcrCreatePage /> }],
          },
          {
            // Deviations: same per-caller-scoped list/detail endpoints and VIEWERS role set as Inspection/NCR above
            // (DeviationController's VIEWERS is identical); create is its own DEVIATION_EDITORS, which also admits
            // Customer Quality (SRS §10.8 - unlike Inspection/NCR, a customer may raise its own deviation).
            element: <RequireRole roles={QUALITY_VIEWERS} />,
            children: [{ path: '/quality/deviations', element: <DeviationListPage /> }, { path: '/quality/deviations/:id', element: <DeviationDetailPage /> }],
          },
          {
            element: <RequireRole roles={DEVIATION_EDITORS} />,
            children: [{ path: '/quality/deviations/new', element: <DeviationCreatePage /> }],
          },
          {
            // SRS §10.10: every role may open the Communication Center; the backend returns only the threads it may read.
            element: <RequireRole roles={COMMUNICATION_VIEWERS} />,
            children: [{ path: '/communication', element: <CommunicationCenterPage /> }],
          },
          {
            // SRS §10.9: every role may open the Document Library; the backend scopes which documents it returns.
            element: <RequireRole roles={DOCUMENT_VIEWERS} />,
            children: [{ path: '/documents', element: <DocumentLibraryPage /> }, { path: '/documents/:id', element: <DocumentDetailPage /> }],
          },
          {
            element: <RequireRole roles={COMPANY_MANAGERS} />,
            children: [{ path: '/companies', element: <CompaniesPage /> }],
          },
          { element: <RequireRole roles={EXTERNAL_ROLES} />, children: [{ path: '/company', element: <MyCompanyPage /> }] },
          {
            element: <RequireRole roles={['ERGO_ADMIN']} />,
            children: [
              { path: '/admin/users', element: <UsersPage /> },
              { path: '/admin/permissions', element: <PermissionMatrixPage /> },
            ],
          },
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
