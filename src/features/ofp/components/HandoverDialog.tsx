import { Button, Dialog, DialogActions, DialogContent, DialogTitle, MenuItem, TextField } from '@mui/material'
import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router'
import { num, str } from '@/utils/form'
import { useUsersQuery } from '@/features/admin/services/adminApi'
import { useHandoverOrderMutation } from '@/features/ofp/services/ofpApi'
import { ProblemAlert } from '@/components/ui/ProblemAlert'

/** NPD → OFP handover (SRS §14.5): explicit action that creates the order. Customer, supplier, part and revision
 * come from the NPD project on the server; the server also rejects a project not yet approved for handover. */
export function HandoverDialog({ npdProjectId, onClose }: { npdProjectId: number; onClose: () => void }) {
  const navigate = useNavigate()
  const { data: users = [] } = useUsersQuery()
  const [handover, state] = useHandoverOrderMutation()
  const [s, setS] = useState({ orderedQuantity: '', dueDate: '', customerPoNo: '', supplierPoNo: '', ownerUserId: '' })
  const set = (k: keyof typeof s) => (e: { target: { value: string } }) => setS((p) => ({ ...p, [k]: e.target.value }))

  async function submit(event: FormEvent) {
    event.preventDefault()
    try {
      const order = await handover({
        npdProjectId,
        form: { orderedQuantity: Number(s.orderedQuantity), dueDate: str(s.dueDate), customerPoNo: str(s.customerPoNo), supplierPoNo: str(s.supplierPoNo), ownerUserId: num(s.ownerUserId) },
      }).unwrap()
      void navigate(`/ofp/${order.id}`)
    } catch {
      // shown below
    }
  }

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <form onSubmit={(e) => void submit(e)}>
        <DialogTitle>Handover to OFP</DialogTitle>
        <DialogContent sx={{ display: 'grid', gap: 2, pt: '8px !important' }}>
          <TextField required type="number" label="Ordered quantity" value={s.orderedQuantity} onChange={set('orderedQuantity')} slotProps={{ htmlInput: { min: 0, step: 'any' } }} />
          <TextField type="date" label="Due date" value={s.dueDate} onChange={set('dueDate')} slotProps={{ inputLabel: { shrink: true } }} />
          <TextField label="Customer PO no." value={s.customerPoNo} onChange={set('customerPoNo')} />
          <TextField label="Supplier PO no." value={s.supplierPoNo} onChange={set('supplierPoNo')} />
          <TextField select label="Owner" value={s.ownerUserId} onChange={set('ownerUserId')}>
            <MenuItem value="">Not assigned</MenuItem>
            {users.filter((u) => u.companyType === 'ERGO').map((u) => (
              <MenuItem key={u.id} value={String(u.id)}>
                {u.firstName} {u.lastName}
              </MenuItem>
            ))}
          </TextField>
          {state.error != null && <ProblemAlert error={state.error} />}
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose}>Cancel</Button>
          <Button type="submit" variant="contained" loading={state.isLoading} disabled={!(Number(s.orderedQuantity) > 0)}>
            Create order
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  )
}
