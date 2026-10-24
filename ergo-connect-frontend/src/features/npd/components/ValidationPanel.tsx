import { Alert, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle, MenuItem, Paper, Stack, TextField, Typography } from '@mui/material'
import { useState } from 'react'
import { CommentDialog } from '@/components/ui/CommentDialog'
import { problemMessage } from '@/utils/problem'
import { formatDate } from '../../rfq/labels'
import { NPD_STAGE, NPD_STAGE_ORDER, VALIDATION_PARTY, VALIDATION_STATUS } from '../utils/labels'
import {
  useDecideNpdValidationMutation,
  useSubmitNpdValidationMutation,
  type NpdStage,
  type NpdView,
  type ValidationDecisionView,
} from '../services/npdApi'

/** Development stages a rejected project can go back to: Planning up to, not including, Final Sample Readiness. */
const RETURN_STAGES = NPD_STAGE_ORDER.filter((s) => s !== 'PENDING_CONVERSION' && NPD_STAGE_ORDER.indexOf(s) < NPD_STAGE_ORDER.indexOf('FINAL_SAMPLE_READINESS'))

/**
 * Flow doc steps 9-10: Supplier Quality approves internally and submits, ERGO Quality and Supplier Quality both approve,
 * then Customer NPD and Customer Quality both approve. A rejection sends the project back with the reason. What each
 * audience sees of the history is decided by the backend; every button is one it listed in `actions`.
 */
export function ValidationPanel({ npd }: { npd: NpdView }) {
  const v = npd.validation
  const [dialog, setDialog] = useState<'submit' | 'approve' | 'reject' | null>(null)
  const [submit, submitting] = useSubmitNpdValidationMutation()
  const [decide, deciding] = useDecideNpdValidationMutation()
  const canSubmit = npd.actions.includes('SUBMIT_VALIDATION')
  const canDecide = npd.actions.includes('DECIDE_VALIDATION')
  const reachedFinalStage = NPD_STAGE_ORDER.indexOf(npd.stage) >= NPD_STAGE_ORDER.indexOf('FINAL_SAMPLE_READINESS')
  if (!reachedFinalStage && v.decisions.length === 0 && !canSubmit && !canDecide) return null
  const status = VALIDATION_STATUS[v.status]
  // The rejection form asks for the development stage to return to only in the quality validation (not the customer's review).
  const customerPhase = v.status === 'IN_CUSTOMER_REVIEW'

  async function doSubmit(comment: string) {
    try {
      await submit({ id: npd.id, comment: comment || undefined }).unwrap()
      setDialog(null)
    } catch {
      // shown in the dialog
    }
  }

  async function doApprove(comment: string) {
    try {
      await decide({ id: npd.id, form: { approved: true, comment: comment || null, returnStage: null } }).unwrap()
      setDialog(null)
    } catch {
      // shown in the dialog
    }
  }

  return (
    <Paper variant="outlined" sx={{ p: 2 }}>
      <Stack spacing={1.5}>
        <Stack direction="row" spacing={1} useFlexGap sx={{ alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap' }}>
          <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
            <Typography variant="subtitle1" sx={{ fontWeight: 600 }}>
              Quality validation and final approval
            </Typography>
            <Chip size="small" label={status.label} color={status.color} />
            <Typography variant="caption" color="text.secondary">
              Round {v.round}
            </Typography>
          </Stack>
          <Stack direction="row" spacing={1}>
            {canSubmit && (
              <Button variant="contained" onClick={() => setDialog('submit')}>
                Approve internally and submit
              </Button>
            )}
            {canDecide && (
              <>
                <Button color="error" onClick={() => setDialog('reject')}>
                  Reject
                </Button>
                <Button variant="contained" onClick={() => setDialog('approve')}>
                  Approve
                </Button>
              </>
            )}
          </Stack>
        </Stack>
        <Typography variant="body2" color="text.secondary">
          {hint(npd)}
        </Typography>
        {v.decisions.length > 0 && <History decisions={v.decisions} />}
      </Stack>

      {dialog === 'submit' && (
        <CommentDialog
          title="Approve internally and submit"
          label="Note (optional)"
          required={false}
          confirmLabel="Submit to validation"
          intro="Confirms your own inspection passed and sends the project to ERGO Quality and Supplier Quality for validation. Every mandatory step of the stage must be done."
          busy={submitting.isLoading}
          error={submitting.error}
          onConfirm={(c) => void doSubmit(c)}
          onClose={() => setDialog(null)}
        />
      )}
      {dialog === 'approve' && (
        <CommentDialog
          title="Approve"
          label="Comment (optional)"
          required={false}
          confirmLabel="Approve"
          busy={deciding.isLoading}
          error={deciding.error}
          onConfirm={(c) => void doApprove(c)}
          onClose={() => setDialog(null)}
        />
      )}
      {dialog === 'reject' && <RejectDialog npdId={npd.id} askStage={!customerPhase} production={npd.kind === 'PRODUCTION'} onClose={() => setDialog(null)} />}
    </Paper>
  )
}

function hint(npd: NpdView): string {
  const audience = npd.audience
  switch (npd.validation.status) {
    case 'NOT_STARTED':
      if (audience === 'CUSTOMER') return 'Your team will be asked for its final review once ERGO has validated the sample.'
      return npd.stage === 'FINAL_SAMPLE_READINESS'
        ? 'Supplier Quality approves internally and submits the project to ERGO Quality and Supplier Quality.'
        : 'Starts when the project reaches Final Sample Readiness.'
    case 'IN_VALIDATION':
      return 'With ERGO Quality and Supplier Quality: both must approve before it can go to the customer.'
    case 'VALIDATED':
      return 'Both quality teams approved. Closing Final Sample Readiness submits the project to the customer.'
    case 'IN_CUSTOMER_REVIEW':
      return 'With Customer NPD and Customer Quality: both must approve.'
    case 'CUSTOMER_APPROVED':
      return 'Both customer teams approved. The project can move on.'
  }
}

function History({ decisions }: { decisions: ValidationDecisionView[] }) {
  return (
    <Stack spacing={0.75}>
      <Typography variant="caption" color="text.secondary">
        Quality history
      </Typography>
      {decisions.map((d, i) => (
        <Typography key={i} variant="body2">
          <strong>
            {VALIDATION_PARTY[d.party]}: {d.decision === 'APPROVED' ? (d.party === 'SUPPLIER_INTERNAL' ? 'approved and submitted' : 'approved') : 'rejected'}
          </strong>
          {d.decidedBy ? ` · ${d.decidedBy}` : ''} · round {d.round} · {formatDate(d.at)}
          {d.returnStage ? ` · sent back to ${NPD_STAGE[d.returnStage].label}` : ''}
          {d.comment ? ` — ${d.comment}` : ''}
        </Typography>
      ))}
    </Stack>
  )
}

function RejectDialog({ npdId, askStage, production, onClose }: { npdId: number; askStage: boolean; production: boolean; onClose: () => void }) {
  const [decide, deciding] = useDecideNpdValidationMutation()
  const [comment, setComment] = useState('')
  const [returnStage, setReturnStage] = useState<NpdStage | ''>('')
  const valid = comment.trim().length > 0 && (!askStage || returnStage !== '')

  async function save() {
    try {
      await decide({ id: npdId, form: { approved: false, comment: comment.trim(), returnStage: askStage ? (returnStage as NpdStage) : null } }).unwrap()
      onClose()
    } catch {
      // shown below
    }
  }

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Reject</DialogTitle>
      <DialogContent sx={{ display: 'grid', gap: 2, pt: '8px !important' }}>
        <Typography color="text.secondary">
          {askStage
            ? 'The project goes back to the development stage you choose; the finished steps of that stage are reopened for rework. Your reason is recorded in the history and the supplier can read it.'
            : 'The project goes back to Final Sample Readiness for corrective action and resubmission. Your reason is recorded in the history.'}
        </Typography>
        {askStage && (
          <TextField select required label="Send back to" value={returnStage} onChange={(e) => setReturnStage(e.target.value as NpdStage)}>
            {RETURN_STAGES.filter((s) => !production || (s !== 'TOOLING_MATERIAL_RELEASE' && s !== 'TRIAL_PREPARATION')).map((s) => (
              <MenuItem key={s} value={s}>
                {NPD_STAGE[s].label}
              </MenuItem>
            ))}
          </TextField>
        )}
        <TextField required multiline minRows={3} label="Reason" value={comment} onChange={(e) => setComment(e.target.value)} slotProps={{ htmlInput: { maxLength: 2000 } }} />
        {deciding.error != null && <Alert severity="error">{problemMessage(deciding.error)}</Alert>}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button color="error" variant="contained" disabled={!valid} loading={deciding.isLoading} onClick={() => void save()}>
          Reject
        </Button>
      </DialogActions>
    </Dialog>
  )
}
