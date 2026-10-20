import { api } from '@/services/baseApi'
import type { CompanyType, Role, RoleOption } from '@/features/auth/store/authSlice'
import type { MasterOption } from '@/utils/masterData'

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

/** SRS §10.x legend: V/C/E/S/A grant codes, X = no access, RV = restricted view. */
export type Grant = 'V' | 'C' | 'E' | 'S' | 'A' | 'X' | 'RV'

export interface GrantCell {
  value: Grant
  footnote: string | null
}

export interface RoleGrant {
  role: Role
  view: GrantCell
  create: GrantCell
  edit: GrantCell
  submit: GrantCell
  approve: GrantCell
}

export interface PermissionModule {
  section: string
  title: string
  roles: RoleGrant[]
  notes: string[]
}

export interface PermissionMatrix {
  legend: { code: Grant; meaning: string }[]
  modules: PermissionModule[]
}

/** Permission Management: stored access level codes. */
export type AccessCode = 'NV' | 'V' | 'A' | 'O'

export interface PermissionCell {
  level: AccessCode
  /** The signed-off default; equal to `level` for a module with no shipped default. */
  defaultLevel: AccessCode
  /** False for a module whose backend checks are still fixed in code. */
  editable: boolean
  /** What the server will accept for this cell. */
  allowed: AccessCode[]
}

export interface PermissionRoleRow {
  code: Role
  name: string
  active: boolean
  cells: Record<string, PermissionCell>
}

export interface PermissionColumn {
  code: string
  label: string
  configurable: boolean
}

export interface EditablePermissions {
  /** Global version; a save must send the one it was based on. */
  version: number
  side: CompanyType
  levels: AccessCode[]
  modules: PermissionColumn[]
  roles: PermissionRoleRow[]
}

export interface PermissionChange {
  role: Role
  module: string
  level: AccessCode
}

export interface PermissionHistoryEntry {
  at: string
  changedBy: string | null
  changedByRole: Role | null
  role: Role
  module: string
  oldLevel: AccessCode
  newLevel: AccessCode
  reason: string | null
}

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

const adminApi = api.enhanceEndpoints({ addTagTypes: ['User', 'MasterData', 'ExchangeRate', 'Permissions'] }).injectEndpoints({
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
    permissionMatrix: build.query<PermissionMatrix, void>({ query: () => '/admin/permission-matrix' }),
    editablePermissions: build.query<EditablePermissions, CompanyType>({
      query: (side) => `/admin/permissions/${side}`,
      providesTags: ['Permissions'],
    }),
    savePermissions: build.mutation<
      EditablePermissions,
      { side: CompanyType; expectedVersion: number; reason: string; changes: PermissionChange[] }
    >({
      query: ({ side, ...body }) => ({ url: `/admin/permissions/${side}`, method: 'PUT', body }),
      invalidatesTags: ['Permissions'],
    }),
    resetPermissions: build.mutation<EditablePermissions, { side: CompanyType; expectedVersion: number; reason: string }>({
      query: ({ side, ...body }) => ({ url: `/admin/permissions/${side}/reset`, method: 'POST', body }),
      invalidatesTags: ['Permissions'],
    }),
    permissionHistory: build.query<PermissionHistoryEntry[], { role?: Role; module?: string } | void>({
      query: (params) => ({ url: '/admin/permissions/history', params: params ?? undefined }),
      providesTags: ['Permissions'],
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
  usePermissionMatrixQuery,
  useEditablePermissionsQuery,
  useSavePermissionsMutation,
  useResetPermissionsMutation,
  usePermissionHistoryQuery,
  useMasterListsQuery,
  useMasterEntriesQuery,
  useCreateMasterEntryMutation,
  useUpdateMasterEntryMutation,
} = adminApi
