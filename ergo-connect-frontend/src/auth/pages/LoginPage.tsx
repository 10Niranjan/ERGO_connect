import { Alert, Box, Button, TextField } from '@mui/material'
import type { FormEvent } from 'react'
import { Link, useLocation, useNavigate } from 'react-router'
import { CenteredCard } from '../../shared/components/CenteredCard'
import { problemMessage } from '../../shared/problem'
import { useLoginMutation } from '../authApi'

// SRS 12.4.1
export function LoginPage() {
  const [login, { isLoading, error }] = useLoginMutation()
  const navigate = useNavigate()
  const from = (useLocation().state as { from?: string } | null)?.from ?? '/'

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    const email = String(form.get('email'))
    try {
      const result = await login({ email, password: String(form.get('password')) }).unwrap()
      if ('otpRequired' in result) {
        navigate('/verify-otp', { replace: true, state: { email, from } })
        return
      }
      const next = result.mustChangePassword ? '/change-password' : result.roles.length > 1 ? '/select-role' : from
      navigate(next, { replace: true })
    } catch {
      // shown below
    }
  }

  return (
    <CenteredCard title="Sign in">
      <Box component="form" onSubmit={handleSubmit} sx={{ display: 'grid', gap: 2 }}>
        <TextField name="email" type="email" label="Email" autoComplete="username" required autoFocus />
        <TextField name="password" type="password" label="Password" autoComplete="current-password" required />
        {error && <Alert severity="error">{problemMessage(error)}</Alert>}
        <Button type="submit" variant="contained" size="large" loading={isLoading}>
          Sign in
        </Button>
        <Button component={Link} to="/forgot-password">
          Forgot password?
        </Button>
      </Box>
    </CenteredCard>
  )
}
