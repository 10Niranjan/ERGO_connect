import LockOutlinedIcon from '@mui/icons-material/LockOutlined'
import MailOutlineIcon from '@mui/icons-material/MailOutlined'
import VisibilityOffOutlinedIcon from '@mui/icons-material/VisibilityOffOutlined'
import VisibilityOutlinedIcon from '@mui/icons-material/VisibilityOutlined'
import { Alert, Box, Button, IconButton, InputAdornment, TextField } from '@mui/material'
import type { FormEvent, KeyboardEvent } from 'react'
import { useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router'
import { CenteredCard } from '@/components/ui/CenteredCard'
import { problemMessage } from '@/utils/problem'
import { useLoginMutation } from '../services/authApi'

// SRS 12.4.1
export function LoginPage() {
  const [login, { isLoading, error }] = useLoginMutation()
  const navigate = useNavigate()
  const from = (useLocation().state as { from?: string } | null)?.from ?? '/'
  const [showPassword, setShowPassword] = useState(false)
  const [capsLock, setCapsLock] = useState(false)

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

  const trackCapsLock = (event: KeyboardEvent) => setCapsLock(event.getModifierState('CapsLock'))

  return (
    <CenteredCard title="Welcome back" subtitle="Sign in to your ERGO Connect account.">
      <Box component="form" onSubmit={handleSubmit} sx={{ display: 'grid', gap: 2 }}>
        <TextField
          name="email"
          type="email"
          label="Email"
          placeholder="you@company.com"
          autoComplete="username"
          required
          autoFocus
          slotProps={{
            input: {
              startAdornment: (
                <InputAdornment position="start">
                  <MailOutlineIcon fontSize="small" sx={{ color: 'grey.500' }} />
                </InputAdornment>
              ),
            },
          }}
        />
        <TextField
          name="password"
          type={showPassword ? 'text' : 'password'}
          label="Password"
          autoComplete="current-password"
          required
          onKeyDown={trackCapsLock}
          onKeyUp={trackCapsLock}
          onBlur={() => setCapsLock(false)}
          helperText={capsLock ? 'Caps Lock is on' : undefined}
          slotProps={{
            input: {
              startAdornment: (
                <InputAdornment position="start">
                  <LockOutlinedIcon fontSize="small" sx={{ color: 'grey.500' }} />
                </InputAdornment>
              ),
              endAdornment: (
                <InputAdornment position="end">
                  <IconButton
                    size="small"
                    edge="end"
                    aria-label={showPassword ? 'Hide password' : 'Show password'}
                    onClick={() => setShowPassword((shown) => !shown)}
                    onMouseDown={(event) => event.preventDefault()}
                  >
                    {showPassword ? <VisibilityOffOutlinedIcon fontSize="small" /> : <VisibilityOutlinedIcon fontSize="small" />}
                  </IconButton>
                </InputAdornment>
              ),
            },
            formHelperText: { sx: { color: 'warning.dark' } },
          }}
        />
        {error && <Alert severity="error">{problemMessage(error)}</Alert>}
        <Button type="submit" variant="contained" size="large" loading={isLoading} fullWidth>
          Sign in
        </Button>
        <Button component={Link} to="/forgot-password" size="small">
          Forgot password?
        </Button>
      </Box>
    </CenteredCard>
  )
}
