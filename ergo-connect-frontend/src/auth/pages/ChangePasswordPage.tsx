import { Alert, Box, Button, FormControlLabel, Switch, TextField } from '@mui/material'
import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router'
import { useAppSelector } from '../../app/store'
import { CenteredCard } from '../../shared/components/CenteredCard'
import { problemMessage } from '../../shared/problem'
import { useChangePasswordMutation, useRefreshMutation, useSetTwoFactorMutation } from '../authApi'

export function ChangePasswordPage() {
  const forced = useAppSelector((state) => state.auth.session?.mustChangePassword ?? false)
  const user = useAppSelector((state) => state.auth.session?.user)
  const isErgoAdmin = useAppSelector((state) => state.auth.session?.roles.some((r) => r.code === 'ERGO_ADMIN') ?? false)
  const [changePassword, { isLoading, error }] = useChangePasswordMutation()
  const [setTwoFactor, { isLoading: twoFactorSaving, error: twoFactorError }] = useSetTwoFactorMutation()
  const [refresh] = useRefreshMutation()
  const [mismatch, setMismatch] = useState(false)
  const [confirmingDisable, setConfirmingDisable] = useState(false)
  const navigate = useNavigate()

  async function handleTwoFactorToggle(enabled: boolean, currentPassword?: string) {
    try {
      await setTwoFactor({ enabled, currentPassword }).unwrap()
      await refresh().unwrap() // picks up the new twoFactorEnabled flag; refresh's own onQueryStarted updates the store
      setConfirmingDisable(false)
    } catch {
      // shown below
    }
  }

  async function handleDisableConfirm(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    await handleTwoFactorToggle(false, String(new FormData(event.currentTarget).get('currentPassword')))
  }

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
      {!forced && user && (
        <Box sx={{ mt: 3, display: 'grid', gap: 1 }}>
          <FormControlLabel
            control={
              <Switch
                checked={user.twoFactorEnabled}
                disabled={twoFactorSaving || (isErgoAdmin && user.twoFactorEnabled)}
                onChange={(event) => (event.target.checked ? handleTwoFactorToggle(true) : setConfirmingDisable(true))}
              />
            }
            label="Email a code at sign-in (two-factor authentication)"
          />
          {isErgoAdmin && <Alert severity="info">ERGO Admin accounts must keep this on.</Alert>}
          {confirmingDisable && (
            <Box component="form" onSubmit={handleDisableConfirm} sx={{ display: 'grid', gap: 1 }}>
              <TextField
                name="currentPassword"
                type="password"
                label="Current password"
                autoComplete="current-password"
                size="small"
                required
                autoFocus
                helperText="Confirm your password to turn this off"
              />
              <Box sx={{ display: 'flex', gap: 1 }}>
                <Button type="submit" variant="outlined" size="small" loading={twoFactorSaving}>
                  Turn off
                </Button>
                <Button size="small" onClick={() => setConfirmingDisable(false)}>
                  Cancel
                </Button>
              </Box>
            </Box>
          )}
          {twoFactorError && <Alert severity="error">{problemMessage(twoFactorError)}</Alert>}
        </Box>
      )}
    </CenteredCard>
  )
}
