import {
  Alert,
  Button,
  Chip,
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
import { useState } from 'react'
import { useNavigate } from 'react-router'
import { problemMessage } from '../../../shared/problem'
import { formatDate } from '../../rfq/labels'
import { NPD_STAGE, PLAN_STATUS, RISK_LEVEL, stageProgress } from '../labels'
import { useNpdListQuery, type RiskLevel } from '../npdApi'

// SRS §12.8.1. Table view only in 3a (no Kanban/Gantt): this list is small, so filtering stays client-side.
export function NpdListPage() {
  const { data: rows = [], isLoading, error } = useNpdListQuery()
  const [stage, setStage] = useState('')
  const [risk, setRisk] = useState('')
  const [search, setSearch] = useState('')
  const navigate = useNavigate()

  const needle = search.trim().toLowerCase()
  const shown = rows.filter(
    (r) =>
      (!stage || r.stage === stage) &&
      (!risk || r.riskLevel === risk) &&
      (!needle || [r.npdNumber, r.rfqNumber, r.customerName, r.supplierName, r.partNumber].some((v) => v?.toLowerCase().includes(needle))),
  )
  const stages = [...new Set(rows.map((r) => r.stage))]

  return (
    <Stack spacing={2}>
      <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center' }}>
        <Typography variant="h5" component="h1">
          NPD projects
        </Typography>
        <Button variant="contained" onClick={() => navigate('/npd/sample-pos')}>
          Sample POs
        </Button>
      </Stack>
      <Stack direction="row" spacing={2}>
        <TextField size="small" label="Search" value={search} onChange={(e) => setSearch(e.target.value)} sx={{ minWidth: 260 }} />
        <TextField select size="small" label="Stage" value={stage} onChange={(e) => setStage(e.target.value)} sx={{ minWidth: 240 }}>
          <MenuItem value="">All</MenuItem>
          {stages.map((s) => (
            <MenuItem key={s} value={s}>
              {NPD_STAGE[s].label}
            </MenuItem>
          ))}
        </TextField>
        <TextField select size="small" label="Risk" value={risk} onChange={(e) => setRisk(e.target.value)} sx={{ minWidth: 160 }}>
          <MenuItem value="">All</MenuItem>
          {(Object.keys(RISK_LEVEL) as RiskLevel[]).map((r) => (
            <MenuItem key={r} value={r}>
              {RISK_LEVEL[r].label}
            </MenuItem>
          ))}
        </TextField>
      </Stack>
      {error && <Alert severity="error">{problemMessage(error)}</Alert>}
      <TableContainer component={Paper} variant="outlined">
        {isLoading && <LinearProgress />}
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>NPD</TableCell>
              <TableCell>RFQ</TableCell>
              <TableCell>Customer</TableCell>
              <TableCell>Supplier</TableCell>
              <TableCell>Part</TableCell>
              <TableCell>Stage</TableCell>
              <TableCell>Plan</TableCell>
              <TableCell>Target sample date</TableCell>
              <TableCell>Delay</TableCell>
              <TableCell>NPD owner</TableCell>
              <TableCell>Risk</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {shown.map((r) => {
              const progress = stageProgress(r.stage, r.kind)
              return (
                <TableRow key={r.id} hover sx={{ cursor: 'pointer' }} onClick={() => navigate(`/npd/${r.id}`)}>
                  <TableCell>
                    {r.npdNumber}
                    {r.kind === 'PRODUCTION' && <Chip size="small" variant="outlined" label="OFP" sx={{ ml: 1 }} />}
                  </TableCell>
                  <TableCell>{r.rfqNumber ?? '—'}</TableCell>
                  <TableCell>{r.customerName}</TableCell>
                  <TableCell>{r.supplierName}</TableCell>
                  <TableCell>{r.partNumber}</TableCell>
                  <TableCell>
                    <Stack spacing={0.5} sx={{ alignItems: 'flex-start' }}>
                      <Chip size="small" label={NPD_STAGE[r.stage].label} color={NPD_STAGE[r.stage].color} />
                      <Typography variant="caption" color="text.secondary">
                        {progress.label}
                      </Typography>
                    </Stack>
                  </TableCell>
                  <TableCell>
                    <Chip size="small" label={PLAN_STATUS[r.planStatus].label} color={PLAN_STATUS[r.planStatus].color} />
                  </TableCell>
                  <TableCell>{formatDate(r.targetSampleDate)}</TableCell>
                  <TableCell sx={r.delayDays != null && r.delayDays > 0 ? { color: 'error.main', fontWeight: 600 } : undefined}>
                    {r.delayDays == null ? '—' : `${r.delayDays} d`}
                  </TableCell>
                  <TableCell>{r.owner ?? '—'}</TableCell>
                  <TableCell>
                    {r.riskLevel ? <Chip size="small" label={RISK_LEVEL[r.riskLevel].label} color={RISK_LEVEL[r.riskLevel].color} /> : '—'}
                  </TableCell>
                </TableRow>
              )
            })}
            {!isLoading && shown.length === 0 && (
              <TableRow>
                <TableCell colSpan={11} sx={{ color: 'text.secondary' }}>
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
