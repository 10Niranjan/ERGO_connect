import {
  Alert,
  Autocomplete,
  Button,
  Checkbox,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  LinearProgress,
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
import { useNavigate } from 'react-router'
import { QUOTATION_ERGO_APPROVE, useCan } from '@/features/permissions'
import { CommentDialog } from '../../../shared/components/CommentDialog'
import { problemMessage } from '../../../shared/problem'
import { useCompaniesQuery, type Company } from '../../companies/companiesApi'
import { formatDate, formatMoney, formatNumber } from '../../rfq/labels'
import { QUOTE_STATUS } from '../labels'
import { useDistributeRfqMutation, useQuoteCommandMutation, useRfqQuotesQuery, type PartRow } from '../quoteApi'
import { GenerateQuotationDialog } from './GenerateQuotationDialog'

/** A part whose quote ERGO has accepted (or the customer sent back) can go into a customer quotation. */
const READY: PartRow['status'][] = ['ACCEPTED', 'CUSTOMER_CORRECTION']

type RowDialog = { row: PartRow; command: 'revision' | 'recall' }

/**
 * ERGO's view of an RFQ's parts (flow doc §2-4): which supplier each part is with and where its quote stands, the
 * actions on each quote (accept, revision required, take back), sending parts to suppliers as sub-RFQs, and
 * generating the customer quotation from the accepted ones.
 */
export function PartsPanel({ rfqId }: { rfqId: number }) {
  const navigate = useNavigate()
  const { data, isLoading, error } = useRfqQuotesQuery(rfqId)
  const canGenerate = useCan(QUOTATION_ERGO_APPROVE)
  const [run, running] = useQuoteCommandMutation()
  const [toSend, setToSend] = useState<number[]>([])
  const [toQuote, setToQuote] = useState<number[]>([])
  const [sending, setSending] = useState(false)
  const [generating, setGenerating] = useState(false)
  const [dialog, setDialog] = useState<RowDialog | null>(null)
  const [actionError, setActionError] = useState<unknown>(null)
  if (isLoading) return <LinearProgress />
  if (!data) return <Alert severity="error">{problemMessage(error)}</Alert>

  const unassigned = data.parts.filter((p) => p.quoteId === null)
  const ready = data.parts.filter((p) => p.status !== null && READY.includes(p.status))
  const toggle = (list: number[], set: (value: number[]) => void, id: number) =>
    set(list.includes(id) ? list.filter((x) => x !== id) : [...list, id])

  async function accept(row: PartRow) {
    setActionError(null)
    try {
      await run({ id: row.quoteId!, command: 'accept' }).unwrap()
    } catch (e) {
      setActionError(e)
    }
  }

  async function withComment(comment: string) {
    if (!dialog) return
    try {
      await run({ id: dialog.row.quoteId!, command: dialog.command, comment }).unwrap()
      setDialog(null)
    } catch {
      // shown in the dialog
    }
  }

  return (
    <Stack spacing={2}>
      <Stack direction="row" spacing={1} useFlexGap sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
        {data.canDistribute && (
          <>
            <Button variant="contained" disabled={toSend.length === 0} onClick={() => setSending(true)}>
              Send {toSend.length || ''} selected part{toSend.length === 1 ? '' : 's'} to a supplier
            </Button>
            <Button onClick={() => setToSend(unassigned.map((p) => p.part.itemId))}>Select all unsent parts</Button>
          </>
        )}
        {canGenerate && ready.length > 0 && (
          <Button variant="contained" color="secondary" disabled={toQuote.length === 0} onClick={() => setGenerating(true)}>
            Generate quotation for customer ({toQuote.length})
          </Button>
        )}
      </Stack>
      <Typography variant="body2" color="text.secondary">
        Send the whole RFQ to one supplier, or select some parts and send them to a supplier as a sub-RFQ; repeat for the other parts. A supplier sees only
        the parts sent to it: the part, technical data, planned process and the files you share with suppliers. Never the customer or the targets.
      </Typography>
      {actionError != null && <Alert severity="error">{problemMessage(actionError)}</Alert>}

      <TableContainer component={Paper} variant="outlined">
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell padding="checkbox" />
              <TableCell>Part</TableCell>
              <TableCell>Sub-RFQ</TableCell>
              <TableCell>Supplier</TableCell>
              <TableCell>Status</TableCell>
              <TableCell>Due</TableCell>
              <TableCell>Version</TableCell>
              <TableCell align="right">Piece price</TableCell>
              <TableCell align="right">Tooling</TableCell>
              <TableCell align="right">MOQ</TableCell>
              <TableCell align="right">Production lead time</TableCell>
              <TableCell>Actions</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {data.parts.map((p) => {
              const sendable = data.canDistribute && p.quoteId === null
              const quotable = canGenerate && p.status !== null && READY.includes(p.status)
              return (
                <TableRow key={p.part.itemId} hover>
                  <TableCell padding="checkbox">
                    {sendable && (
                      <Checkbox
                        checked={toSend.includes(p.part.itemId)}
                        onChange={() => toggle(toSend, setToSend, p.part.itemId)}
                        slotProps={{ input: { 'aria-label': `Select ${p.part.partNumber} to send` } }}
                      />
                    )}
                    {quotable && (
                      <Checkbox
                        checked={toQuote.includes(p.quoteId!)}
                        onChange={() => toggle(toQuote, setToQuote, p.quoteId!)}
                        slotProps={{ input: { 'aria-label': `Select ${p.part.partNumber} for the customer quotation` } }}
                      />
                    )}
                  </TableCell>
                  <TableCell>
                    {p.part.partNumber}
                    {p.part.revision ? ` rev ${p.part.revision}` : ''}
                    <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
                      {p.part.description ?? ''}
                    </Typography>
                  </TableCell>
                  <TableCell>{p.subRfqNo ?? '—'}</TableCell>
                  <TableCell>{p.supplier?.name ?? <em>Not sent yet</em>}</TableCell>
                  <TableCell>
                    {p.status ? <Chip size="small" label={QUOTE_STATUS[p.status].label} color={QUOTE_STATUS[p.status].color} /> : '—'}
                  </TableCell>
                  <TableCell>{formatDate(p.responseDueDate)}</TableCell>
                  <TableCell>{p.version || '—'}</TableCell>
                  <TableCell align="right">{formatMoney(p.productionPiecePrice, p.currency ?? undefined)}</TableCell>
                  <TableCell align="right">{formatMoney(p.toolingCostTotal, p.currency ?? undefined)}</TableCell>
                  <TableCell align="right">{formatNumber(p.moq)}</TableCell>
                  <TableCell align="right">{formatNumber(p.productionLeadTimeDays, ' days')}</TableCell>
                  <TableCell>
                    {p.quoteId !== null && (
                      <Stack direction="row" spacing={0.5} useFlexGap sx={{ flexWrap: 'wrap' }}>
                        <Button size="small" onClick={() => navigate(`/quotes/${p.quoteId}`)}>
                          Open
                        </Button>
                        {p.actions.includes('ACCEPT') && (
                          <Button size="small" variant="contained" loading={running.isLoading} onClick={() => void accept(p)}>
                            Accept
                          </Button>
                        )}
                        {p.actions.includes('REQUEST_REVISION') && (
                          <Button size="small" onClick={() => setDialog({ row: p, command: 'revision' })}>
                            Revision required
                          </Button>
                        )}
                        {p.actions.includes('RECALL') && (
                          <Button size="small" color="error" onClick={() => setDialog({ row: p, command: 'recall' })}>
                            Take back
                          </Button>
                        )}
                      </Stack>
                    )}
                  </TableCell>
                </TableRow>
              )
            })}
            {data.parts.length === 0 && (
              <TableRow>
                <TableCell colSpan={12} sx={{ color: 'text.secondary' }}>
                  This RFQ has no parts.
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </TableContainer>

      {sending && (
        <SendToSupplierDialog
          rfqId={rfqId}
          itemIds={toSend}
          onClose={(sent) => {
            setSending(false)
            if (sent) setToSend([])
          }}
        />
      )}
      {generating && (
        <GenerateQuotationDialog
          rfqId={rfqId}
          rows={data.parts.filter((p) => p.quoteId !== null && toQuote.includes(p.quoteId))}
          onClose={(generated) => {
            setGenerating(false)
            if (generated) setToQuote([])
          }}
        />
      )}
      {dialog && (
        <CommentDialog
          title={dialog.command === 'revision' ? `Revision required: ${dialog.row.part.partNumber}` : `Take back ${dialog.row.part.partNumber}`}
          label={dialog.command === 'revision' ? 'What the supplier should change' : 'Why the part is taken back from this supplier'}
          required
          confirmLabel={dialog.command === 'revision' ? 'Send back' : 'Take back'}
          intro={
            dialog.command === 'revision'
              ? 'The quote goes back to the supplier with your comments. They edit and resubmit; the submitted version stays on record.'
              : 'The supplier no longer sees this part and you can send it to another supplier. Their quote stays on record.'
          }
          danger={dialog.command === 'recall'}
          busy={running.isLoading}
          error={running.error}
          onConfirm={(comment) => void withComment(comment)}
          onClose={() => setDialog(null)}
        />
      )}
    </Stack>
  )
}

/** One sub-RFQ: the selected parts go to one supplier (flow doc §2). */
function SendToSupplierDialog({ rfqId, itemIds, onClose }: { rfqId: number; itemIds: number[]; onClose: (sent: boolean) => void }) {
  const { data: companies = [], isLoading } = useCompaniesQuery()
  const [distribute, distributing] = useDistributeRfqMutation()
  const [supplier, setSupplier] = useState<Company | null>(null)
  const [due, setDue] = useState('')
  const suppliers = companies.filter((c) => c.type === 'SUPPLIER' && c.status === 'ACTIVE')

  async function send() {
    if (!supplier) return
    try {
      await distribute({ rfqId, subRfqs: [{ supplierId: supplier.id, itemIds }], responseDueDate: due }).unwrap()
      onClose(true)
    } catch {
      // shown below
    }
  }

  return (
    <Dialog open onClose={() => onClose(false)} fullWidth maxWidth="sm">
      <DialogTitle>
        Send {itemIds.length} part{itemIds.length === 1 ? '' : 's'} to a supplier
      </DialogTitle>
      <DialogContent sx={{ display: 'grid', gap: 2, pt: '8px !important' }}>
        <Autocomplete
          loading={isLoading}
          options={suppliers}
          value={supplier}
          onChange={(_e, value) => setSupplier(value)}
          getOptionLabel={(c) => `${c.profile.name} (${c.code})`}
          isOptionEqualToValue={(a, b) => a.id === b.id}
          noOptionsText="No active suppliers"
          renderInput={(params) => <TextField {...params} label="Supplier" autoFocus />}
        />
        <TextField
          type="date"
          label="Quotes due by"
          required
          value={due}
          onChange={(e) => setDue(e.target.value)}
          slotProps={{ inputLabel: { shrink: true }, htmlInput: { min: new Date().toLocaleDateString('en-CA') } }}
        />
        {distributing.error != null && <Alert severity="error">{problemMessage(distributing.error)}</Alert>}
      </DialogContent>
      <DialogActions>
        <Button onClick={() => onClose(false)}>Cancel</Button>
        <Button variant="contained" disabled={!supplier || !due} loading={distributing.isLoading} onClick={() => void send()}>
          Send
        </Button>
      </DialogActions>
    </Dialog>
  )
}
