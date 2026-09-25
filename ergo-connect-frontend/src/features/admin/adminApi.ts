import { api } from '../../app/store'
import type { CompanyType, Role, RoleOption } from '../../auth/authSlice'
import type { MasterOption } from '../../shared/masterData'

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
  /** Customer accounts of an ERGO Key Account Manager */
  accountIds: number[]
  lastLoginAt: string | null
}

export interface UserProfile {
  firstName: string
  lastName: string
  email: string
  mobile: string | null
}

export interface MasterField {
  key: string
  label: string
  maxLength: number
  /** Non-empty = required choice */
  choices: string[]
}

export interface MasterList {
  key: string
  title: string
  fields: MasterField[]
}

export interface MasterEntry {
  id: number
  code: string
  name: string
  active: boolean
  fields: Record<string, string | null>
}

export type MasterEntryInput = Omit<MasterEntry, 'id'> & { list: string }

/** 1 `from` = `rate` `to`, from `rateDate` on. */
export interface ExchangeRate {
  id: number
  from: MasterOption
  to: MasterOption
  rateDate: string
  rate: number
  source: string | null
  createdAt: string
}

const adminApi = api.enhanceEndpoints({ addTagTypes: ['User', 'MasterData', 'ExchangeRate'] }).injectEndpoints({
  endpoints: (build) => ({
    users: build.query<User[], void>({ query: () => '/users', providesTags: ['User'] }),
    exchangeRates: build.query<ExchangeRate[], void>({ query: () => '/master-data/exchange-rates', providesTags: ['ExchangeRate'] }),
    // rates feed the comparison's converted prices (tag shared with the comparison query)
    createExchangeRate: build.mutation<ExchangeRate, { fromCurrencyId: number; toCurrencyId: number; rateDate: string; rate: number; source: string | null }>({
      query: (body) => ({ url: '/master-data/exchange-rates', method: 'POST', body }),
      invalidatesTags: ['ExchangeRate'],
    }),
    deleteExchangeRate: build.mutation<void, number>({
      query: (id) => ({ url: `/master-data/exchange-rates/${id}`, method: 'DELETE' }),
      invalidatesTags: ['ExchangeRate'],
    }),
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
    setUserAccounts: build.mutation<User, { id: number; customerIds: number[] }>({
      query: ({ id, ...body }) => ({ url: `/users/${id}/accounts`, method: 'PUT', body }),
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
    masterLists: build.query<MasterList[], void>({ query: () => '/master-data' }),
    masterEntries: build.query<MasterEntry[], string>({
      query: (list) => `/master-data/${list}`,
      providesTags: (_result, _error, list) => [{ type: 'MasterData', id: list }],
    }),
    createMasterEntry: build.mutation<MasterEntry, MasterEntryInput>({
      query: ({ list, ...body }) => ({ url: `/master-data/${list}`, method: 'POST', body }),
      invalidatesTags: (_result, _error, { list }) => [{ type: 'MasterData', id: list }],
    }),
    updateMasterEntry: build.mutation<MasterEntry, MasterEntryInput & { id: number }>({
      query: ({ list, id, ...body }) => ({ url: `/master-data/${list}/${id}`, method: 'PUT', body }),
      invalidatesTags: (_result, _error, { list }) => [{ type: 'MasterData', id: list }],
    }),
  }),
})

export const {
  useExchangeRatesQuery,
  useCreateExchangeRateMutation,
  useDeleteExchangeRateMutation,
  useUsersQuery,
  useRolesQuery,
  useCreateUserMutation,
  useUpdateUserMutation,
  useSetUserRolesMutation,
  useSetUserAccountsMutation,
  useSetUserActiveMutation,
  useResetUserPasswordMutation,
  useMasterListsQuery,
  useMasterEntriesQuery,
  useCreateMasterEntryMutation,
  useUpdateMasterEntryMutation,
} = adminApi
