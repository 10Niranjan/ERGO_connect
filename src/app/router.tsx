import { createBrowserRouter } from 'react-router'
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
  ChangePasswordPage,
  NotFoundPage,
  ForgotPasswordPage,
  LoginPage,
  ResetPasswordPage,
  RoleSelectPage,
  VerifyOtpPage,
  RequireAuth,
  RequireRole,
} from '@/features/auth'
import { MainLayout as AppShell } from '@/layouts'

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
          { index: true, lazy: async () => ({ Component: (await import('@/features/dashboard/pages/DashboardPage')).DashboardPage }) },
          {
            element: <RequireRole roles={RFQ_VIEWERS} />,
            children: [
              { path: '/rfqs', lazy: async () => ({ Component: (await import('@/features/rfq/pages/RfqListPage')).RfqListPage }) },
              { path: '/rfqs/:id', lazy: async () => ({ Component: (await import('@/features/rfq/pages/RfqDetailPage')).RfqDetailPage }) },
            ],
          },
          {
            element: <RequireRole roles={RFQ_CREATORS} />,
            children: [
              { path: '/rfqs/new', lazy: async () => ({ Component: (await import('@/features/rfq/pages/RfqEditPage')).RfqEditPage }) },
              { path: '/rfqs/:id/edit', lazy: async () => ({ Component: (await import('@/features/rfq/pages/RfqEditPage')).RfqEditPage }) },
            ],
          },
          { element: <RequireRole roles={SUPPLIER_QUOTERS} />, children: [{ path: '/quotes', lazy: async () => ({ Component: (await import('@/features/quotation/pages/AssignedRfqsPage')).AssignedRfqsPage }) }] },
          { element: <RequireRole roles={QUOTE_VIEWERS} />, children: [{ path: '/quotes/:id', lazy: async () => ({ Component: (await import('@/features/quotation/pages/QuotePage')).QuotePage }) }] },
          {
            element: <RequireRole roles={NPD_VIEWERS} />,
            children: [
              { path: '/npd', lazy: async () => ({ Component: (await import('@/features/npd/pages/NpdListPage')).NpdListPage }) },
              { path: '/npd/conversion-queue', lazy: async () => ({ Component: (await import('@/features/npd/pages/ConversionQueuePage')).ConversionQueuePage }) },
            ],
          },
          {
            element: <RequireRole roles={[...NPD_CUSTOMER_ROLES, ...NPD_SUPPLIER_ROLES]} />,
            children: [{ path: '/npd/mine', lazy: async () => ({ Component: (await import('@/features/npd/pages/MyNpdProjectsPage')).MyNpdProjectsPage }) }],
          },
          {
            element: <RequireRole roles={NPD_DETAIL_VIEWERS} />,
            children: [{ path: '/npd/:id', lazy: async () => ({ Component: (await import('@/features/npd/pages/NpdDetailPage')).NpdDetailPage }) }],
          },
          {
            // /ofp's own single list/detail endpoints already scope by caller, so one route set serves every
            // audience (ERGO, the four supplier roles and Customer OFP) rather than NPD's separate ERGO/"mine" split.
            element: <RequireRole roles={[...OFP_VIEWERS, ...OFP_SUPPLIER_ROLES, ...OFP_CUSTOMER_ROLES]} />,
            children: [{ path: '/ofp', lazy: async () => ({ Component: (await import('@/features/ofp/pages/OrderListPage')).OrderListPage }) }],
          },
          {
            element: <RequireRole roles={OFP_EDITORS} />,
            children: [{ path: '/ofp/new', lazy: async () => ({ Component: (await import('@/features/ofp/pages/OrderCreatePage')).OrderCreatePage }) }],
          },
          {
            element: <RequireRole roles={OFP_DETAIL_VIEWERS} />,
            children: [{ path: '/ofp/:id', lazy: async () => ({ Component: (await import('@/features/ofp/pages/OrderDetailPage')).OrderDetailPage }) }],
          },
          {
            // /quality's own single list/detail endpoints already scope by caller (InspectionService.list()/get()),
            // same pattern as /ofp above.
            element: <RequireRole roles={QUALITY_VIEWERS} />,
            children: [{ path: '/quality', lazy: async () => ({ Component: (await import('@/features/quality/pages/InspectionListPage')).InspectionListPage }) }, { path: '/quality/:id', lazy: async () => ({ Component: (await import('@/features/quality/pages/InspectionDetailPage')).InspectionDetailPage }) }],
          },
          {
            element: <RequireRole roles={QUALITY_EDITORS} />,
            children: [{ path: '/quality/new', lazy: async () => ({ Component: (await import('@/features/quality/pages/InspectionCreatePage')).InspectionCreatePage }) }],
          },
          {
            // NCR/CAPA: same per-caller-scoped list/detail endpoints and role sets as Inspection above (NcrController's
            // VIEWERS/EDITORS are identical to InspectionController's).
            element: <RequireRole roles={QUALITY_VIEWERS} />,
            children: [{ path: '/quality/ncrs', lazy: async () => ({ Component: (await import('@/features/quality/pages/NcrListPage')).NcrListPage }) }, { path: '/quality/ncrs/:id', lazy: async () => ({ Component: (await import('@/features/quality/pages/NcrDetailPage')).NcrDetailPage }) }],
          },
          {
            element: <RequireRole roles={QUALITY_EDITORS} />,
            children: [{ path: '/quality/ncrs/new', lazy: async () => ({ Component: (await import('@/features/quality/pages/NcrCreatePage')).NcrCreatePage }) }],
          },
          {
            // Deviations: same per-caller-scoped list/detail endpoints and VIEWERS role set as Inspection/NCR above
            // (DeviationController's VIEWERS is identical); create is its own DEVIATION_EDITORS, which also admits
            // Customer Quality (SRS §10.8 - unlike Inspection/NCR, a customer may raise its own deviation).
            element: <RequireRole roles={QUALITY_VIEWERS} />,
            children: [{ path: '/quality/deviations', lazy: async () => ({ Component: (await import('@/features/quality/pages/DeviationListPage')).DeviationListPage }) }, { path: '/quality/deviations/:id', lazy: async () => ({ Component: (await import('@/features/quality/pages/DeviationDetailPage')).DeviationDetailPage }) }],
          },
          {
            element: <RequireRole roles={DEVIATION_EDITORS} />,
            children: [{ path: '/quality/deviations/new', lazy: async () => ({ Component: (await import('@/features/quality/pages/DeviationCreatePage')).DeviationCreatePage }) }],
          },
          {
            // SRS §10.10: every role may open the Communication Center; the backend returns only the threads it may read.
            element: <RequireRole roles={COMMUNICATION_VIEWERS} />,
            children: [{ path: '/communication', lazy: async () => ({ Component: (await import('@/features/communication/pages/CommunicationCenterPage')).CommunicationCenterPage }) }],
          },
          {
            // SRS §10.9: every role may open the Document Library; the backend scopes which documents it returns.
            element: <RequireRole roles={DOCUMENT_VIEWERS} />,
            children: [{ path: '/documents', lazy: async () => ({ Component: (await import('@/features/documents/pages/DocumentLibraryPage')).DocumentLibraryPage }) }, { path: '/documents/:id', lazy: async () => ({ Component: (await import('@/features/documents/pages/DocumentDetailPage')).DocumentDetailPage }) }],
          },
          {
            element: <RequireRole roles={COMPANY_MANAGERS} />,
            children: [{ path: '/companies', lazy: async () => ({ Component: (await import('@/features/companies/pages/CompaniesPage')).CompaniesPage }) }],
          },
          { element: <RequireRole roles={EXTERNAL_ROLES} />, children: [{ path: '/company', lazy: async () => ({ Component: (await import('@/features/companies/pages/MyCompanyPage')).MyCompanyPage }) }] },
          {
            element: <RequireRole roles={['ERGO_ADMIN']} />,
            children: [
              { path: '/admin/users', lazy: async () => ({ Component: (await import('@/features/admin/pages/UsersPage')).UsersPage }) },
              { path: '/admin/permissions', lazy: async () => ({ Component: (await import('@/features/admin/pages/PermissionMatrixPage')).PermissionMatrixPage }) },
            ],
          },
          {
            element: <RequireRole roles={MASTER_DATA_VIEWERS} />,
            children: [{ path: '/admin/master-data', lazy: async () => ({ Component: (await import('@/features/admin/pages/MasterDataPage')).MasterDataPage }) }],
          },
          { path: '*', element: <NotFoundPage /> },
        ],
      },
    ],
  },
])
