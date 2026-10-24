import { Alert, LinearProgress, Paper, Stack, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Typography } from '@mui/material'
import { useNavigate, useSearchParams } from 'react-router'
import { problemMessage } from '../../../shared/problem'
import { formatDate } from '../../rfq/labels'
import { NPD_STAGE } from '../labels'
import { useMyNpdProjectsQuery } from '../npdApi'

// SRS §12.15's customer "NPD" / supplier "NPD Tasks" nav entry: the caller's own company's projects only (no
// customer/supplier column — it is always "mine").
export function MyNpdProjectsPage() {
  const { data: all = [], isLoading, error } = useMyNpdProjectsQuery()
  // the "OFP projects" menu entry opens this page with ?kind=PRODUCTION
  const kind = useSearchParams()[0].get('kind')
  const rows = kind === 'PRODUCTION' || kind === 'SAMPLE' ? all.filter((r) => r.kind === kind) : all
  const navigate = useNavigate()

  return (
    <Stack spacing={2}>
      <Typography variant="h5" component="h1">
        {kind === 'PRODUCTION' ? 'OFP projects' : 'NPD projects'}
      </Typography>
      {error && <Alert severity="error">{problemMessage(error)}</Alert>}
      <TableContainer component={Paper} variant="outlined">
        {isLoading && <LinearProgress />}
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>Project</TableCell>
              <TableCell>Part</TableCell>
              <TableCell>Stage</TableCell>
              <TableCell>Target sample date</TableCell>
              <TableCell>Delay</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {rows.map((r) => (
              <TableRow key={r.id} hover sx={{ cursor: 'pointer' }} onClick={() => navigate(`/npd/${r.id}`)}>
                <TableCell>{r.npdNumber}</TableCell>
                <TableCell>
                  {r.partNumber}
                  {r.revision ? ` rev ${r.revision}` : ''}
                </TableCell>
                <TableCell>{NPD_STAGE[r.stage].label}</TableCell>
                <TableCell>{formatDate(r.targetSampleDate)}</TableCell>
                <TableCell sx={r.delayDays != null && r.delayDays > 0 ? { color: 'error.main', fontWeight: 600 } : undefined}>
                  {r.delayDays == null ? '—' : `${r.delayDays} d`}
                </TableCell>
              </TableRow>
            ))}
            {!isLoading && rows.length === 0 && (
              <TableRow>
                <TableCell colSpan={5} sx={{ color: 'text.secondary' }}>
                  No NPD projects yet.
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </TableContainer>
    </Stack>
  )
}
