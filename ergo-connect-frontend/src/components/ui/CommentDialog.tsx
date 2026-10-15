import { Alert, Button, Dialog, DialogActions, DialogContent, DialogTitle, TextField, Typography } from '@mui/material'
import type { FormEvent } from 'react'
import { problemMessage } from '@/utils/problem'

interface Props {
  title: string
  label: string
  /** Workflow actions such as reject, re-open or cancel need a reason (SRS §14.1). */
  required: boolean
  confirmLabel: string
  intro?: string
  danger?: boolean
  busy: boolean
  error?: unknown
  onConfirm: (comment: string) => void
  onClose: () => void
}

/** Asks for the comment or reason that goes into the audit trail with a workflow action. */
export function CommentDialog({ title, label, required, confirmLabel, intro, danger, busy, error, onConfirm, onClose }: Props) {
  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    onConfirm(String(new FormData(event.currentTarget).get('comment') ?? '').trim())
  }

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <form onSubmit={handleSubmit}>
        <DialogTitle>{title}</DialogTitle>
        <DialogContent sx={{ display: 'grid', gap: 2, pt: '8px !important' }}>
          {intro && <Typography color="text.secondary">{intro}</Typography>}
          <TextField
            name="comment"
            label={label}
            required={required}
            multiline
            minRows={3}
            autoFocus
            slotProps={{ htmlInput: { maxLength: 2000 } }}
          />
          {error != null && <Alert severity="error">{problemMessage(error)}</Alert>}
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose}>Cancel</Button>
          <Button type="submit" variant="contained" color={danger ? 'error' : 'primary'} loading={busy}>
            {confirmLabel}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  )
}
