import { Button, Dialog, DialogActions, DialogContent, DialogTitle, TextField } from '@mui/material'
import type { FormEvent } from 'react'
import { useReviewCompanyMutation, type Company } from '@/features/companies/services/companiesApi'
import { ProblemAlert } from '@/components/ui/ProblemAlert'

/** Approve or reject a pending company. The comment is stored in the audit trail with the decision (SRS §11.4). */
export function ReviewDialog({ company, decision, onClose }: { company: Company; decision: 'approve' | 'reject'; onClose: () => void }) {
  const [reviewCompany, { isLoading, error }] = useReviewCompanyMutation()

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
          {decision === 'approve' ? 'Approve' : 'Reject'} {company.profile.name}
        </DialogTitle>
        <DialogContent sx={{ display: 'grid', gap: 2, pt: '8px !important' }}>
          <TextField
            name="comment"
            label={decision === 'approve' ? 'Comment (optional)' : 'Reason for rejection'}
            required={decision === 'reject'}
            multiline
            minRows={3}
            autoFocus
            slotProps={{ htmlInput: { maxLength: 1000 } }}
          />
          {error && <ProblemAlert error={error} />}
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose}>Cancel</Button>
          <Button type="submit" variant="contained" color={decision === 'approve' ? 'primary' : 'error'} loading={isLoading}>
            {decision === 'approve' ? 'Approve' : 'Reject'}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  )
}
