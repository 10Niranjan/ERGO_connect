import { Alert, Box, Button, TextField } from '@mui/material'
import { useEffect, useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router'
import { CenteredCard } from '@/components/ui/CenteredCard'
import { problemMessage } from '@/utils/problem'
import { useResetPasswordMutation } from '../services/authApi'

// SRS 12.4.1. The token travels in the URL fragment (#token=...), never the query string, so it's never sent
// to any server in a request line or Referer header (Phase 8f security review).
export function ResetPasswordPage() {
  const [token] = useState(() => new URLSearchParams(window.location.hash.slice(1)).get('token') ?? '')
  const [resetPassword, { isLoading, error }] = useResetPasswordMutation()
  const [mismatch, setMismatch] = useState(false)
  const navigate = useNavigate()

  useEffect(() => {
    // the token is secret and single-use - don't leave it sitting in the address bar/history longer than needed.
    // Pass the existing history.state through (not null) so React Router's own idx/key survive the replace.
    if (window.location.hash) window.history.replaceState(window.history.state, '', window.location.pathname)
  }, [])

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    const newPassword = String(form.get('newPassword'))
    setMismatch(newPassword !== form.get('confirmPassword'))
    if (newPassword !== form.get('confirmPassword')) return
    try {
      await resetPassword({ token, newPassword }).unwrap()
      navigate('/login', { replace: true })
    } catch {
      // shown below
    }
  }

  if (!token) {
    return (
      <CenteredCard title="Reset password">
        <Alert severity="error">This reset link is missing its token.</Alert>
        <Button component={Link} to="/forgot-password">
          Request a new link
        </Button>
      </CenteredCard>
    )
  }

  return (
    <CenteredCard title="Reset password" subtitle="Choose a new password. Every device will be signed out.">
      <Box component="form" onSubmit={handleSubmit} sx={{ display: 'grid', gap: 2 }}>
        <TextField
          name="newPassword"
          type="password"
          label="New password"
          autoComplete="new-password"
          helperText="At least 10 characters"
          required
          autoFocus
          slotProps={{ htmlInput: { minLength: 10, maxLength: 64 } }}
        />
        <TextField
          name="confirmPassword"
          type="password"
          label="Repeat new password"
          autoComplete="new-password"
          required
        />
        {mismatch && <Alert severity="error">The new passwords do not match.</Alert>}
        {error && <Alert severity="error">{problemMessage(error)}</Alert>}
        <Button type="submit" variant="contained" size="large" loading={isLoading}>
          Reset password
        </Button>
      </Box>
    </CenteredCard>
  )
}
