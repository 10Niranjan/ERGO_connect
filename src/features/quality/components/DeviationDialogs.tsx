import { Button, Checkbox, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, Stack, TextField } from '@mui/material'
import { useState } from 'react'
import { str } from '@/utils/form'
import { useEditDeviationMutation, type DeviationDetail, type DeviationUpdateForm } from '@/features/quality/services/deviationApi'
import { ProblemAlert } from '@/components/ui/ProblemAlert'

/** SRS §12.10.4 edit: the only non-workflow form (REQUESTED/UNDER_REVIEW only, DeviationService.allowed(EDIT, ...)).
 * customerApprovalRequiredFlag/linkedModule/linkedRecordId are locked after creation (DeviationUpdateForm carries no
 * such fields), same convention as NcrUpdateDialog/InspectionUpdateDialog for their own locked fields. */
export function DeviationUpdateDialog({ deviation, onClose }: { deviation: DeviationDetail; onClose: () => void }) {
  const ergo = 'customerCompanyId' in deviation ? deviation : null
  const [edit, editing] = useEditDeviationMutation()
  const [s, setS] = useState({
    partNumber: deviation.partNumber,
    deviationDescription: deviation.deviationDescription,
    qualityImpact: deviation.qualityImpact ?? '',
    technicalImpact: deviation.technicalImpact ?? '',
    validityQuantity: String(deviation.validityQuantity),
    validityEndDate: deviation.validityEndDate.slice(0, 10),
    customerVisible: ergo?.customerVisible ?? false,
    supplierVisible: ergo?.supplierVisible ?? false,
  })
  const set = (k: keyof typeof s) => (e: { target: { value: string } }) => setS((p) => ({ ...p, [k]: e.target.value }))

  async function confirm() {
    const form: DeviationUpdateForm = {
      partNumber: s.partNumber.trim(),
      deviationDescription: s.deviationDescription.trim(),
      qualityImpact: str(s.qualityImpact),
      technicalImpact: str(s.technicalImpact),
      validityQuantity: Number(s.validityQuantity),
      validityEndDate: s.validityEndDate,
      customerVisible: ergo ? s.customerVisible : false,
      supplierVisible: ergo ? s.supplierVisible : true,
    }
    try {
      await edit({ id: deviation.id, form }).unwrap()
      onClose()
    } catch {
      // shown below
    }
  }

  const valid = s.partNumber.trim() !== '' && s.deviationDescription.trim() !== '' && Number(s.validityQuantity) > 0 && s.validityEndDate.trim() !== ''

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="md">
      <DialogTitle>Edit deviation</DialogTitle>
      <DialogContent sx={{ display: 'grid', gap: 2, pt: '8px !important' }}>
        <Stack direction="row" spacing={2} useFlexGap sx={{ flexWrap: 'wrap' }}>
          <TextField required label="Part number" value={s.partNumber} onChange={set('partNumber')} slotProps={{ htmlInput: { maxLength: 100 } }} sx={{ minWidth: 220 }} />
          <TextField required label="Validity quantity" type="number" value={s.validityQuantity} onChange={set('validityQuantity')} slotProps={{ htmlInput: { min: 0.01, step: '0.01' } }} sx={{ minWidth: 180 }} />
          <TextField required label="Validity end date" type="date" value={s.validityEndDate} onChange={set('validityEndDate')} slotProps={{ inputLabel: { shrink: true } }} sx={{ minWidth: 200 }} />
        </Stack>
        <TextField required label="Deviation description" value={s.deviationDescription} onChange={set('deviationDescription')} multiline minRows={2} slotProps={{ htmlInput: { maxLength: 4000 } }} />
        <TextField label="Quality impact" value={s.qualityImpact} onChange={set('qualityImpact')} multiline minRows={2} slotProps={{ htmlInput: { maxLength: 4000 } }} />
        <TextField label="Technical impact" value={s.technicalImpact} onChange={set('technicalImpact')} multiline minRows={2} slotProps={{ htmlInput: { maxLength: 4000 } }} />
        {ergo && (
          <Stack direction="row" spacing={2} useFlexGap sx={{ flexWrap: 'wrap' }}>
            <FormControlLabel control={<Checkbox checked={s.customerVisible} onChange={(e) => setS((p) => ({ ...p, customerVisible: e.target.checked }))} />} label="Visible to the customer" />
            <FormControlLabel control={<Checkbox checked={s.supplierVisible} onChange={(e) => setS((p) => ({ ...p, supplierVisible: e.target.checked }))} />} label="Visible to the supplier" />
          </Stack>
        )}
        {editing.error != null && <ProblemAlert error={editing.error} />}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" loading={editing.isLoading} disabled={!valid} onClick={() => void confirm()}>
          Save changes
        </Button>
      </DialogActions>
    </Dialog>
  )
}
