import { Alert, Button, Chip, Link, LinearProgress, Paper, Stack, Tab, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Tabs, Typography, Box } from '@mui/material'
import { useState, type ReactNode } from 'react'
import { Link as RouterLink, useParams } from 'react-router'
import { CommentsPanel } from '../../communication/components/CommentsPanel'
import { AuditTrail } from '../../../shared/components/AuditTrail'
import { CommentDialog } from '../../../shared/components/CommentDialog'
import { Facts } from '../../../shared/components/Facts'
import { useMasterOptionsQuery } from '../../../shared/masterData'
import { problemMessage } from '../../../shared/problem'
import { formatDate, formatNumber } from '../../rfq/labels'
import { INSPECTION_STATUS, MATERIAL_STATUS, ORDER_STATUS, PACKAGING_STATUS, PRODUCTION_STATUS, isDelayed, statusProgress } from '../labels'
import { MaterialDialog, OrderEditDialog, PackagingDialog, ProductionDialog, ShipmentDialog } from '../components/OrderDialogs'
import {
  useAdvanceOrderMutation,
  useCloseOrderMutation,
  useDispatchOrderMutation,
  useDownloadPodMutation,
  useHoldOrderMutation,
  useMarkDeliveredMutation,
  useMarkDispatchReadyMutation,
  useMarkInTransitMutation,
  useOfpOrderQuery,
  useOfpOrderHistoryQuery,
  useReleaseOrderMutation,
  type CustomerProductionProgressView,
  type ExternalShipmentView,
  type MaterialStatusView,
  type OrderAction,
  type OrderDetail,
  type PackagingDispatchView,
  type ProductionProgressView,
  type ShipmentView,
  type SupplierMaterialStatusView,
} from '../ofpApi'

export function OrderDetailPage() {
  const id = Number(useParams().id)
  const { data: order, isLoading, error } = useOfpOrderQuery(id)
  if (isLoading) return <LinearProgress />
  if (!order) return <Alert severity="error">{problemMessage(error)}</Alert>
  return <OrderDetailView order={order} />
}

/** Status-changing buttons. Each is rendered only when the API listed its action; `required` = a reason is mandatory. */
const WORKFLOW: { action: OrderAction; label: string; required: boolean; danger?: boolean; intro?: string }[] = [
  { action: 'ADVANCE', label: 'Advance status', required: false, intro: 'Moves the order to the next production status.' },
  { action: 'MARK_DISPATCH_READY', label: 'Mark dispatch ready', required: false, intro: 'Blocked until every material inspection has passed.' },
  { action: 'DISPATCH', label: 'Dispatch', required: false },
  { action: 'MARK_IN_TRANSIT', label: 'Mark in transit', required: false },
  { action: 'MARK_DELIVERED', label: 'Mark delivered', required: false, intro: 'Requires a proof of delivery on file.' },
  { action: 'CLOSE_ORDER', label: 'Close order', required: false, intro: 'Closing is final.' },
  { action: 'HOLD', label: 'Put on hold', required: true, danger: true },
  { action: 'RELEASE', label: 'Release from hold', required: false },
]

type Dialog = { kind: 'workflow'; action: OrderAction } | { kind: 'edit' } | { kind: 'material'; row: MaterialStatusView | SupplierMaterialStatusView | null } | { kind: 'production'; row: ProductionProgressView | null } | { kind: 'packaging'; row: PackagingDispatchView | null } | { kind: 'shipment'; row: ShipmentView | ExternalShipmentView | null }

function OrderDetailView({ order }: { order: OrderDetail }) {
  const [tab, setTab] = useState('overview')
  const [dialog, setDialog] = useState<Dialog | null>(null)
  const { data: modes = [] } = useMasterOptionsQuery('shipment-modes')
  const [advance, advancing] = useAdvanceOrderMutation()
  const [ready, readying] = useMarkDispatchReadyMutation()
  const [dispatch, dispatching] = useDispatchOrderMutation()
  const [transit, transiting] = useMarkInTransitMutation()
  const [deliver, delivering] = useMarkDeliveredMutation()
  const [close, closing] = useCloseOrderMutation()
  const [hold, holding] = useHoldOrderMutation()
  const [release, releasing] = useReleaseOrderMutation()

  const runners: Partial<Record<OrderAction, { run: (comment: string) => Promise<unknown>; state: { isLoading: boolean; error?: unknown } }>> = {
    ADVANCE: { run: (c) => advance({ id: order.id, comment: c || undefined }).unwrap(), state: advancing },
    MARK_DISPATCH_READY: { run: (c) => ready({ id: order.id, comment: c || undefined }).unwrap(), state: readying },
    DISPATCH: { run: (c) => dispatch({ id: order.id, comment: c || undefined }).unwrap(), state: dispatching },
    MARK_IN_TRANSIT: { run: (c) => transit({ id: order.id, comment: c || undefined }).unwrap(), state: transiting },
    MARK_DELIVERED: { run: (c) => deliver({ id: order.id, comment: c || undefined }).unwrap(), state: delivering },
    CLOSE_ORDER: { run: (c) => close({ id: order.id, comment: c || undefined }).unwrap(), state: closing },
    HOLD: { run: (c) => hold({ id: order.id, comment: c }).unwrap(), state: holding },
    RELEASE: { run: (c) => release({ id: order.id, comment: c || undefined }).unwrap(), state: releasing },
  }

  // Audience is told apart structurally (the backend sends no discriminant). `in` narrows to the one shape that has the field.
  const ergo = 'customer' in order ? order : null
  const has = (a: OrderAction) => order.actions.includes(a)
  const status = ORDER_STATUS[order.currentStatus]
  const workflowDialog = dialog?.kind === 'workflow' ? WORKFLOW.find((w) => w.action === dialog.action) : undefined
  const runner = dialog?.kind === 'workflow' ? runners[dialog.action] : undefined
  const modeName = (id: number | null) => (id == null ? '—' : (modes.find((m) => m.id === id)?.name ?? '—'))
  const closeDialog = () => setDialog(null)

  async function confirm(comment: string) {
    try {
      await runner?.run(comment)
      closeDialog()
    } catch {
      // shown in the dialog
    }
  }

  const facts: [string, ReactNode][] = [['Order number', order.orderNumber]]
  if (ergo) facts.push(['Customer', ergo.customer], ['Supplier', ergo.supplier])
  if ('customerPoNo' in order) facts.push(['Customer PO', order.customerPoNo ?? '—'])
  if ('supplierPoNo' in order) facts.push(['Supplier PO', order.supplierPoNo ?? '—'])
  facts.push(['Part number', order.partNumber], ['Revision', order.revision ?? '—'], ['Ordered quantity', formatNumber(order.orderedQuantity)])
  const dates: [string, ReactNode][] = [
    ['Due date', formatDate(order.dueDate)],
    ['Committed dispatch', formatDate(order.committedDispatchDate)],
    ['Actual dispatch', formatDate(order.actualDispatchDate)],
  ]
  if (ergo) dates.push(['Owner', ergo.owner ?? 'Not assigned'], ['NPD project', ergo.linkedNpdProjectId ? <Link component={RouterLink} to={`/npd/${ergo.linkedNpdProjectId}`}>Open project</Link> : '—'],
    ['RFQ', ergo.linkedRfqId ? <Link component={RouterLink} to={`/rfqs/${ergo.linkedRfqId}`}>Open RFQ</Link> : '—'],
  )

  const production = 'productionProgress' in order ? order.productionProgress : []
  const packaging = 'packagingDispatch' in order ? order.packagingDispatch : null

  return (
    <Stack spacing={2}>
      <Stack direction={{ xs: 'column', md: 'row' }} sx={{ justifyContent: 'space-between', gap: 2 }}>
        <Stack spacing={0.5}>
          <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
            <Typography variant="h5" component="h1">
              {order.orderNumber}
            </Typography>
            <Chip label={status.label} color={status.color} size="small" />
            <Typography variant="caption" color="text.secondary">
              {statusProgress(order.currentStatus).label}
            </Typography>
          </Stack>
          <Typography color="text.secondary">
            {ergo && `${ergo.customer} → ${ergo.supplier} · `}
            {order.partNumber}
            {order.revision ? ` rev ${order.revision}` : ''} · Qty {formatNumber(order.orderedQuantity)}
          </Typography>
          <Typography variant="body2" sx={isDelayed(order.dueDate, order.currentStatus) ? { color: 'error.main', fontWeight: 600 } : { color: 'text.secondary' }}>
            Due {formatDate(order.dueDate)}
            {isDelayed(order.dueDate, order.currentStatus) && ' · delayed'}
          </Typography>
        </Stack>
        <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap', alignItems: 'flex-start' }}>
          {ergo && has('EDIT') && <Button onClick={() => setDialog({ kind: 'edit' })}>Edit order</Button>}
          {WORKFLOW.filter((w) => has(w.action)).map((w) => (
            <Button key={w.action} variant={w.danger ? 'outlined' : 'contained'} color={w.danger ? 'error' : 'primary'} onClick={() => setDialog({ kind: 'workflow', action: w.action })}>
              {w.label}
            </Button>
          ))}
        </Stack>
      </Stack>

      <Tabs value={tab} onChange={(_e, v: string) => setTab(v)}>
        <Tab value="overview" label="Overview" />
        <Tab value="material" label="Material" />
        <Tab value="production" label="Production" />
        {packaging && <Tab value="packaging" label="Packaging and dispatch" />}
        <Tab value="shipments" label="Shipments" />
        <Tab value="comments" label="Comments" />
        {ergo && <Tab value="audit" label="Audit trail" />}
      </Tabs>

      {tab === 'overview' && (
        <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', md: '1fr 1fr' } }}>
          <Facts title="Order" facts={facts} />
          <Facts title="Schedule" facts={dates} />
        </Box>
      )}

      {tab === 'material' && (
        <Section
          canAdd={has('UPDATE_MATERIAL_STATUS')}
          addLabel="Add material status"
          onAdd={() => setDialog({ kind: 'material', row: null })}
          head={['Material', 'Inspection', 'Ordered', 'Expected', 'Received', ...(ergo ? ['Remarks'] : []), '']}
          empty={order.materialStatus.length === 0}
        >
          {order.materialStatus.map((m) => (
            <TableRow key={m.id}>
              <TableCell>
                <Chip size="small" label={MATERIAL_STATUS[m.materialStatus].label} color={MATERIAL_STATUS[m.materialStatus].color} />
              </TableCell>
              <TableCell>
                <Chip size="small" label={INSPECTION_STATUS[m.inspectionStatus].label} color={INSPECTION_STATUS[m.inspectionStatus].color} />
              </TableCell>
              <TableCell>{formatDate(m.materialOrderDate)}</TableCell>
              <TableCell>{formatDate(m.expectedReceiptDate)}</TableCell>
              <TableCell>{formatDate(m.actualReceiptDate)}</TableCell>
              {'remarks' in m && <TableCell sx={{ maxWidth: 260, wordBreak: 'break-word' }}>{m.remarks ?? '—'}</TableCell>}
              <TableCell align="right">
                {has('UPDATE_MATERIAL_STATUS') && (
                  <Button size="small" onClick={() => setDialog({ kind: 'material', row: m })}>
                    Edit
                  </Button>
                )}
              </TableCell>
            </TableRow>
          ))}
        </Section>
      )}

      {tab === 'production' && (
        <Section
          canAdd={has('UPDATE_PRODUCTION_PROGRESS')}
          addLabel="Add production step"
          onAdd={() => setDialog({ kind: 'production', row: null })}
          head={['Step', 'Planned', 'Completed', 'Accepted', 'Rejected', 'Rework', 'Status', 'Revised dispatch', ...('packagingDispatch' in order ? ['Blocker', 'Updated by'] : []), '']}
          empty={production.length === 0}
        >
          {production.map((p) => (
            <TableRow key={p.id}>
              <TableCell>{p.processStepName}</TableCell>
              <TableCell align="right">{formatNumber(p.plannedQuantity)}</TableCell>
              <TableCell align="right">{formatNumber(p.completedQuantity)}</TableCell>
              <TableCell align="right">{formatNumber(p.acceptedQuantity)}</TableCell>
              <TableCell align="right">{formatNumber(p.rejectedQuantity)}</TableCell>
              <TableCell align="right">{formatNumber(p.reworkQuantity)}</TableCell>
              <TableCell>
                <Chip size="small" label={PRODUCTION_STATUS[p.status].label} color={PRODUCTION_STATUS[p.status].color} />
              </TableCell>
              <TableCell>{formatDate(p.revisedDispatchDate)}</TableCell>
              {isFullProgress(p) && (
                <>
                  <TableCell sx={{ maxWidth: 220, wordBreak: 'break-word' }}>{p.blockerReason ?? '—'}</TableCell>
                  <TableCell>{p.updatedBy ?? '—'}</TableCell>
                </>
              )}
              <TableCell align="right">
                {has('UPDATE_PRODUCTION_PROGRESS') && isFullProgress(p) && (
                  <Button size="small" onClick={() => setDialog({ kind: 'production', row: p })}>
                    Edit
                  </Button>
                )}
              </TableCell>
            </TableRow>
          ))}
        </Section>
      )}

      {tab === 'packaging' && packaging && (
        <Section
          canAdd={has('UPDATE_PACKAGING_DISPATCH')}
          addLabel="Add packaging record"
          onAdd={() => setDialog({ kind: 'packaging', row: null })}
          head={['Packed qty', 'Status', 'Ready flag', 'Dispatch date', 'Remarks', 'Updated by', '']}
          empty={packaging.length === 0}
        >
          {packaging.map((p) => (
            <TableRow key={p.id}>
              <TableCell align="right">{formatNumber(p.packedQuantity)}</TableCell>
              <TableCell>
                <Chip size="small" label={PACKAGING_STATUS[p.packagingStatus].label} color={PACKAGING_STATUS[p.packagingStatus].color} />
              </TableCell>
              <TableCell>{p.dispatchReadyFlag ? 'Yes' : 'No'}</TableCell>
              <TableCell>{formatDate(p.dispatchDate)}</TableCell>
              <TableCell sx={{ maxWidth: 260, wordBreak: 'break-word' }}>{p.dispatchRemarks ?? '—'}</TableCell>
              <TableCell>{p.updatedBy ?? '—'}</TableCell>
              <TableCell align="right">
                {has('UPDATE_PACKAGING_DISPATCH') && (
                  <Button size="small" onClick={() => setDialog({ kind: 'packaging', row: p })}>
                    Edit
                  </Button>
                )}
              </TableCell>
            </TableRow>
          ))}
        </Section>
      )}

      {tab === 'comments' && <CommentsPanel linkedModule="ofp" recordId={order.id} />}
      {tab === 'audit' && ergo && <OrderHistoryTab orderId={order.id} />}

      {tab === 'shipments' && (
        <Section
          canAdd={has('CREATE_SHIPMENT')}
          addLabel="Add shipment"
          onAdd={() => setDialog({ kind: 'shipment', row: null })}
          head={['Mode', 'Transporter', 'Forwarder', 'Tracking', 'AWB / BL', 'ETA', 'Delivered', 'Receipt confirmed', 'Status', 'POD', '']}
          empty={order.shipments.length === 0}
        >
          {order.shipments.map((s) => (
            <ShipmentRow key={s.id} s={s} mode={modeName(s.shipmentModeId)} canEdit={has('UPDATE_SHIPMENT')} canPod={ergo !== null} onEdit={() => setDialog({ kind: 'shipment', row: s })} />
          ))}
        </Section>
      )}

      {ergo && dialog?.kind === 'edit' && <OrderEditDialog order={ergo} onClose={closeDialog} />}
      {dialog?.kind === 'material' && <MaterialDialog orderId={order.id} row={dialog.row} onClose={closeDialog} />}
      {dialog?.kind === 'production' && <ProductionDialog orderId={order.id} row={dialog.row} onClose={closeDialog} />}
      {dialog?.kind === 'packaging' && <PackagingDialog orderId={order.id} row={dialog.row} onClose={closeDialog} />}
      {dialog?.kind === 'shipment' && <ShipmentDialog orderId={order.id} row={dialog.row} onClose={closeDialog} />}
      {workflowDialog && runner && (
        <CommentDialog
          title={workflowDialog.label}
          label={workflowDialog.required ? 'Reason' : 'Comment (optional)'}
          required={workflowDialog.required}
          danger={workflowDialog.danger}
          intro={workflowDialog.intro}
          confirmLabel={workflowDialog.label}
          busy={runner.state.isLoading}
          error={runner.state.error}
          onConfirm={(c) => void confirm(c)}
          onClose={closeDialog}
        />
      )}
    </Stack>
  )
}

/** ProductionProgressView is a structural subtype of the customer's row, so `in` alone cannot narrow it. */
function isFullProgress(p: ProductionProgressView | CustomerProductionProgressView): p is ProductionProgressView {
  return 'updatedBy' in p
}

/** SRS §12.9.2/§15.5 audit trail tab, ERGO only (server-enforced; the tab itself is also hidden for other audiences). */
function OrderHistoryTab({ orderId }: { orderId: number }) {
  const { data = [], error } = useOfpOrderHistoryQuery(orderId)
  if (error) return <Alert severity="error">{problemMessage(error)}</Alert>
  return <AuditTrail rows={data} />
}

function Section(props: { canAdd: boolean; addLabel: string; onAdd: () => void; head: string[]; empty: boolean; children: ReactNode }) {
  return (
    <Stack spacing={2}>
      <Stack direction="row" sx={{ justifyContent: 'flex-end' }}>
        {props.canAdd && (
          <Button variant="contained" onClick={props.onAdd}>
            {props.addLabel}
          </Button>
        )}
      </Stack>
      <TableContainer component={Paper} variant="outlined">
        <Table size="small">
          <TableHead>
            <TableRow>
              {props.head.map((h, i) => (
                <TableCell key={i}>{h}</TableCell>
              ))}
            </TableRow>
          </TableHead>
          <TableBody>
            {props.children}
            {props.empty && (
              <TableRow>
                <TableCell colSpan={props.head.length} sx={{ color: 'text.secondary' }}>
                  Nothing recorded yet.
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </TableContainer>
    </Stack>
  )
}

/** POD: ERGO only. Suppliers and customers never get the file (canPod false); customers see receipt fields. */
function ShipmentRow({ s, mode, canEdit, canPod, onEdit }: { s: ShipmentView | ExternalShipmentView; mode: string; canEdit: boolean; canPod: boolean; onEdit: () => void }) {
  const [download, downloading] = useDownloadPodMutation()
  const hasPod = 'podDocumentId' in s ? s.podDocumentId != null : true
  async function doDownload() {
    try {
      const { url, fileName } = await download(s.id).unwrap()
      const a = document.createElement('a')
      a.href = url
      a.download = fileName
      a.click()
      setTimeout(() => URL.revokeObjectURL(url), 10_000)
    } catch {
      // shown below
    }
  }
  return (
    <TableRow>
      <TableCell>{mode}</TableCell>
      <TableCell>{s.transporterName ?? '—'}</TableCell>
      <TableCell>{s.forwarderName ?? '—'}</TableCell>
      <TableCell>{s.trackingNumber ?? '—'}</TableCell>
      <TableCell>{s.awbBlNo ?? '—'}</TableCell>
      <TableCell>{formatDate(s.eta)}</TableCell>
      <TableCell>{formatDate(s.actualDeliveryDate)}</TableCell>
      <TableCell>{s.receiptConfirmedByCustomerFlag ? `Yes${s.receiptConfirmedAt ? ` (${formatDate(s.receiptConfirmedAt)})` : ''}` : 'No'}</TableCell>
      <TableCell>
        <Chip size="small" label={ORDER_STATUS[s.status].label} color={ORDER_STATUS[s.status].color} />
      </TableCell>
      <TableCell>
        {canPod && hasPod ? (
          <>
            <Button size="small" loading={downloading.isLoading} onClick={() => void doDownload()}>
              {'podFileName' in s && s.podFileName ? s.podFileName : 'Download POD'}
            </Button>
            {downloading.error != null && (
              <Typography variant="caption" color="error" sx={{ display: 'block' }}>
                {problemMessage(downloading.error)}
              </Typography>
            )}
          </>
        ) : (
          '—'
        )}
      </TableCell>
      <TableCell align="right">
        {canEdit && (
          <Button size="small" onClick={onEdit}>
            Edit
          </Button>
        )}
      </TableCell>
    </TableRow>
  )
}
