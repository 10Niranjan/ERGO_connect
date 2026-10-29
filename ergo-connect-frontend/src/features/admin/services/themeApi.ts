import type { CompanyType } from '@/features/auth/store/authSlice'
import { api } from '@/services/baseApi'
import type { ThemeConfig } from '@/styles/theme'

export interface ThemeAdminView {
  side: CompanyType
  /** Unpublished changes; null when there are none */
  draft: ThemeConfig | null
  /** null = the built-in default look */
  published: ThemeConfig | null
  version: number
}

const themeApi = api.enhanceEndpoints({ addTagTypes: ['MyTheme', 'AdminTheme'] }).injectEndpoints({
  endpoints: (build) => ({
    /** The signed-in user's own side, published only. */
    myTheme: build.query<{ config: ThemeConfig | null }, void>({ query: () => '/theme', providesTags: ['MyTheme'] }),
    adminTheme: build.query<ThemeAdminView, CompanyType>({
      query: (side) => `/admin/themes/${side}`,
      providesTags: (_r, _e, side) => [{ type: 'AdminTheme', id: side }],
    }),
    saveThemeDraft: build.mutation<ThemeAdminView, { side: CompanyType; expectedVersion: number; config: ThemeConfig }>({
      query: ({ side, ...body }) => ({ url: `/admin/themes/${side}/draft`, method: 'PUT', body }),
      invalidatesTags: (_r, _e, { side }) => [{ type: 'AdminTheme', id: side }],
    }),
    publishTheme: build.mutation<ThemeAdminView, { side: CompanyType; expectedVersion: number }>({
      query: ({ side, expectedVersion }) => ({ url: `/admin/themes/${side}/publish`, method: 'POST', body: { expectedVersion } }),
      invalidatesTags: (_r, _e, { side }) => [{ type: 'AdminTheme', id: side }, 'MyTheme'],
    }),
    resetTheme: build.mutation<ThemeAdminView, { side: CompanyType; expectedVersion: number }>({
      query: ({ side, expectedVersion }) => ({ url: `/admin/themes/${side}/reset`, method: 'POST', body: { expectedVersion } }),
      invalidatesTags: (_r, _e, { side }) => [{ type: 'AdminTheme', id: side }, 'MyTheme'],
    }),
  }),
})

export const {
  useMyThemeQuery,
  useAdminThemeQuery,
  useSaveThemeDraftMutation,
  usePublishThemeMutation,
  useResetThemeMutation,
} = themeApi
