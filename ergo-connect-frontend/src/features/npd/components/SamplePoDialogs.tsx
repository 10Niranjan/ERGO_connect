import { Alert, Button, Dialog, DialogActions, DialogContent, DialogTitle, LinearProgress, MenuItem, TextField, Typography } from '@mui/material'
import { useState } from 'react'
import { useNavigate } from 'react-router'
import { num, str, text } from '../../../shared/form'
import { problemMessage } from '../../../shared/problem'
import {
  useAssigneesQuery,
  useCreateProjectFromSamplePoMutation,
  useRaiseSamplePoMutation,
  useResubmitSamplePoMutation,
  type SamplePoView,
} from '../services/samplePoApi'

interface SamplePoFields {
  customerPoNumber: string
  quantity: number
  requiredDate: string
  remarks: string | null
}

/** The fields Customer NPD fills in; shared by "raise" (a part is chosen) and "correct a returned Sample PO". */
function SamplePoFormDialog({
  title,
  intro,
  confirmLabel,
  initial,
  busy,
  error,
  onSubmit,
  onClose,
}: {
  title: string
  intro?: string
  confirmLabel: string
  initial?: SamplePoView
  busy: boolean
  error: unknown
  onSubmit: (form: SamplePoFields) => void
  onClose: () => void
}) {
  const [state, setState] = useState({
    customerPoNumber: text(initial?.customerPoNumber),
    quantity: text(initial?.quantity),
    requiredDate: text(initial?.requiredDate),
    remarks: text(initial?.remarks),
  })
  const quantity = num(state.quantity)
  const valid = state.customerPoNumber.trim() !== '' && quantity != null && Number.isInteger(quantity) && quantity > 0 && state.requiredDate !== ''

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{title}</DialogTitle>
      <DialogContent sx={{ display: 'grid', gap: 2, pt: '8px !important' }}>
        {intro && <Typography color="text.secondary">{intro}</Typography>}
        <TextField
          label="Your PO number"
          required
          autoFocus
          value={state.customerPoNumber}
          onChange={(e) => setState((s) => ({ ...s, customerPoNumber: e.target.value }))}
          slotProps={{ htmlInput: { maxLength: 60 } }}
        />
        <TextField
          label="Sample quantity"
          type="number"
          required
          value={state.quantity}
          onChange={(e) => setState((s) => ({ ...s, quantity: e.target.value }))}
          slotProps={{ htmlInput: { min: 1, step: 1 } }}
        />
        <TextField
          label="Required by"
          type="date"
          required
          value={state.requiredDate}
          onChange={(e) => setState((s) => ({ ...s, requiredDate: e.target.value }))}
          slotProps={{ inputLabel: { shrink: true } }}
        />
        <TextField
          label="Remarks"
          multiline
          minRows={2}
          value={state.remarks}
          onChange={(e) => setState((s) => ({ ...s, remarks: e.target.value }))}
          slotProps={{ htmlInput: { maxLength: 2000 } }}
        />
        {error != null && <Alert severity="error">{problemMessage(error)}</Alert>}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button
          variant="contained"
          loading={busy}
          disabled={!valid}
          onClick={() =>
            onSubmit({ customerPoNumber: state.customerPoNumber.trim(), quantity: quantity ?? 0, requiredDate: state.requiredDate, remarks: str(state.remarks) })
          }
        >
          {confirmLabel}
        </Button>
      </DialogActions>
    </Dialog>
  )
}

/** Customer NPD raises a Sample PO for one approved part and submits it to the ERGO KAM. */
export function RaiseSamplePoDialog({ part, onClose }: { part: { rfqItemId: number; partNumber: string; rfqNumber: string | null }; onClose: () => void }) {
  const [raise, state] = useRaiseSamplePoMutation()

  async function submit(form: SamplePoFields) {
    try {
      await raise({ rfqItemId: part.rfqItemId, ...form }).unwrap()
      onClose()
    } catch {
      // shown in the dialog
    }
  }

  return (
    <SamplePoFormDialog
      title={`Sample PO for ${part.partNumber}`}
      intro={`Submitted to your ERGO account manager for review${part.rfqNumber ? ` (quotation ${part.rfqNumber})` : ''}.`}
      confirmLabel="Submit Sample PO"
      busy={state.isLoading}
      error={state.error}
      onSubmit={(form) => void submit(form)}
      onClose={onClose}
    />
  )
}

/** Customer NPD corrects a Sample PO the KAM returned and sends it again. */
export function EditSamplePoDialog({ samplePo, onClose }: { samplePo: SamplePoView; onClose: () => void }) {
  const [resubmit, state] = useResubmitSamplePoMutation()

  async function submit(form: SamplePoFields) {
    try {
      await resubmit({ id: samplePo.id, form }).unwrap()
      onClose()
    } catch {
      // shown in the dialog
    }
  }

  return (
    <SamplePoFormDialog
      title={`Correct ${samplePo.samplePoNumber}`}
      intro={samplePo.returnComment ? `Returned with: ${samplePo.returnComment}` : undefined}
      confirmLabel="Resubmit Sample PO"
      initial={samplePo}
      busy={state.isLoading}
      error={state.error}
      onSubmit={(form) => void submit(form)}
      onClose={onClose}
    />
  )
}

/** The KAM accepts the Sample PO by creating the sample project, assigned to one ERGO NPD and one ERGO Quality user. */
export function CreateProjectDialog({ samplePo, onClose }: { samplePo: SamplePoView; onClose: () => void }) {
  const navigate = useNavigate()
  const { data, isLoading, error: loadError } = useAssigneesQuery()
  const [create, state] = useCreateProjectFromSamplePoMutation()
  const [npdUserId, setNpdUserId] = useState('')
  const [qualityUserId, setQualityUserId] = useState('')
  const [comment, setComment] = useState('')

  async function submit() {
    try {
      const project = await create({
        id: samplePo.id,
        form: { ergoNpdUserId: Number(npdUserId), ergoQualityUserId: Number(qualityUserId), comment: str(comment) },
      }).unwrap()
      onClose()
      navigate(`/npd/${project.id}`)
    } catch {
      // shown in the dialog
    }
  }

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Create sample project from {samplePo.samplePoNumber}</DialogTitle>
      <DialogContent sx={{ display: 'grid', gap: 2, pt: '8px !important' }}>
        <Typography color="text.secondary">
          Accepting the Sample PO creates the sample development project. ERGO NPD then builds the plan and releases it to the supplier.
        </Typography>
        {isLoading && <LinearProgress />}
        {loadError != null && <Alert severity="error">{problemMessage(loadError)}</Alert>}
        <TextField select label="ERGO NPD" required value={npdUserId} onChange={(e) => setNpdUserId(e.target.value)} disabled={!data}>
          {data?.npd.map((u) => (
            <MenuItem key={u.id} value={String(u.id)}>
              {u.name}
            </MenuItem>
          ))}
        </TextField>
        <TextField select label="ERGO Quality" required value={qualityUserId} onChange={(e) => setQualityUserId(e.target.value)} disabled={!data}>
          {data?.quality.map((u) => (
            <MenuItem key={u.id} value={String(u.id)}>
              {u.name}
            </MenuItem>
          ))}
        </TextField>
        {data && (data.npd.length === 0 || data.quality.length === 0) && (
          <Alert severity="warning">There is no active {data.npd.length === 0 ? 'ERGO NPD' : 'ERGO Quality'} user to assign. Ask ERGO Admin to add one.</Alert>
        )}
        <TextField
          label="Comment (optional)"
          multiline
          minRows={2}
          value={comment}
          onChange={(e) => setComment(e.target.value)}
          slotProps={{ htmlInput: { maxLength: 2000 } }}
        />
        {state.error != null && <Alert severity="error">{problemMessage(state.error)}</Alert>}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" loading={state.isLoading} disabled={!npdUserId || !qualityUserId} onClick={() => void submit()}>
          Create project
        </Button>
      </DialogActions>
    </Dialog>
  )
}
