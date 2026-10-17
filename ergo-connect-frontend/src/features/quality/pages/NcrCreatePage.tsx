import { Box, Button, MenuItem, Stack, TextField, Typography } from '@mui/material'
import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router'
import { useAppSelector } from '../../../app/store'
import { useUsersQuery } from '@/features/admin/services/adminApi'
import { useCompaniesQuery } from '@/features/companies/services/companiesApi'
import { LINKED_MODULE } from '@/features/quality/utils/labels'
import { useCreateNcrMutation, type LinkedModule } from '@/features/quality/services/qualityApi'
import { LinkedRecordPicker } from '@/features/quality/components/LinkedRecordPicker'
import { ProblemAlert } from '@/components/ui/ProblemAlert'

/** SRS §12.10/§13.6.3 create. customerCompanyId/supplierCompanyId/ownerUserId only take effect for an ERGO caller -
 * a supplier's own create is always tagged to its own company (NcrService.create), so those fields are hidden for
 * suppliers. NcrForm carries no customerVisible field: every NCR starts hidden from the customer (security review
 * H2) and an ERGO editor turns it on later via edit, once there is something reviewed worth sharing. */
export function NcrCreatePage() {
  const navigate = useNavigate()
  const companyType = useAppSelector((state) => state.auth.session?.user.companyType)
  const { data: companies = [] } = useCompaniesQuery(undefined, { skip: companyType !== 'ERGO' })
  const { data: users = [] } = useUsersQuery(undefined, { skip: companyType !== 'ERGO' })
  const [create, creating] = useCreateNcrMutation()
  const [s, setS] = useState({
    linkedModule: 'NPD' as LinkedModule,
    linkedRecordId: '',
    issueCategory: '',
    issueDescription: '',
    targetClosureDate: '',
    customerCompanyId: '',
    supplierCompanyId: '',
    ownerUserId: '',
  })
  const set = (k: keyof typeof s) => (e: { target: { value: string } }) => setS((p) => ({ ...p, [k]: e.target.value }))
  const active = companies.filter((c) => c.status === 'ACTIVE')
  // Security review M5 precedent (same as InspectionCreatePage): for a linkedModule of NPD/OFP, the server always
  // derives both companies from that record and ignores whatever this form sends for them (NcrService.create).
  const companiesDerived = s.linkedModule === 'NPD' || s.linkedModule === 'OFP'

  async function submit(event: FormEvent) {
    event.preventDefault()
    try {
      const ncr = await create({
        linkedModule: s.linkedModule,
        linkedRecordId: Number(s.linkedRecordId),
        issueCategory: s.issueCategory.trim(),
        issueDescription: s.issueDescription.trim(),
        targetClosureDate: s.targetClosureDate,
        customerCompanyId: s.customerCompanyId ? Number(s.customerCompanyId) : null,
        supplierCompanyId: s.supplierCompanyId ? Number(s.supplierCompanyId) : null,
        ownerUserId: s.ownerUserId ? Number(s.ownerUserId) : null,
      }).unwrap()
      void navigate(`/quality/ncrs/${ncr.id}`)
    } catch {
      // shown below
    }
  }

  const valid = s.issueCategory.trim() && s.issueDescription.trim() && s.targetClosureDate.trim() && Number(s.linkedRecordId) > 0
  return (
    <Stack spacing={2} component="form" onSubmit={(e) => void submit(e)} sx={{ maxWidth: 960 }}>
      <Typography variant="h5" component="h1">
        New NCR
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
          onChange={(id) => setS((p) => ({ ...p, linkedRecordId: id }))}
          helperText="The NPD project or order this NCR relates to"
        />
        <TextField required label="Issue category" value={s.issueCategory} onChange={set('issueCategory')} slotProps={{ htmlInput: { maxLength: 100 } }} />
        <TextField required label="Target closure date" type="date" value={s.targetClosureDate} onChange={set('targetClosureDate')} slotProps={{ inputLabel: { shrink: true } }} />
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
        {companyType === 'ERGO' && (
          <TextField select label="Owner" value={s.ownerUserId} onChange={set('ownerUserId')}>
            <MenuItem value="">Not assigned</MenuItem>
            {users.filter((u) => u.companyType === 'ERGO').map((u) => (
              <MenuItem key={u.id} value={String(u.id)}>
                {u.firstName} {u.lastName}
              </MenuItem>
            ))}
          </TextField>
        )}
      </Box>
      {companyType === 'ERGO' && companiesDerived && (
        <Typography variant="body2" color="text.secondary">
          Customer and supplier are taken from the linked NPD project or OFP order.
        </Typography>
      )}
      <TextField required label="Issue description" value={s.issueDescription} onChange={set('issueDescription')} multiline minRows={3} slotProps={{ htmlInput: { maxLength: 4000 } }} />
      {creating.error != null && <ProblemAlert error={creating.error} />}
      <Stack direction="row" spacing={1}>
        <Button onClick={() => void navigate('/quality/ncrs')}>Cancel</Button>
        <Button type="submit" variant="contained" loading={creating.isLoading} disabled={!valid}>
          Create NCR
        </Button>
      </Stack>
    </Stack>
  )
}
