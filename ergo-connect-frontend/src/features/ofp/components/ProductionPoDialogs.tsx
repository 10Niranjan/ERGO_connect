import { Alert, Button, Dialog, DialogActions, DialogContent, DialogTitle, LinearProgress, MenuItem, TextField, Typography } from '@mui/material'
import { useState } from 'react'
import { useNavigate } from 'react-router'
import { num, str, text } from '../../../shared/form'
import { problemMessage } from '../../../shared/problem'
import {
  useCreateOfpProjectMutation,
  useOfpAssigneesQuery,
  useRaiseProductionPoMutation,
  useResubmitProductionPoMutation,
  type ProductionPoView,
} from '../services/productionPoApi'

interface ProductionPoFields {
  customerPoNumber: string
  quantity: number
  requiredDate: string
  remarks: string | null
}

/** The fields Customer OFP fills in; shared by "release" (a project is chosen) and "correct a returned production order". */
function ProductionPoFormDialog({
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
  initial?: ProductionPoView
  busy: boolean
  error: unknown
  onSubmit: (form: ProductionPoFields) => void
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
          label="Production quantity"
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

/** Customer OFP releases a production order for an approved sample project and submits it to the ERGO KAM. */
export function RaiseProductionPoDialog({ project, onClose }: { project: { projectId: number; partNumber: string; npdNumber: string }; onClose: () => void }) {
  const [raise, state] = useRaiseProductionPoMutation()

  async function submit(form: ProductionPoFields) {
    try {
      await raise({ sourceProjectId: project.projectId, ...form }).unwrap()
      onClose()
    } catch {
      // shown in the dialog
    }
  }

  return (
    <ProductionPoFormDialog
      title={`Production PO for ${project.partNumber}`}
      intro={`Submitted to your ERGO account manager, who starts the production project (continuing sample project ${project.npdNumber}).`}
      confirmLabel="Submit production PO"
      busy={state.isLoading}
      error={state.error}
      onSubmit={(form) => void submit(form)}
      onClose={onClose}
    />
  )
}

/** Customer OFP corrects a production order the KAM returned and sends it again. */
export function EditProductionPoDialog({ productionPo, onClose }: { productionPo: ProductionPoView; onClose: () => void }) {
  const [resubmit, state] = useResubmitProductionPoMutation()

  async function submit(form: ProductionPoFields) {
    try {
      await resubmit({ id: productionPo.id, form }).unwrap()
      onClose()
    } catch {
      // shown in the dialog
    }
  }

  return (
    <ProductionPoFormDialog
      title={`Correct ${productionPo.productionPoNumber}`}
      intro={productionPo.returnComment ? `Returned with: ${productionPo.returnComment}` : undefined}
      confirmLabel="Resubmit production PO"
      initial={productionPo}
      busy={state.isLoading}
      error={state.error}
      onSubmit={(form) => void submit(form)}
      onClose={onClose}
    />
  )
}

/** The KAM accepts the production order by creating the OFP project, assigned to one ERGO OFP and one ERGO Quality user. */
export function CreateOfpProjectDialog({ productionPo, onClose }: { productionPo: ProductionPoView; onClose: () => void }) {
  const navigate = useNavigate()
  const { data, isLoading, error: loadError } = useOfpAssigneesQuery()
  const [create, state] = useCreateOfpProjectMutation()
  const [ofpUserId, setOfpUserId] = useState('')
  const [qualityUserId, setQualityUserId] = useState('')
  const [comment, setComment] = useState('')

  async function submit() {
    try {
      const project = await create({
        id: productionPo.id,
        form: { ergoOfpUserId: Number(ofpUserId), ergoQualityUserId: Number(qualityUserId), comment: str(comment) },
      }).unwrap()
      onClose()
      navigate(`/npd/${project.id}`)
    } catch {
      // shown in the dialog
    }
  }

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Create OFP project from {productionPo.productionPoNumber}</DialogTitle>
      <DialogContent sx={{ display: 'grid', gap: 2, pt: '8px !important' }}>
        <Typography color="text.secondary">
          Accepting the production order creates the OFP project. ERGO OFP then builds the plan and releases it to the supplier.
        </Typography>
        {isLoading && <LinearProgress />}
        {loadError != null && <Alert severity="error">{problemMessage(loadError)}</Alert>}
        <TextField select label="ERGO OFP" required value={ofpUserId} onChange={(e) => setOfpUserId(e.target.value)} disabled={!data}>
          {data?.ofp.map((u) => (
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
        {data && (data.ofp.length === 0 || data.quality.length === 0) && (
          <Alert severity="warning">There is no active {data.ofp.length === 0 ? 'ERGO OFP' : 'ERGO Quality'} user to assign. Ask ERGO Admin to add one.</Alert>
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
        <Button variant="contained" loading={state.isLoading} disabled={!ofpUserId || !qualityUserId} onClick={() => void submit()}>
          Create project
        </Button>
      </DialogActions>
    </Dialog>
  )
}
