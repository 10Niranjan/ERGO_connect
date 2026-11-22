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
  TextField,
  Typography,
} from '@mui/material'
import { useState } from 'react'
import { problemMessage } from '../../../shared/problem'
import { useCompaniesQuery, type Company } from '../../companies/companiesApi'
import { FILE_KIND_LABELS } from '../../rfq/labels'
import { useRfqQuery } from '../../rfq/rfqApi'
import { QUOTE_STATUS } from '../labels'
import { useDistributeRfqMutation, useRfqQuotesQuery, type PartRow } from '../quoteApi'
import { groupByPart, overallStatus } from '../utils/partGroups'

/**
 * ERGO's "Parts & suppliers" tab (flow doc §2): which parts are with which supplier, and sending parts to one or
 * several suppliers. The quotes themselves are compared on the "Supplier quotations" tab.
 */
export function PartsPanel({ rfqId, onOpenQuotes }: { rfqId: number; onOpenQuotes: () => void }) {
  const { data, isLoading, error } = useRfqQuotesQuery(rfqId)
  const [toSend, setToSend] = useState<number[]>([])
  const [sending, setSending] = useState(false)
  if (isLoading) return <LinearProgress />
  if (!data) return <Alert severity="error">{problemMessage(error)}</Alert>

  const groups = groupByPart(data.parts)
  const toggle = (id: number) => setToSend((list) => (list.includes(id) ? list.filter((x) => x !== id) : [...list, id]))

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
        {data.parts.some((p) => p.quoteId !== null) && <Button onClick={onOpenQuotes}>Compare supplier quotations</Button>}
      </Stack>
      <Typography variant="body2" color="text.secondary">
        Tick the parts and send them to one or several suppliers. A supplier sees only the parts sent to it: the part, its technical data and the files you
        share. Never the customer, the targets or another supplier. Quotes are compared and selected on the Supplier quotations tab.
      </Typography>

      {groups.length === 0 && <Typography color="text.secondary">This RFQ has no parts.</Typography>}
      {groups.map((group) => {
        const status = overallStatus(group.rows)
        const chip = status ? QUOTE_STATUS[status] : null
        return (
          <Paper variant="outlined" key={group.itemId} sx={{ p: 1.5 }}>
            <Stack direction="row" spacing={1} sx={{ alignItems: 'center', flexWrap: 'wrap' }} useFlexGap>
              {data.sendableItemIds.includes(group.itemId) && data.canDistribute && (
                <Checkbox checked={toSend.includes(group.itemId)} onChange={() => toggle(group.itemId)} slotProps={{ input: { 'aria-label': `Select ${group.part.partNumber} to send` } }} />
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
            </Stack>
            {group.rows.length > 0 && (
              <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap', mt: 1 }}>
                {group.rows.map((r: PartRow) => (
                  <Chip
                    key={r.quoteId}
                    size="small"
                    variant="outlined"
                    color={r.selected ? 'primary' : 'default'}
                    label={`${r.supplier?.name}: ${r.status ? QUOTE_STATUS[r.status].label : '—'}${r.selected ? ' (selected)' : ''}`}
                  />
                ))}
              </Stack>
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
