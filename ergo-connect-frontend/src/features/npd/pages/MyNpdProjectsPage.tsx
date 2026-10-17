import { LinearProgress, Paper, Stack, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Typography } from '@mui/material'
import { useNavigate } from 'react-router'
import { formatDate } from '@/features/rfq/utils/labels'
import { NPD_STAGE } from '@/features/npd/utils/labels'
import { useMyNpdProjectsQuery } from '@/features/npd/services/npdApi'
import { ProblemAlert } from '@/components/ui/ProblemAlert'

// SRS §12.15's customer "NPD" / supplier "NPD Tasks" nav entry: the caller's own company's projects only (no
// customer/supplier column — it is always "mine").
export function MyNpdProjectsPage() {
  const { data: rows = [], isLoading, error } = useMyNpdProjectsQuery()
  const navigate = useNavigate()

  return (
    <Stack spacing={2}>
      <Typography variant="h5" component="h1">
        NPD projects
      </Typography>
      {error && <ProblemAlert error={error} />}
      <TableContainer component={Paper} variant="outlined">
        {isLoading && <LinearProgress />}
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>NPD</TableCell>
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
