import { Paper, Table, TableBody, TableCell, TableContainer, TableHead, TableRow } from '@mui/material'
import { formatDateTime } from '@/utils/date'
import { roleTitle } from '@/features/auth/utils/demoAccounts'

export interface AuditRow {
  at: string
  user: string | null
  role: string | null
  event: string
  field: string | null
  oldValue: string | null
  newValue: string | null
  reason: string | null
}

const describe = (row: AuditRow) =>
  row.field ? `${row.field}: ${row.oldValue ?? '—'} → ${row.newValue ?? '—'}` : row.newValue ?? row.oldValue ?? ''

/** SRS §15.5 audit trail tab: date-time, user, role, action, change, reason. */
export function AuditTrail({ rows }: { rows: AuditRow[] }) {
  return (
    <TableContainer component={Paper} variant="outlined">
      <Table size="small">
        <TableHead>
          <TableRow>
            <TableCell>When</TableCell>
            <TableCell>Who</TableCell>
            <TableCell>Action</TableCell>
            <TableCell>Change</TableCell>
            <TableCell>Reason / comment</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {rows.map((row) => (
            <TableRow key={`${row.at}-${row.event}`}>
              <TableCell sx={{ whiteSpace: 'nowrap' }}>{formatDateTime(row.at)}</TableCell>
              <TableCell>
                {row.user ?? 'System'}
                {row.role && <div style={{ fontSize: 12, opacity: 0.7 }}>{roleTitle(row.role)}</div>}
              </TableCell>
              <TableCell>{row.event}</TableCell>
              <TableCell sx={{ maxWidth: 420, wordBreak: 'break-word' }}>{describe(row)}</TableCell>
              <TableCell sx={{ maxWidth: 300, wordBreak: 'break-word' }}>{row.reason ?? ''}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TableContainer>
  )
}
