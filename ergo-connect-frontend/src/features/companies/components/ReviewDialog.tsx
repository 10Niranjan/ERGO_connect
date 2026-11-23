import { Alert, Button, Dialog, DialogActions, DialogContent, DialogTitle, TextField } from '@mui/material'
import type { FormEvent } from 'react'
import { problemMessage } from '../../../shared/problem'
import { useReviewCompanyMutation, type Company, type ReviewDecision } from '../companiesApi'

const TEXT: Record<ReviewDecision, { title: string; field: string; button: string; color: 'primary' | 'error' }> = {
  approve: { title: 'Approve', field: 'Comment (optional)', button: 'Approve', color: 'primary' },
  reject: { title: 'Reject', field: 'Reason for rejection', button: 'Reject', color: 'error' },
  'request-info': { title: 'Ask for more information on', field: 'What information is needed?', button: 'Send back', color: 'primary' },
}

/**
 * ERGO Management approves, rejects or sends a pending company back for more information (client question 13). The
 * comment is stored in the audit trail with the decision (SRS §11.4) and shown to ERGO on the company.
 */
export function ReviewDialog({ company, decision, onClose }: { company: Company; decision: ReviewDecision; onClose: () => void }) {
  const [reviewCompany, { isLoading, error }] = useReviewCompanyMutation()
  const text = TEXT[decision]

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const comment = String(new FormData(event.currentTarget).get('comment') ?? '')
    try {
      await reviewCompany({ id: company.id, decision, comment }).unwrap()
      onClose()
    } catch {
      // shown below
    }
  }

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="xs">
      <form onSubmit={handleSubmit}>
        <DialogTitle>
          {text.title} {company.profile.name}
        </DialogTitle>
        <DialogContent sx={{ display: 'grid', gap: 2, pt: '8px !important' }}>
          <TextField
            name="comment"
            label={text.field}
            required={decision !== 'approve'}
            multiline
            minRows={3}
            autoFocus
            slotProps={{ htmlInput: { maxLength: 1000 } }}
          />
          {error && <Alert severity="error">{problemMessage(error)}</Alert>}
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose}>Cancel</Button>
          <Button type="submit" variant="contained" color={text.color} loading={isLoading}>
            {text.button}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  )
}
