import { Box, Button, Checkbox, FormControlLabel, MenuItem, Stack, TextField, Typography } from '@mui/material'
import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router'
import { useAppSelector } from '../../../app/store'
import { str } from '@/utils/form'
import { useCompaniesQuery } from '@/features/companies/services/companiesApi'
import { LINKED_MODULE } from '@/features/quality/utils/labels'
import { useCreateInspectionMutation, type LinkedModule } from '@/features/quality/services/qualityApi'
import { LinkedRecordPicker } from '@/features/quality/components/LinkedRecordPicker'
import { ProblemAlert } from '@/components/ui/ProblemAlert'

/** SRS §12.10.2 create. customerCompanyId/customerVisible only take effect for an ERGO caller - a supplier's own
 * create is always tagged to its own company (InspectionService.create), so those fields are hidden for suppliers. */
export function InspectionCreatePage() {
  const navigate = useNavigate()
  const companyType = useAppSelector((state) => state.auth.session?.user.companyType)
  const { data: companies = [] } = useCompaniesQuery(undefined, { skip: companyType !== 'ERGO' })
  const [create, creating] = useCreateInspectionMutation()
  const [s, setS] = useState({
    linkedModule: 'INCOMING' as LinkedModule,
    linkedRecordId: '',
    partNumber: '',
    batchLotNo: '',
    customerCompanyId: '',
    supplierCompanyId: '',
    customerVisible: false,
  })
  const set = (k: keyof typeof s) => (e: { target: { value: string } }) => setS((p) => ({ ...p, [k]: e.target.value }))
  const active = companies.filter((c) => c.status === 'ACTIVE')
  // Security review M5: for a linkedModule of NPD/OFP, the server always derives both companies from that record
  // and ignores whatever this form sends for them (InspectionService.create) - the pickers would just be misleading.
  const companiesDerived = s.linkedModule === 'NPD' || s.linkedModule === 'OFP'

  async function submit(event: FormEvent) {
    event.preventDefault()
    try {
      const inspection = await create({
        linkedModule: s.linkedModule,
        linkedRecordId: Number(s.linkedRecordId),
        partNumber: s.partNumber.trim(),
        batchLotNo: str(s.batchLotNo),
        customerCompanyId: s.customerCompanyId ? Number(s.customerCompanyId) : null,
        supplierCompanyId: s.supplierCompanyId ? Number(s.supplierCompanyId) : null,
        customerVisible: s.customerVisible,
      }).unwrap()
      void navigate(`/quality/${inspection.id}`)
    } catch {
      // shown below
    }
  }

  const valid = s.partNumber.trim() && Number(s.linkedRecordId) > 0
  return (
    <Stack spacing={2} component="form" onSubmit={(e) => void submit(e)} sx={{ maxWidth: 960 }}>
      <Typography variant="h5" component="h1">
        New inspection
      </Typography>
      <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', md: '1fr 1fr' } }}>
        <TextField select required label="Linked module" value={s.linkedModule} onChange={(e) => setS((p) => ({ ...p, linkedModule: e.target.value as LinkedModule, linkedRecordId: '' }))}>
          {(Object.keys(LINKED_MODULE) as LinkedModule[]).map((m) => (
            <MenuItem key={m} value={m}>
              {LINKED_MODULE[m]}
            </MenuItem>
          ))}
        </TextField>
        <LinkedRecordPicker
          module={s.linkedModule}
          value={s.linkedRecordId}
          onChange={(id, partNumber) => setS((p) => ({ ...p, linkedRecordId: id, partNumber: p.partNumber || partNumber || '' }))}
          helperText="The NPD project or order this inspection is checked against"
        />
        <TextField required label="Part number" value={s.partNumber} onChange={set('partNumber')} slotProps={{ htmlInput: { maxLength: 100 } }} />
        <TextField label="Batch / lot no." value={s.batchLotNo} onChange={set('batchLotNo')} slotProps={{ htmlInput: { maxLength: 100 } }} />
        {companyType === 'ERGO' && !companiesDerived && (
          <>
            <TextField select label="Customer" value={s.customerCompanyId} onChange={set('customerCompanyId')}>
              <MenuItem value="">None</MenuItem>
              {active.filter((c) => c.type === 'CUSTOMER').map((c) => (
                <MenuItem key={c.id} value={String(c.id)}>
                  {c.profile.name}
                </MenuItem>
              ))}
            </TextField>
            <TextField select label="Supplier" value={s.supplierCompanyId} onChange={set('supplierCompanyId')}>
              <MenuItem value="">None</MenuItem>
              {active.filter((c) => c.type === 'SUPPLIER').map((c) => (
                <MenuItem key={c.id} value={String(c.id)}>
                  {c.profile.name}
                </MenuItem>
              ))}
            </TextField>
          </>
        )}
      </Box>
      {companyType === 'ERGO' && companiesDerived && (
        <Typography variant="body2" color="text.secondary">
          Customer and supplier are taken from the linked NPD project or OFP order.
        </Typography>
      )}
      {companyType === 'ERGO' && (
        <FormControlLabel
          control={<Checkbox checked={s.customerVisible} onChange={(e) => setS((p) => ({ ...p, customerVisible: e.target.checked }))} />}
          label="Visible to the customer"
        />
      )}
      {creating.error != null && <ProblemAlert error={creating.error} />}
      <Stack direction="row" spacing={1}>
        <Button onClick={() => void navigate('/quality')}>Cancel</Button>
        <Button type="submit" variant="contained" loading={creating.isLoading} disabled={!valid}>
          Create inspection
        </Button>
      </Stack>
    </Stack>
  )
}
