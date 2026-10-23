import {
  Alert,
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  LinearProgress,
  MenuItem,
  Paper,
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
import { num, str, text } from '../../../shared/form'
import { useMasterOptionsQuery, type MasterOption } from '../../../shared/masterData'
import { problemMessage } from '../../../shared/problem'
import { useGenerateCustomerQuoteMutation } from '../customerQuoteApi'
import type { PartRow } from '../quoteApi'

/** ERGO's terms for one part: prefilled from the supplier's accepted quote, to be replaced by ERGO's own. */
interface PartTerms {
  productionPiecePrice: string
  ppapPiecePrice: string
  toolingCost: string
  moq: string
  materialLeadTimeWeeks: string
  toolingLeadTimeDays: string
  sampleLeadTimeDays: string
  productionLeadTimeDays: string
}

const TERM_FIELDS: { key: keyof PartTerms; label: string; required?: boolean }[] = [
  { key: 'productionPiecePrice', label: 'Production piece price', required: true },
  { key: 'ppapPiecePrice', label: 'PPAP piece price' },
  { key: 'toolingCost', label: 'Tooling cost' },
  { key: 'moq', label: 'MOQ' },
  { key: 'materialLeadTimeWeeks', label: 'Material (wk)' },
  { key: 'toolingLeadTimeDays', label: 'Tooling (d)' },
  { key: 'sampleLeadTimeDays', label: 'Sample (d)' },
  { key: 'productionLeadTimeDays', label: 'Production (d)' },
]

/**
 * "Generate quotation for customer" (flow doc §4): ERGO's own quotation for the chosen parts, in the customer's
 * format. The supplier's quotation is not forwarded - the customer sees ERGO's terms only, never the supplier or
 * ERGO's margin; both stay on record.
 */
export function GenerateQuotationDialog({ rfqId, rows, onClose }: { rfqId: number; rows: PartRow[]; onClose: (generated: boolean) => void }) {
  const [generate, generating] = useGenerateCustomerQuoteMutation()
  const lists = [useMasterOptionsQuery('currencies'), useMasterOptionsQuery('payment-terms'), useMasterOptionsQuery('incoterms')]
  const [currencies, paymentTerms, incoterms] = lists.map((list) => list.data ?? [])
  const firstCurrency = rows.find((r) => r.currency)?.currency
  const [common, setCommon] = useState({ currencyId: '', paymentTermId: '', incotermId: '', validityDate: '', remarks: '' })
  const [terms, setTerms] = useState<Record<number, PartTerms>>(() =>
    Object.fromEntries(
      rows.map((r) => [
        r.quoteId!,
        {
          productionPiecePrice: text(r.productionPiecePrice),
          ppapPiecePrice: '',
          toolingCost: text(r.toolingCostTotal),
          moq: text(r.moq),
          materialLeadTimeWeeks: '',
          toolingLeadTimeDays: '',
          sampleLeadTimeDays: '',
          productionLeadTimeDays: text(r.productionLeadTimeDays),
        },
      ]),
    ),
  )

  if (lists.some((list) => list.isLoading)) return <LinearProgress />
  // the quotation is in the supplier's currency until ERGO chooses another
  const currencyId = common.currencyId || String(currencies.find((c) => c.code === firstCurrency)?.id ?? currencies[0]?.id ?? '')

  const option = (list: MasterOption[]) => [
    <MenuItem key="" value="">
      —
    </MenuItem>,
    ...list.map((o) => (
      <MenuItem key={o.id} value={String(o.id)}>
        {o.code ? `${o.code} – ${o.name}` : o.name}
      </MenuItem>
    )),
  ]
  const setTerm = (quoteId: number, key: keyof PartTerms, value: string) =>
    setTerms((t) => ({ ...t, [quoteId]: { ...t[quoteId], [key]: value } }))

  async function send() {
    try {
      await generate({
        rfqId,
        remarks: str(common.remarks),
        lines: rows.map((r) => {
          const t = terms[r.quoteId!]
          return {
            quoteId: r.quoteId!,
            ergoQuote: {
              currencyId: num(currencyId),
              productionPiecePrice: num(t.productionPiecePrice),
              ppapPiecePrice: num(t.ppapPiecePrice),
              toolingCost: num(t.toolingCost),
              paymentTermId: num(common.paymentTermId),
              incotermId: num(common.incotermId),
              moq: num(t.moq),
              materialLeadTimeWeeks: num(t.materialLeadTimeWeeks),
              toolingLeadTimeDays: num(t.toolingLeadTimeDays),
              sampleLeadTimeDays: num(t.sampleLeadTimeDays),
              productionLeadTimeDays: num(t.productionLeadTimeDays),
              validityDate: str(common.validityDate),
            },
          }
        }),
      }).unwrap()
      onClose(true)
    } catch {
      // shown below
    }
  }

  const complete = !!currencyId && !!common.validityDate && rows.every((r) => num(terms[r.quoteId!].productionPiecePrice) != null)

  return (
    <Dialog open onClose={() => onClose(false)} fullWidth maxWidth="lg">
      <DialogTitle>Generate quotation for the customer</DialogTitle>
      <DialogContent sx={{ display: 'grid', gap: 2, pt: '8px !important' }}>
        <Typography color="text.secondary">
          The system builds the customer-facing quotation from ERGO&apos;s terms below, with a line per part, and sends it to the customer. The supplier
          quotations stay with ERGO and are never shown to the customer; neither is ERGO&apos;s margin. The prices start from the supplier&apos;s: set
          ERGO&apos;s own.
        </Typography>
        <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', sm: 'repeat(4, 1fr)' } }}>
          <TextField select size="small" label="Currency" required value={currencyId} onChange={(e) => setCommon((c) => ({ ...c, currencyId: e.target.value }))}>
            {option(currencies)}
          </TextField>
          <TextField select size="small" label="Payment terms" value={common.paymentTermId} onChange={(e) => setCommon((c) => ({ ...c, paymentTermId: e.target.value }))}>
            {option(paymentTerms)}
          </TextField>
          <TextField select size="small" label="Incoterm" value={common.incotermId} onChange={(e) => setCommon((c) => ({ ...c, incotermId: e.target.value }))}>
            {option(incoterms)}
          </TextField>
          <TextField
            size="small"
            type="date"
            label="Valid until"
            required
            value={common.validityDate}
            onChange={(e) => setCommon((c) => ({ ...c, validityDate: e.target.value }))}
            slotProps={{ inputLabel: { shrink: true }, htmlInput: { min: new Date().toLocaleDateString('en-CA') } }}
          />
        </Box>
        <TableContainer component={Paper} variant="outlined">
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Part</TableCell>
                {TERM_FIELDS.map((f) => (
                  <TableCell key={f.key}>{f.label}</TableCell>
                ))}
              </TableRow>
            </TableHead>
            <TableBody>
              {rows.map((r) => (
                <TableRow key={r.quoteId}>
                  <TableCell>
                    {r.part.partNumber}
                    {r.part.revision ? ` rev ${r.part.revision}` : ''}
                  </TableCell>
                  {TERM_FIELDS.map((f) => (
                    <TableCell key={f.key} sx={{ minWidth: 110 }}>
                      <TextField
                        size="small"
                        type="number"
                        required={f.required}
                        value={terms[r.quoteId!][f.key]}
                        onChange={(e) => setTerm(r.quoteId!, f.key, e.target.value)}
                        slotProps={{ htmlInput: { min: 0, step: 'any', 'aria-label': `${f.label} for ${r.part.partNumber}` } }}
                      />
                    </TableCell>
                  ))}
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
        <TextField label="Remarks for the customer (optional)" multiline minRows={2} value={common.remarks} onChange={(e) => setCommon((c) => ({ ...c, remarks: e.target.value }))} slotProps={{ htmlInput: { maxLength: 4000 } }} />
        {generating.error != null && <Alert severity="error">{problemMessage(generating.error)}</Alert>}
      </DialogContent>
      <DialogActions>
        <Button onClick={() => onClose(false)}>Cancel</Button>
        <Button variant="contained" disabled={!complete} loading={generating.isLoading} onClick={() => void send()}>
          Generate and send to customer
        </Button>
      </DialogActions>
    </Dialog>
  )
}
