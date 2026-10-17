import { Box, Button, Checkbox, FormControlLabel, MenuItem, Stack, TextField, Typography } from '@mui/material'
import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router'
import { str } from '@/utils/form'
import { useCreateDeviationMutation } from '@/features/quality/services/deviationApi'
import { LINKED_MODULE } from '@/features/quality/utils/labels'
import { LinkedRecordPicker } from '@/features/quality/components/LinkedRecordPicker'
import { ProblemAlert } from '@/components/ui/ProblemAlert'

/** SRS §12.10.4 create. A deviation can only be linked to an NPD project or an OFP order (DeviationService.create) -
 * unlike Inspection/NCR's LinkedModule, the picker below is restricted to those two. customerCompanyId/
 * supplierCompanyId are never sent - both are always derived from the linked record server-side (security review M5
 * precedent, same as NcrCreatePage/InspectionCreatePage). customerApprovalRequiredFlag is set once here and locked
 * after creation. */
export function DeviationCreatePage() {
  const navigate = useNavigate()
  const [create, creating] = useCreateDeviationMutation()
  const [s, setS] = useState({
    linkedModule: 'NPD' as 'NPD' | 'OFP',
    linkedRecordId: '',
    partNumber: '',
    deviationDescription: '',
    qualityImpact: '',
    technicalImpact: '',
    customerApprovalRequiredFlag: false,
    validityQuantity: '',
    validityEndDate: '',
  })
  const set = (k: keyof typeof s) => (e: { target: { value: string } }) => setS((p) => ({ ...p, [k]: e.target.value }))

  async function submit(event: FormEvent) {
    event.preventDefault()
    try {
      const deviation = await create({
        linkedModule: s.linkedModule,
        linkedRecordId: Number(s.linkedRecordId),
        inspectionId: null,
        partNumber: s.partNumber.trim(),
        deviationDescription: s.deviationDescription.trim(),
        qualityImpact: str(s.qualityImpact),
        technicalImpact: str(s.technicalImpact),
        customerApprovalRequiredFlag: s.customerApprovalRequiredFlag,
        validityQuantity: Number(s.validityQuantity),
        validityEndDate: s.validityEndDate,
      }).unwrap()
      void navigate(`/quality/deviations/${deviation.id}`)
    } catch {
      // shown below
    }
  }

  const valid =
    s.partNumber.trim() !== '' &&
    s.deviationDescription.trim() !== '' &&
    Number(s.linkedRecordId) > 0 &&
    Number(s.validityQuantity) > 0 &&
    s.validityEndDate.trim() !== ''

  return (
    <Stack spacing={2} component="form" onSubmit={(e) => void submit(e)} sx={{ maxWidth: 960 }}>
      <Typography variant="h5" component="h1">
        New deviation
      </Typography>
      <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', md: '1fr 1fr' } }}>
        <TextField select required label="Linked module" value={s.linkedModule} onChange={(e) => setS((p) => ({ ...p, linkedModule: e.target.value as 'NPD' | 'OFP', linkedRecordId: '' }))}>
          <MenuItem value="NPD">{LINKED_MODULE.NPD}</MenuItem>
          <MenuItem value="OFP">{LINKED_MODULE.OFP}</MenuItem>
        </TextField>
        <LinkedRecordPicker
          module={s.linkedModule}
          value={s.linkedRecordId}
          onChange={(id, partNumber) => setS((p) => ({ ...p, linkedRecordId: id, partNumber: p.partNumber || partNumber || '' }))}
          helperText="The NPD project or order this deviation is raised against"
        />
        <TextField required label="Part number" value={s.partNumber} onChange={set('partNumber')} slotProps={{ htmlInput: { maxLength: 100 } }} />
        <TextField required label="Validity quantity" type="number" value={s.validityQuantity} onChange={set('validityQuantity')} slotProps={{ htmlInput: { min: 0.01, step: '0.01' } }} />
        <TextField required label="Validity end date" type="date" value={s.validityEndDate} onChange={set('validityEndDate')} slotProps={{ inputLabel: { shrink: true } }} />
      </Box>
      <Typography variant="body2" color="text.secondary">
        Customer and supplier are taken from the linked NPD project or OFP order.
      </Typography>
      <TextField required label="Deviation description" value={s.deviationDescription} onChange={set('deviationDescription')} multiline minRows={3} slotProps={{ htmlInput: { maxLength: 4000 } }} />
      <TextField label="Quality impact" value={s.qualityImpact} onChange={set('qualityImpact')} multiline minRows={2} slotProps={{ htmlInput: { maxLength: 4000 } }} />
      <TextField label="Technical impact" value={s.technicalImpact} onChange={set('technicalImpact')} multiline minRows={2} slotProps={{ htmlInput: { maxLength: 4000 } }} />
      <FormControlLabel
        control={<Checkbox checked={s.customerApprovalRequiredFlag} onChange={(e) => setS((p) => ({ ...p, customerApprovalRequiredFlag: e.target.checked }))} />}
        label="Requires customer approval"
      />
      {creating.error != null && <ProblemAlert error={creating.error} />}
      <Stack direction="row" spacing={1}>
        <Button onClick={() => void navigate('/quality/deviations')}>Cancel</Button>
        <Button type="submit" variant="contained" loading={creating.isLoading} disabled={!valid}>
          Create deviation
        </Button>
      </Stack>
    </Stack>
  )
}
