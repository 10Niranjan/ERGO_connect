import {
  Alert,
  Button,
  Card,
  CardContent,
  IconButton,
  LinearProgress,
  MenuItem,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TextField,
  Typography,
} from '@mui/material'
import { useState } from 'react'
import { FormSection } from '../../../shared/components/FormSection'
import { num, str, text } from '../../../shared/form'
import { useMasterOptionsQuery, type MasterOption } from '../../../shared/masterData'
import { problemMessage } from '../../../shared/problem'
import { ProcessFlowTable } from '../../rfq/components/ProcessFlowTable'
import { formatMoney } from '../../rfq/labels'
import { toProcessSteps, toStepRows, type StepRow } from '../../rfq/processFlow'
import { useQuoteCommandMutation, useSaveQuoteMutation, type QuoteForm, type SupplierQuoteView } from '../quoteApi'
import { useConfirm } from '@/components/ui/useConfirm'

type Field = Exclude<keyof QuoteForm, 'tooling' | 'processSteps'>

interface ToolRow {
  toolName: string
  toolingCost: string
  remarks: string
}

type FormState = Record<Field, string> & { tooling: ToolRow[]; steps: StepRow[] }

const COSTS = ['rawMaterialCost', 'processingCost', 'overheadCost', 'packagingCost', 'profit'] as const

function fromView(view: SupplierQuoteView): FormState {
  const q = view.quote
  return {
    quoteReference: text(q?.quoteReference),
    validityDate: text(q?.validityDate),
    currencyId: text(q?.currency?.id),
    incotermId: text(q?.incoterm?.id),
    paymentTermId: text(q?.paymentTerm?.id),
    toolingCostTotal: text(q?.toolingCostTotal),
    ppapPiecePrice: text(q?.ppapPiecePrice),
    productionPiecePrice: text(q?.productionPiecePrice),
    rawMaterialCost: text(q?.costs.rawMaterialCost),
    processingCost: text(q?.costs.processingCost),
    overheadCost: text(q?.costs.overheadCost),
    packagingCost: text(q?.costs.packagingCost),
    profit: text(q?.costs.profit),
    moq: text(q?.moq),
    materialLeadTimeWeeks: text(q?.materialLeadTimeWeeks),
    toolingLeadTimeDays: text(q?.toolingLeadTimeDays),
    sampleLeadTimeDays: text(q?.sampleLeadTimeDays),
    productionLeadTimeDays: text(q?.productionLeadTimeDays),
    assumptionsDeviations: text(q?.assumptionsDeviations),
    remarks: text(q?.remarks),
    tooling: q?.tooling.map((t) => ({ toolName: t.toolName, toolingCost: text(t.toolingCost), remarks: text(t.remarks) })) ?? [],
    // Client decision: each supplier's route starts as a copy of ERGO's planned route.
    steps: toStepRows(
      q?.processSteps.length
        ? q.processSteps.map((s) => ({ ...s, processId: s.process?.id ?? null }))
        : view.part.processSteps.map((s) => ({ processId: s.processId, stepName: s.stepName, cycleTimeSeconds: null, sourcingType: null, outsourcedSupplierName: null, remarks: null })),
    ),
  }
}

function toForm(s: FormState): QuoteForm {
  return {
    quoteReference: str(s.quoteReference),
    validityDate: str(s.validityDate),
    currencyId: num(s.currencyId),
    incotermId: num(s.incotermId),
    paymentTermId: num(s.paymentTermId),
    toolingCostTotal: num(s.toolingCostTotal),
    tooling: s.tooling
      .filter((t) => t.toolName.trim() || t.toolingCost.trim())
      .map((t) => ({ toolName: t.toolName.trim(), toolingCost: num(t.toolingCost), remarks: str(t.remarks) })),
    ppapPiecePrice: num(s.ppapPiecePrice),
    productionPiecePrice: num(s.productionPiecePrice),
    rawMaterialCost: num(s.rawMaterialCost),
    processingCost: num(s.processingCost),
    overheadCost: num(s.overheadCost),
    packagingCost: num(s.packagingCost),
    profit: num(s.profit),
    moq: num(s.moq),
    materialLeadTimeWeeks: num(s.materialLeadTimeWeeks),
    toolingLeadTimeDays: num(s.toolingLeadTimeDays),
    sampleLeadTimeDays: num(s.sampleLeadTimeDays),
    productionLeadTimeDays: num(s.productionLeadTimeDays),
    processSteps: toProcessSteps(s.steps),
    assumptionsDeviations: str(s.assumptionsDeviations),
    remarks: str(s.remarks),
  }
}

/** The supplier's quote while it is open for editing (SRS §5.4 fields). ERGO sees it only once submitted. */
export function QuoteEditor({ view }: { view: SupplierQuoteView }) {
  const [state, setState] = useState(() => fromView(view))
  const [saved, setSaved] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [pending, setPending] = useState<'save' | 'submit' | null>(null)
  const [save] = useSaveQuoteMutation()
  const [command] = useQuoteCommandMutation()
  const [confirm, confirmDialog] = useConfirm()
  const lists = [useMasterOptionsQuery('currencies'), useMasterOptionsQuery('incoterms'), useMasterOptionsQuery('payment-terms'), useMasterOptionsQuery('processes')]
  const [currencies, incoterms, paymentTerms, processes] = lists.map((list) => list.data ?? [])

  const change = (patch: Partial<FormState>) => {
    setSaved(false)
    setState((s) => ({ ...s, ...patch }))
  }
  const field = (key: Field, label: string, extra: object = {}) => (
    <TextField label={label} value={state[key]} onChange={(e) => change({ [key]: e.target.value })} fullWidth {...extra} />
  )
  const numberField = (key: Field, label: string, extra: object = {}) =>
    field(key, label, { type: 'number', slotProps: { htmlInput: { min: 0, step: 'any' } }, ...extra })
  const select = (key: Field, label: string, options: MasterOption[], extra: object = {}) =>
    field(key, label, {
      select: true,
      children: [
        <MenuItem key="" value="">
          —
        </MenuItem>,
        ...options.map((o) => (
          <MenuItem key={o.id} value={String(o.id)}>
            {o.code} – {o.name}
          </MenuItem>
        )),
      ],
      ...extra,
    })
  const setTool = (index: number, patch: Partial<ToolRow>) =>
    change({ tooling: state.tooling.map((t, i) => (i === index ? { ...t, ...patch } : t)) })

  const currency = currencies.find((c) => String(c.id) === state.currencyId)?.code
  const toolingSum = state.tooling.reduce((sum, t) => sum + (Number(t.toolingCost) || 0), 0)
  const breakdown = COSTS.every((c) => state[c].trim()) ? COSTS.reduce((sum, c) => sum + Number(state[c]), 0) : null

  // a select cannot show a saved value before its options arrive
  if (lists.some((list) => list.isLoading)) return <LinearProgress />

  async function saveDraft() {
    setError(null)
    setPending('save')
    try {
      await save({ id: view.id, form: toForm(state) }).unwrap()
      setSaved(true)
    } catch (e) {
      setError(e)
    }
    setPending(null)
  }

  async function submit() {
    if (!(await confirm('Submit this quote to ERGO? It is locked once submitted; ERGO can re-open it if a revision is needed.', 'Submit quote'))) return
    setError(null)
    setPending('submit')
    try {
      await save({ id: view.id, form: toForm(state) }).unwrap() // what is on screen is what gets submitted
      await command({ id: view.id, command: 'submit' }).unwrap()
    } catch (e) {
      setError(e)
    }
    setPending(null)
  }

  return (
    <Stack spacing={2} sx={{ maxWidth: 1200 }}>
      <FormSection title="Commercial terms">
        {field('quoteReference', 'Your quote reference', { slotProps: { htmlInput: { maxLength: 50 } } })}
        {field('validityDate', 'Valid until', { type: 'date', required: true, slotProps: { inputLabel: { shrink: true } } })}
        {select('currencyId', 'Currency', currencies, { required: true })}
        {select('incotermId', 'Incoterm', incoterms)}
        {select('paymentTermId', 'Payment terms', paymentTerms)}
        {numberField('moq', 'MOQ (pieces)', { required: true })}
      </FormSection>

      <FormSection title="Piece prices" note="Per piece, in the quote currency.">
        {numberField('productionPiecePrice', 'Production piece price', { required: true })}
        {numberField('ppapPiecePrice', 'PPAP piece price')}
      </FormSection>

      <Card variant="outlined">
        <CardContent sx={{ display: 'grid', gap: 1.5 }}>
          <Typography variant="h6" component="h2">
            Tooling
          </Typography>
          <Typography variant="body2" color="text.secondary">
            List each tool with its cost, or give only the total.
          </Typography>
          {state.tooling.length > 0 && (
            <TableContainer component={Paper} variant="outlined">
              <Table size="small">
                <TableHead>
                  <TableRow>
                    <TableCell>Tool</TableCell>
                    <TableCell sx={{ width: 200 }}>Cost</TableCell>
                    <TableCell>Remarks</TableCell>
                    <TableCell sx={{ width: 48 }} />
                  </TableRow>
                </TableHead>
                <TableBody>
                  {state.tooling.map((t, index) => (
                    <TableRow key={index}>
                      <TableCell>
                        <TextField size="small" fullWidth value={t.toolName} onChange={(e) => setTool(index, { toolName: e.target.value })} slotProps={{ htmlInput: { maxLength: 200, 'aria-label': `Tool ${index + 1} name` } }} />
                      </TableCell>
                      <TableCell>
                        <TextField size="small" type="number" value={t.toolingCost} onChange={(e) => setTool(index, { toolingCost: e.target.value })} slotProps={{ htmlInput: { min: 0, step: 'any', 'aria-label': `Tool ${index + 1} cost` } }} />
                      </TableCell>
                      <TableCell>
                        <TextField size="small" fullWidth value={t.remarks} onChange={(e) => setTool(index, { remarks: e.target.value })} slotProps={{ htmlInput: { maxLength: 500, 'aria-label': `Tool ${index + 1} remarks` } }} />
                      </TableCell>
                      <TableCell>
                        <IconButton aria-label={`Remove tool ${index + 1}`} onClick={() => change({ tooling: state.tooling.filter((_, i) => i !== index) })}>
                          ×
                        </IconButton>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          )}
          <Stack direction="row" spacing={2} sx={{ alignItems: 'center' }}>
            <Button onClick={() => change({ tooling: [...state.tooling, { toolName: '', toolingCost: '', remarks: '' }] })}>+ Add tool</Button>
            {state.tooling.length > 0 ? (
              <Typography>Total tooling cost: {formatMoney(toolingSum, currency)}</Typography>
            ) : (
              numberField('toolingCostTotal', 'Total tooling cost', { fullWidth: false })
            )}
          </Stack>
        </CardContent>
      </Card>

      <FormSection
        title="Cost breakdown per piece"
        note={`All five are needed to submit. Sum ${formatMoney(breakdown, currency)}; production piece price ${formatMoney(num(state.productionPiecePrice), currency)}.`}
      >
        {numberField('rawMaterialCost', 'Raw material', { required: true })}
        {numberField('processingCost', 'Processing', { required: true })}
        {numberField('overheadCost', 'Overhead', { required: true })}
        {numberField('packagingCost', 'Packaging', { required: true })}
        {numberField('profit', 'Profit', { required: true })}
      </FormSection>

      <FormSection title="Lead times">
        {numberField('materialLeadTimeWeeks', 'Material (weeks)')}
        {numberField('toolingLeadTimeDays', 'Tooling (days)')}
        {numberField('sampleLeadTimeDays', 'Samples (days)')}
        {numberField('productionLeadTimeDays', 'Production from PO (days)', { required: true })}
      </FormSection>

      <Card variant="outlined">
        <CardContent sx={{ display: 'grid', gap: 1.5 }}>
          <Typography variant="h6" component="h2">
            Your process route
          </Typography>
          <Typography variant="body2" color="text.secondary">
            Starts with ERGO&apos;s planned steps. Fill in your cycle times and sourcing, and change the steps to match how you would make the part.
          </Typography>
          <ProcessFlowTable steps={state.steps} processes={processes} onChange={(steps) => change({ steps })} />
        </CardContent>
      </Card>

      <FormSection title="Assumptions and remarks">
        {field('assumptionsDeviations', 'Assumptions and deviations', { multiline: true, minRows: 3, sx: { gridColumn: '1 / -1' }, slotProps: { htmlInput: { maxLength: 4000 } } })}
        {field('remarks', 'Remarks', { multiline: true, minRows: 2, sx: { gridColumn: '1 / -1' }, slotProps: { htmlInput: { maxLength: 4000 } } })}
      </FormSection>

      {error != null && <Alert severity="error">{problemMessage(error)}</Alert>}
      {saved && <Alert severity="success">Draft saved. ERGO sees your quote only once you submit it.</Alert>}
      <Stack direction="row" spacing={1}>
        <Button onClick={() => void saveDraft()} loading={pending === 'save'} disabled={pending !== null}>
          Save draft
        </Button>
        {view.actions.includes('SUBMIT') && (
          <Button variant="contained" onClick={() => void submit()} loading={pending === 'submit'} disabled={pending !== null}>
            {view.version > 0 ? `Resubmit as version ${view.version + 1}` : 'Submit quote'}
          </Button>
        )}
      </Stack>
      {confirmDialog}
    </Stack>
  )
}
