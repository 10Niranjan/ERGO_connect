import { Alert, Button, Checkbox, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, MenuItem, Stack, TextField, Typography } from '@mui/material'
import { useState } from 'react'
import { str } from '../../../shared/form'
import { problemMessage } from '../../../shared/problem'
import { useUsersQuery } from '../../admin/adminApi'
import { useCompaniesQuery } from '../../companies/companiesApi'
import {
  useCreateCapaActionMutation,
  useEditCapaActionMutation,
  useEditNcrMutation,
  useMoveNcrToCapaMutation,
  useMoveNcrToContainmentMutation,
  useMoveNcrToRootCauseMutation,
  useSubmitNcrForClosureMutation,
  type CapaActionForm,
  type CapaActionType,
  type NcrDetail,
  type NcrUpdateForm,
} from '../qualityApi'

const MODE_LABEL: Record<UpdateMode, string> = {
  EDIT: 'Edit NCR',
  MOVE_TO_CONTAINMENT: 'Move to containment',
  MOVE_TO_ROOT_CAUSE: 'Move to root cause',
  MOVE_TO_CAPA: 'Move to CAPA',
  SUBMIT_FOR_CLOSURE: 'Submit for closure',
}

type UpdateMode = 'EDIT' | 'MOVE_TO_CONTAINMENT' | 'MOVE_TO_ROOT_CAUSE' | 'MOVE_TO_CAPA' | 'SUBMIT_FOR_CLOSURE'

/** SRS §12.10.2/§14.7 header and progressive fields, shared by EDIT and every forward transition - one form for
 * all five, the status move (and which field becomes required) is the only difference (NcrUpdateForm's own
 * javadoc). The hard gates (containment before root cause, root cause before CAPA, corrective+preventive before
 * closure) live in NcrService; the `required` marks below are a client-side nicety that fills them in at the step
 * that naturally produces them, not a second copy of the backend rule. */
export function NcrUpdateDialog({ mode, ncr, onClose }: { mode: UpdateMode; ncr: NcrDetail; onClose: () => void }) {
  const ergo = 'customerCompanyId' in ncr ? ncr : null
  // Security review M5 precedent: an NPD/OFP-linked NCR's companies are derived from the linked record at creation
  // and can never be reassigned by a later edit (NcrService.applyUpdate) - the pickers below are locked for those.
  const companiesLocked = ncr.linkedModule === 'NPD' || ncr.linkedModule === 'OFP'
  const { data: companies = [] } = useCompaniesQuery(undefined, { skip: ergo === null })
  const { data: users = [] } = useUsersQuery(undefined, { skip: ergo === null })
  const active = companies.filter((c) => c.status === 'ACTIVE')

  const [edit, editing] = useEditNcrMutation()
  const [moveToContainment, movingToContainment] = useMoveNcrToContainmentMutation()
  const [moveToRootCause, movingToRootCause] = useMoveNcrToRootCauseMutation()
  const [moveToCapa, movingToCapa] = useMoveNcrToCapaMutation()
  const [submitForClosure, submittingForClosure] = useSubmitNcrForClosureMutation()
  const running = { EDIT: editing, MOVE_TO_CONTAINMENT: movingToContainment, MOVE_TO_ROOT_CAUSE: movingToRootCause, MOVE_TO_CAPA: movingToCapa, SUBMIT_FOR_CLOSURE: submittingForClosure }[mode]

  const [s, setS] = useState({
    issueCategory: ncr.issueCategory,
    issueDescription: ncr.issueDescription,
    targetClosureDate: ncr.targetClosureDate.slice(0, 10),
    containmentAction: ncr.containmentAction ?? '',
    rootCause: ncr.rootCause ?? '',
    correctiveAction: ncr.correctiveAction ?? '',
    preventiveAction: ncr.preventiveAction ?? '',
    effectivenessStatus: ergo?.effectivenessStatus ?? '',
    ownerUserId: ergo?.ownerUserId ? String(ergo.ownerUserId) : '',
    customerVisible: ergo?.customerVisible ?? false,
    customerCompanyId: ergo?.customerCompanyId ? String(ergo.customerCompanyId) : '',
    supplierCompanyId: ergo?.supplierCompanyId ? String(ergo.supplierCompanyId) : '',
  })
  const set = (k: keyof typeof s) => (e: { target: { value: string } }) => setS((p) => ({ ...p, [k]: e.target.value }))

  async function confirm() {
    const form: NcrUpdateForm = {
      issueCategory: s.issueCategory.trim(),
      issueDescription: s.issueDescription.trim(),
      targetClosureDate: s.targetClosureDate,
      containmentAction: str(s.containmentAction),
      rootCause: str(s.rootCause),
      correctiveAction: str(s.correctiveAction),
      preventiveAction: str(s.preventiveAction),
      effectivenessStatus: ergo ? str(s.effectivenessStatus) : null,
      ownerUserId: ergo && s.ownerUserId ? Number(s.ownerUserId) : null,
      customerCompanyId: ergo && s.customerCompanyId ? Number(s.customerCompanyId) : null,
      supplierCompanyId: ergo && s.supplierCompanyId ? Number(s.supplierCompanyId) : null,
      customerVisible: ergo ? s.customerVisible : false,
    }
    try {
      if (mode === 'EDIT') await edit({ id: ncr.id, form }).unwrap()
      else if (mode === 'MOVE_TO_CONTAINMENT') await moveToContainment({ id: ncr.id, form }).unwrap()
      else if (mode === 'MOVE_TO_ROOT_CAUSE') await moveToRootCause({ id: ncr.id, form }).unwrap()
      else if (mode === 'MOVE_TO_CAPA') await moveToCapa({ id: ncr.id, form }).unwrap()
      else await submitForClosure({ id: ncr.id, form }).unwrap()
      onClose()
    } catch {
      // shown below
    }
  }

  const needsContainment = mode === 'MOVE_TO_CONTAINMENT'
  const needsRootCause = mode === 'MOVE_TO_ROOT_CAUSE'
  const needsCapaPlan = mode === 'SUBMIT_FOR_CLOSURE'
  const valid =
    s.issueCategory.trim() !== '' &&
    s.issueDescription.trim() !== '' &&
    s.targetClosureDate.trim() !== '' &&
    (!needsContainment || s.containmentAction.trim() !== '') &&
    (!needsRootCause || s.rootCause.trim() !== '') &&
    (!needsCapaPlan || (s.correctiveAction.trim() !== '' && s.preventiveAction.trim() !== ''))

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="md">
      <DialogTitle>{MODE_LABEL[mode]}</DialogTitle>
      <DialogContent sx={{ display: 'grid', gap: 2, pt: '8px !important' }}>
        <Stack direction="row" spacing={2} useFlexGap sx={{ flexWrap: 'wrap' }}>
          <TextField required label="Issue category" value={s.issueCategory} onChange={set('issueCategory')} slotProps={{ htmlInput: { maxLength: 100 } }} sx={{ minWidth: 220 }} />
          <TextField required label="Target closure date" type="date" value={s.targetClosureDate} onChange={set('targetClosureDate')} slotProps={{ inputLabel: { shrink: true } }} sx={{ minWidth: 200 }} />
        </Stack>
        <TextField required label="Issue description" value={s.issueDescription} onChange={set('issueDescription')} multiline minRows={2} slotProps={{ htmlInput: { maxLength: 4000 } }} />
        <TextField required={needsContainment} label="Containment action" value={s.containmentAction} onChange={set('containmentAction')} multiline minRows={2} slotProps={{ htmlInput: { maxLength: 4000 } }} />
        <TextField required={needsRootCause} label="Root cause" value={s.rootCause} onChange={set('rootCause')} multiline minRows={2} slotProps={{ htmlInput: { maxLength: 4000 } }} />
        <TextField required={needsCapaPlan} label="Corrective action" value={s.correctiveAction} onChange={set('correctiveAction')} multiline minRows={2} slotProps={{ htmlInput: { maxLength: 4000 } }} />
        <TextField required={needsCapaPlan} label="Preventive action" value={s.preventiveAction} onChange={set('preventiveAction')} multiline minRows={2} slotProps={{ htmlInput: { maxLength: 4000 } }} />

        {ergo && (
          <Stack spacing={1}>
            <Stack direction="row" spacing={2} useFlexGap sx={{ flexWrap: 'wrap', alignItems: 'center' }}>
              <TextField select disabled={companiesLocked} label="Customer" value={s.customerCompanyId} onChange={set('customerCompanyId')} sx={{ minWidth: 220 }}>
                <MenuItem value="">None</MenuItem>
                {active.filter((c) => c.type === 'CUSTOMER').map((c) => (
                  <MenuItem key={c.id} value={String(c.id)}>
                    {c.profile.name}
                  </MenuItem>
                ))}
              </TextField>
              <TextField select disabled={companiesLocked} label="Supplier" value={s.supplierCompanyId} onChange={set('supplierCompanyId')} sx={{ minWidth: 220 }}>
                <MenuItem value="">None</MenuItem>
                {active.filter((c) => c.type === 'SUPPLIER').map((c) => (
                  <MenuItem key={c.id} value={String(c.id)}>
                    {c.profile.name}
                  </MenuItem>
                ))}
              </TextField>
              <TextField select label="Owner" value={s.ownerUserId} onChange={set('ownerUserId')} sx={{ minWidth: 200 }}>
                <MenuItem value="">Not assigned</MenuItem>
                {users.filter((u) => u.companyType === 'ERGO').map((u) => (
                  <MenuItem key={u.id} value={String(u.id)}>
                    {u.firstName} {u.lastName}
                  </MenuItem>
                ))}
              </TextField>
              <FormControlLabel control={<Checkbox checked={s.customerVisible} onChange={(e) => setS((p) => ({ ...p, customerVisible: e.target.checked }))} />} label="Visible to the customer" />
            </Stack>
            {companiesLocked ? (
              <Typography variant="caption" color="text.secondary">
                Customer and supplier are set from the linked NPD/OFP record and cannot be changed here.
              </Typography>
            ) : (
              <Typography variant="caption" color="text.secondary">
                Reassigning the customer or supplier is only possible while the NCR is Open, and clears containment/root
                cause/corrective/preventive progress and any CAPA actions already recorded.
              </Typography>
            )}
            <TextField label="Effectiveness status" value={s.effectivenessStatus} onChange={set('effectivenessStatus')} slotProps={{ htmlInput: { maxLength: 40 } }} helperText="ERGO's own post-closure judgment; not shared outside ERGO" sx={{ maxWidth: 320 }} />
          </Stack>
        )}

        {running.error != null && <Alert severity="error">{problemMessage(running.error)}</Alert>}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" loading={running.isLoading} disabled={!valid} onClick={() => void confirm()}>
          {MODE_LABEL[mode]}
        </Button>
      </DialogActions>
    </Dialog>
  )
}

/** Create/edit a CAPA action. Owner may be an ERGO user or a user of the NCR's own supplier company
 * (NcrService.resolveCapaOwner); a supplier caller may only assign its own company's users. `row` accepts whichever
 * per-audience CAPA row shape the caller's own NcrDetail carries - only CapaActionView (ERGO) exposes ownerUserId to
 * pre-fill from, the supplier's own edit still full-replaces it (CapaActionForm's own convention). */
export function CapaActionDialog({ ncr, row, onClose }: { ncr: NcrDetail; row: NcrDetail['capaActions'][number] | null; onClose: () => void }) {
  const isErgo = 'customerCompanyId' in ncr
  const supplierCompanyId = isErgo ? ncr.supplierCompanyId : null
  const { data: users = [] } = useUsersQuery()
  // An ERGO caller may assign an ERGO user or a user of this NCR's own supplier company (NcrService.resolveCapaOwner).
  // A supplier caller's own /users list is already scoped to its own company (UserAdminService.list()), so every row
  // returned is already a valid owner for it - no extra filtering needed there.
  const ownerOptions = isErgo ? users.filter((u) => u.companyType === 'ERGO' || (supplierCompanyId != null && u.companyId === supplierCompanyId)) : users
  const [create, creating] = useCreateCapaActionMutation()
  const [edit, editing] = useEditCapaActionMutation()
  const running = row ? editing : creating
  const [s, setS] = useState({
    actionType: (row?.actionType ?? 'CORRECTIVE') as CapaActionType,
    actionDescription: row?.actionDescription ?? '',
    targetDate: row?.targetDate.slice(0, 10) ?? '',
    ownerUserId: row && 'ownerUserId' in row && row.ownerUserId ? String(row.ownerUserId) : '',
  })
  const set = (k: keyof typeof s) => (e: { target: { value: string } }) => setS((p) => ({ ...p, [k]: e.target.value }))

  async function confirm() {
    const form: CapaActionForm = {
      actionType: s.actionType,
      actionDescription: s.actionDescription.trim(),
      targetDate: s.targetDate,
      ownerUserId: s.ownerUserId ? Number(s.ownerUserId) : null,
    }
    try {
      if (row) await edit({ id: ncr.id, capaActionId: row.id, form }).unwrap()
      else await create({ id: ncr.id, form }).unwrap()
      onClose()
    } catch {
      // shown below
    }
  }

  const valid = s.actionDescription.trim() !== '' && s.targetDate.trim() !== ''
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{row ? 'Edit CAPA action' : 'Add CAPA action'}</DialogTitle>
      <DialogContent sx={{ display: 'grid', gap: 2, pt: '8px !important' }}>
        <TextField select required label="Type" value={s.actionType} onChange={(e) => setS((p) => ({ ...p, actionType: e.target.value as CapaActionType }))}>
          <MenuItem value="CORRECTIVE">Corrective</MenuItem>
          <MenuItem value="PREVENTIVE">Preventive</MenuItem>
        </TextField>
        <TextField required label="Action description" value={s.actionDescription} onChange={set('actionDescription')} multiline minRows={2} slotProps={{ htmlInput: { maxLength: 4000 } }} />
        <TextField required label="Target date" type="date" value={s.targetDate} onChange={set('targetDate')} slotProps={{ inputLabel: { shrink: true } }} />
        <TextField select label="Owner" value={s.ownerUserId} onChange={set('ownerUserId')}>
          <MenuItem value="">Not assigned</MenuItem>
          {ownerOptions.map((u) => (
            <MenuItem key={u.id} value={String(u.id)}>
              {u.firstName} {u.lastName} ({u.companyName})
            </MenuItem>
          ))}
        </TextField>
        {running.error != null && <Alert severity="error">{problemMessage(running.error)}</Alert>}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" loading={running.isLoading} disabled={!valid} onClick={() => void confirm()}>
          {row ? 'Save changes' : 'Add action'}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
