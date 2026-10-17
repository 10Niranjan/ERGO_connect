import { Box, Button, Checkbox, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, LinearProgress, MenuItem, TextField } from '@mui/material'
import { useState, type ReactNode } from 'react'
import { useUsersQuery } from '@/features/admin/services/adminApi'
import { num, str, text } from '@/utils/form'
import { useMasterOptionsQuery } from '@/utils/masterData'
import { INSPECTION_STATUS, MATERIAL_STATUS, ORDER_STATUS, PACKAGING_STATUS, PRODUCTION_STATUS } from '@/features/ofp/utils/labels'
import {
  useAddMaterialStatusMutation,
  useAddPackagingDispatchMutation,
  useAddProductionProgressMutation,
  useCreateShipmentMutation,
  useUpdateMaterialStatusMutation,
  useUpdateOrderMutation,
  useUpdatePackagingDispatchMutation,
  useUpdateProductionProgressMutation,
  useUpdateShipmentMutation,
  useUploadPodMutation,
  type ExternalShipmentView,
  type InspectionStatus,
  type MaterialStatus,
  type MaterialStatusView,
  type OrderStatus,
  type OrderView,
  type PackagingDispatchView,
  type PackagingStatus,
  type ProductionProgressView,
  type ProductionStatus,
  type ShipmentView,
  type SupplierMaterialStatusView,
} from '@/features/ofp/services/ofpApi'
import { ProblemAlert } from '@/components/ui/ProblemAlert'

/** Shared modal shell: save button, ProblemDetail message, and busy state. */
function FormDialog(props: { title: string; busy: boolean; error: unknown; canSave?: boolean; onSave: () => void; onClose: () => void; children: ReactNode }) {
  return (
    <Dialog open onClose={props.onClose} fullWidth maxWidth="sm">
      <DialogTitle>{props.title}</DialogTitle>
      <DialogContent sx={{ display: 'grid', gap: 2, pt: '8px !important' }}>
        {props.children}
        {props.error != null && <ProblemAlert error={props.error} />}
      </DialogContent>
      <DialogActions>
        <Button onClick={props.onClose}>Cancel</Button>
        <Button variant="contained" loading={props.busy} disabled={props.canSave === false} onClick={props.onSave}>
          Save
        </Button>
      </DialogActions>
    </Dialog>
  )
}

const DATE = { inputLabel: { shrink: true } }
const grid2 = { display: 'grid', gap: 2, gridTemplateColumns: 'repeat(2, 1fr)' }

function DateField({ label, value, onChange }: { label: string; value: string; onChange: (v: string) => void }) {
  return <TextField label={label} type="date" value={value} onChange={(e) => onChange(e.target.value)} slotProps={DATE} />
}

function NumberField({ label, value, onChange }: { label: string; value: string; onChange: (v: string) => void }) {
  return <TextField label={label} type="number" value={value} onChange={(e) => onChange(e.target.value)} slotProps={{ htmlInput: { min: 0, step: '0.01' } }} />
}

function ChoiceField<T extends string>(props: { label: string; value: T; options: Record<string, { label: string }>; onChange: (v: T) => void }) {
  return (
    <TextField select label={props.label} value={props.value} onChange={(e) => props.onChange(e.target.value as T)}>
      {Object.entries(props.options).map(([k, v]) => (
        <MenuItem key={k} value={k}>
          {v.label}
        </MenuItem>
      ))}
    </TextField>
  )
}

/** SRS §17.5 header fields still editable after creation (ERGO OFP/Admin only, EDIT action). */
export function OrderEditDialog({ order, onClose }: { order: OrderView; onClose: () => void }) {
  const { data: users = [], isLoading } = useUsersQuery()
  const [save, saving] = useUpdateOrderMutation()
  const [s, setS] = useState({
    customerPoNo: text(order.customerPoNo),
    supplierPoNo: text(order.supplierPoNo),
    dueDate: text(order.dueDate),
    committedDispatchDate: text(order.committedDispatchDate),
    ownerUserId: text(order.ownerUserId),
  })
  const set = (k: keyof typeof s) => (v: string) => setS((p) => ({ ...p, [k]: v }))
  async function doSave() {
    try {
      await save({
        id: order.id,
        form: {
          customerPoNo: str(s.customerPoNo),
          supplierPoNo: str(s.supplierPoNo),
          dueDate: str(s.dueDate),
          committedDispatchDate: str(s.committedDispatchDate),
          ownerUserId: num(s.ownerUserId),
        },
      }).unwrap()
      onClose()
    } catch {
      // shown in the dialog
    }
  }
  if (isLoading) return <LinearProgress />
  return (
    <FormDialog title="Edit order" busy={saving.isLoading} error={saving.error} onSave={() => void doSave()} onClose={onClose}>
      <Box sx={grid2}>
        <TextField label="Customer PO no." value={s.customerPoNo} onChange={(e) => set('customerPoNo')(e.target.value)} slotProps={{ htmlInput: { maxLength: 100 } }} />
        <TextField label="Supplier PO no." value={s.supplierPoNo} onChange={(e) => set('supplierPoNo')(e.target.value)} slotProps={{ htmlInput: { maxLength: 100 } }} />
        <DateField label="Due date" value={s.dueDate} onChange={set('dueDate')} />
        <DateField label="Committed dispatch date" value={s.committedDispatchDate} onChange={set('committedDispatchDate')} />
      </Box>
      <TextField select label="Owner" value={s.ownerUserId} onChange={(e) => set('ownerUserId')(e.target.value)}>
        <MenuItem value="">Not assigned</MenuItem>
        {users
          .filter((u) => u.companyType === 'ERGO')
          .map((u) => (
            <MenuItem key={u.id} value={String(u.id)}>
              {u.firstName} {u.lastName}
            </MenuItem>
          ))}
      </TextField>
    </FormDialog>
  )
}

/** ERGO's own view carries `remarks`; the supplier/customer row type does not, so `remarks` is only edited for ERGO. */
export function MaterialDialog({ orderId, row, onClose }: { orderId: number; row: MaterialStatusView | SupplierMaterialStatusView | null; onClose: () => void }) {
  const [add, adding] = useAddMaterialStatusMutation()
  const [update, updating] = useUpdateMaterialStatusMutation()
  const [s, setS] = useState({
    materialStatus: (row?.materialStatus ?? 'PENDING') as MaterialStatus,
    inspectionStatus: (row?.inspectionStatus ?? 'PENDING') as InspectionStatus,
    materialOrderDate: text(row?.materialOrderDate),
    expectedReceiptDate: text(row?.expectedReceiptDate),
    actualReceiptDate: text(row?.actualReceiptDate),
    remarks: text(row && 'remarks' in row ? row.remarks : null),
  })
  const set = (k: keyof typeof s) => (v: string) => setS((p) => ({ ...p, [k]: v }))
  async function doSave() {
    const form = {
      materialStatus: s.materialStatus,
      inspectionStatus: s.inspectionStatus,
      materialOrderDate: str(s.materialOrderDate),
      expectedReceiptDate: str(s.expectedReceiptDate),
      actualReceiptDate: str(s.actualReceiptDate),
      remarks: str(s.remarks),
    }
    try {
      if (row) await update({ statusId: row.id, form }).unwrap()
      else await add({ id: orderId, form }).unwrap()
      onClose()
    } catch {
      // shown in the dialog
    }
  }
  return (
    <FormDialog title={row ? 'Edit material status' : 'Add material status'} busy={adding.isLoading || updating.isLoading} error={adding.error ?? updating.error} onSave={() => void doSave()} onClose={onClose}>
      <Box sx={grid2}>
        <ChoiceField label="Material status" value={s.materialStatus} options={MATERIAL_STATUS} onChange={set('materialStatus')} />
        <ChoiceField label="Inspection" value={s.inspectionStatus} options={INSPECTION_STATUS} onChange={set('inspectionStatus')} />
        <DateField label="Ordered on" value={s.materialOrderDate} onChange={set('materialOrderDate')} />
        <DateField label="Expected receipt" value={s.expectedReceiptDate} onChange={set('expectedReceiptDate')} />
        <DateField label="Actual receipt" value={s.actualReceiptDate} onChange={set('actualReceiptDate')} />
      </Box>
      <TextField label="Remarks (ERGO only)" multiline minRows={2} value={s.remarks} onChange={(e) => set('remarks')(e.target.value)} slotProps={{ htmlInput: { maxLength: 2000 } }} />
    </FormDialog>
  )
}

export function ProductionDialog({ orderId, row, onClose }: { orderId: number; row: ProductionProgressView | null; onClose: () => void }) {
  const [add, adding] = useAddProductionProgressMutation()
  const [update, updating] = useUpdateProductionProgressMutation()
  const [s, setS] = useState({
    processStepName: text(row?.processStepName),
    plannedQuantity: text(row?.plannedQuantity),
    completedQuantity: text(row?.completedQuantity),
    acceptedQuantity: text(row?.acceptedQuantity),
    rejectedQuantity: text(row?.rejectedQuantity),
    reworkQuantity: text(row?.reworkQuantity),
    status: (row?.status ?? 'NOT_STARTED') as ProductionStatus,
    blockerReason: text(row?.blockerReason),
    revisedDispatchDate: text(row?.revisedDispatchDate),
  })
  const set = (k: keyof typeof s) => (v: string) => setS((p) => ({ ...p, [k]: v }))
  async function doSave() {
    const form = {
      processStepName: s.processStepName.trim(),
      plannedQuantity: num(s.plannedQuantity),
      completedQuantity: num(s.completedQuantity),
      acceptedQuantity: num(s.acceptedQuantity),
      rejectedQuantity: num(s.rejectedQuantity),
      reworkQuantity: num(s.reworkQuantity),
      status: s.status,
      blockerReason: str(s.blockerReason),
      revisedDispatchDate: str(s.revisedDispatchDate),
    }
    try {
      if (row) await update({ progressId: row.id, form }).unwrap()
      else await add({ id: orderId, form }).unwrap()
      onClose()
    } catch {
      // shown in the dialog
    }
  }
  return (
    <FormDialog
      title={row ? 'Edit production step' : 'Add production step'}
      busy={adding.isLoading || updating.isLoading}
      error={adding.error ?? updating.error}
      canSave={s.processStepName.trim() !== ''}
      onSave={() => void doSave()}
      onClose={onClose}
    >
      <TextField label="Process step" required value={s.processStepName} onChange={(e) => set('processStepName')(e.target.value)} slotProps={{ htmlInput: { maxLength: 200 } }} />
      <Box sx={grid2}>
        <NumberField label="Planned qty" value={s.plannedQuantity} onChange={set('plannedQuantity')} />
        <NumberField label="Completed qty" value={s.completedQuantity} onChange={set('completedQuantity')} />
        <NumberField label="Accepted qty" value={s.acceptedQuantity} onChange={set('acceptedQuantity')} />
        <NumberField label="Rejected qty" value={s.rejectedQuantity} onChange={set('rejectedQuantity')} />
        <NumberField label="Rework qty" value={s.reworkQuantity} onChange={set('reworkQuantity')} />
        <ChoiceField label="Status" value={s.status} options={PRODUCTION_STATUS} onChange={set('status')} />
        <DateField label="Revised dispatch date" value={s.revisedDispatchDate} onChange={set('revisedDispatchDate')} />
      </Box>
      <TextField label="Blocker reason" multiline minRows={2} value={s.blockerReason} onChange={(e) => set('blockerReason')(e.target.value)} slotProps={{ htmlInput: { maxLength: 2000 } }} />
    </FormDialog>
  )
}

export function PackagingDialog({ orderId, row, onClose }: { orderId: number; row: PackagingDispatchView | null; onClose: () => void }) {
  const [add, adding] = useAddPackagingDispatchMutation()
  const [update, updating] = useUpdatePackagingDispatchMutation()
  const [s, setS] = useState({
    packedQuantity: text(row?.packedQuantity),
    packagingStatus: (row?.packagingStatus ?? 'PENDING') as PackagingStatus,
    dispatchReadyFlag: row?.dispatchReadyFlag ?? false,
    dispatchDate: text(row?.dispatchDate),
    dispatchRemarks: text(row?.dispatchRemarks),
  })
  async function doSave() {
    const form = {
      packedQuantity: num(s.packedQuantity),
      packagingStatus: s.packagingStatus,
      dispatchReadyFlag: s.dispatchReadyFlag,
      dispatchDate: str(s.dispatchDate),
      dispatchRemarks: str(s.dispatchRemarks),
    }
    try {
      if (row) await update({ dispatchId: row.id, form }).unwrap()
      else await add({ id: orderId, form }).unwrap()
      onClose()
    } catch {
      // shown in the dialog
    }
  }
  return (
    <FormDialog title={row ? 'Edit packaging and dispatch' : 'Add packaging and dispatch'} busy={adding.isLoading || updating.isLoading} error={adding.error ?? updating.error} onSave={() => void doSave()} onClose={onClose}>
      <Box sx={grid2}>
        <NumberField label="Packed qty" value={s.packedQuantity} onChange={(v) => setS((p) => ({ ...p, packedQuantity: v }))} />
        <ChoiceField label="Packaging status" value={s.packagingStatus} options={PACKAGING_STATUS} onChange={(v) => setS((p) => ({ ...p, packagingStatus: v }))} />
        <DateField label="Dispatch date" value={s.dispatchDate} onChange={(v) => setS((p) => ({ ...p, dispatchDate: v }))} />
      </Box>
      <FormControlLabel
        control={<Checkbox checked={s.dispatchReadyFlag} onChange={(e) => setS((p) => ({ ...p, dispatchReadyFlag: e.target.checked }))} />}
        label="Ready for dispatch (a claim only; ERGO confirms it separately)"
      />
      <TextField label="Remarks" multiline minRows={2} value={s.dispatchRemarks} onChange={(e) => setS((p) => ({ ...p, dispatchRemarks: e.target.value }))} slotProps={{ htmlInput: { maxLength: 2000 } }} />
    </FormDialog>
  )
}

/** ERGO OFP/Admin only. POD is uploaded on an existing shipment via the dedicated endpoint. */
export function ShipmentDialog({ orderId, row, onClose }: { orderId: number; row: ShipmentView | ExternalShipmentView | null; onClose: () => void }) {
  const { data: modes = [] } = useMasterOptionsQuery('shipment-modes')
  const [add, adding] = useCreateShipmentMutation()
  const [update, updating] = useUpdateShipmentMutation()
  const [upload, uploading] = useUploadPodMutation()
  const [file, setFile] = useState<File | null>(null)
  const [s, setS] = useState({
    shipmentModeId: text(row?.shipmentModeId),
    transporterName: text(row?.transporterName),
    forwarderName: text(row?.forwarderName),
    trackingNumber: text(row?.trackingNumber),
    awbBlNo: text(row?.awbBlNo),
    eta: text(row?.eta),
    actualDeliveryDate: text(row?.actualDeliveryDate),
    status: (row?.status ?? 'DISPATCHED') as OrderStatus,
    receipt: row?.receiptConfirmedByCustomerFlag ?? false,
  })
  const set = (k: keyof typeof s) => (v: string) => setS((p) => ({ ...p, [k]: v }))
  async function doSave() {
    const form = {
      shipmentModeId: num(s.shipmentModeId),
      transporterName: str(s.transporterName),
      forwarderName: str(s.forwarderName),
      trackingNumber: str(s.trackingNumber),
      awbBlNo: str(s.awbBlNo),
      eta: str(s.eta),
      actualDeliveryDate: str(s.actualDeliveryDate),
      status: s.status,
      receiptConfirmedByCustomerFlag: s.receipt,
    }
    try {
      if (row) {
        await update({ shipmentId: row.id, form }).unwrap()
        if (file) await upload({ shipmentId: row.id, file }).unwrap()
      } else await add({ id: orderId, form }).unwrap()
      onClose()
    } catch {
      // shown in the dialog
    }
  }
  const shipmentStatuses = Object.fromEntries((['DISPATCHED', 'IN_TRANSIT', 'DELIVERED'] as const).map((k) => [k, ORDER_STATUS[k]]))
  return (
    <FormDialog title={row ? 'Edit shipment' : 'Add shipment'} busy={adding.isLoading || updating.isLoading || uploading.isLoading} error={adding.error ?? updating.error ?? uploading.error} onSave={() => void doSave()} onClose={onClose}>
      <Box sx={grid2}>
        <TextField select label="Shipment mode" value={s.shipmentModeId} onChange={(e) => set('shipmentModeId')(e.target.value)}>
          <MenuItem value="">Not set</MenuItem>
          {modes.map((m) => (
            <MenuItem key={m.id} value={String(m.id)}>
              {m.name}
            </MenuItem>
          ))}
        </TextField>
        <ChoiceField label="Status" value={s.status} options={shipmentStatuses} onChange={set('status')} />
        <TextField label="Transporter" value={s.transporterName} onChange={(e) => set('transporterName')(e.target.value)} slotProps={{ htmlInput: { maxLength: 200 } }} />
        <TextField label="Forwarder" value={s.forwarderName} onChange={(e) => set('forwarderName')(e.target.value)} slotProps={{ htmlInput: { maxLength: 200 } }} />
        <TextField label="Tracking number" value={s.trackingNumber} onChange={(e) => set('trackingNumber')(e.target.value)} slotProps={{ htmlInput: { maxLength: 100 } }} />
        <TextField label="AWB / BL no." value={s.awbBlNo} onChange={(e) => set('awbBlNo')(e.target.value)} slotProps={{ htmlInput: { maxLength: 100 } }} />
        <DateField label="ETA" value={s.eta} onChange={set('eta')} />
        <DateField label="Actual delivery date" value={s.actualDeliveryDate} onChange={set('actualDeliveryDate')} />
      </Box>
      <FormControlLabel control={<Checkbox checked={s.receipt} onChange={(e) => setS((p) => ({ ...p, receipt: e.target.checked }))} />} label="Receipt confirmed by customer" />
      {row && (
        <Button component="label" variant="outlined">
          {file ? `POD: ${file.name}` : 'Upload proof of delivery'}
          <input hidden type="file" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
        </Button>
      )}
    </FormDialog>
  )
}
