import { Alert, Button, Dialog, DialogActions, DialogContent, DialogContentText, DialogTitle, TextField } from '@mui/material'
import type { FormEvent } from 'react'
import { problemMessage } from '../../../shared/problem'
import { useDeleteUserMutation, type User } from '../adminApi'

/** Deleting an account needs a reason; it is stored in the audit trail with the deletion. */
export function DeleteUserDialog({ user, onClose }: { user: User; onClose: () => void }) {
  const [deleteUser, { isLoading, error }] = useDeleteUserMutation()

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const reason = String(new FormData(event.currentTarget).get('reason') ?? '').trim()
    try {
      await deleteUser({ id: user.id, reason }).unwrap()
      onClose()
    } catch {
      // shown below
    }
  }

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="xs">
      <form onSubmit={handleSubmit}>
        <DialogTitle>
          Delete {user.firstName} {user.lastName}
        </DialogTitle>
        <DialogContent sx={{ display: 'grid', gap: 2, pt: '8px !important' }}>
          <DialogContentText>
            {user.email} is signed out and can never sign in again. The audit trail is kept. This cannot be undone.
          </DialogContentText>
          <TextField name="reason" label="Reason for deleting" required multiline minRows={2} slotProps={{ htmlInput: { maxLength: 500 } }} />
          {error && <Alert severity="error">{problemMessage(error)}</Alert>}
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose}>Cancel</Button>
          <Button type="submit" color="error" variant="contained" loading={isLoading}>
            Delete user
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  )
}
