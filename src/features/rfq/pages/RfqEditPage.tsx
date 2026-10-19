import { Button, Card, CardContent, LinearProgress, MenuItem, Stack, TextField, Typography } from '@mui/material'
import { useState } from 'react'
import { useNavigate, useParams } from 'react-router'
import { useAppSelector } from '../../../app/store'
import { FormSection as Section } from '@/components/ui/FormSection'
import { num, str, text } from '@/utils/form'
import { useMasterOptionsQuery } from '@/utils/masterData'
import { useCompaniesQuery } from '@/features/companies/services/companiesApi'
import { ProcessFlowTable } from '../components/ProcessFlowTable'
import { newStep, toProcessSteps, toStepRows, type StepRow } from '@/features/rfq/utils/processFlow'
import { PRIORITIES } from '@/features/rfq/utils/labels'
import { useCreateRfqMutation, useRfqQuery, useUpdateRfqMutation, type Priority, type RfqForm, type RfqView } from '@/features/rfq/services/rfqApi'
import { ProblemAlert } from '@/components/ui/ProblemAlert'

type FormState = Record<
  Exclude<keyof RfqForm, 'processSteps' | 'priority' | 'customerCompanyId'> | 'customerCompanyId',
  string
> & { priority: Priority; steps: StepRow[] }

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
      ? toStepRows(view.part.processSteps.map((s) => ({ ...s, processId: s.process?.id ?? null })))
      : [newStep(), newStep()], // SRS §5.5: starts with 2 rows
  }
}

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
    processSteps: toProcessSteps(state.steps),
    customerRemarks: str(state.customerRemarks),
    internalNotes: str(state.internalNotes),
  }
}

/** New RFQ (no id) or edit (id). */
export function RfqEditPage() {
  const { id } = useParams()
  const { data: view, isLoading, error } = useRfqQuery(Number(id), { skip: id === undefined })
  if (id !== undefined && isLoading) return <LinearProgress />
  if (error) return <ProblemAlert error={error} />
  return <RfqFormEditor key={view?.id ?? 'new'} view={view} />
}

function RfqFormEditor({ view }: { view: RfqView | undefined }) {
  const isErgo = useAppSelector((state) => state.auth.session?.user.companyType === 'ERGO')
  const navigate = useNavigate()
  const [state, setState] = useState<FormState>(() => fromView(view))
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

  // a select cannot show a saved value before its options arrive
  if (lists.some((list) => list.isLoading)) return <LinearProgress />

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
            The planned process route. Suppliers see only the process and step names; each fills in its own cycle times, sourcing and sub-suppliers when quoting.
          </Typography>
          <ProcessFlowTable steps={state.steps} processes={processes} onChange={(steps) => setState((s) => ({ ...s, steps }))} />
        </CardContent>
      </Card>

      <Section title="Remarks">
        {field('customerRemarks', 'Requirements and remarks', { multiline: true, minRows: 3, sx: { gridColumn: '1 / -1' }, slotProps: { htmlInput: { maxLength: 4000 } } })}
        {isErgo &&
          field('internalNotes', 'Internal notes (ERGO only)', { multiline: true, minRows: 3, sx: { gridColumn: '1 / -1' }, slotProps: { htmlInput: { maxLength: 4000 } } })}
      </Section>

      {error != null && <ProblemAlert error={error} />}
      <Stack direction="row" spacing={1}>
        <Button variant="contained" onClick={() => void save()} loading={creating.isLoading || updating.isLoading} disabled={!state.partNumber.trim()}>
          {view ? 'Save changes' : 'Save draft'}
        </Button>
        <Button onClick={() => navigate(view ? `/rfqs/${view.id}` : '/rfqs')}>Cancel</Button>
      </Stack>
    </Stack>
  )
}
