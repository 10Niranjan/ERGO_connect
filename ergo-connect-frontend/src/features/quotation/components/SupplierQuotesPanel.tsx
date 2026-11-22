import MoreVertIcon from '@mui/icons-material/MoreVert'
import { Alert, Box, Button, Chip, IconButton, LinearProgress, Menu, MenuItem, Paper, Stack, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Typography } from '@mui/material'
import { useState } from 'react'
import { useNavigate } from 'react-router'
import { QUOTATION_ERGO_APPROVE, useCan } from '@/features/permissions'
import { useConfirm } from '@/components/ui/useConfirm'
import { CommentDialog } from '../../../shared/components/CommentDialog'
import { problemMessage } from '../../../shared/problem'
import { formatDate, formatMoney, formatNumber } from '../../rfq/labels'
import { QUOTE_STATUS } from '../labels'
import { useQuoteCommandMutation, useRfqQuotesQuery, type PartRow } from '../quoteApi'
import { READY, groupByPart, overallStatus, type PartGroup } from '../utils/partGroups'
import { GenerateQuotationDialog } from './GenerateQuotationDialog'

type RowDialog = { row: PartRow; command: 'revision' | 'recall' }

/**
 * ERGO's "Supplier quotations" tab (flow doc §3-4, several suppliers per part since 2026-11-22): each part's quotes side
 * by side. ERGO selects ONE quote per part, then sends the selected quote to the customer; every row action is in its
 * three-dots menu. Only the selected quote ever reaches the customer, and only as ERGO's own quotation.
 */
export function SupplierQuotesPanel({ rfqId }: { rfqId: number }) {
  const navigate = useNavigate()
  const { data, isLoading, error } = useRfqQuotesQuery(rfqId)
  const canGenerate = useCan(QUOTATION_ERGO_APPROVE)
  const [run, running] = useQuoteCommandMutation()
  const [confirm, confirmDialog] = useConfirm()
  const [sendRows, setSendRows] = useState<PartRow[] | null>(null)
  const [dialog, setDialog] = useState<RowDialog | null>(null)
  const [actionError, setActionError] = useState<unknown>(null)
  if (isLoading) return <LinearProgress />
  if (!data) return <Alert severity="error">{problemMessage(error)}</Alert>

  const groups = groupByPart(data.parts).filter((g) => g.rows.length > 0)
  const ready = data.parts.filter((p) => p.status !== null && READY.includes(p.status))

  async function select(row: PartRow, group: PartGroup) {
    const earlier = group.rows.find((r) => r.selected && r.quoteId !== row.quoteId)
    if (earlier && !(await confirm(`${earlier.supplier?.name} is selected for ${group.part.partNumber}. Select ${row.supplier?.name} instead?`, 'Select instead'))) return
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
        {canGenerate && ready.length > 1 && (
          <Button variant="contained" color="secondary" onClick={() => setSendRows(ready)}>
            Send all {ready.length} selected quotes to the customer
          </Button>
        )}
      </Stack>
      <Typography variant="body2" color="text.secondary">
        Compare the suppliers' quotes for each part, then use the ⋮ menu: Select for customer, and after that Send to customer. Only the selected quote is sent, as
        your own quotation; the customer never sees a supplier or the other quotes.
      </Typography>
      {actionError != null && <Alert severity="error">{problemMessage(actionError)}</Alert>}
      {groups.length === 0 && <Typography color="text.secondary">No part has been sent to a supplier yet. Send parts from the Parts &amp; suppliers tab.</Typography>}

      {groups.map((group) => {
        const status = overallStatus(group.rows)
        const chip = status ? QUOTE_STATUS[status] : null
        const prices = group.rows.map((r) => r.priceInRfqCurrency).filter((p): p is number => p !== null)
        const lowest = prices.length > 1 ? Math.min(...prices) : null
        return (
          <Paper variant="outlined" key={group.itemId}>
            <Stack direction="row" spacing={1} sx={{ alignItems: 'center', p: 1.5, flexWrap: 'wrap' }} useFlexGap>
              <Box sx={{ flexGrow: 1 }}>
                <Typography variant="subtitle1" component="h2">
                  {group.part.partNumber}
                  {group.part.revision ? ` rev ${group.part.revision}` : ''}
                </Typography>
                <Typography variant="caption" color="text.secondary">
                  {group.part.description ?? ''}
                </Typography>
              </Box>
              {chip && <Chip size="small" label={chip.label} color={chip.color} />}
              <Chip size="small" variant="outlined" label={`${group.rows.length} supplier${group.rows.length === 1 ? '' : 's'}`} />
            </Stack>
            <TableContainer>
              <Table size="small">
                <TableHead>
                  <TableRow>
                    <TableCell>Supplier</TableCell>
                    <TableCell>Status</TableCell>
                    <TableCell>Due</TableCell>
                    <TableCell>Version</TableCell>
                    <TableCell align="right">Piece price</TableCell>
                    <TableCell align="right">In RFQ currency</TableCell>
                    <TableCell align="right">Tooling</TableCell>
                    <TableCell align="right">MOQ</TableCell>
                    <TableCell align="right">Production lead time</TableCell>
                    <TableCell>Valid until</TableCell>
                    <TableCell align="right">Actions</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {group.rows.map((p) => {
                    const isLowest = lowest !== null && p.priceInRfqCurrency === lowest
                    return (
                      <TableRow key={p.quoteId} hover selected={p.selected}>
                        <TableCell>
                          {p.supplier?.name}
                          {p.selected && <Chip size="small" color="primary" label="Selected" sx={{ ml: 1 }} />}
                        </TableCell>
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
                        <TableCell align="right">
                          <RowMenu
                            row={p}
                            busy={running.isLoading}
                            canSend={canGenerate && p.status !== null && READY.includes(p.status)}
                            onOpen={() => navigate(`/quotes/${p.quoteId}`)}
                            onSelect={() => void select(p, group)}
                            onRevision={() => setDialog({ row: p, command: 'revision' })}
                            onRecall={() => setDialog({ row: p, command: 'recall' })}
                            onSend={() => setSendRows([p])}
                          />
                        </TableCell>
                      </TableRow>
                    )
                  })}
                </TableBody>
              </Table>
            </TableContainer>
          </Paper>
        )
      })}

      {sendRows && <GenerateQuotationDialog rfqId={rfqId} rows={sendRows} onClose={() => setSendRows(null)} />}
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

interface RowMenuProps {
  row: PartRow
  busy: boolean
  canSend: boolean
  onOpen: () => void
  onSelect: () => void
  onRevision: () => void
  onRecall: () => void
  onSend: () => void
}

/** Every action on one supplier's quote, in one three-dots menu; only what the server allows is listed. */
function RowMenu({ row, busy, canSend, onOpen, onSelect, onRevision, onRecall, onSend }: RowMenuProps) {
  const [anchor, setAnchor] = useState<HTMLElement | null>(null)
  const close = () => setAnchor(null)
  const pick = (action: () => void) => () => {
    close()
    action()
  }
  return (
    <>
      <IconButton size="small" disabled={busy} aria-label={`Actions for ${row.supplier?.name}`} onClick={(e) => setAnchor(e.currentTarget)}>
        <MoreVertIcon />
      </IconButton>
      <Menu anchorEl={anchor} open={anchor !== null} onClose={close}>
        <MenuItem onClick={pick(onOpen)}>Open</MenuItem>
        {row.actions.includes('ACCEPT') && <MenuItem onClick={pick(onSelect)}>Select for customer</MenuItem>}
        {canSend && <MenuItem onClick={pick(onSend)}>Send to customer</MenuItem>}
        {row.actions.includes('REQUEST_REVISION') && <MenuItem onClick={pick(onRevision)}>Revision required</MenuItem>}
        {row.actions.includes('RECALL') && (
          <MenuItem onClick={pick(onRecall)} sx={{ color: 'error.main' }}>
            Take back
          </MenuItem>
        )}
      </Menu>
    </>
  )
}
