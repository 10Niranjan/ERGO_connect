import { api } from '../../app/store'
import type { CompanyType, Role, RoleOption } from '../../auth/authSlice'

export interface User {
  id: number
  companyId: number
  companyName: string
  companyType: CompanyType
  firstName: string
  lastName: string
  email: string
  mobile: string | null
  active: boolean
  roles: Role[]
  defaultRole: Role | null
  lastLoginAt: string | null
}

export interface UserProfile {
  firstName: string
  lastName: string
  email: string
  mobile: string | null
}

export interface Company {
  id: number
  code: string
  name: string
  type: CompanyType
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
  active: boolean
}

export type CompanyInput = Omit<Company, 'id'>

const adminApi = api.enhanceEndpoints({ addTagTypes: ['User', 'Company'] }).injectEndpoints({
  endpoints: (build) => ({
    users: build.query<User[], void>({ query: () => '/users', providesTags: ['User'] }),
    roles: build.query<RoleOption[], void>({ query: () => '/roles' }),
    createUser: build.mutation<
      { user: User; temporaryPassword: string },
      UserProfile & { companyId: number; roles: Role[]; defaultRole: Role | null }
    >({
      query: (body) => ({ url: '/users', method: 'POST', body }),
      invalidatesTags: ['User'],
    }),
    updateUser: build.mutation<User, UserProfile & { id: number }>({
      query: ({ id, ...body }) => ({ url: `/users/${id}`, method: 'PUT', body }),
      invalidatesTags: ['User'],
    }),
    setUserRoles: build.mutation<User, { id: number; roles: Role[]; defaultRole: Role | null }>({
      query: ({ id, ...body }) => ({ url: `/users/${id}/roles`, method: 'PUT', body }),
      invalidatesTags: ['User'],
    }),
    setUserActive: build.mutation<User, { id: number; active: boolean }>({
      query: ({ id, ...body }) => ({ url: `/users/${id}/active`, method: 'PUT', body }),
      invalidatesTags: ['User'],
    }),
    resetUserPassword: build.mutation<{ temporaryPassword: string }, number>({
      query: (id) => ({ url: `/users/${id}/reset-password`, method: 'POST' }),
      invalidatesTags: ['User'],
    }),
    companies: build.query<Company[], void>({ query: () => '/companies', providesTags: ['Company'] }),
    createCompany: build.mutation<Company, CompanyInput>({
      query: (body) => ({ url: '/companies', method: 'POST', body }),
      invalidatesTags: ['Company'],
    }),
    updateCompany: build.mutation<Company, Company>({
      query: ({ id, ...body }) => ({ url: `/companies/${id}`, method: 'PUT', body }),
      invalidatesTags: ['Company', 'User'], // user rows show company names
    }),
  }),
})

export const {
  useUsersQuery,
  useRolesQuery,
  useCreateUserMutation,
  useUpdateUserMutation,
  useSetUserRolesMutation,
  useSetUserActiveMutation,
  useResetUserPasswordMutation,
  useCompaniesQuery,
  useCreateCompanyMutation,
  useUpdateCompanyMutation,
} = adminApi
