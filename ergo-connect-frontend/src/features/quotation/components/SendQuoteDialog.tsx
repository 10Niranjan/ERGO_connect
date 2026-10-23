import { Alert, Box, Button, Dialog, DialogActions, DialogContent, DialogTitle, LinearProgress, MenuItem, TextField, Typography } from '@mui/material'
import { useState } from 'react'
import { num, text } from '../../../shared/form'
import { useMasterOptionsQuery, type MasterOption } from '../../../shared/masterData'
import { problemMessage } from '../../../shared/problem'
import { formatMoney } from '../../rfq/labels'
import { useSendCustomerQuoteMutation, type ComparisonLine, type ComparisonView, type ErgoQuoteForm } from '../comparisonApi'

type Field = keyof ErgoQuoteForm

/**
 * "Generate quotation for customer" (flow doc §4): ERGO's own quotation, never the supplier's. It starts from the
 * supplier's terms, in the RFQ currency where a rate converts them; the supplier and ERGO's margin stay hidden.
 */
export function SendQuoteDialog({ view, line, onClose }: { view: ComparisonView; line: ComparisonLine; onClose: () => void }) {
  const q = line.quote
  const inRfqCurrency = q.currency?.id === view.currency.id
  const price = (amount: number | null, converted: number | null) => text(inRfqCurrency ? amount : converted)
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
    field(key, label, {
      type: 'number',
      slotProps: { htmlInput: { min: 0, step: 'any' } },
      ...extra,
    })
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

  async function submit() {
    if (margin != null && margin <= 0) {
      const zero = margin === 0
      if (!window.confirm(`This ERGO quote has ${zero ? 'no margin over' : 'a lower price than'} the supplier's cost. Send anyway?`)) return
    }
    try {
      await send({
        rfqId: view.rfqId,
        quoteId: line.quoteId,
        type: 'ERGO_QUOTE',
        remarks: remarks.trim() || null,
        ergoQuote: {
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
        },
      }).unwrap()
      onClose()
    } catch {
      // shown below
    }
  }

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="md">
      <DialogTitle>Generate quotation for the customer</DialogTitle>
      <DialogContent sx={{ display: 'grid', gap: 2 }}>
        <Typography color="text.secondary">
          Based on {line.supplier.name}&apos;s quote, version {line.version}. The customer then approves it, requests a correction, puts it on hold or rejects
          it. The supplier quotation stays with ERGO and is kept for reference.
        </Typography>
        {lists.some((list) => list.isLoading) ? (
          <LinearProgress />
        ) : (
          <>
            <Box
              sx={{
                display: 'grid',
                gap: 2,
                gridTemplateColumns: { xs: '1fr', sm: 'repeat(3, 1fr)' },
              }}
            >
              {select('currencyId', 'Currency', currencies, { required: true })}
              {numberField('productionPiecePrice', 'Production piece price', {
                required: true,
              })}
              {numberField('ppapPiecePrice', 'PPAP piece price')}
              {numberField('toolingCost', 'Tooling cost')}
              {select('paymentTermId', 'Payment terms', paymentTerms)}
              {select('incotermId', 'Incoterm', incoterms)}
              {numberField('moq', 'MOQ (pieces)')}
              {field('validityDate', 'Valid until', {
                type: 'date',
                required: true,
                slotProps: { inputLabel: { shrink: true } },
              })}
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
        )}

        <TextField
          label="Remarks for the customer (optional)"
          multiline
          minRows={2}
          value={remarks}
          onChange={(e) => setRemarks(e.target.value)}
          slotProps={{ htmlInput: { maxLength: 4000 } }}
        />
        {sending.error != null && <Alert severity="error">{problemMessage(sending.error)}</Alert>}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" loading={sending.isLoading} onClick={() => void submit()}>
          Generate and send to customer
        </Button>
      </DialogActions>
    </Dialog>
  )
}
