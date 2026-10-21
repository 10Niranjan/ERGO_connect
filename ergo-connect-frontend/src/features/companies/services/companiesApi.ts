import { api } from '@/services/baseApi'
import type { CompanyType } from '@/features/auth/store/authSlice'

export type CompanyStatus = 'PENDING_APPROVAL' | 'ACTIVE' | 'INACTIVE' | 'REJECTED'

/** Computed by the backend for the current user, role and company status. The UI shows exactly these. */
export type CompanyAction = 'EDIT' | 'APPROVE' | 'REJECT' | 'RESUBMIT' | 'ACTIVATE' | 'DEACTIVATE' | 'ADD_USER'

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
}

export interface Company {
  id: number
  code: string
  type: CompanyType
  status: CompanyStatus
  profile: CompanyProfile
  actions: CompanyAction[]
}

export interface CompanyFilter {
  type?: CompanyType
  status?: CompanyStatus
}

export const TYPE_LABELS: Record<CompanyType, string> = { ERGO: 'ERGO', CUSTOMER: 'Customer', SUPPLIER: 'Supplier' }

export const STATUS_COLORS: Record<CompanyStatus, 'default' | 'success' | 'warning' | 'error'> = {
  PENDING_APPROVAL: 'warning',
  ACTIVE: 'success',
  INACTIVE: 'default',
  REJECTED: 'error',
}

export const STATUS_LABELS: Record<CompanyStatus, string> = {
  PENDING_APPROVAL: 'Pending approval',
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
    createCompany: build.mutation<Company, { code: string; type: CompanyType; profile: CompanyProfile }>({
      query: (body) => ({ url: '/companies', method: 'POST', body }),
      invalidatesTags: ['Company', 'User'], // a KAM's new customer is assigned to them
    }),
    updateCompany: build.mutation<Company, { id: number; profile: CompanyProfile }>({
      query: ({ id, profile }) => ({ url: `/companies/${id}`, method: 'PUT', body: profile }),
      invalidatesTags: ['Company', 'User'], // user rows show company names
    }),
    reviewCompany: build.mutation<Company, { id: number; decision: 'approve' | 'reject'; comment: string }>({
      query: ({ id, decision, comment }) => ({ url: `/companies/${id}/${decision}`, method: 'POST', body: { comment } }),
      invalidatesTags: ['Company'],
    }),
    resubmitCompany: build.mutation<Company, number>({
      query: (id) => ({ url: `/companies/${id}/resubmit`, method: 'POST' }),
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
  useResubmitCompanyMutation,
  useSetCompanyActiveMutation,
} = companiesApi
