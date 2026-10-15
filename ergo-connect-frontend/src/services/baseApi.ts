import {
  createApi,
  fetchBaseQuery,
  type BaseQueryFn,
  type FetchArgs,
  type FetchBaseQueryError,
} from '@reduxjs/toolkit/query/react'
import { API_BASE_URL } from '@/constants/config'
import { sessionEnded, sessionStarted, type AuthState, type Session } from '@/features/auth/store/authSlice'

const rawBaseQuery = fetchBaseQuery({
  baseUrl: API_BASE_URL,
  prepareHeaders: (headers, { getState }) => {
    const token = (getState() as { auth: AuthState }).auth?.session?.accessToken
    if (token) headers.set('Authorization', `Bearer ${token}`)
    headers.set('X-Ergo-Client', 'web') // audit trail channel (UI vs API)
    return headers
  },
})

const NO_RETRY = ['/auth/login', '/auth/refresh', '/auth/logout']

// Requests that hit an expired access token share one refresh call, then retry once.
let refreshing: Promise<boolean> | null = null

export const baseQuery: BaseQueryFn<string | FetchArgs, unknown, FetchBaseQueryError> = async (args, api, extra) => {
  const result = await rawBaseQuery(args, api, extra)
  const url = typeof args === 'string' ? args : args.url
  if (result.error?.status !== 401 || NO_RETRY.includes(url)) return result

  refreshing ??= Promise.resolve(rawBaseQuery({ url: '/auth/refresh', method: 'POST' }, api, extra))
    .then((refreshed) => {
      if (refreshed.data) api.dispatch(sessionStarted(refreshed.data as Session))
      else api.dispatch(sessionEnded())
      return Boolean(refreshed.data)
    })
    .finally(() => {
      refreshing = null
    })
  return (await refreshing) ? rawBaseQuery(args, api, extra) : result
}

// RTK Query root. Each feature adds its endpoints with api.injectEndpoints().
export const api = createApi({
  baseQuery,
  endpoints: () => ({}),
})
