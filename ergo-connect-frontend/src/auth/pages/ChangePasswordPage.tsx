import { Alert, Box, Button, TextField } from '@mui/material'
import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router'
import { useAppSelector } from '../../app/store'
import { CenteredCard } from '../../shared/components/CenteredCard'
import { problemMessage } from '../../shared/problem'
import { useChangePasswordMutation } from '../authApi'

export function ChangePasswordPage() {
  const forced = useAppSelector((state) => state.auth.session?.mustChangePassword ?? false)
  const [changePassword, { isLoading, error }] = useChangePasswordMutation()
  const [mismatch, setMismatch] = useState(false)
  const navigate = useNavigate()

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    const newPassword = String(form.get('newPassword'))
    setMismatch(newPassword !== form.get('confirmPassword'))
    if (newPassword !== form.get('confirmPassword')) return
    try {
      const session = await changePassword({
        currentPassword: String(form.get('currentPassword')),
        newPassword,
      }).unwrap()
      navigate(forced && session.roles.length > 1 ? '/select-role' : '/', { replace: true })
    } catch {
      // shown below
    }
  }

  return (
    <CenteredCard
      title="Change password"
      subtitle={forced ? 'Choose your own password before you continue. Other devices will be signed out.' : undefined}
    >
      <Box component="form" onSubmit={handleSubmit} sx={{ display: 'grid', gap: 2 }}>
        <TextField
          name="currentPassword"
          type="password"
          label={forced ? 'Temporary password' : 'Current password'}
          autoComplete="current-password"
          required
          autoFocus
        />
        <TextField
          name="newPassword"
          type="password"
          label="New password"
          autoComplete="new-password"
          helperText="At least 10 characters"
          required
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
          Change password
        </Button>
        {!forced && <Button onClick={() => navigate(-1)}>Cancel</Button>}
      </Box>
    </CenteredCard>
  )
}
