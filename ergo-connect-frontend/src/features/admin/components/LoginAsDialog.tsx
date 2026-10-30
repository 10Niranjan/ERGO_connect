import { Alert, Button, Dialog, DialogActions, DialogContent, DialogContentText, DialogTitle, TextField } from '@mui/material'
import type { FormEvent } from 'react'
import { problemMessage } from '../../../shared/problem'
import { useLoginAsMutation } from '../../auth/services/authApi'
import type { User } from '../adminApi'

/** Super Admin "Login as": a view-only session as this user. The reason is stored in the audit trail. */
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
            You will see ERGO Connect exactly as {user.email} does. It is view only: nothing can be changed. It ends after
            30 minutes or when you press Exit, and the start is recorded in the audit trail with your reason.
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
            Start viewing
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  )
}
