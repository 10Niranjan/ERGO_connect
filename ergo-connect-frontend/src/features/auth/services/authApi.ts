import type { UnknownAction } from '@reduxjs/toolkit'
import { api } from '@/services/baseApi'
import { sessionEnded, sessionStarted, type Role, type Session } from '../store/authSlice'

/** What {@code /login} returns when the account has 2FA enabled, instead of a session (Phase 8g). */
export interface OtpChallenge {
  otpRequired: true
}

function isOtpChallenge(data: Session | OtpChallenge): data is OtpChallenge {
  return 'otpRequired' in data
}

/**
 * A new or changed session starts with an empty cache: data loaded under the previous role must never be shown
 * under the next one.
 */
const startsSession = {
  async onQueryStarted(
    _arg: unknown,
    {
      dispatch,
      queryFulfilled,
    }: { dispatch: (action: UnknownAction) => unknown; queryFulfilled: Promise<{ data: Session | OtpChallenge }> },
  ) {
    try {
      const { data } = await queryFulfilled
      if (isOtpChallenge(data)) return // login isn't complete until verifyOtp() succeeds
      dispatch(api.util.resetApiState())
      dispatch(sessionStarted(data))
    } catch {
      // the calling screen shows the error
    }
  },
}

export const authApi = api.injectEndpoints({
  endpoints: (build) => ({
    login: build.mutation<Session | OtpChallenge, { email: string; password: string }>({
      query: (body) => ({ url: '/auth/login', method: 'POST', body }),
      ...startsSession,
    }),
    verifyOtp: build.mutation<Session, { email: string; code: string }>({
      query: (body) => ({ url: '/auth/verify-otp', method: 'POST', body }),
      ...startsSession,
    }),
    setTwoFactor: build.mutation<void, { enabled: boolean; currentPassword?: string }>({
      query: (body) => ({ url: '/auth/2fa', method: 'POST', body }),
    }),
    switchRole: build.mutation<Session, Role>({
      query: (role) => ({ url: '/auth/switch-role', method: 'POST', body: { role } }),
      ...startsSession,
    }),
    changePassword: build.mutation<Session, { currentPassword: string; newPassword: string }>({
      query: (body) => ({ url: '/auth/change-password', method: 'POST', body }),
      ...startsSession,
    }),
    forgotPassword: build.mutation<void, { email: string }>({
      query: (body) => ({ url: '/auth/forgot-password', method: 'POST', body }),
    }),
    resetPassword: build.mutation<void, { token: string; newPassword: string }>({
      query: (body) => ({ url: '/auth/reset-password', method: 'POST', body }),
    }),
    refresh: build.mutation<Session, void>({
      query: () => ({ url: '/auth/refresh', method: 'POST' }),
      async onQueryStarted(_arg, { dispatch, queryFulfilled }) {
        try {
          dispatch(sessionStarted((await queryFulfilled).data))
        } catch {
          dispatch(sessionEnded())
        }
      },
    }),
    logout: build.mutation<void, void>({
      query: () => ({ url: '/auth/logout', method: 'POST' }),
      async onQueryStarted(_arg, { dispatch, queryFulfilled }) {
        await queryFulfilled.catch(() => undefined) // signed out locally even if the server is unreachable
        dispatch(api.util.resetApiState())
        dispatch(sessionEnded())
      },
    }),
  }),
})

export const {
  useLoginMutation,
  useVerifyOtpMutation,
  useSetTwoFactorMutation,
  useSwitchRoleMutation,
  useChangePasswordMutation,
  useForgotPasswordMutation,
  useResetPasswordMutation,
  useRefreshMutation,
  useLogoutMutation,
} = authApi
