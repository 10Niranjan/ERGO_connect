import { Button, Chip, Stack, Typography } from '@mui/material'
import type { ReactNode } from 'react'
import { Link as RouterLink } from 'react-router'
import { PRIORITIES, formatDate } from '../../rfq/labels'
import type { Priority } from '../../rfq/rfqApi'
import { RISK_LEVEL } from '../../npd/labels'
import type { RiskLevel } from '../../npd/npdApi'
import {
  type Awaiting,
  type DeviationDashboardItem,
  type NcrDashboardItem,
  type NpdDashboardItem,
  type OrderDashboardItem,
  type PartStatusItem,
  type RfqDashboardItem,
  type SupplierPerformanceRow,
  type SupplierQuoteItem,
} from '../dashboardApi'
import { partStatusLabel } from '../../quotation/utils/labels'
import { AWAITING, humanize } from '../labels'
import { ItemsTable, type Column } from './ItemsTable'

function AwaitingChip({ value }: { value: Awaiting }) {
  const a = AWAITING[value]
  return <Chip size="small" label={a.label} color={a.color} />
}

/** A titled section that disappears entirely when empty (Functional dashboard - SRS §12.5: most single-module
 * roles only ever populate one of the five lists). Other audiences pass hideWhenEmpty=false and get the
 * ItemsTable's own "No items" row instead (point 7 of the dashboard spec). */
function Section({
  title,
  hidden,
  shown,
  total,
  viewAll,
  children,
}: {
  title: string
  hidden: boolean
  shown: number
  total: number
  viewAll?: string
  children: ReactNode
}) {
  if (hidden) return null
  // The server keeps the rows that need attention first and says how many there are in all (Phase 8o).
  const capped = total > shown
  return (
    <Stack spacing={1}>
      <Stack direction="row" spacing={2} sx={{ alignItems: 'baseline' }}>
        <Typography variant="h6" component="h2">
          {title}
        </Typography>
        {capped && (
          <Typography variant="body2" color="text.secondary">
            Showing the {shown} that need attention first, of {total.toLocaleString()}
          </Typography>
        )}
        {capped && viewAll && (
          <Button component={RouterLink} to={viewAll} size="small">
            View all
          </Button>
        )}
      </Stack>
      {children}
    </Stack>
  )
}

export function RfqSection({
  title = 'RFQs',
  items,
  total,
  viewAll,
  hideWhenEmpty = false,
}: {
  title?: string
  items: RfqDashboardItem[]
  total: number
  viewAll?: string
  hideWhenEmpty?: boolean
}) {
  const columns: Column<RfqDashboardItem>[] = [
    { header: 'RFQ no.', render: (r) => r.number },
    { header: 'Customer', render: (r) => r.customerName },
    { header: 'Parts', render: (r) => r.parts.join(', ') || '—' },
    { header: 'Status', render: (r) => humanize(r.status) },
    { header: 'Awaiting', render: (r) => <AwaitingChip value={r.awaiting} /> },
    {
      header: 'Target quote date',
      render: (r) => `${formatDate(r.targetQuoteDate)}${r.overdue ? ' (overdue)' : ''}`,
      cellSx: (r) => (r.overdue ? { color: 'error.main', fontWeight: 600 } : {}),
    },
    { header: 'Priority', render: (r) => PRIORITIES[r.priority as Priority] ?? r.priority },
  ]
  return (
    <Section title={title} hidden={hideWhenEmpty && items.length === 0} shown={items.length} total={total} viewAll={viewAll}>
      <ItemsTable rows={items} columns={columns} getKey={(r) => r.id} emptyMessage="No RFQs." />
    </Section>
  )
}

/** Flow doc §4: the dashboard shows the status of each part, not only of the RFQ. A customer reads the collapsed status and never sees a supplier. */
export function PartStatusSection({
  title = 'Status of each part',
  items,
  total,
  viewAll,
  showSupplier = false,
  hideWhenEmpty = false,
}: {
  title?: string
  items: PartStatusItem[]
  total: number
  viewAll?: string
  showSupplier?: boolean
  hideWhenEmpty?: boolean
}) {
  const columns: Column<PartStatusItem>[] = [
    { header: 'RFQ no.', render: (r) => r.rfqNumber },
    { header: 'Part', render: (r) => r.partNumber },
    ...(showSupplier ? [{ header: 'Supplier', render: (r: PartStatusItem) => r.supplierName ?? '—' }] : []),
    {
      header: 'Status',
      render: (r) => {
        const s = partStatusLabel(r.status as Parameters<typeof partStatusLabel>[0])
        return <Chip size="small" label={s.label} color={s.color} />
      },
    },
  ]
  return (
    <Section title={title} hidden={hideWhenEmpty && items.length === 0} shown={items.length} total={total} viewAll={viewAll}>
      <ItemsTable rows={items} columns={columns} getKey={(r) => r.rfqItemId} emptyMessage="No parts yet." />
    </Section>
  )
}

export function NpdSection({
  title = 'NPD projects',
  items,
  total,
  viewAll,
  showSupplier = false,
  hideWhenEmpty = false,
}: {
  title?: string
  items: NpdDashboardItem[]
  total: number
  viewAll?: string
  showSupplier?: boolean
  hideWhenEmpty?: boolean
}) {
  const columns: Column<NpdDashboardItem>[] = [
    { header: 'NPD no.', render: (r) => r.npdNumber },
    { header: 'Customer', render: (r) => r.customerName },
    ...(showSupplier ? [{ header: 'Supplier', render: (r: NpdDashboardItem) => r.supplierName ?? '—' }] : []),
    { header: 'Stage', render: (r) => humanize(r.stage) },
    { header: 'Awaiting', render: (r) => <AwaitingChip value={r.awaiting} /> },
    { header: 'Target sample date', render: (r) => formatDate(r.targetSampleDate) },
    {
      header: 'Delay',
      render: (r) => (r.delayDays != null ? `${r.delayDays}d` : '—'),
      cellSx: (r) => (r.delayDays != null && r.delayDays > 0 ? { color: 'error.main', fontWeight: 600 } : {}),
    },
    {
      header: 'Risk',
      render: (r) => (r.riskLevel ? <Chip size="small" label={RISK_LEVEL[r.riskLevel as RiskLevel]?.label ?? r.riskLevel} color={RISK_LEVEL[r.riskLevel as RiskLevel]?.color} /> : '—'),
    },
  ]
  return (
    <Section title={title} hidden={hideWhenEmpty && items.length === 0} shown={items.length} total={total} viewAll={viewAll}>
      <ItemsTable rows={items} columns={columns} getKey={(r) => r.id} emptyMessage="No NPD projects." />
    </Section>
  )
}

export function OrderSection({
  title = 'Orders',
  items,
  total,
  viewAll,
  showSupplier = false,
  hideWhenEmpty = false,
}: {
  title?: string
  items: OrderDashboardItem[]
  total: number
  viewAll?: string
  showSupplier?: boolean
  hideWhenEmpty?: boolean
}) {
  const columns: Column<OrderDashboardItem>[] = [
    { header: 'Order no.', render: (r) => r.orderNumber },
    { header: 'Customer', render: (r) => r.customerName },
    ...(showSupplier ? [{ header: 'Supplier', render: (r: OrderDashboardItem) => r.supplierName ?? '—' }] : []),
    { header: 'Status', render: (r) => humanize(r.status) },
    { header: 'Awaiting', render: (r) => <AwaitingChip value={r.awaiting} /> },
    {
      header: 'Committed dispatch',
      render: (r) => `${formatDate(r.committedDispatchDate)}${r.atRisk ? ' (at risk)' : ''}`,
      cellSx: (r) => (r.atRisk ? { color: 'error.main', fontWeight: 600 } : {}),
    },
  ]
  return (
    <Section title={title} hidden={hideWhenEmpty && items.length === 0} shown={items.length} total={total} viewAll={viewAll}>
      <ItemsTable rows={items} columns={columns} getKey={(r) => r.id} emptyMessage="No orders." />
    </Section>
  )
}

export function NcrSection({
  title = 'NCRs',
  items,
  total,
  viewAll,
  showSupplier = false,
  hideWhenEmpty = false,
}: {
  title?: string
  items: NcrDashboardItem[]
  total: number
  viewAll?: string
  showSupplier?: boolean
  hideWhenEmpty?: boolean
}) {
  const columns: Column<NcrDashboardItem>[] = [
    { header: 'NCR no.', render: (r) => r.ncrNumber },
    { header: 'Customer', render: (r) => r.customerName },
    ...(showSupplier ? [{ header: 'Supplier', render: (r: NcrDashboardItem) => r.supplierName ?? '—' }] : []),
    { header: 'Status', render: (r) => humanize(r.status) },
    { header: 'Awaiting', render: (r) => <AwaitingChip value={r.awaiting} /> },
    {
      header: 'Target closure date',
      render: (r) => `${formatDate(r.targetClosureDate)}${r.overdue ? ' (overdue)' : ''}`,
      cellSx: (r) => (r.overdue ? { color: 'error.main', fontWeight: 600 } : {}),
    },
  ]
  return (
    <Section title={title} hidden={hideWhenEmpty && items.length === 0} shown={items.length} total={total} viewAll={viewAll}>
      <ItemsTable rows={items} columns={columns} getKey={(r) => r.id} emptyMessage="No NCRs." />
    </Section>
  )
}

export function DeviationSection({
  title = 'Deviations',
  items,
  total,
  viewAll,
  showSupplier = false,
  hideWhenEmpty = false,
}: {
  title?: string
  items: DeviationDashboardItem[]
  total: number
  viewAll?: string
  showSupplier?: boolean
  hideWhenEmpty?: boolean
}) {
  const columns: Column<DeviationDashboardItem>[] = [
    { header: 'Deviation no.', render: (r) => r.deviationNumber },
    { header: 'Customer', render: (r) => r.customerName },
    ...(showSupplier ? [{ header: 'Supplier', render: (r: DeviationDashboardItem) => r.supplierName ?? '—' }] : []),
    { header: 'Status', render: (r) => humanize(r.status) },
    { header: 'Awaiting', render: (r) => <AwaitingChip value={r.awaiting} /> },
    { header: 'Validity end date', render: (r) => formatDate(r.validityEndDate) },
  ]
  return (
    <Section title={title} hidden={hideWhenEmpty && items.length === 0} shown={items.length} total={total} viewAll={viewAll}>
      <ItemsTable rows={items} columns={columns} getKey={(r) => r.id} emptyMessage="No deviations." />
    </Section>
  )
}

export function QuoteSection({ title = 'Assigned RFQs', items, total, viewAll }: { title?: string; items: SupplierQuoteItem[]; total: number; viewAll?: string }) {
  const columns: Column<SupplierQuoteItem>[] = [
    { header: 'RFQ no.', render: (r) => r.rfqNumber },
    { header: 'Part no.', render: (r) => r.partNumber },
    { header: 'Status', render: (r) => humanize(r.status) },
    {
      header: 'Response due',
      render: (r) => `${formatDate(r.responseDueDate)}${r.overdue ? ' (overdue)' : ''}`,
      cellSx: (r) => (r.overdue ? { color: 'error.main', fontWeight: 600 } : {}),
    },
  ]
  return (
    <Section title={title} hidden={false} shown={items.length} total={total} viewAll={viewAll}>
      <ItemsTable rows={items} columns={columns} getKey={(r) => r.invitationId} emptyMessage="No RFQs assigned." />
    </Section>
  )
}

export function SupplierPerformanceSection({ title = 'Supplier performance', rows }: { title?: string; rows: SupplierPerformanceRow[] }) {
  const columns: Column<SupplierPerformanceRow>[] = [
    { header: 'Supplier', render: (r) => r.supplierName },
    { header: 'Open orders', align: 'right', render: (r) => r.openOrderCount },
    { header: 'Open NCRs', align: 'right', render: (r) => r.openNcrCount },
    { header: 'Dispatched orders', align: 'right', render: (r) => r.dispatchedOrderCount },
    { header: 'On-time dispatched', align: 'right', render: (r) => r.onTimeDispatchedCount },
    {
      header: 'On-time %',
      align: 'right',
      render: (r) => (r.dispatchedOrderCount > 0 ? `${Math.round((r.onTimeDispatchedCount / r.dispatchedOrderCount) * 100)}%` : '—'),
    },
  ]
  return (
    <Section title={title} hidden={false} shown={rows.length} total={rows.length}>
      <ItemsTable rows={rows} columns={columns} getKey={(r) => r.supplierCompanyId} emptyMessage="No supplier activity yet." />
    </Section>
  )
}
