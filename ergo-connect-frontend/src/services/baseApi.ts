import {
  createApi,
  fetchBaseQuery,
  type BaseQueryApi,
  type BaseQueryFn,
  type FetchArgs,
  type FetchBaseQueryError,
} from '@reduxjs/toolkit/query/react'
import { API_BASE_URL } from '@/constants/config'
import {
  accessUpdated,
  sessionEnded,
  sessionStarted,
  type AccessView,
  type AuthState,
  type Session,
} from '@/features/auth/store/authSlice'

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

const DEMO_STORAGE_KEY = 'ergo_connect_demo_session'

// ERGO Admin can change permissions at any time. The server stamps every response with the policy version; when it
// differs from ours (or a call is refused), fetch the caller's permissions and sidebar once, so the UI follows.
let refreshingAccess: Promise<void> | null = null

function refreshAccess(api: BaseQueryApi, extra: object): void {
  refreshingAccess ??= Promise.resolve(rawBaseQuery({ url: '/auth/access' }, api, extra))
    .then((res) => {
      if (res.data) api.dispatch(accessUpdated(res.data as AccessView))
    })
    .finally(() => {
      refreshingAccess = null
    })
}

export const baseQuery: BaseQueryFn<string | FetchArgs, unknown, FetchBaseQueryError> = async (args, api, extra) => {
  const result = await rawBaseQuery(args, api, extra)
  const url = typeof args === 'string' ? args : args.url
  const current = (api.getState() as { auth: AuthState }).auth?.session?.access?.version
  const served = Number(result.meta?.response?.headers.get('X-Permissions-Version'))
  if (url !== '/auth/access' && current !== undefined && ((served && served !== current) || result.error?.status === 403)) {
    refreshAccess(api, extra)
  }
  if (result.error?.status !== 401 || NO_RETRY.includes(url)) return result

  // During client demo presentations, never terminate the session on a 401
  const isDemo = typeof window !== 'undefined' && Boolean(sessionStorage.getItem(DEMO_STORAGE_KEY))
  if (isDemo) {
    return result
  }

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
