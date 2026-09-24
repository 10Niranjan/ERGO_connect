import {
  Alert,
  Autocomplete,
  Button,
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
import { problemMessage } from '../../../shared/problem'
import { useCompaniesQuery, type Company } from '../../companies/companiesApi'
import { formatDate, formatMoney, formatNumber } from '../../rfq/labels'
import { QUOTE_STATUS } from '../labels'
import { useInviteSuppliersMutation, useRfqSuppliersQuery } from '../quoteApi'

/** ERGO's view of an RFQ's supplier float (SRS §5.3): who was invited, where each quote stands, the headline figures. */
export function SuppliersPanel({ rfqId }: { rfqId: number }) {
  const navigate = useNavigate()
  const { data, isLoading, error } = useRfqSuppliersQuery(rfqId)
  const [inviting, setInviting] = useState(false)
  if (isLoading) return <LinearProgress />
  if (!data) return <Alert severity="error">{problemMessage(error)}</Alert>

  return (
    <Stack spacing={2}>
      {data.canInvite && (
        <Stack direction="row" spacing={2} sx={{ alignItems: 'center' }}>
          <Button variant="contained" onClick={() => setInviting(true)}>
            Invite suppliers
          </Button>
          <Typography variant="body2" color="text.secondary">
            Suppliers see the part, technical data, planned process and the files you share with suppliers. Never the customer or the targets.
          </Typography>
        </Stack>
      )}
      <TableContainer component={Paper} variant="outlined">
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>Supplier</TableCell>
              <TableCell>Status</TableCell>
              <TableCell>Due</TableCell>
              <TableCell>Version</TableCell>
              <TableCell>Submitted</TableCell>
              <TableCell align="right">Piece price</TableCell>
              <TableCell align="right">Tooling</TableCell>
              <TableCell align="right">MOQ</TableCell>
              <TableCell align="right">Production lead time</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {data.quotes.map((q) => (
              <TableRow key={q.id} hover onClick={() => navigate(`/quotes/${q.id}`)} sx={{ cursor: 'pointer' }}>
                <TableCell>{q.supplier.name}</TableCell>
                <TableCell>
                  <Chip size="small" label={QUOTE_STATUS[q.status].label} color={QUOTE_STATUS[q.status].color} />
                </TableCell>
                <TableCell>{formatDate(q.responseDueDate)}</TableCell>
                <TableCell>{q.version || '—'}</TableCell>
                <TableCell>{formatDate(q.submittedAt)}</TableCell>
                <TableCell align="right">{formatMoney(q.productionPiecePrice, q.currency ?? undefined)}</TableCell>
                <TableCell align="right">{formatMoney(q.toolingCostTotal, q.currency ?? undefined)}</TableCell>
                <TableCell align="right">{formatNumber(q.moq)}</TableCell>
                <TableCell align="right">{formatNumber(q.productionLeadTimeDays, ' days')}</TableCell>
              </TableRow>
            ))}
            {data.quotes.length === 0 && (
              <TableRow>
                <TableCell colSpan={9} sx={{ color: 'text.secondary' }}>
                  No suppliers invited yet.
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </TableContainer>
      {inviting && <InviteDialog rfqId={rfqId} invited={data.quotes.map((q) => q.supplier.id)} onClose={() => setInviting(false)} />}
    </Stack>
  )
}

function InviteDialog({ rfqId, invited, onClose }: { rfqId: number; invited: number[]; onClose: () => void }) {
  const { data: companies = [], isLoading } = useCompaniesQuery()
  const [invite, inviting] = useInviteSuppliersMutation()
  const [selected, setSelected] = useState<Company[]>([])
  const [due, setDue] = useState('')
  const suppliers = companies.filter((c) => c.type === 'SUPPLIER' && c.status === 'ACTIVE' && !invited.includes(c.id))

  async function send() {
    try {
      await invite({ rfqId, supplierIds: selected.map((c) => c.id), responseDueDate: due }).unwrap()
      onClose()
    } catch {
      // shown below
    }
  }

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Invite suppliers</DialogTitle>
      <DialogContent sx={{ display: 'grid', gap: 2, pt: '8px !important' }}>
        <Autocomplete
          multiple
          loading={isLoading}
          options={suppliers}
          value={selected}
          onChange={(_e, value) => setSelected(value)}
          getOptionLabel={(c) => `${c.profile.name} (${c.code})`}
          isOptionEqualToValue={(a, b) => a.id === b.id}
          noOptionsText="No other active suppliers"
          renderInput={(params) => <TextField {...params} label="Suppliers" autoFocus />}
        />
        <TextField
          type="date"
          label="Quotes due by"
          required
          value={due}
          onChange={(e) => setDue(e.target.value)}
          slotProps={{ inputLabel: { shrink: true }, htmlInput: { min: new Date().toLocaleDateString('en-CA') } }}
        />
        {inviting.error != null && <Alert severity="error">{problemMessage(inviting.error)}</Alert>}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={selected.length === 0 || !due} loading={inviting.isLoading} onClick={() => void send()}>
          Invite {selected.length || ''}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
