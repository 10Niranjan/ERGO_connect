import { Alert, Box, Button, Card, CardContent, LinearProgress, MenuItem, Stack, TextField, Typography } from '@mui/material'
import { useState, type ReactNode } from 'react'
import { useNavigate, useParams } from 'react-router'
import { useAppSelector } from '../../../app/store'
import { useMasterOptionsQuery } from '../../../shared/masterData'
import { problemMessage } from '../../../shared/problem'
import { useCompaniesQuery } from '../../companies/companiesApi'
import { ProcessFlowTable } from '../components/ProcessFlowTable'
import { EMPTY_STEP, type StepRow } from '../processFlow'
import { PRIORITIES } from '../labels'
import { useCreateRfqMutation, useRfqQuery, useUpdateRfqMutation, type Priority, type RfqForm, type RfqView } from '../rfqApi'

// Numbers are kept as typed text until saved.
type FormState = Record<
  Exclude<keyof RfqForm, 'processSteps' | 'priority' | 'customerCompanyId'> | 'customerCompanyId',
  string
> & { priority: Priority; steps: StepRow[] }

const text = (value: string | number | null | undefined) => (value == null ? '' : String(value))

function fromView(view: RfqView | undefined): FormState {
  const p = view?.part
  const t = view?.targets
  return {
    customerCompanyId: view?.audience === 'ERGO' ? String(view.customer.id) : '',
    partNumber: text(p?.partNumber),
    revision: text(p?.revision),
    description: text(p?.description),
    eau: text(p?.eau),
    netWeightKg: text(p?.netWeightKg),
    grossWeightKg: text(p?.grossWeightKg),
    materialId: text(p?.material?.id),
    materialSpec: text(p?.materialSpec),
    coatingId: text(p?.coating?.id),
    coatingSpec: text(p?.coatingSpec),
    targetQuoteDate: text(p?.targetQuoteDate),
    priority: p?.priority ?? 'NORMAL',
    currencyId: text(t?.currency.id),
    targetToolingCost: text(t?.targetToolingCost),
    targetPpapPiecePrice: text(t?.targetPpapPiecePrice),
    targetProductionPiecePrice: text(t?.targetProductionPiecePrice),
    paymentTermId: text(t?.paymentTerm?.id),
    materialLeadTimeWeeks: text(t?.materialLeadTimeWeeks),
    toolingLeadTimeDays: text(t?.toolingLeadTimeDays),
    sampleLeadTimeDays: text(t?.sampleLeadTimeDays),
    productionLeadTimeDays: text(t?.productionLeadTimeDays),
    moq: text(t?.moq),
    customerRemarks: text(view?.customerRemarks),
    internalNotes: view?.audience === 'ERGO' ? text(view.internalNotes) : '',
    steps: view
      ? view.part.processSteps.map((s) => ({
          processId: text(s.process?.id),
          stepName: s.stepName,
          cycleTimeSeconds: text(s.cycleTimeSeconds),
          sourcingType: s.sourcingType ?? '',
          outsourcedSupplierName: text(s.outsourcedSupplierName),
          remarks: text(s.remarks),
        }))
      : [{ ...EMPTY_STEP }, { ...EMPTY_STEP }], // SRS §5.5: starts with 2 rows
  }
}

const num = (value: string) => (value.trim() === '' ? null : Number(value))
const str = (value: string) => value.trim() || null

function toForm(state: FormState): RfqForm {
  return {
    customerCompanyId: num(state.customerCompanyId),
    partNumber: state.partNumber.trim(),
    revision: str(state.revision),
    description: str(state.description),
    eau: num(state.eau),
    netWeightKg: num(state.netWeightKg),
    grossWeightKg: num(state.grossWeightKg),
    materialId: num(state.materialId),
    materialSpec: str(state.materialSpec),
    coatingId: num(state.coatingId),
    coatingSpec: str(state.coatingSpec),
    targetQuoteDate: str(state.targetQuoteDate),
    priority: state.priority,
    currencyId: num(state.currencyId),
    targetToolingCost: num(state.targetToolingCost),
    targetPpapPiecePrice: num(state.targetPpapPiecePrice),
    targetProductionPiecePrice: num(state.targetProductionPiecePrice),
    paymentTermId: num(state.paymentTermId),
    materialLeadTimeWeeks: num(state.materialLeadTimeWeeks),
    toolingLeadTimeDays: num(state.toolingLeadTimeDays),
    sampleLeadTimeDays: num(state.sampleLeadTimeDays),
    productionLeadTimeDays: num(state.productionLeadTimeDays),
    moq: num(state.moq),
    // empty rows are ignored
    processSteps: state.steps
      .filter((s) => s.stepName.trim() || s.processId)
      .map((s) => ({
        processId: num(s.processId),
        stepName: s.stepName.trim(),
        cycleTimeSeconds: num(s.cycleTimeSeconds),
        sourcingType: s.sourcingType || null,
        outsourcedSupplierName: str(s.outsourcedSupplierName),
        remarks: str(s.remarks),
      })),
    customerRemarks: str(state.customerRemarks),
    internalNotes: str(state.internalNotes),
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
  const [state, setState] = useState<FormState>(() => fromView(view))
  const [createRfq, creating] = useCreateRfqMutation()
  const [updateRfq, updating] = useUpdateRfqMutation()
  const { data: companies = [] } = useCompaniesQuery(undefined, { skip: !isErgo || view !== undefined })
  const { data: materials = [] } = useMasterOptionsQuery('materials')
  const { data: coatings = [] } = useMasterOptionsQuery('coatings')
  const { data: currencies = [] } = useMasterOptionsQuery('currencies')
  const { data: paymentTerms = [] } = useMasterOptionsQuery('payment-terms')
  const { data: processes = [] } = useMasterOptionsQuery('processes')
  const error = creating.error ?? updating.error
  // SRS §17.1: part number, revision and description are fixed once ERGO's review has started.
  const partLocked = view?.status === 'UNDER_REVIEW'

  const set = (key: keyof FormState) => (event: { target: { value: string } }) =>
    setState((s) => ({ ...s, [key]: event.target.value }))
  const field = (key: Exclude<keyof FormState, 'steps' | 'priority'>, label: string, extra: object = {}) => (
    <TextField label={label} value={state[key]} onChange={set(key)} fullWidth {...extra} />
  )
  const numberField = (key: Exclude<keyof FormState, 'steps' | 'priority'>, label: string) =>
    field(key, label, { type: 'number', slotProps: { htmlInput: { min: 0, step: 'any' } } })
  const select = (key: Exclude<keyof FormState, 'steps' | 'priority'>, label: string, options: { id: number; name: string; code?: string }[], extra: object = {}) =>
    field(key, label, {
      select: true,
      children: [
        <MenuItem key="" value="">
          —
        </MenuItem>,
        ...options.map((o) => (
          <MenuItem key={o.id} value={String(o.id)}>
            {o.code ? `${o.code} – ${o.name}` : o.name}
          </MenuItem>
        )),
      ],
      ...extra,
    })

  if (!view && !state.currencyId && currencies.length > 0) {
    const inr = currencies.find((c) => c.code === 'INR') ?? currencies[0]
    setState((s) => ({ ...s, currencyId: String(inr.id) }))
  }

  async function save() {
    try {
      const saved = view ? await updateRfq({ id: view.id, form: toForm(state) }).unwrap() : await createRfq(toForm(state)).unwrap()
      navigate(`/rfqs/${saved.id}`)
    } catch {
      // shown below
    }
  }

  const customers = companies.filter((c) => c.type === 'CUSTOMER' && c.status === 'ACTIVE')

  return (
    <Stack spacing={2} sx={{ maxWidth: 1200 }}>
      <Typography variant="h5" component="h1">
        {view ? `Edit ${view.number}` : 'New RFQ'}
      </Typography>

      <Section title="Summary">
        {isErgo &&
          (view ? (
            <TextField label="Customer" value={view.audience === 'ERGO' ? view.customer.name : ''} disabled fullWidth />
          ) : (
            select('customerCompanyId', 'Customer', customers.map((c) => ({ id: c.id, name: c.profile.name })), { required: true })
          ))}
        {field('partNumber', 'Part number', { required: true, disabled: partLocked, slotProps: { htmlInput: { maxLength: 100 } } })}
        {field('revision', 'Revision', { disabled: partLocked, slotProps: { htmlInput: { maxLength: 30 } } })}
        {field('description', 'Description', { disabled: partLocked, sx: { gridColumn: '1 / -1' }, slotProps: { htmlInput: { maxLength: 1000 } } })}
        {numberField('eau', 'EAU (pieces per year)')}
        {numberField('netWeightKg', 'Net weight (kg)')}
        {numberField('grossWeightKg', 'Gross weight (kg)')}
        {field('targetQuoteDate', 'Quote needed by', { type: 'date', slotProps: { inputLabel: { shrink: true } } })}
        <TextField select label="Priority" value={state.priority} onChange={(e) => setState((s) => ({ ...s, priority: e.target.value as Priority }))} fullWidth>
          {Object.entries(PRIORITIES).map(([value, label]) => (
            <MenuItem key={value} value={value}>
              {label}
            </MenuItem>
          ))}
        </TextField>
      </Section>

      <Section title="Technical inputs">
        {select('materialId', 'Material', materials)}
        {field('materialSpec', 'Material specification (if not in the list)', { slotProps: { htmlInput: { maxLength: 500 } } })}
        {select('coatingId', 'Coating / surface treatment', coatings)}
        {field('coatingSpec', 'Coating specification', { slotProps: { htmlInput: { maxLength: 500 } } })}
      </Section>

      <Section title="Commercial targets" note="Your target prices and terms are never shown to suppliers.">
        {select('currencyId', 'Currency', currencies, { required: true })}
        {numberField('targetToolingCost', 'Target tooling cost')}
        {numberField('targetPpapPiecePrice', 'Target PPAP piece price')}
        {numberField('targetProductionPiecePrice', 'Target production piece price')}
        {select('paymentTermId', 'Payment terms', paymentTerms)}
        {numberField('moq', 'MOQ (pieces)')}
        {numberField('materialLeadTimeWeeks', 'Material lead time (weeks)')}
        {numberField('toolingLeadTimeDays', 'Tooling lead time (days)')}
        {numberField('sampleLeadTimeDays', 'Sample lead time (days)')}
        {numberField('productionLeadTimeDays', 'Production lead time from PO (days)')}
      </Section>

      <Card variant="outlined">
        <CardContent sx={{ display: 'grid', gap: 1.5 }}>
          <Typography variant="h6" component="h2">
            Process flow
          </Typography>
          <Typography variant="body2" color="text.secondary">
            The planned process route. Each supplier fills in its own cycle times and sourcing when quoting.
          </Typography>
          <ProcessFlowTable steps={state.steps} processes={processes} onChange={(steps) => setState((s) => ({ ...s, steps }))} />
        </CardContent>
      </Card>

      <Section title="Remarks">
        {field('customerRemarks', 'Requirements and remarks', { multiline: true, minRows: 3, sx: { gridColumn: '1 / -1' }, slotProps: { htmlInput: { maxLength: 4000 } } })}
        {isErgo &&
          field('internalNotes', 'Internal notes (ERGO only)', { multiline: true, minRows: 3, sx: { gridColumn: '1 / -1' }, slotProps: { htmlInput: { maxLength: 4000 } } })}
      </Section>

      {error != null && <Alert severity="error">{problemMessage(error)}</Alert>}
      <Stack direction="row" spacing={1}>
        <Button variant="contained" onClick={() => void save()} loading={creating.isLoading || updating.isLoading} disabled={!state.partNumber.trim()}>
          {view ? 'Save changes' : 'Save draft'}
        </Button>
        <Button onClick={() => navigate(view ? `/rfqs/${view.id}` : '/rfqs')}>Cancel</Button>
      </Stack>
    </Stack>
  )
}

function Section({ title, note, children }: { title: string; note?: string; children: ReactNode }) {
  return (
    <Card variant="outlined">
      <CardContent sx={{ display: 'grid', gap: 1.5 }}>
        <Typography variant="h6" component="h2">
          {title}
        </Typography>
        {note && (
          <Typography variant="body2" color="text.secondary">
            {note}
          </Typography>
        )}
        <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', md: 'repeat(3, 1fr)' } }}>{children}</Box>
      </CardContent>
    </Card>
  )
}
