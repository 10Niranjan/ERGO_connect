import { Alert, Box, Button, TextField, Typography } from '@mui/material'
import type { FormEvent } from 'react'
import { Link } from 'react-router'
import { CenteredCard } from '../../shared/components/CenteredCard'
import { problemMessage } from '../../shared/problem'
import { useForgotPasswordMutation } from '../authApi'

// SRS 12.4.1
export function ForgotPasswordPage() {
  const [forgotPassword, { isLoading, error, isSuccess }] = useForgotPasswordMutation()

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    try {
      await forgotPassword({ email: String(form.get('email')) }).unwrap()
    } catch {
      // shown below
    }
  }

  return (
    <CenteredCard title="Forgot password">
      {isSuccess ? (
        <Typography>
          If that email is registered, we&apos;ve sent a link to reset your password. It will expire soon, so use it
          right away.
        </Typography>
      ) : (
        <Box component="form" onSubmit={handleSubmit} sx={{ display: 'grid', gap: 2 }}>
          <Typography color="text.secondary">
            Enter your email and we&apos;ll send you a link to reset your password.
          </Typography>
          <TextField name="email" type="email" label="Email" autoComplete="username" required autoFocus />
          {error && <Alert severity="error">{problemMessage(error)}</Alert>}
          <Button type="submit" variant="contained" size="large" loading={isLoading}>
            Send reset link
          </Button>
        </Box>
      )}
      <Button component={Link} to="/login">
        Back to sign in
      </Button>
    </CenteredCard>
  )
}
