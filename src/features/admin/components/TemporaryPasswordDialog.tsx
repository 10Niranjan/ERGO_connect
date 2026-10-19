import { Button, Dialog, DialogActions, DialogContent, DialogTitle, TextField, Typography } from '@mui/material'
import { useState } from 'react'

/** Shows a one-time password exactly once. It is never stored in plain text and cannot be shown again. */
export function TemporaryPasswordDialog({ email, password, onClose }: { email: string; password: string; onClose: () => void }) {
  const [copied, setCopied] = useState(false)

  return (
    <Dialog open onClose={onClose} maxWidth="xs" fullWidth>
      <DialogTitle>Temporary password</DialogTitle>
      <DialogContent sx={{ display: 'grid', gap: 2 }}>
        <Typography>
          Give this password to <strong>{email}</strong> through a safe channel. It is shown only now, and they must
          choose their own password when they first sign in.
        </Typography>
        <TextField
          label="Temporary password"
          value={password}
          slotProps={{ htmlInput: { readOnly: true, style: { fontFamily: 'monospace', fontSize: 18 } } }}
        />
      </DialogContent>
      <DialogActions>
        <Button onClick={() => void navigator.clipboard.writeText(password).then(() => setCopied(true))}>
          {copied ? 'Copied' : 'Copy'}
        </Button>
        <Button variant="contained" onClick={onClose}>
          Done
        </Button>
      </DialogActions>
    </Dialog>
  )
}
