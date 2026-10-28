import { Button, Dialog, DialogActions, DialogContent, DialogContentText, DialogTitle } from '@mui/material'
import { useState, type ReactNode } from 'react'

/** MUI replacement for window.confirm(): `if (!(await confirm('Remove file?'))) return`, and render `dialog` once in the component. */
export function useConfirm(): [confirm: (message: string, confirmLabel?: string) => Promise<boolean>, dialog: ReactNode] {
  const [ask, setAsk] = useState<{ message: string; confirmLabel: string; resolve: (ok: boolean) => void } | null>(null)

  const confirm = (message: string, confirmLabel = 'Confirm') => new Promise<boolean>((resolve) => setAsk({ message, confirmLabel, resolve }))
  const answer = (ok: boolean) => {
    ask?.resolve(ok)
    setAsk(null)
  }

  const dialog = ask && (
    <Dialog open onClose={() => answer(false)} fullWidth maxWidth="xs">
      <DialogTitle>Please confirm</DialogTitle>
      <DialogContent>
        <DialogContentText>{ask.message}</DialogContentText>
      </DialogContent>
      <DialogActions>
        <Button onClick={() => answer(false)}>Cancel</Button>
        <Button variant="contained" autoFocus onClick={() => answer(true)}>
          {ask.confirmLabel}
        </Button>
      </DialogActions>
    </Dialog>
  )
  return [confirm, dialog]
}
