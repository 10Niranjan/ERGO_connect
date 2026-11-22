import { Alert, Button, Dialog, DialogActions, DialogContent, DialogTitle, Typography } from '@mui/material'
import { problemMessage } from '../../../shared/problem'
import type { QuoteData } from '../quoteApi'
import { QuoteSummary } from './QuoteSummary'

interface Props {
  /** The quote exactly as saved, so what is read here is what ERGO receives. */
  quote: QuoteData
  /** "Resubmit as version 2" or "Submit quote". */
  confirmLabel: string
  busy: boolean
  error: unknown
  onBack: () => void
  onConfirm: () => void
}

/** The supplier's last look at its quote before it is locked and sent to ERGO. */
export function QuotePreviewDialog({ quote, confirmLabel, busy, error, onBack, onConfirm }: Props) {
  return (
    <Dialog open onClose={busy ? undefined : onBack} fullWidth maxWidth="lg">
      <DialogTitle>Preview your quote before submitting</DialogTitle>
      <DialogContent sx={{ display: 'grid', gap: 2 }}>
        <Typography variant="body2" color="text.secondary">
          This is what ERGO will receive. Once submitted the quote is locked; ERGO can send it back if a revision is needed. Go back to edit if anything is wrong.
        </Typography>
        <QuoteSummary quote={quote} />
        {error != null && <Alert severity="error">{problemMessage(error)}</Alert>}
      </DialogContent>
      <DialogActions>
        <Button onClick={onBack} disabled={busy}>
          Back to edit
        </Button>
        <Button variant="contained" onClick={onConfirm} loading={busy}>
          {confirmLabel}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
