import {
  Chip,
  LinearProgress,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Tooltip,
  Typography,
} from '@mui/material'
import { usePermissionMatrixQuery, useRolesQuery, type Grant } from '@/features/admin/services/adminApi'
import { ProblemAlert } from '@/components/ui/ProblemAlert'

const GRANT_COLOR: Record<Grant, 'default' | 'success' | 'info' | 'warning'> = {
  V: 'info',
  RV: 'info',
  C: 'success',
  E: 'success',
  S: 'success',
  A: 'warning',
  X: 'default',
}

// SRS §12.13.2 Permission Mapping Screen, scoped to a read-only viewer, ERGO Admin only
// (ARCHITECTURE.md §8, 2026-10-07): renders the §10.x tables the backend serves as a static constant.
// Nothing here configures live behavior - every permission check is enforced server-side per module.
export function PermissionMatrixPage() {
  const { data, isFetching, error } = usePermissionMatrixQuery()
  const { data: roleOptions = [] } = useRolesQuery()
  const roleName = (code: string) => roleOptions.find((r) => r.code === code)?.name ?? code

  return (
    <Stack spacing={2}>
      <Stack spacing={0.5}>
        <Typography variant="h5" component="h1">
          Permission mapping
        </Typography>
        <Typography variant="body2" color="text.secondary">
          Reference view of the SRS §10 role/module permission tables. This screen cannot change what a role may
          actually do - every check is enforced in the backend.
        </Typography>
      </Stack>
      {error && <ProblemAlert error={error} />}
      {isFetching && <LinearProgress />}
      {data && (
        <>
          <Paper variant="outlined" sx={{ p: 2 }}>
            <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
              {data.legend.map((l) => (
                <Chip key={l.code} size="small" color={GRANT_COLOR[l.code]} label={`${l.code} — ${l.meaning}`} />
              ))}
            </Stack>
          </Paper>
          {data.modules.map((module) => (
            <Paper key={module.section} variant="outlined" sx={{ p: 2 }}>
              <Typography variant="subtitle1" sx={{ mb: 1 }}>
                §{module.section} {module.title}
              </Typography>
              <TableContainer>
                <Table size="small">
                  <TableHead>
                    <TableRow>
                      <TableCell>Role</TableCell>
                      <TableCell>View</TableCell>
                      <TableCell>Create</TableCell>
                      <TableCell>Edit</TableCell>
                      <TableCell>Submit</TableCell>
                      <TableCell>Approve</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {module.roles.map((r) => (
                      <TableRow key={r.role} hover>
                        <TableCell>{roleName(r.role)}</TableCell>
                        {([r.view, r.create, r.edit, r.submit, r.approve] as const).map((cell, i) => (
                          <TableCell key={['view', 'create', 'edit', 'submit', 'approve'][i]}>
                            <Tooltip title={cell.footnote ?? ''} disableHoverListener={!cell.footnote}>
                              <Chip
                                size="small"
                                color={GRANT_COLOR[cell.value]}
                                label={cell.footnote ? `${cell.value} ${cell.footnote}` : cell.value}
                              />
                            </Tooltip>
                          </TableCell>
                        ))}
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </TableContainer>
              {module.notes.length > 0 && (
                <Stack spacing={0.5} sx={{ mt: 1.5 }}>
                  {module.notes.map((note) => (
                    <Typography key={note} variant="caption" color="text.secondary">
                      {note}
                    </Typography>
                  ))}
                </Stack>
              )}
            </Paper>
          ))}
        </>
      )}
    </Stack>
  )
}
