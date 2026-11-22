import {
  Alert,
  Autocomplete,
  Box,
  Button,
  Checkbox,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
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
import { useConfirm } from '@/components/ui/useConfirm'
import { CommentDialog } from '../../../shared/components/CommentDialog'
import { problemMessage } from '../../../shared/problem'
import { useCompaniesQuery, type Company } from '../../companies/companiesApi'
import { FILE_KIND_LABELS, formatDate, formatMoney, formatNumber } from '../../rfq/labels'
import { useRfqQuery } from '../../rfq/rfqApi'
import { QUOTE_STATUS } from '../labels'
import { useDistributeRfqMutation, useQuoteCommandMutation, useRfqQuotesQuery, type PartRow, type QuoteStatus } from '../quoteApi'
import { GenerateQuotationDialog } from './GenerateQuotationDialog'

/** A part whose quote ERGO has selected (or the customer sent back) can go into a customer quotation. */
const READY: PartRow['status'][] = ['ACCEPTED', 'CUSTOMER_CORRECTION']

/** The order a part's overall status is read in when no quote is selected yet. */
const PROGRESS: QuoteStatus[] = ['SUBMITTED', 'RESUBMITTED', 'REVISION_REQUIRED', 'DRAFT_SAVED', 'NOT_STARTED']

type RowDialog = { row: PartRow; command: 'revision' | 'recall' }

interface PartGroup {
  itemId: number
  part: PartRow['part']
  /** One row per supplier holding the part; empty when it has not been sent. */
  rows: PartRow[]
}

function groupByPart(parts: PartRow[]): PartGroup[] {
  const groups = new Map<number, PartGroup>()
  for (const row of parts) {
    const group = groups.get(row.part.itemId) ?? { itemId: row.part.itemId, part: row.part, rows: [] }
    if (row.quoteId !== null) group.rows.push(row)
    groups.set(row.part.itemId, group)
  }
  return [...groups.values()]
}

/** Where the part stands: the selected quote's status, else the furthest-along one. */
function overallStatus(rows: PartRow[]): QuoteStatus | null {
  const selected = rows.find((r) => r.selected)
  if (selected) return selected.status
  return PROGRESS.find((s) => rows.some((r) => r.status === s)) ?? (rows.length > 0 ? 'CLOSED' : null)
}

/**
 * ERGO's view of an RFQ's parts (flow doc §2-4, changed 2026-11-22): each part may be with several suppliers, and
 * their quotes are compared side by side. ERGO selects ONE quote per part; only the selected quotes go into the
 * customer quotation. Also: sending parts to suppliers, sending a quote back for revision, taking a part back.
 */
export function PartsPanel({ rfqId }: { rfqId: number }) {
  const navigate = useNavigate()
  const { data, isLoading, error } = useRfqQuotesQuery(rfqId)
  const canGenerate = useCan(QUOTATION_ERGO_APPROVE)
  const [run, running] = useQuoteCommandMutation()
  const [confirm, confirmDialog] = useConfirm()
  const [toSend, setToSend] = useState<number[]>([])
  const [toQuote, setToQuote] = useState<number[]>([])
  const [sending, setSending] = useState(false)
  const [generating, setGenerating] = useState(false)
  const [dialog, setDialog] = useState<RowDialog | null>(null)
  const [actionError, setActionError] = useState<unknown>(null)
  if (isLoading) return <LinearProgress />
  if (!data) return <Alert severity="error">{problemMessage(error)}</Alert>

  const groups = groupByPart(data.parts)
  const ready = data.parts.filter((p) => p.status !== null && READY.includes(p.status))
  const toggle = (list: number[], set: (value: number[]) => void, id: number) =>
    set(list.includes(id) ? list.filter((x) => x !== id) : [...list, id])

  async function select(row: PartRow, group: PartGroup) {
    const earlier = group.rows.find((r) => r.selected && r.quoteId !== row.quoteId)
    if (earlier && !(await confirm(`${earlier.supplier?.name} is selected for ${group.part.partNumber}. Select ${row.supplier?.name} instead?`, 'Select instead'))) return
    setActionError(null)
    try {
      await run({ id: row.quoteId!, command: 'accept' }).unwrap()
      setToQuote((list) => list.filter((id) => !group.rows.some((r) => r.quoteId === id))) // a switched selection is not ticked any more
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
              Send {toSend.length || ''} selected part{toSend.length === 1 ? '' : 's'} to suppliers
            </Button>
            <Button onClick={() => setToSend(data.sendableItemIds)}>Select all parts that can be sent</Button>
          </>
        )}
        {canGenerate && ready.length > 0 && (
          <Button variant="contained" color="secondary" disabled={toQuote.length === 0} onClick={() => setGenerating(true)}>
            Generate quotation for customer ({toQuote.length})
          </Button>
        )}
      </Stack>
      <Typography variant="body2" color="text.secondary">
        Select the parts and send them to one or several suppliers. When several suppliers quote the same part, compare their quotes here and select the one
        that goes to the customer; the others are never sent. A supplier sees only the parts sent to it: the part, its technical data and the files you share.
        Never the customer, the targets or another supplier.
      </Typography>
      {actionError != null && <Alert severity="error">{problemMessage(actionError)}</Alert>}

      {groups.length === 0 && <Typography color="text.secondary">This RFQ has no parts.</Typography>}
      {groups.map((group) => {
        const sendable = data.sendableItemIds.includes(group.itemId)
        const status = overallStatus(group.rows)
        const chip = status ? QUOTE_STATUS[status] : null
        const prices = group.rows.map((r) => r.priceInRfqCurrency).filter((p): p is number => p !== null)
        const lowest = prices.length > 1 ? Math.min(...prices) : null
        return (
          <Paper variant="outlined" key={group.itemId}>
            <Stack direction="row" spacing={1} sx={{ alignItems: 'center', p: 1.5, flexWrap: 'wrap' }} useFlexGap>
              {sendable && data.canDistribute && (
                <Checkbox
                  checked={toSend.includes(group.itemId)}
                  onChange={() => toggle(toSend, setToSend, group.itemId)}
                  slotProps={{ input: { 'aria-label': `Select ${group.part.partNumber} to send` } }}
                />
              )}
              <Box sx={{ flexGrow: 1 }}>
                <Typography variant="subtitle1" component="h2">
                  {group.part.partNumber}
                  {group.part.revision ? ` rev ${group.part.revision}` : ''}
                </Typography>
                <Typography variant="caption" color="text.secondary">
                  {group.part.description ?? ''}
                </Typography>
              </Box>
              {chip ? <Chip size="small" label={chip.label} color={chip.color} /> : <Chip size="small" label="Not sent to a supplier" />}
              <Chip size="small" variant="outlined" label={`${group.rows.length} supplier${group.rows.length === 1 ? '' : 's'}`} />
            </Stack>
            {group.rows.length > 0 && (
              <TableContainer>
                <Table size="small">
                  <TableHead>
                    <TableRow>
                      <TableCell padding="checkbox" />
                      <TableCell>Supplier</TableCell>
                      <TableCell>Sub-RFQ</TableCell>
                      <TableCell>Status</TableCell>
                      <TableCell>Due</TableCell>
                      <TableCell>Version</TableCell>
                      <TableCell align="right">Piece price</TableCell>
                      <TableCell align="right">In RFQ currency</TableCell>
                      <TableCell align="right">Tooling</TableCell>
                      <TableCell align="right">MOQ</TableCell>
                      <TableCell align="right">Production lead time</TableCell>
                      <TableCell>Valid until</TableCell>
                      <TableCell>Actions</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {group.rows.map((p) => {
                      const quotable = canGenerate && p.status !== null && READY.includes(p.status)
                      const isLowest = lowest !== null && p.priceInRfqCurrency === lowest
                      return (
                        <TableRow key={p.quoteId} hover selected={p.selected}>
                          <TableCell padding="checkbox">
                            {quotable && (
                              <Checkbox
                                checked={toQuote.includes(p.quoteId!)}
                                onChange={() => toggle(toQuote, setToQuote, p.quoteId!)}
                                slotProps={{ input: { 'aria-label': `Select ${p.part.partNumber} from ${p.supplier?.name} for the customer quotation` } }}
                              />
                            )}
                          </TableCell>
                          <TableCell>
                            {p.supplier?.name}
                            {p.selected && <Chip size="small" color="primary" label="Selected" sx={{ ml: 1 }} />}
                          </TableCell>
                          <TableCell>{p.subRfqNo ?? '—'}</TableCell>
                          <TableCell>{p.status ? <Chip size="small" label={QUOTE_STATUS[p.status].label} color={QUOTE_STATUS[p.status].color} /> : '—'}</TableCell>
                          <TableCell>{formatDate(p.responseDueDate)}</TableCell>
                          <TableCell>{p.version || '—'}</TableCell>
                          <TableCell align="right">{formatMoney(p.productionPiecePrice, p.currency ?? undefined)}</TableCell>
                          <TableCell align="right" sx={isLowest ? { fontWeight: 700, color: 'success.main' } : undefined}>
                            {p.priceInRfqCurrency === null ? '—' : formatNumber(p.priceInRfqCurrency)}
                            {isLowest && ' (lowest)'}
                          </TableCell>
                          <TableCell align="right">{formatMoney(p.toolingCostTotal, p.currency ?? undefined)}</TableCell>
                          <TableCell align="right">{formatNumber(p.moq)}</TableCell>
                          <TableCell align="right">{formatNumber(p.productionLeadTimeDays, ' days')}</TableCell>
                          <TableCell>{formatDate(p.validityDate)}</TableCell>
                          <TableCell>
                            <Stack direction="row" spacing={0.5} useFlexGap sx={{ flexWrap: 'wrap' }}>
                              <Button size="small" onClick={() => navigate(`/quotes/${p.quoteId}`)}>
                                Open
                              </Button>
                              {p.actions.includes('ACCEPT') && (
                                <Button size="small" variant="contained" loading={running.isLoading} onClick={() => void select(p, group)}>
                                  Select for customer
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
                          </TableCell>
                        </TableRow>
                      )
                    })}
                  </TableBody>
                </Table>
              </TableContainer>
            )}
          </Paper>
        )
      })}

      {sending && (
        <SendToSupplierDialog
          rfqId={rfqId}
          itemIds={toSend}
          held={data.parts.filter((p) => p.quoteId !== null)}
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
          title={dialog.command === 'revision' ? `Revision required: ${dialog.row.part.partNumber} (${dialog.row.supplier?.name})` : `Take back ${dialog.row.part.partNumber} from ${dialog.row.supplier?.name}`}
          label={dialog.command === 'revision' ? 'What the supplier should change' : 'Why the part is taken back from this supplier'}
          required
          confirmLabel={dialog.command === 'revision' ? 'Send back' : 'Take back'}
          intro={
            dialog.command === 'revision'
              ? 'The quote goes back to this supplier with your comments. They edit and resubmit; the submitted version stays on record.'
              : 'This supplier no longer sees the part and you can send it to another one. Their quote stays on record.'
          }
          danger={dialog.command === 'recall'}
          busy={running.isLoading}
          error={running.error}
          onConfirm={(comment) => void withComment(comment)}
          onClose={() => setDialog(null)}
        />
      )}
      {confirmDialog}
    </Stack>
  )
}

/** Sends the selected parts to one or several suppliers, one sub-RFQ each, with the part files ERGO chooses to share. */
function SendToSupplierDialog({ rfqId, itemIds, held, onClose }: { rfqId: number; itemIds: number[]; held: PartRow[]; onClose: (sent: boolean) => void }) {
  const { data: companies = [], isLoading } = useCompaniesQuery()
  const { data: rfq } = useRfqQuery(rfqId)
  const [distribute, distributing] = useDistributeRfqMutation()
  const [suppliers, setSuppliers] = useState<Company[]>([])
  const [due, setDue] = useState('')
  const parts = rfq?.audience === 'ERGO' ? rfq.items.filter((i) => itemIds.includes(i.id)) : []
  const files = parts.flatMap((p) => p.files.map((f) => ({ ...f, partNumber: p.partNumber })))
  // every file is ticked to start with; ERGO unticks what suppliers should not get (a drawing with the customer's logo)
  const [unticked, setUnticked] = useState<number[]>([])
  // a supplier that already holds one of the selected parts cannot be sent it again
  const holding = new Set(held.filter((r) => itemIds.includes(r.part.itemId)).map((r) => r.supplier?.id))
  const options = companies.filter((c) => c.type === 'SUPPLIER' && c.status === 'ACTIVE' && !holding.has(c.id))

  async function send() {
    if (suppliers.length === 0) return
    try {
      await distribute({
        rfqId,
        subRfqs: suppliers.map((s) => ({ supplierId: s.id, itemIds })),
        responseDueDate: due,
        sharedDocumentIds: files.filter((f) => !unticked.includes(f.id)).map((f) => f.id),
      }).unwrap()
      onClose(true)
    } catch {
      // shown below
    }
  }

  return (
    <Dialog open onClose={() => onClose(false)} fullWidth maxWidth="sm">
      <DialogTitle>
        Send {itemIds.length} part{itemIds.length === 1 ? '' : 's'} to suppliers
      </DialogTitle>
      <DialogContent sx={{ display: 'grid', gap: 2, pt: '8px !important' }}>
        <Autocomplete
          multiple
          loading={isLoading}
          options={options}
          value={suppliers}
          onChange={(_e, value) => setSuppliers(value)}
          getOptionLabel={(c) => `${c.profile.name} (${c.code})`}
          isOptionEqualToValue={(a, b) => a.id === b.id}
          noOptionsText="No active suppliers"
          renderInput={(params) => <TextField {...params} label="Suppliers (choose one or several)" autoFocus />}
        />
        {suppliers.length > 1 && (
          <Typography variant="body2" color="text.secondary">
            Each supplier quotes these parts separately and never sees the others. You compare the quotes and select one per part.
          </Typography>
        )}
        <TextField
          type="date"
          label="Quotes due by"
          required
          value={due}
          onChange={(e) => setDue(e.target.value)}
          slotProps={{ inputLabel: { shrink: true }, htmlInput: { min: new Date().toLocaleDateString('en-CA') } }}
        />
        <Box>
          <Typography variant="subtitle2">Files to share with the suppliers</Typography>
          <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
            Check each file for anything that names the customer. Sharing is per part: a file already shared for a part stays visible to every supplier of that part, including these.
          </Typography>
          {files.length === 0 ? (
            <Typography variant="body2" color="text.secondary">
              The customer attached no files to these parts.
            </Typography>
          ) : (
            files.map((f) => (
              <FormControlLabel
                key={f.id}
                sx={{ display: 'flex' }}
                control={<Checkbox checked={!unticked.includes(f.id)} onChange={() => setUnticked((list) => (list.includes(f.id) ? list.filter((x) => x !== f.id) : [...list, f.id]))} />}
                label={`${f.partNumber}: ${FILE_KIND_LABELS[f.kind]}: ${f.fileName}`}
              />
            ))
          )}
        </Box>
        {distributing.error != null && <Alert severity="error">{problemMessage(distributing.error)}</Alert>}
      </DialogContent>
      <DialogActions>
        <Button onClick={() => onClose(false)}>Cancel</Button>
        <Button variant="contained" disabled={suppliers.length === 0 || !due} loading={distributing.isLoading} onClick={() => void send()}>
          Send
        </Button>
      </DialogActions>
    </Dialog>
  )
}
