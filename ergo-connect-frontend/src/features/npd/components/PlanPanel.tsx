import { Alert, Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle, LinearProgress, Paper, Stack, TextField, Typography } from '@mui/material'
import { useState } from 'react'
import { CommentDialog } from '@/components/ui/CommentDialog'
import { problemMessage } from '@/utils/problem'
import { formatDate } from '../../rfq/labels'
import { NPD_STAGE, PLAN_EVENT, PLAN_STATUS } from '../utils/labels'
import {
  useAgreeNpdPlanMutation,
  useSendBackNpdPlanMutation,
  useSubmitNpdPlanMutation,
  useUpdatePlanStepMutation,
  type ActivityView,
  type ErgoNpdView,
  type NpdStage,
  type PlanHistoryView,
  type ProgressView,
  type SupplierNpdView,
} from '../services/npdApi'

type PlanAction = 'submit' | 'agree' | 'send-back'

/**
 * Flow doc step 8: the supplier fills in the plan ERGO NPD released to it and submits it; ERGO NPD agrees it or sends
 * it back with a comment; only an agreed plan goes to execution. Shown to ERGO and the supplier only: the customer's
 * view carries no plan at all. Every button is one the backend listed in `actions`.
 */
export function PlanPanel({ npd }: { npd: ErgoNpdView | SupplierNpdView }) {
  const [action, setAction] = useState<PlanAction | null>(null)
  const [submit, submitting] = useSubmitNpdPlanMutation()
  const [agree, agreeing] = useAgreeNpdPlanMutation()
  const [sendBack, sendingBack] = useSendBackNpdPlanMutation()
  const status = PLAN_STATUS[npd.planStatus]
  const canSubmit = npd.actions.includes('SUBMIT_PLAN')
  const canDecide = npd.actions.includes('AGREE_PLAN')
  const returned = [...npd.planHistory].reverse().find((h) => h.event === 'SENT_BACK')

  async function run(kind: PlanAction, comment: string) {
    try {
      if (kind === 'submit') await submit({ id: npd.id, comment: comment || undefined }).unwrap()
      if (kind === 'agree') await agree({ id: npd.id, comment: comment || undefined }).unwrap()
      if (kind === 'send-back') await sendBack({ id: npd.id, comment }).unwrap()
      setAction(null)
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
              Plan
            </Typography>
            <Chip size="small" label={status.label} color={status.color} />
          </Stack>
          <Stack direction="row" spacing={1}>
            {canSubmit && (
              <Button variant="contained" onClick={() => setAction('submit')}>
                Submit plan
              </Button>
            )}
            {canDecide && (
              <>
                <Button color="error" onClick={() => setAction('send-back')}>
                  Send back
                </Button>
                <Button variant="contained" onClick={() => setAction('agree')}>
                  Agree plan
                </Button>
              </>
            )}
          </Stack>
        </Stack>

        <Typography variant="body2" color="text.secondary">
          {hint(npd)}
        </Typography>
        {npd.planStatus === 'CHANGES_REQUESTED' && returned?.comment && <Alert severity="warning">ERGO NPD asked for changes: {returned.comment}</Alert>}

        {npd.planHistory.length > 0 && <PlanHistory history={npd.planHistory} />}
      </Stack>

      {action === 'submit' && (
        <CommentDialog
          title="Submit plan to ERGO NPD"
          label="Note for ERGO NPD (optional)"
          required={false}
          confirmLabel="Submit plan"
          intro="Every step needs a planned date. Once submitted you cannot change the plan until ERGO NPD agrees it or sends it back."
          busy={submitting.isLoading}
          error={submitting.error}
          onConfirm={(c) => void run('submit', c)}
          onClose={() => setAction(null)}
        />
      )}
      {action === 'agree' && (
        <CommentDialog
          title="Agree the plan"
          label="Comment (optional)"
          required={false}
          confirmLabel="Agree plan"
          intro="Execution starts: the teams can update their steps and stages can be closed. Your comment stays internal to ERGO; the supplier sees only that the plan was agreed."
          busy={agreeing.isLoading}
          error={agreeing.error}
          onConfirm={(c) => void run('agree', c)}
          onClose={() => setAction(null)}
        />
      )}
      {action === 'send-back' && (
        <CommentDialog
          title="Send the plan back to the supplier"
          label="What needs to change"
          required
          danger
          confirmLabel="Send back"
          intro="The supplier sees this comment. Do not name the customer or quote prices."
          busy={sendingBack.isLoading}
          error={sendingBack.error}
          onConfirm={(c) => void run('send-back', c)}
          onClose={() => setAction(null)}
        />
      )}
    </Paper>
  )
}

function hint(npd: ErgoNpdView | SupplierNpdView): string {
  const ergo = npd.audience === 'ERGO'
  switch (npd.planStatus) {
    case 'AWAITING_PLAN':
      return 'Build the plan: add the steps the supplier has to plan, then assign the plan to the supplier.'
    case 'ASSIGNED_TO_SUPPLIER':
      return ergo ? 'With the supplier: waiting for them to fill in their steps and submit the plan.' : 'Give each of your steps a planned date, then submit the plan to ERGO NPD.'
    case 'CHANGES_REQUESTED':
      return ergo ? 'Sent back to the supplier: waiting for a corrected plan.' : 'Update the steps as asked, then submit the plan again.'
    case 'SUBMITTED':
      return ergo ? "Review the supplier's dates, then agree the plan or send it back with a comment." : 'With ERGO NPD for agreement. The plan cannot be changed meanwhile.'
    case 'AGREED':
      return 'The plan is agreed. Execution is under way.'
  }
}

function PlanHistory({ history }: { history: PlanHistoryView[] }) {
  return (
    <Stack spacing={0.75}>
      <Typography variant="caption" color="text.secondary">
        Plan history
      </Typography>
      {history.map((h, i) => (
        <Typography key={i} variant="body2">
          <strong>{PLAN_EVENT[h.event]}</strong>
          {h.by ? ` · ${h.by}` : ''} · {formatDate(h.at)}
          {h.comment ? ` — ${h.comment}` : ''}
        </Typography>
      ))}
    </Stack>
  )
}

/** Overall progress and each stage's state, from the activities the caller can see. */
export function ProgressSummary({ progress, currentStage }: { progress: ProgressView; currentStage: NpdStage }) {
  return (
    <Paper variant="outlined" sx={{ p: 2 }}>
      <Stack spacing={1.5}>
        <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'baseline' }}>
          <Typography variant="subtitle1" sx={{ fontWeight: 600 }}>
            Progress
          </Typography>
          <Typography variant="body2" color="text.secondary">
            {progress.done} of {progress.total} activities done · {progress.percent}%
          </Typography>
        </Stack>
        <LinearProgress variant="determinate" value={progress.percent} />
        <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1 }}>
          {progress.stages.map((s) => (
            <Chip
              key={s.stage}
              size="small"
              variant={s.stage === currentStage ? 'filled' : 'outlined'}
              color={s.state === 'COMPLETED' ? 'success' : s.state === 'CURRENT' ? 'primary' : 'default'}
              label={`${NPD_STAGE[s.stage].label}${s.total > 0 ? ` · ${s.done}/${s.total}` : ''}`}
            />
          ))}
        </Box>
      </Stack>
    </Paper>
  )
}

/** The supplier's planning of one of its own steps: a date and a remark; the name, stage and owner stay ERGO's. */
export function PlanStepDialog({ activity, onClose }: { activity: ActivityView; onClose: () => void }) {
  const [update, updating] = useUpdatePlanStepMutation()
  const [plannedDate, setPlannedDate] = useState(activity.plannedDate ?? '')
  const [remarks, setRemarks] = useState(activity.remarks ?? '')

  async function save() {
    try {
      await update({
        activityId: activity.id,
        form: { plannedDate, responsibleUserId: activity.responsibleUserId, remarks: remarks.trim() || null },
      }).unwrap()
      onClose()
    } catch {
      // shown below
    }
  }

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Plan: {activity.activityName}</DialogTitle>
      <DialogContent sx={{ display: 'grid', gap: 2, pt: '8px !important' }}>
        {activity.activityDescription && <Typography color="text.secondary">{activity.activityDescription}</Typography>}
        <TextField
          label="Planned date"
          type="date"
          required
          value={plannedDate}
          onChange={(e) => setPlannedDate(e.target.value)}
          slotProps={{ inputLabel: { shrink: true } }}
        />
        <TextField label="Remarks" multiline minRows={2} value={remarks} onChange={(e) => setRemarks(e.target.value)} slotProps={{ htmlInput: { maxLength: 2000 } }} />
        {updating.error != null && <Alert severity="error">{problemMessage(updating.error)}</Alert>}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={!plannedDate} loading={updating.isLoading} onClick={() => void save()}>
          Save
        </Button>
      </DialogActions>
    </Dialog>
  )
}
