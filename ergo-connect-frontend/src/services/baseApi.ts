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

// A Super Admin's "Login as" session is view-only (the server refuses every change anyway); say so without a round trip.
const VIEW_ONLY_ALLOWED = ['/auth/refresh', '/auth/logout', '/auth/switch-role', '/auth/impersonation/end']

// Requests that hit an expired access token share one refresh call, then retry once.
let refreshing: Promise<boolean> | null = null

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
  const url = typeof args === 'string' ? args : args.url
  const method = (typeof args === 'string' ? 'GET' : (args.method ?? 'GET')).toUpperCase()
  const state = api.getState() as { auth: AuthState }
  if (state.auth?.session?.impersonation && method !== 'GET' && !VIEW_ONLY_ALLOWED.includes(url)) {
    return {
      error: {
        status: 403,
        data: { title: 'Forbidden', status: 403, detail: 'View-only session: nothing can be changed while viewing as another user.' },
      },
    }
  }
  const result = await rawBaseQuery(args, api, extra)
  const current = (api.getState() as { auth: AuthState }).auth?.session?.access?.version
  const served = Number(result.meta?.response?.headers.get('X-Permissions-Version'))
  if (url !== '/auth/access' && current !== undefined && ((served && served !== current) || result.error?.status === 403)) {
    refreshAccess(api, extra)
  }
  if (result.error?.status !== 401 || NO_RETRY.includes(url)) return result

  refreshing ??= Promise.resolve(rawBaseQuery({ url: '/auth/refresh', method: 'POST' }, api, extra))
    .then((refreshed) => {
      if (refreshed.data) {
        const next = refreshed.data as Session
        // a view that ended lands back on the admin: nothing loaded as the other user may stay in the cache
        if ((api.getState() as { auth: AuthState }).auth?.session?.user.id !== next.user.id) {
          api.dispatch(apiRoot.util.resetApiState())
        }
        api.dispatch(sessionStarted(next))
      } else api.dispatch(sessionEnded())
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
const apiRoot = api
