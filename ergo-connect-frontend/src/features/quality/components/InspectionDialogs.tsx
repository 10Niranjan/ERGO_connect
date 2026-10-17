import {
  Button,
  Checkbox,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  MenuItem,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  TextField,
  Typography,
} from '@mui/material'
import { useState } from 'react'
import { str, text } from '@/utils/form'
import { useCompaniesQuery } from '@/features/companies/services/companiesApi'
import { DEFECT_SEVERITY } from '@/features/quality/utils/labels'
import {
  useEditInspectionMutation,
  useSubmitInspectionMutation,
  type DefectForm,
  type DefectSeverity,
  type InspectionDetail,
  type InspectionUpdateForm,
} from '@/features/quality/services/qualityApi'
import { ProblemAlert } from '@/components/ui/ProblemAlert'

/** SRS §12.10.2 header/result fields, shared by EDIT and SUBMIT (OPEN -> IN_INSPECTION) - one form for both, the
 * status move is the only difference (QualityDtos.InspectionUpdateForm's own comment). */
export function InspectionUpdateDialog({ mode, inspection, onClose }: { mode: 'edit' | 'submit'; inspection: InspectionDetail; onClose: () => void }) {
  const ergo = 'customerCompanyId' in inspection ? inspection : null
  // Security review M5: an NPD/OFP-linked inspection's companies are derived from the linked record at creation and
  // can never be reassigned by a later edit (InspectionService.applyUpdate) - the pickers below are locked for those.
  const companiesLocked = inspection.linkedModule === 'NPD' || inspection.linkedModule === 'OFP'
  const { data: companies = [] } = useCompaniesQuery(undefined, { skip: ergo === null })
  const active = companies.filter((c) => c.status === 'ACTIVE')
  const [edit, editing] = useEditInspectionMutation()
  const [submit, submitting] = useSubmitInspectionMutation()
  const running = mode === 'edit' ? editing : submitting

  const [s, setS] = useState({
    partNumber: inspection.partNumber,
    batchLotNo: inspection.batchLotNo ?? '',
    quantityChecked: text(inspection.quantityChecked),
    quantityRejected: text(inspection.quantityRejected),
    inspectionDate: inspection.inspectionDate ? inspection.inspectionDate.slice(0, 10) : '',
    remarks: 'remarks' in inspection ? (inspection.remarks ?? '') : '',
    customerVisible: ergo?.customerVisible ?? false,
    customerCompanyId: ergo?.customerCompanyId ? String(ergo.customerCompanyId) : '',
    supplierCompanyId: ergo?.supplierCompanyId ? String(ergo.supplierCompanyId) : '',
  })
  const set = (k: keyof typeof s) => (e: { target: { value: string } }) => setS((p) => ({ ...p, [k]: e.target.value }))
  const [defects, setDefects] = useState<DefectForm[]>(
    inspection.defects.map((d) => ({
      defectCategory: 'defectCategory' in d ? d.defectCategory : '',
      defectDescription: 'defectDescription' in d ? d.defectDescription : null,
      defectQuantity: d.defectQuantity,
      severity: d.severity,
    })),
  )
  const [newDefect, setNewDefect] = useState({ defectCategory: '', defectDescription: '', defectQuantity: '', severity: 'MINOR' as DefectSeverity })

  function addDefect() {
    if (!newDefect.defectCategory.trim() || Number(newDefect.defectQuantity) <= 0) return
    setDefects((p) => [...p, { defectCategory: newDefect.defectCategory.trim(), defectDescription: str(newDefect.defectDescription), defectQuantity: Number(newDefect.defectQuantity), severity: newDefect.severity }])
    setNewDefect({ defectCategory: '', defectDescription: '', defectQuantity: '', severity: 'MINOR' })
  }

  async function confirm() {
    const form: InspectionUpdateForm = {
      partNumber: s.partNumber.trim(),
      batchLotNo: str(s.batchLotNo),
      quantityChecked: s.quantityChecked.trim() === '' ? null : Number(s.quantityChecked),
      quantityRejected: s.quantityRejected.trim() === '' ? null : Number(s.quantityRejected),
      inspectionDate: str(s.inspectionDate),
      remarks: str(s.remarks),
      customerVisible: s.customerVisible,
      customerCompanyId: s.customerCompanyId ? Number(s.customerCompanyId) : null,
      supplierCompanyId: s.supplierCompanyId ? Number(s.supplierCompanyId) : null,
      defects,
    }
    try {
      if (mode === 'edit') await edit({ id: inspection.id, form }).unwrap()
      else await submit({ id: inspection.id, form }).unwrap()
      onClose()
    } catch {
      // shown below
    }
  }

  const valid = s.partNumber.trim() !== '' && (mode !== 'submit' || (s.quantityChecked.trim() !== '' && s.inspectionDate.trim() !== ''))

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="md">
      <DialogTitle>{mode === 'edit' ? 'Edit inspection' : 'Submit inspection'}</DialogTitle>
      <DialogContent sx={{ display: 'grid', gap: 2, pt: '8px !important' }}>
        {mode === 'submit' && <Typography color="text.secondary">Records the raw checked/rejected quantities and defects, and moves the inspection to In inspection.</Typography>}
        <Stack direction="row" spacing={2} useFlexGap sx={{ flexWrap: 'wrap' }}>
          <TextField required label="Part number" value={s.partNumber} onChange={set('partNumber')} slotProps={{ htmlInput: { maxLength: 100 } }} sx={{ minWidth: 220 }} />
          <TextField label="Batch / lot no." value={s.batchLotNo} onChange={set('batchLotNo')} slotProps={{ htmlInput: { maxLength: 100 } }} sx={{ minWidth: 220 }} />
        </Stack>
        <Stack direction="row" spacing={2} useFlexGap sx={{ flexWrap: 'wrap' }}>
          <TextField required={mode === 'submit'} label="Quantity checked" type="number" value={s.quantityChecked} onChange={set('quantityChecked')} slotProps={{ htmlInput: { min: 0, step: '0.01' } }} sx={{ minWidth: 200 }} />
          <TextField label="Quantity rejected" type="number" value={s.quantityRejected} onChange={set('quantityRejected')} slotProps={{ htmlInput: { min: 0, step: '0.01' } }} sx={{ minWidth: 200 }} />
          <TextField required={mode === 'submit'} label="Inspection date" type="date" value={s.inspectionDate} onChange={set('inspectionDate')} slotProps={{ inputLabel: { shrink: true } }} sx={{ minWidth: 200 }} />
        </Stack>
        <TextField label="Remarks" value={s.remarks} onChange={set('remarks')} multiline minRows={2} slotProps={{ htmlInput: { maxLength: 4000 } }} />

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
              <FormControlLabel control={<Checkbox checked={s.customerVisible} onChange={(e) => setS((p) => ({ ...p, customerVisible: e.target.checked }))} />} label="Visible to the customer" />
            </Stack>
            {companiesLocked && (
              <Typography variant="caption" color="text.secondary">
                Customer and supplier are set from the linked NPD/OFP record and cannot be changed here.
              </Typography>
            )}
          </Stack>
        )}

        <Typography variant="subtitle2">Defects</Typography>
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>Category</TableCell>
              <TableCell>Description</TableCell>
              <TableCell align="right">Quantity</TableCell>
              <TableCell>Severity</TableCell>
              <TableCell />
            </TableRow>
          </TableHead>
          <TableBody>
            {defects.map((d, i) => (
              <TableRow key={i}>
                <TableCell>{d.defectCategory}</TableCell>
                <TableCell sx={{ maxWidth: 220, wordBreak: 'break-word' }}>{d.defectDescription ?? '—'}</TableCell>
                <TableCell align="right">{d.defectQuantity}</TableCell>
                <TableCell>
                  <Chip size="small" label={DEFECT_SEVERITY[d.severity].label} color={DEFECT_SEVERITY[d.severity].color} />
                </TableCell>
                <TableCell align="right">
                  <Button size="small" color="error" onClick={() => setDefects((p) => p.filter((_, j) => j !== i))}>
                    Remove
                  </Button>
                </TableCell>
              </TableRow>
            ))}
            <TableRow>
              <TableCell>
                <TextField size="small" placeholder="Category" value={newDefect.defectCategory} onChange={(e) => setNewDefect((p) => ({ ...p, defectCategory: e.target.value }))} slotProps={{ htmlInput: { maxLength: 100 } }} />
              </TableCell>
              <TableCell>
                <TextField size="small" placeholder="Description" value={newDefect.defectDescription} onChange={(e) => setNewDefect((p) => ({ ...p, defectDescription: e.target.value }))} slotProps={{ htmlInput: { maxLength: 2000 } }} />
              </TableCell>
              <TableCell align="right">
                <TextField size="small" type="number" placeholder="Qty" value={newDefect.defectQuantity} onChange={(e) => setNewDefect((p) => ({ ...p, defectQuantity: e.target.value }))} slotProps={{ htmlInput: { min: 0, step: '0.01' } }} sx={{ width: 90 }} />
              </TableCell>
              <TableCell>
                <TextField select size="small" value={newDefect.severity} onChange={(e) => setNewDefect((p) => ({ ...p, severity: e.target.value as DefectSeverity }))} sx={{ minWidth: 110 }}>
                  {(Object.keys(DEFECT_SEVERITY) as DefectSeverity[]).map((sev) => (
                    <MenuItem key={sev} value={sev}>
                      {DEFECT_SEVERITY[sev].label}
                    </MenuItem>
                  ))}
                </TextField>
              </TableCell>
              <TableCell align="right">
                <Button size="small" onClick={addDefect}>
                  Add
                </Button>
              </TableCell>
            </TableRow>
          </TableBody>
        </Table>

        {running.error != null && <ProblemAlert error={running.error} />}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" loading={running.isLoading} disabled={!valid} onClick={() => void confirm()}>
          {mode === 'edit' ? 'Save changes' : 'Submit'}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
