import { createBrowserRouter, Navigate } from 'react-router'
import {
  ChangePasswordPage,
  ForgotPasswordPage,
  LoginPage,
  ResetPasswordPage,
  RoleSelectPage,
  VerifyOtpPage,
  RequireAuth,
} from '@/features/auth'
import {
  ASSIGNED_RFQ_VIEW,
  COMMUNICATION_VIEW,
  COMPANIES_VIEW,
  SUPPLIERS_VIEW,
  COMPANY_PROFILE_VIEW,
  DEVIATION_CREATE,
  DOCUMENTS_VIEW,
  MASTER_DATA_VIEW,
  NPD_ERGO_VIEW,
  NPD_MINE_VIEW,
  NPD_VIEW,
  SAMPLE_PO_VIEW,
  PRODUCTION_PO_VIEW,
  OFP_CREATE,
  OFP_VIEW,
  PERMISSIONS_VIEW,
  QUALITY_CREATE,
  QUALITY_VIEW,
  QUOTE_VIEW,
  RFQ_CREATE,
  RFQ_VIEW,
  RequirePermission,
  USERS_VIEW,
} from '@/features/permissions'
import { MasterDataPage } from '@/features/admin/pages/MasterDataPage'
import { RequireRole } from '@/features/auth/components/RequireRole'
import { AppearancePage } from '@/features/admin/pages/AppearancePage'
import { PermissionManagementPage } from '@/features/admin/pages/PermissionManagementPage'
import { UsersPage } from '@/features/admin/pages/UsersPage'
import { CompaniesPage } from '@/features/companies/pages/CompaniesPage'
import { SuppliersPage } from '@/features/companies/pages/SuppliersPage'
import { MyCompanyPage } from '@/features/companies/pages/MyCompanyPage'
import { CommunicationCenterPage } from '@/features/communication/pages/CommunicationCenterPage'
import { DashboardPage } from '@/features/dashboard/pages/DashboardPage'
import { DocumentDetailPage } from '@/features/documents/pages/DocumentDetailPage'
import { DocumentLibraryPage } from '@/features/documents/pages/DocumentLibraryPage'
import { SamplePoDetailPage } from '@/features/npd/pages/SamplePoDetailPage'
import { SamplePoListPage } from '@/features/npd/pages/SamplePoListPage'
import { ProductionPoDetailPage } from '@/features/ofp/pages/ProductionPoDetailPage'
import { ProductionPoListPage } from '@/features/ofp/pages/ProductionPoListPage'
import { MyNpdProjectsPage } from '@/features/npd/pages/MyNpdProjectsPage'
import { NpdDetailPage } from '@/features/npd/pages/NpdDetailPage'
import { NpdListPage } from '@/features/npd/pages/NpdListPage'
import { OrderCreatePage } from '@/features/ofp/pages/OrderCreatePage'
import { OrderDetailPage } from '@/features/ofp/pages/OrderDetailPage'
import { OrderListPage } from '@/features/ofp/pages/OrderListPage'
import { DeviationCreatePage } from '@/features/quality/pages/DeviationCreatePage'
import { DeviationDetailPage } from '@/features/quality/pages/DeviationDetailPage'
import { DeviationListPage } from '@/features/quality/pages/DeviationListPage'
import { InspectionCreatePage } from '@/features/quality/pages/InspectionCreatePage'
import { InspectionDetailPage } from '@/features/quality/pages/InspectionDetailPage'
import { InspectionListPage } from '@/features/quality/pages/InspectionListPage'
import { NcrCreatePage } from '@/features/quality/pages/NcrCreatePage'
import { NcrDetailPage } from '@/features/quality/pages/NcrDetailPage'
import { NcrListPage } from '@/features/quality/pages/NcrListPage'
import { AssignedRfqsPage } from '@/features/quotation/pages/AssignedRfqsPage'
import { QuotePage } from '@/features/quotation/pages/QuotePage'
import { RfqDetailPage } from '@/features/rfq/pages/RfqDetailPage'
import { RfqEditPage } from '@/features/rfq/pages/RfqEditPage'
import { RfqListPage } from '@/features/rfq/pages/RfqListPage'
import { MainLayout as AppShell } from '@/layouts'

// Route guards only hide screens (from the permissions the backend sends for the active role); the backend enforces
// every permission and record scope itself.
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
            element: <RequirePermission rules={RFQ_VIEW} />,
            children: [
              { path: '/rfqs', element: <RfqListPage /> },
              { path: '/rfqs/:id', element: <RfqDetailPage /> },
            ],
          },
          {
            element: <RequirePermission rules={RFQ_CREATE} />,
            children: [
              { path: '/rfqs/new', element: <RfqEditPage /> },
              { path: '/rfqs/:id/edit', element: <RfqEditPage /> },
            ],
          },
          { element: <RequirePermission rules={ASSIGNED_RFQ_VIEW} />, children: [{ path: '/quotes', element: <AssignedRfqsPage /> }] },
          { element: <RequirePermission rules={QUOTE_VIEW} />, children: [{ path: '/quotes/:id', element: <QuotePage /> }] },
          {
            element: <RequirePermission rules={SAMPLE_PO_VIEW} />,
            children: [
              { path: '/npd/sample-pos', element: <SamplePoListPage /> },
              { path: '/npd/sample-pos/:id', element: <SamplePoDetailPage /> },
            ],
          },
          {
            element: <RequirePermission rules={PRODUCTION_PO_VIEW} />,
            children: [
              { path: '/ofp/production-pos', element: <ProductionPoListPage /> },
              { path: '/ofp/production-pos/:id', element: <ProductionPoDetailPage /> },
            ],
          },
          {
            element: <RequirePermission rules={NPD_ERGO_VIEW} />,
            children: [
              { path: '/npd', element: <NpdListPage /> },
            ],
          },
          {
            element: <RequirePermission rules={NPD_MINE_VIEW} />,
            children: [{ path: '/npd/mine', element: <MyNpdProjectsPage /> }],
          },
          {
            element: <RequirePermission rules={NPD_VIEW} />,
            children: [{ path: '/npd/:id', element: <NpdDetailPage /> }],
          },
          {
            // /ofp's own single list/detail endpoints already scope by caller, so one route set serves every
            // audience (ERGO, the four supplier roles and Customer OFP) rather than NPD's separate ERGO/"mine" split.
            element: <RequirePermission rules={OFP_VIEW} />,
            children: [{ path: '/ofp', element: <OrderListPage /> }],
          },
          {
            element: <RequirePermission rules={OFP_CREATE} />,
            children: [{ path: '/ofp/new', element: <OrderCreatePage /> }],
          },
          {
            element: <RequirePermission rules={OFP_VIEW} />,
            children: [{ path: '/ofp/:id', element: <OrderDetailPage /> }],
          },
          {
            // /quality's own single list/detail endpoints already scope by caller (InspectionService.list()/get()),
            // same pattern as /ofp above.
            element: <RequirePermission rules={QUALITY_VIEW} />,
            children: [{ path: '/quality', element: <InspectionListPage /> }, { path: '/quality/:id', element: <InspectionDetailPage /> }],
          },
          {
            element: <RequirePermission rules={QUALITY_CREATE} />,
            children: [{ path: '/quality/new', element: <InspectionCreatePage /> }],
          },
          {
            // NCR/CAPA: same per-caller-scoped list/detail endpoints and role sets as Inspection above (NcrController's
            // VIEWERS/EDITORS are identical to InspectionController's).
            element: <RequirePermission rules={QUALITY_VIEW} />,
            children: [{ path: '/quality/ncrs', element: <NcrListPage /> }, { path: '/quality/ncrs/:id', element: <NcrDetailPage /> }],
          },
          {
            element: <RequirePermission rules={QUALITY_CREATE} />,
            children: [{ path: '/quality/ncrs/new', element: <NcrCreatePage /> }],
          },
          {
            // Deviations: same per-caller-scoped list/detail endpoints and VIEWERS role set as Inspection/NCR above
            // (DeviationController's VIEWERS is identical); create is its own DEVIATION_EDITORS, which also admits
            // Customer Quality (SRS §10.8 - unlike Inspection/NCR, a customer may raise its own deviation).
            element: <RequirePermission rules={QUALITY_VIEW} />,
            children: [{ path: '/quality/deviations', element: <DeviationListPage /> }, { path: '/quality/deviations/:id', element: <DeviationDetailPage /> }],
          },
          {
            element: <RequirePermission rules={DEVIATION_CREATE} />,
            children: [{ path: '/quality/deviations/new', element: <DeviationCreatePage /> }],
          },
          {
            // SRS §10.10: every role may open the Communication Center; the backend returns only the threads it may read.
            element: <RequirePermission rules={COMMUNICATION_VIEW} />,
            children: [{ path: '/communication', element: <CommunicationCenterPage /> }],
          },
          {
            // SRS §10.9: every role may open the Document Library; the backend scopes which documents it returns.
            element: <RequirePermission rules={DOCUMENTS_VIEW} />,
            children: [{ path: '/documents', element: <DocumentLibraryPage /> }, { path: '/documents/:id', element: <DocumentDetailPage /> }],
          },
          {
            element: <RequirePermission rules={COMPANIES_VIEW} />,
            children: [{ path: '/companies', element: <CompaniesPage /> }],
          },
          {
            element: <RequirePermission rules={SUPPLIERS_VIEW} />,
            children: [{ path: '/suppliers', element: <SuppliersPage /> }],
          },
          { element: <RequirePermission rules={COMPANY_PROFILE_VIEW} />, children: [{ path: '/company', element: <MyCompanyPage /> }] },
          { element: <RequirePermission rules={USERS_VIEW} />, children: [{ path: '/admin/users', element: <UsersPage /> }] },
          {
            element: <RequirePermission rules={PERMISSIONS_VIEW} />,
            children: [
              { path: '/admin/permissions', element: <PermissionManagementPage /> },
            ],
          },
          {
            element: <RequireRole roles={['ERGO_ADMIN']} />,
            children: [{ path: '/admin/appearance', element: <AppearancePage /> }],
          },
          {
            element: <RequirePermission rules={MASTER_DATA_VIEW} />,
            children: [{ path: '/admin/master-data', element: <MasterDataPage /> }],
          },
          { path: '*', element: <Navigate to="/" replace /> },
        ],
      },
    ],
  },
])
