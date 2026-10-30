import { Alert, Button, Dialog, DialogActions, DialogContent, DialogContentText, DialogTitle, TextField } from '@mui/material'
import type { FormEvent } from 'react'
import { problemMessage } from '../../../shared/problem'
import { useLoginAsMutation } from '../../auth/services/authApi'
import type { User } from '../adminApi'

/** Super Admin "Login as": a session acting as this user. The reason is stored in the audit trail. */
export function LoginAsDialog({ user, onClose, onStarted }: { user: User; onClose: () => void; onStarted: () => void }) {
  const [loginAs, { isLoading, error }] = useLoginAsMutation()

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const reason = String(new FormData(event.currentTarget).get('reason') ?? '').trim()
    try {
      await loginAs({ id: user.id, reason }).unwrap()
      onClose()
      onStarted()
    } catch {
      // shown below
    }
  }

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="xs">
      <form onSubmit={handleSubmit}>
        <DialogTitle>
          Login as {user.firstName} {user.lastName}
        </DialogTitle>
        <DialogContent sx={{ display: 'grid', gap: 2, pt: '8px !important' }}>
          <DialogContentText>
            You will be signed in as {user.email} and can do everything they can. Every change is saved as theirs and the audit
            trail names you as the Super Admin behind it. You cannot change their password or 2FA. It ends after 30 minutes or
            when you press Exit; the start is recorded with your reason.
          </DialogContentText>
          <TextField
            name="reason"
            label="Reason"
            required
            multiline
            minRows={2}
            slotProps={{ htmlInput: { minLength: 5, maxLength: 500 } }}
          />
          {error && <Alert severity="error">{problemMessage(error)}</Alert>}
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose}>Cancel</Button>
          <Button type="submit" variant="contained" loading={isLoading}>
            Log in as this user
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  )
}
