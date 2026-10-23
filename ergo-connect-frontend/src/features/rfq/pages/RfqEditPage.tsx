import { Alert, Button, Card, CardContent, LinearProgress, MenuItem, Stack, TextField, Typography } from '@mui/material'
import { useState } from 'react'
import { useNavigate, useParams } from 'react-router'
import { useAppSelector } from '../../../app/store'
import { FormSection as Section } from '../../../shared/components/FormSection'
import { num, str, text } from '../../../shared/form'
import { useMasterOptionsQuery, type MasterOption } from '../../../shared/masterData'
import { problemMessage } from '../../../shared/problem'
import { useCompaniesQuery } from '../../companies/companiesApi'
import { ProcessFlowTable } from '../components/ProcessFlowTable'
import { EMPTY_STEP, toProcessSteps, toStepRows, type StepRow } from '../processFlow'
import { PRIORITIES } from '../labels'
import { useCreateRfqMutation, useRfqQuery, useUpdateRfqMutation, type ItemForm, type Priority, type RfqForm, type RfqView } from '../rfqApi'

/** The request-wide fields (flow doc §1: one RFQ, many parts). */
interface HeaderState {
  customerCompanyId: string
  targetQuoteDate: string
  priority: Priority
  currencyId: string
  paymentTermId: string
  customerRemarks: string
  internalNotes: string
}

type PartFields = Exclude<keyof ItemForm, 'id' | 'processSteps'>

/** One part of the RFQ in the form; `id` is kept for a part that already exists, so its quotes stay with it. */
type PartState = Record<PartFields, string> & { id: number | null; key: number; steps: StepRow[] }

let nextKey = 1

function emptyPart(): PartState {
  return {
    id: null,
    key: nextKey++,
    partNumber: '',
    revision: '',
    description: '',
    eau: '',
    netWeightKg: '',
    grossWeightKg: '',
    materialId: '',
    materialSpec: '',
    coatingId: '',
    coatingSpec: '',
    targetToolingCost: '',
    targetPpapPiecePrice: '',
    targetProductionPiecePrice: '',
    materialLeadTimeWeeks: '',
    toolingLeadTimeDays: '',
    sampleLeadTimeDays: '',
    productionLeadTimeDays: '',
    moq: '',
    steps: [{ ...EMPTY_STEP }, { ...EMPTY_STEP }], // SRS §5.5: starts with 2 rows
  }
}

function headerFromView(view: RfqView | undefined): HeaderState {
  return {
    customerCompanyId: view?.audience === 'ERGO' ? String(view.customer.id) : '',
    targetQuoteDate: text(view?.targetQuoteDate),
    priority: view?.priority ?? 'NORMAL',
    currencyId: text(view?.currency.id),
    paymentTermId: text(view?.paymentTerm?.id),
    customerRemarks: text(view?.customerRemarks),
    internalNotes: view?.audience === 'ERGO' ? text(view.internalNotes) : '',
  }
}

function partsFromView(view: RfqView | undefined): PartState[] {
  if (!view) return [emptyPart()]
  return view.items.map((i) => ({
    id: i.id,
    key: nextKey++,
    partNumber: text(i.partNumber),
    revision: text(i.revision),
    description: text(i.description),
    eau: text(i.eau),
    netWeightKg: text(i.netWeightKg),
    grossWeightKg: text(i.grossWeightKg),
    materialId: text(i.material?.id),
    materialSpec: text(i.materialSpec),
    coatingId: text(i.coating?.id),
    coatingSpec: text(i.coatingSpec),
    targetToolingCost: text(i.targets.targetToolingCost),
    targetPpapPiecePrice: text(i.targets.targetPpapPiecePrice),
    targetProductionPiecePrice: text(i.targets.targetProductionPiecePrice),
    materialLeadTimeWeeks: text(i.targets.materialLeadTimeWeeks),
    toolingLeadTimeDays: text(i.targets.toolingLeadTimeDays),
    sampleLeadTimeDays: text(i.targets.sampleLeadTimeDays),
    productionLeadTimeDays: text(i.targets.productionLeadTimeDays),
    moq: text(i.targets.moq),
    steps: toStepRows(i.processSteps.map((s) => ({ ...s, processId: s.process?.id ?? null }))),
  }))
}

function toForm(header: HeaderState, parts: PartState[]): RfqForm {
  return {
    customerCompanyId: num(header.customerCompanyId),
    targetQuoteDate: str(header.targetQuoteDate),
    priority: header.priority,
    currencyId: num(header.currencyId),
    paymentTermId: num(header.paymentTermId),
    customerRemarks: str(header.customerRemarks),
    internalNotes: str(header.internalNotes),
    items: parts.map((p) => ({
      id: p.id,
      partNumber: p.partNumber.trim(),
      revision: str(p.revision),
      description: str(p.description),
      eau: num(p.eau),
      netWeightKg: num(p.netWeightKg),
      grossWeightKg: num(p.grossWeightKg),
      materialId: num(p.materialId),
      materialSpec: str(p.materialSpec),
      coatingId: num(p.coatingId),
      coatingSpec: str(p.coatingSpec),
      targetToolingCost: num(p.targetToolingCost),
      targetPpapPiecePrice: num(p.targetPpapPiecePrice),
      targetProductionPiecePrice: num(p.targetProductionPiecePrice),
      materialLeadTimeWeeks: num(p.materialLeadTimeWeeks),
      toolingLeadTimeDays: num(p.toolingLeadTimeDays),
      sampleLeadTimeDays: num(p.sampleLeadTimeDays),
      productionLeadTimeDays: num(p.productionLeadTimeDays),
      moq: num(p.moq),
      processSteps: toProcessSteps(p.steps),
    })),
  }
}

/** New RFQ (no id) or edit (id). */
export function RfqEditPage() {
  const { id } = useParams()
  const { data: view, isLoading, error } = useRfqQuery(Number(id), { skip: id === undefined })
  if (id !== undefined && isLoading) return <LinearProgress />
  if (error) return <Alert severity="error">{problemMessage(error)}</Alert>
  return <RfqFormEditor key={view?.id ?? 'new'} view={view} />
}

function RfqFormEditor({ view }: { view: RfqView | undefined }) {
  const isErgo = useAppSelector((state) => state.auth.session?.user.companyType === 'ERGO')
  const navigate = useNavigate()
  const [header, setHeader] = useState<HeaderState>(() => headerFromView(view))
  const [parts, setParts] = useState<PartState[]>(() => partsFromView(view))
  const [createRfq, creating] = useCreateRfqMutation()
  const [updateRfq, updating] = useUpdateRfqMutation()
  const { data: companies = [] } = useCompaniesQuery(undefined, { skip: !isErgo || view !== undefined })
  const lists = [
    useMasterOptionsQuery('materials'),
    useMasterOptionsQuery('coatings'),
    useMasterOptionsQuery('currencies'),
    useMasterOptionsQuery('payment-terms'),
    useMasterOptionsQuery('processes'),
  ]
  const [materials, coatings, currencies, paymentTerms, processes] = lists.map((list) => list.data ?? [])
  const error = creating.error ?? updating.error

  const setH = (key: keyof HeaderState) => (event: { target: { value: string } }) =>
    setHeader((h) => ({ ...h, [key]: event.target.value }))
  const hField = (key: Exclude<keyof HeaderState, 'priority'>, label: string, extra: object = {}) => (
    <TextField label={label} value={header[key]} onChange={setH(key)} fullWidth {...extra} />
  )
  const hSelect = (key: Exclude<keyof HeaderState, 'priority'>, label: string, options: { id: number; name: string; code?: string }[], extra: object = {}) =>
    hField(key, label, { select: true, children: optionItems(options), ...extra })

  // a select cannot show a saved value before its options arrive
  if (lists.some((list) => list.isLoading)) return <LinearProgress />

  if (!view && !header.currencyId && currencies.length > 0) {
    const inr = currencies.find((c) => c.code === 'INR') ?? currencies[0]
    setHeader((h) => ({ ...h, currencyId: String(inr.id) }))
  }

  async function save() {
    try {
      const form = toForm(header, parts)
      const saved = view ? await updateRfq({ id: view.id, form }).unwrap() : await createRfq(form).unwrap()
      navigate(`/rfqs/${saved.id}`)
    } catch {
      // shown below
    }
  }

  const updatePart = (key: number, patch: Partial<PartState>) => setParts((list) => list.map((p) => (p.key === key ? { ...p, ...patch } : p)))
  const customers = companies.filter((c) => c.type === 'CUSTOMER' && c.status === 'ACTIVE')

  return (
    <Stack spacing={2} sx={{ maxWidth: 1200 }}>
      <Typography variant="h5" component="h1">
        {view ? `Edit ${view.number}` : 'New RFQ'}
      </Typography>

      <Section title="Request">
        {isErgo &&
          (view ? (
            <TextField label="Customer" value={view.audience === 'ERGO' ? view.customer.name : ''} disabled fullWidth />
          ) : (
            hSelect('customerCompanyId', 'Customer', customers.map((c) => ({ id: c.id, name: c.profile.name })), { required: true })
          ))}
        {hField('targetQuoteDate', 'Quote needed by', { type: 'date', slotProps: { inputLabel: { shrink: true } } })}
        <TextField select label="Priority" value={header.priority} onChange={(e) => setHeader((h) => ({ ...h, priority: e.target.value as Priority }))} fullWidth>
          {Object.entries(PRIORITIES).map(([value, label]) => (
            <MenuItem key={value} value={value}>
              {label}
            </MenuItem>
          ))}
        </TextField>
        {hSelect('currencyId', 'Currency of your target prices', currencies, { required: true })}
        {hSelect('paymentTermId', 'Payment terms', paymentTerms)}
      </Section>

      {parts.map((part, index) => (
        <PartEditor
          key={part.key}
          index={index}
          part={part}
          materials={materials}
          coatings={coatings}
          processes={processes}
          canRemove={parts.length > 1}
          onChange={(patch) => updatePart(part.key, patch)}
          onRemove={() => setParts((list) => list.filter((p) => p.key !== part.key))}
        />
      ))}
      <Stack direction="row">
        <Button variant="outlined" onClick={() => setParts((list) => [...list, emptyPart()])}>
          Add another part
        </Button>
      </Stack>

      <Section title="Remarks">
        {hField('customerRemarks', 'Requirements and remarks', { multiline: true, minRows: 3, sx: { gridColumn: '1 / -1' }, slotProps: { htmlInput: { maxLength: 4000 } } })}
        {isErgo &&
          hField('internalNotes', 'Internal notes (ERGO only)', { multiline: true, minRows: 3, sx: { gridColumn: '1 / -1' }, slotProps: { htmlInput: { maxLength: 4000 } } })}
      </Section>

      {error != null && <Alert severity="error">{problemMessage(error)}</Alert>}
      <Stack direction="row" spacing={1}>
        <Button
          variant="contained"
          onClick={() => void save()}
          loading={creating.isLoading || updating.isLoading}
          disabled={parts.some((p) => !p.partNumber.trim())}
        >
          {view ? 'Save changes' : 'Save draft'}
        </Button>
        <Button onClick={() => navigate(view ? `/rfqs/${view.id}` : '/rfqs')}>Cancel</Button>
      </Stack>
    </Stack>
  )
}

function optionItems(options: { id: number; name: string; code?: string }[]) {
  return [
    <MenuItem key="" value="">
      —
    </MenuItem>,
    ...options.map((o) => (
      <MenuItem key={o.id} value={String(o.id)}>
        {o.code ? `${o.code} – ${o.name}` : o.name}
      </MenuItem>
    )),
  ]
}

interface PartEditorProps {
  index: number
  part: PartState
  materials: MasterOption[]
  coatings: MasterOption[]
  processes: MasterOption[]
  canRemove: boolean
  onChange: (patch: Partial<PartState>) => void
  onRemove: () => void
}

/** One part: its technical inputs, the customer's targets for it (never shown to suppliers) and its process flow. */
function PartEditor({ index, part, materials, coatings, processes, canRemove, onChange, onRemove }: PartEditorProps) {
  const field = (key: PartFields, label: string, extra: object = {}) => (
    <TextField label={label} value={part[key]} onChange={(e) => onChange({ [key]: e.target.value })} fullWidth {...extra} />
  )
  const numberField = (key: PartFields, label: string) => field(key, label, { type: 'number', slotProps: { htmlInput: { min: 0, step: 'any' } } })
  const select = (key: PartFields, label: string, options: MasterOption[]) => field(key, label, { select: true, children: optionItems(options) })

  return (
    <Stack spacing={2}>
      <Stack direction="row" sx={{ alignItems: 'center', justifyContent: 'space-between' }}>
        <Typography variant="h6" component="h2">
          Part {index + 1}
          {part.partNumber.trim() ? ` – ${part.partNumber.trim()}` : ''}
        </Typography>
        {canRemove && (
          <Button color="error" onClick={onRemove}>
            Remove part
          </Button>
        )}
      </Stack>
      <Section title="Part and technical inputs">
        {field('partNumber', 'Part number', { required: true, slotProps: { htmlInput: { maxLength: 100 } } })}
        {field('revision', 'Revision', { slotProps: { htmlInput: { maxLength: 30 } } })}
        {field('description', 'Description', { sx: { gridColumn: '1 / -1' }, slotProps: { htmlInput: { maxLength: 1000 } } })}
        {numberField('eau', 'EAU (pieces per year)')}
        {numberField('netWeightKg', 'Net weight (kg)')}
        {numberField('grossWeightKg', 'Gross weight (kg)')}
        {select('materialId', 'Material', materials)}
        {field('materialSpec', 'Material specification (if not in the list)', { slotProps: { htmlInput: { maxLength: 500 } } })}
        {select('coatingId', 'Coating / surface treatment', coatings)}
        {field('coatingSpec', 'Coating specification', { slotProps: { htmlInput: { maxLength: 500 } } })}
      </Section>
      <Section title="Commercial targets" note="Your target prices and terms are never shown to suppliers.">
        {numberField('targetToolingCost', 'Target tooling cost')}
        {numberField('targetPpapPiecePrice', 'Target PPAP piece price')}
        {numberField('targetProductionPiecePrice', 'Target production piece price')}
        {numberField('moq', 'MOQ (pieces)')}
        {numberField('materialLeadTimeWeeks', 'Material lead time (weeks)')}
        {numberField('toolingLeadTimeDays', 'Tooling lead time (days)')}
        {numberField('sampleLeadTimeDays', 'Sample lead time (days)')}
        {numberField('productionLeadTimeDays', 'Production lead time from PO (days)')}
      </Section>
      <Card variant="outlined">
        <CardContent sx={{ display: 'grid', gap: 1.5 }}>
          <Typography variant="subtitle1" component="h3">
            Process flow
          </Typography>
          <Typography variant="body2" color="text.secondary">
            The planned process route. Suppliers see only the process and step names; each fills in its own cycle times, sourcing and sub-suppliers when quoting.
          </Typography>
          <ProcessFlowTable steps={part.steps} processes={processes} onChange={(steps) => onChange({ steps })} />
        </CardContent>
      </Card>
    </Stack>
  )
}
