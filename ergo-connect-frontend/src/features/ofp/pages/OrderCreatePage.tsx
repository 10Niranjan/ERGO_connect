import { Alert, Box, Button, MenuItem, Stack, TextField, Typography } from '@mui/material'
import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router'
import { num, str } from '../../../shared/form'
import { problemMessage } from '../../../shared/problem'
import { useUsersQuery } from '../../admin/adminApi'
import { useCompaniesQuery } from '../../companies/companiesApi'
import { useCreateOrderMutation } from '../ofpApi'

/** SRS §17.5 standalone (repeat/running) order. Orders coming from NPD use the handover endpoint instead. */
export function OrderCreatePage() {
  const navigate = useNavigate()
  const { data: companies = [] } = useCompaniesQuery()
  const { data: users = [] } = useUsersQuery()
  const [create, creating] = useCreateOrderMutation()
  const [s, setS] = useState({ customerCompanyId: '', supplierCompanyId: '', customerPoNo: '', supplierPoNo: '', partNumber: '', revision: '', orderedQuantity: '', dueDate: '', committedDispatchDate: '', ownerUserId: '' })
  const set = (k: keyof typeof s) => (e: { target: { value: string } }) => setS((p) => ({ ...p, [k]: e.target.value }))
  const active = companies.filter((c) => c.status === 'ACTIVE')

  async function submit(event: FormEvent) {
    event.preventDefault()
    try {
      const order = await create({
        customerCompanyId: Number(s.customerCompanyId),
        supplierCompanyId: Number(s.supplierCompanyId),
        customerPoNo: str(s.customerPoNo),
        supplierPoNo: str(s.supplierPoNo),
        partNumber: s.partNumber.trim(),
        revision: str(s.revision),
        orderedQuantity: Number(s.orderedQuantity),
        dueDate: str(s.dueDate),
        committedDispatchDate: str(s.committedDispatchDate),
        ownerUserId: num(s.ownerUserId),
      }).unwrap()
      void navigate(`/ofp/${order.id}`)
    } catch {
      // shown below
    }
  }

  const valid = s.customerCompanyId && s.supplierCompanyId && s.partNumber.trim() && Number(s.orderedQuantity) > 0
  return (
    <Stack spacing={2} component="form" onSubmit={(e) => void submit(e)} sx={{ maxWidth: 720 }}>
      <Typography variant="h5" component="h1">
        New order
      </Typography>
      <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', md: '1fr 1fr' } }}>
        <TextField select required label="Customer" value={s.customerCompanyId} onChange={set('customerCompanyId')}>
          {active.filter((c) => c.type === 'CUSTOMER').map((c) => (
            <MenuItem key={c.id} value={String(c.id)}>
              {c.profile.name}
            </MenuItem>
          ))}
        </TextField>
        <TextField select required label="Supplier" value={s.supplierCompanyId} onChange={set('supplierCompanyId')}>
          {active.filter((c) => c.type === 'SUPPLIER').map((c) => (
            <MenuItem key={c.id} value={String(c.id)}>
              {c.profile.name}
            </MenuItem>
          ))}
        </TextField>
        <TextField label="Customer PO no." value={s.customerPoNo} onChange={set('customerPoNo')} slotProps={{ htmlInput: { maxLength: 100 } }} />
        <TextField label="Supplier PO no." value={s.supplierPoNo} onChange={set('supplierPoNo')} slotProps={{ htmlInput: { maxLength: 100 } }} />
        <TextField required label="Part number" value={s.partNumber} onChange={set('partNumber')} slotProps={{ htmlInput: { maxLength: 100 } }} />
        <TextField label="Revision" value={s.revision} onChange={set('revision')} slotProps={{ htmlInput: { maxLength: 30 } }} />
        <TextField required label="Ordered quantity" type="number" value={s.orderedQuantity} onChange={set('orderedQuantity')} slotProps={{ htmlInput: { min: 0, step: '0.01' } }} />
        <TextField select label="Owner" value={s.ownerUserId} onChange={set('ownerUserId')}>
          <MenuItem value="">Not assigned</MenuItem>
          {users.filter((u) => u.companyType === 'ERGO').map((u) => (
            <MenuItem key={u.id} value={String(u.id)}>
              {u.firstName} {u.lastName}
            </MenuItem>
          ))}
        </TextField>
        <TextField label="Due date" type="date" value={s.dueDate} onChange={set('dueDate')} slotProps={{ inputLabel: { shrink: true } }} />
        <TextField label="Committed dispatch date" type="date" value={s.committedDispatchDate} onChange={set('committedDispatchDate')} slotProps={{ inputLabel: { shrink: true } }} />
      </Box>
      {creating.error != null && <Alert severity="error">{problemMessage(creating.error)}</Alert>}
      <Stack direction="row" spacing={1}>
        <Button onClick={() => void navigate('/ofp')}>Cancel</Button>
        <Button type="submit" variant="contained" loading={creating.isLoading} disabled={!valid}>
          Create order
        </Button>
      </Stack>
    </Stack>
  )
}
