import type { UnknownAction } from '@reduxjs/toolkit'
import { api } from '@/services/baseApi'
import { sessionStarted, type Role } from '../store/authSlice'
import { buildDemoSession, createSignedDemoJwt, DEMO_STORAGE_KEY, type DemoAccount } from './demoAccounts'

/**
 * Starts (or re-roles) a demo session. Like a real login/role switch, it empties the API cache first: data loaded
 * under the previous persona must never show under the next one.
 */
export async function startDemoSession(
  dispatch: (action: UnknownAction) => unknown,
  account: DemoAccount,
  role: Role = account.role,
) {
  const active = role === account.role ? account : { ...account, role }
  const session = buildDemoSession(active, await createSignedDemoJwt(active))
  sessionStorage.setItem(DEMO_STORAGE_KEY, JSON.stringify(session))
  dispatch(api.util.resetApiState())
  dispatch(sessionStarted(session))
}
