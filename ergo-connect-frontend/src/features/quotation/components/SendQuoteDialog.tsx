import {
  Alert,
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  LinearProgress,
  MenuItem,
  Radio,
  RadioGroup,
  TextField,
  Typography,
} from '@mui/material'
import { useState } from 'react'
import { ConfirmDialog } from '@/components/ui/ConfirmDialog'
import { num, text } from '@/utils/form'
import { useMasterOptionsQuery, type MasterOption } from '@/utils/masterData'
import { formatMoney } from '@/features/rfq/utils/labels'
import { useSendCustomerQuoteMutation, type ComparisonLine, type ComparisonView, type ErgoQuoteForm, type QuoteType } from '@/features/quotation/services/comparisonApi'
import { ProblemAlert } from '@/components/ui/ProblemAlert'

type Field = keyof ErgoQuoteForm

/**
 * Client decision: per quote, ERGO forwards the selected supplier quote as-is, or sends its own quote (supplier and
 * margin hidden). ERGO's quote starts from the supplier's terms, in the RFQ currency where a rate converts them.
 */
export function SendQuoteDialog({ view, line, onClose }: { view: ComparisonView; line: ComparisonLine; onClose: () => void }) {
  const [lowMargin, setLowMargin] = useState(false)
  const q = line.quote
  const inRfqCurrency = q.currency?.id === view.currency.id
  const price = (amount: number | null, converted: number | null) => text(inRfqCurrency ? amount : converted)
  const [type, setType] = useState<QuoteType>('ERGO_QUOTE')
  const [remarks, setRemarks] = useState('')
  const [form, setForm] = useState<Record<Field, string>>({
    currencyId: String(view.currency.id),
    productionPiecePrice: price(q.productionPiecePrice, line.converted.productionPiecePrice),
    ppapPiecePrice: price(q.ppapPiecePrice, line.converted.ppapPiecePrice),
    toolingCost: price(q.toolingCostTotal, line.converted.toolingCost),
    paymentTermId: text(q.paymentTerm?.id),
    incotermId: text(q.incoterm?.id),
    moq: text(q.moq),
    materialLeadTimeWeeks: text(q.materialLeadTimeWeeks),
    toolingLeadTimeDays: text(q.toolingLeadTimeDays),
    sampleLeadTimeDays: text(q.sampleLeadTimeDays),
    productionLeadTimeDays: text(q.productionLeadTimeDays),
    validityDate: text(q.validityDate),
  })
  const [send, sending] = useSendCustomerQuoteMutation()
  const lists = [useMasterOptionsQuery('currencies'), useMasterOptionsQuery('payment-terms'), useMasterOptionsQuery('incoterms')]
  const [currencies, paymentTerms, incoterms] = lists.map((list) => list.data ?? [])

  // ERGO-internal: the margin on the supplier's production piece price, when both are in the same currency
  const quoted = num(form.productionPiecePrice)
  const cost = form.currencyId === String(view.currency.id) ? line.converted.productionPiecePrice : null
  const margin = quoted != null && cost != null ? quoted - cost : null
  const currencyCode = currencies.find((c) => String(c.id) === form.currencyId)?.code

  const field = (key: Field, label: string, extra: object = {}) => (
    <TextField label={label} value={form[key]} onChange={(e) => setForm((f) => ({ ...f, [key]: e.target.value }))} fullWidth size="small" {...extra} />
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

  function trySubmit() {
    if (type === 'ERGO_QUOTE' && margin != null && margin <= 0) setLowMargin(true)
    else void submit()
  }

  async function submit() {
    setLowMargin(false)
    try {
      await send({
        rfqId: view.rfqId,
        quoteId: line.quoteId,
        type,
        remarks: remarks.trim() || null,
        ergoQuote:
          type === 'ERGO_QUOTE'
            ? {
                currencyId: num(form.currencyId),
                productionPiecePrice: num(form.productionPiecePrice),
                ppapPiecePrice: num(form.ppapPiecePrice),
                toolingCost: num(form.toolingCost),
                paymentTermId: num(form.paymentTermId),
                incotermId: num(form.incotermId),
                moq: num(form.moq),
                materialLeadTimeWeeks: num(form.materialLeadTimeWeeks),
                toolingLeadTimeDays: num(form.toolingLeadTimeDays),
                sampleLeadTimeDays: num(form.sampleLeadTimeDays),
                productionLeadTimeDays: num(form.productionLeadTimeDays),
                validityDate: form.validityDate || null,
              }
            : null,
      }).unwrap()
      onClose()
    } catch {
      // shown below
    }
  }

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="md">
      <DialogTitle>Send a quote to the customer</DialogTitle>
      <DialogContent sx={{ display: 'grid', gap: 2 }}>
        <Typography color="text.secondary">
          Based on {line.supplier.name}&apos;s quote, version {line.version}. The customer then approves it, asks for a revision or declines.
        </Typography>
        <RadioGroup value={type} onChange={(e) => setType(e.target.value as QuoteType)}>
          <FormControlLabel value="ERGO_QUOTE" control={<Radio />} label="ERGO quote: ERGO's own prices and terms. The customer does not see the supplier or ERGO's margin." />
          <FormControlLabel value="SUPPLIER_QUOTE" control={<Radio />} label={`Supplier quote as-is: the customer sees ${line.supplier.name}, its prices, cost breakdown, lead times and terms exactly as submitted.`} />
        </RadioGroup>

        {type === 'ERGO_QUOTE' &&
          (lists.some((list) => list.isLoading) ? (
            <LinearProgress />
          ) : (
            <>
              <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', sm: 'repeat(3, 1fr)' } }}>
                {select('currencyId', 'Currency', currencies, { required: true })}
                {numberField('productionPiecePrice', 'Production piece price', { required: true })}
                {numberField('ppapPiecePrice', 'PPAP piece price')}
                {numberField('toolingCost', 'Tooling cost')}
                {select('paymentTermId', 'Payment terms', paymentTerms)}
                {select('incotermId', 'Incoterm', incoterms)}
                {numberField('moq', 'MOQ (pieces)')}
                {field('validityDate', 'Valid until', { type: 'date', required: true, slotProps: { inputLabel: { shrink: true } } })}
                <span />
                {numberField('materialLeadTimeWeeks', 'Material lead time (weeks)')}
                {numberField('toolingLeadTimeDays', 'Tooling lead time (days)')}
                {numberField('sampleLeadTimeDays', 'Sample lead time (days)')}
                {numberField('productionLeadTimeDays', 'Production lead time from PO (days)')}
              </Box>
              <Alert severity={margin != null && margin <= 0 ? 'warning' : 'info'}>
                {margin != null && quoted
                  ? `ERGO margin per piece (internal): ${formatMoney(margin, currencyCode)}, ${((margin / quoted) * 100).toFixed(1)}% of the quoted price.`
                  : 'ERGO margin (internal): worked out when sent, if an exchange rate converts the supplier price into this currency.'}
              </Alert>
            </>
          ))}

        <TextField label="Remarks for the customer (optional)" multiline minRows={2} value={remarks} onChange={(e) => setRemarks(e.target.value)} slotProps={{ htmlInput: { maxLength: 4000 } }} />
        {sending.error != null && <ProblemAlert error={sending.error} />}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" loading={sending.isLoading} onClick={trySubmit}>
          Send to customer
        </Button>
      </DialogActions>
      <ConfirmDialog
        open={lowMargin}
        title="Send at a loss?"
        message={`This ERGO quote has ${margin === 0 ? 'no margin over' : 'a lower price than'} the supplier's cost. Send anyway?`}
        confirmLabel="Send anyway"
        onConfirm={() => void submit()}
        onCancel={() => setLowMargin(false)}
      />
    </Dialog>
  )
}
