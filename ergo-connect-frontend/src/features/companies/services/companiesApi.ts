import { api } from '@/services/baseApi'
import type { CompanyType } from '@/features/auth/store/authSlice'

export type CompanyStatus = 'DRAFT' | 'PENDING_APPROVAL' | 'INFO_REQUESTED' | 'ACTIVE' | 'INACTIVE' | 'REJECTED'

/** Computed by the backend for the current user, role and company status. The UI shows exactly these. */
export type CompanyAction =
  | 'EDIT'
  | 'SEND_FOR_APPROVAL'
  | 'APPROVE'
  | 'REJECT'
  | 'REQUEST_INFO'
  | 'ACTIVATE'
  | 'DEACTIVATE'
  | 'ADD_USER'

export interface CompanyProfile {
  name: string
  addressLine1: string | null
  addressLine2: string | null
  city: string | null
  state: string | null
  country: string | null
  postalCode: string | null
  taxId: string | null
  website: string | null
  primaryContactName: string | null
  primaryContactEmail: string | null
  primaryContactPhone: string | null
  /** Prefix of this customer's RFQ numbers (IC in IC-2026-001); ERGO sets it, null = initials of the name. */
  rfqPrefix: string | null
}

export interface Company {
  id: number
  code: string
  type: CompanyType
  status: CompanyStatus
  profile: CompanyProfile
  actions: CompanyAction[]
  /** ERGO only: Management's latest review comment (its question when it asked for more information). */
  reviewComment?: string | null
  /** ERGO only: what is still needed before the company can be sent for approval (client question 15). */
  missing?: string[]
}

export interface CompanyFilter {
  type?: CompanyType
  status?: CompanyStatus
}

/** ERGO Management's three decisions on a pending company (client question 13); also the endpoint names. */
export type ReviewDecision = 'approve' | 'reject' | 'request-info'

export const TYPE_LABELS: Record<CompanyType, string> = { ERGO: 'ERGO', CUSTOMER: 'Customer', SUPPLIER: 'Supplier' }

export const STATUS_COLORS: Record<CompanyStatus, 'default' | 'success' | 'warning' | 'error' | 'info'> = {
  DRAFT: 'default',
  PENDING_APPROVAL: 'warning',
  INFO_REQUESTED: 'info',
  ACTIVE: 'success',
  INACTIVE: 'default',
  REJECTED: 'error',
}

export const STATUS_LABELS: Record<CompanyStatus, string> = {
  DRAFT: 'Draft',
  PENDING_APPROVAL: 'Pending approval',
  INFO_REQUESTED: 'More information requested',
  ACTIVE: 'Active',
  INACTIVE: 'Inactive',
  REJECTED: 'Rejected',
}

const companiesApi = api.enhanceEndpoints({ addTagTypes: ['Company', 'User'] }).injectEndpoints({
  endpoints: (build) => ({
    // the approved supplier list is { type: 'SUPPLIER', status: 'ACTIVE' }; the backend narrows within the caller's scope
    companies: build.query<Company[], CompanyFilter | void>({
      query: (filter) => ({ url: '/companies', params: filter ?? undefined }),
      providesTags: ['Company'],
    }),
    // the company number (CUS-00001 / SUP-00001) is given by the backend
    createCompany: build.mutation<Company, { type: CompanyType; profile: CompanyProfile }>({
      query: (body) => ({ url: '/companies', method: 'POST', body }),
      invalidatesTags: ['Company', 'User'], // a KAM's new customer is assigned to them
    }),
    updateCompany: build.mutation<Company, { id: number; profile: CompanyProfile }>({
      query: ({ id, profile }) => ({ url: `/companies/${id}`, method: 'PUT', body: profile }),
      invalidatesTags: ['Company', 'User'], // user rows show company names
    }),
    reviewCompany: build.mutation<Company, { id: number; decision: ReviewDecision; comment: string }>({
      query: ({ id, decision, comment }) => ({ url: `/companies/${id}/${decision}`, method: 'POST', body: { comment } }),
      invalidatesTags: ['Company'],
    }),
    // a draft, a company sent back for more information or a rejected one goes (again) to ERGO Management
    sendCompanyForApproval: build.mutation<Company, number>({
      query: (id) => ({ url: `/companies/${id}/send-for-approval`, method: 'POST' }),
      invalidatesTags: ['Company'],
    }),
    setCompanyActive: build.mutation<Company, { id: number; active: boolean }>({
      query: ({ id, active }) => ({ url: `/companies/${id}/active`, method: 'PUT', body: { active } }),
      invalidatesTags: ['Company'],
    }),
  }),
})

export const {
  useCompaniesQuery,
  useCreateCompanyMutation,
  useUpdateCompanyMutation,
  useReviewCompanyMutation,
  useSendCompanyForApprovalMutation,
  useSetCompanyActiveMutation,
} = companiesApi
