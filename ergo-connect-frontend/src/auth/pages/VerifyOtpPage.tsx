import { Alert, Box, Button, TextField } from '@mui/material'
import type { FormEvent } from 'react'
import { Navigate, useLocation, useNavigate } from 'react-router'
import { CenteredCard } from '../../shared/components/CenteredCard'
import { problemMessage } from '../../shared/problem'
import { useVerifyOtpMutation } from '../authApi'

// SRS 12.4.1: the second step of login once /login responds with { otpRequired: true } (Phase 8g).
export function VerifyOtpPage() {
  const state = useLocation().state as { email?: string; from?: string } | null
  const [verifyOtp, { isLoading, error }] = useVerifyOtpMutation()
  const navigate = useNavigate()

  if (!state?.email) {
    return <Navigate to="/login" replace />
  }
  const { email, from = '/' } = state

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const code = String(new FormData(event.currentTarget).get('code'))
    try {
      const session = await verifyOtp({ email, code }).unwrap()
      const next = session.mustChangePassword ? '/change-password' : session.roles.length > 1 ? '/select-role' : from
      navigate(next, { replace: true })
    } catch {
      // shown below
    }
  }

  return (
    <CenteredCard title="Enter your code" subtitle={`We emailed a 6-digit code to ${email}.`}>
      <Box component="form" onSubmit={handleSubmit} sx={{ display: 'grid', gap: 2 }}>
        <TextField
          name="code"
          label="Code"
          autoComplete="one-time-code"
          required
          autoFocus
          slotProps={{ htmlInput: { pattern: '\\d{6}', maxLength: 6, inputMode: 'numeric' } }}
        />
        {error && <Alert severity="error">{problemMessage(error)}</Alert>}
        <Button type="submit" variant="contained" size="large" loading={isLoading}>
          Verify
        </Button>
        <Button onClick={() => navigate('/login', { replace: true })}>Back to sign in</Button>
      </Box>
    </CenteredCard>
  )
}
