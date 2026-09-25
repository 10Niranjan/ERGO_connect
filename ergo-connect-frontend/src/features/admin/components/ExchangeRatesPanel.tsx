import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
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
import { useState, type FormEvent } from 'react'
import { useMasterOptionsQuery } from '../../../shared/masterData'
import { problemMessage } from '../../../shared/problem'
import { formatDate } from '../../rfq/labels'
import { useCreateExchangeRateMutation, useDeleteExchangeRateMutation, useExchangeRatesQuery } from '../adminApi'

/** SRS §13.2.7. ERGO-internal only: rates convert quotes for comparison and never reach customers or suppliers. */
export function ExchangeRatesPanel({ canEdit }: { canEdit: boolean }) {
  const { data: rates = [], isFetching, error } = useExchangeRatesQuery()
  const [remove] = useDeleteExchangeRateMutation()
  const [adding, setAdding] = useState(false)
  const [actionError, setActionError] = useState<unknown>(null)

  return (
    <Stack spacing={2}>
      <Stack direction="row" spacing={2} sx={{ alignItems: 'center' }}>
        {canEdit && (
          <Button variant="contained" onClick={() => setAdding(true)}>
            New rate
          </Button>
        )}
        <Typography variant="body2" color="text.secondary">
          Used only inside ERGO to compare quotes in different currencies. The newest rate dated on or before a day applies; a pair entered one way round is also used the other way.
        </Typography>
      </Stack>
      {(error ?? actionError) != null && <Alert severity="error">{problemMessage(error ?? actionError)}</Alert>}
      <TableContainer component={Paper} variant="outlined">
        {isFetching && <LinearProgress />}
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>Valid from</TableCell>
              <TableCell>Rate</TableCell>
              <TableCell>Source</TableCell>
              {canEdit && <TableCell align="right" />}
            </TableRow>
          </TableHead>
          <TableBody>
            {rates.map((r) => (
              <TableRow key={r.id} hover>
                <TableCell>{formatDate(r.rateDate)}</TableCell>
                <TableCell>
                  1 {r.from.code} = {r.rate.toLocaleString(undefined, { maximumFractionDigits: 8 })} {r.to.code}
                </TableCell>
                <TableCell>{r.source ?? '—'}</TableCell>
                {canEdit && (
                  <TableCell align="right">
                    <IconButton
                      aria-label={`Delete the ${r.from.code}/${r.to.code} rate of ${r.rateDate}`}
                      onClick={() => window.confirm('Delete this rate?') && void remove(r.id).unwrap().catch(setActionError)}
                    >
                      ×
                    </IconButton>
                  </TableCell>
                )}
              </TableRow>
            ))}
            {!isFetching && rates.length === 0 && (
              <TableRow>
                <TableCell colSpan={4} sx={{ color: 'text.secondary' }}>
                  No exchange rates yet. Quotes in other currencies are then compared without conversion.
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </TableContainer>
      {adding && <RateDialog onClose={() => setAdding(false)} />}
    </Stack>
  )
}

function RateDialog({ onClose }: { onClose: () => void }) {
  const { data: currencies = [] } = useMasterOptionsQuery('currencies')
  const [create, creating] = useCreateExchangeRateMutation()
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    try {
      await create({
        fromCurrencyId: Number(from),
        toCurrencyId: Number(to),
        rateDate: String(form.get('rateDate')),
        rate: Number(form.get('rate')),
        source: String(form.get('source') ?? '').trim() || null,
      }).unwrap()
      onClose()
    } catch {
      // shown below
    }
  }

  const currencySelect = (label: string, value: string, onChange: (value: string) => void) => (
    <TextField select required label={label} value={value} onChange={(e) => onChange(e.target.value)}>
      {currencies.map((c) => (
        <MenuItem key={c.id} value={String(c.id)}>
          {c.code} – {c.name}
        </MenuItem>
      ))}
    </TextField>
  )

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="xs">
      <form onSubmit={(e) => void submit(e)}>
        <DialogTitle>New exchange rate</DialogTitle>
        <DialogContent sx={{ display: 'grid', gap: 2, pt: '8px !important' }}>
          {currencySelect('From (1 unit of)', from, setFrom)}
          {currencySelect('To', to, setTo)}
          <TextField name="rate" label="Rate" type="number" required slotProps={{ htmlInput: { min: 0, step: 'any' } }} />
          <TextField name="rateDate" label="Valid from" type="date" required defaultValue={new Date().toLocaleDateString('en-CA')} slotProps={{ inputLabel: { shrink: true } }} />
          <TextField name="source" label="Source (optional)" slotProps={{ htmlInput: { maxLength: 200 } }} />
          {creating.error != null && <Alert severity="error">{problemMessage(creating.error)}</Alert>}
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose}>Cancel</Button>
          <Button type="submit" variant="contained" loading={creating.isLoading}>
            Save
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  )
}
