import {
  Button,
  Chip,
  LinearProgress,
  Paper,
  Stack,
  Tab,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Tabs,
  Typography,
} from '@mui/material'
import { useState } from 'react'
import { useAppSelector } from '../../../app/store'
import { useMasterEntriesQuery, useMasterListsQuery, type MasterEntry } from '@/features/admin/services/adminApi'
import { ExchangeRatesPanel } from '../components/ExchangeRatesPanel'
import { MasterEntryDialog } from '../components/MasterEntryDialog'
import { ProblemAlert } from '@/components/ui/ProblemAlert'

const RATES = 'exchange-rates'

// SRS §12.13.3 master data screens. ERGO Admin maintains; ERGO Management views (SRS §10.12).
export function MasterDataPage() {
  const canEdit = useAppSelector((state) => state.auth.session?.activeRole === 'ERGO_ADMIN')
  const { data: lists = [] } = useMasterListsQuery()
  const [key, setKey] = useState('currencies')
  const { data: entries = [], isFetching, error } = useMasterEntriesQuery(key, { skip: key === RATES })
  const [editing, setEditing] = useState<MasterEntry | 'new' | null>(null)
  const list = lists.find((l) => l.key === key)

  return (
    <Stack spacing={2}>
      <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center' }}>
        <Typography variant="h5" component="h1">
          Master data
        </Typography>
        {canEdit && list && (
          <Button variant="contained" onClick={() => setEditing('new')}>
            New entry
          </Button>
        )}
      </Stack>
      {lists.length > 0 && (
        <Tabs value={key} onChange={(_event, value: string) => setKey(value)} variant="scrollable">
          {lists.map((l) => (
            <Tab key={l.key} value={l.key} label={l.title} />
          ))}
          <Tab value={RATES} label="Exchange rates" />
        </Tabs>
      )}
      {key === RATES && <ExchangeRatesPanel canEdit={canEdit} />}
      {error && <ProblemAlert error={error} />}
      {key !== RATES && (
        <TableContainer component={Paper} variant="outlined">
          {isFetching && <LinearProgress />}
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Code</TableCell>
                <TableCell>Name</TableCell>
                {list?.fields.map((field) => <TableCell key={field.key}>{field.label}</TableCell>)}
                <TableCell>Status</TableCell>
                {canEdit && <TableCell align="right">Actions</TableCell>}
              </TableRow>
            </TableHead>
            <TableBody>
              {entries.map((entry) => (
                <TableRow key={entry.id} hover sx={{ opacity: entry.active ? 1 : 0.6 }}>
                  <TableCell>{entry.code}</TableCell>
                  <TableCell>{entry.name}</TableCell>
                  {list?.fields.map((field) => (
                    <TableCell key={field.key}>{entry.fields[field.key] ?? '—'}</TableCell>
                  ))}
                  <TableCell>
                    <Chip size="small" color={entry.active ? 'success' : 'default'} label={entry.active ? 'Active' : 'Inactive'} />
                  </TableCell>
                  {canEdit && (
                    <TableCell align="right">
                      <Button size="small" onClick={() => setEditing(entry)}>
                        Edit
                      </Button>
                    </TableCell>
                  )}
                </TableRow>
              ))}
              {!isFetching && entries.length === 0 && (
                <TableRow>
                  <TableCell colSpan={(list?.fields.length ?? 0) + 4} sx={{ color: 'text.secondary' }}>
                    No entries yet.
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        </TableContainer>
      )}
      {editing && list && (
        <MasterEntryDialog list={list} entry={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />
      )}
    </Stack>
  )
}
