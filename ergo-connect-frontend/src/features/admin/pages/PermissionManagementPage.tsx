import LockOutlinedIcon from '@mui/icons-material/LockOutlined'
import {
  Alert,
  Box,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  LinearProgress,
  MenuItem,
  Paper,
  Select,
  Stack,
  Tab,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Tabs,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material'
import { useMemo, useState } from 'react'
import type { CompanyType, Role } from '@/features/auth/store/authSlice'
import { problemMessage } from '../../../shared/problem'
import {
  useEditablePermissionsQuery,
  usePermissionHistoryQuery,
  useResetPermissionsMutation,
  useSavePermissionsMutation,
  useSwitchModuleMutation,
  type AccessCode,
  type EditablePermissions,
  type PermissionChange,
  type PermissionColumn,
} from '../services/adminApi'
import { PermissionMatrixPage } from './PermissionMatrixPage'
import { formatDateTime } from '@/utils/date'

const SIDES: { side: CompanyType; label: string }[] = [
  { side: 'ERGO', label: 'ERGO' },
  { side: 'CUSTOMER', label: 'Customer' },
  { side: 'SUPPLIER', label: 'Supplier' },
]

const LEVEL_LABEL: Record<AccessCode, string> = { NV: 'No access', V: 'View', A: 'Act', O: 'Owner' }
const LEVEL_MEANING: Record<AccessCode, string> = {
  NV: 'No access: the page, its API and its notifications are closed.',
  V: 'Read-only access.',
  A: 'Create, edit, update and submit, but no final approval.',
  O: 'Full authorised access, including final approval where the workflow has one.',
}
const LEVEL_COLOR: Record<AccessCode, 'default' | 'info' | 'success' | 'warning'> = {
  NV: 'default',
  V: 'info',
  A: 'success',
  O: 'warning',
}

// The module switch uses the same four levels, read as a platform-wide ceiling over every role.
const SWITCH_LABEL: Record<AccessCode, string> = {
  O: 'On',
  A: 'No final approvals',
  V: 'Read-only',
  NV: 'Hidden',
}
const SWITCH_MEANING: Record<AccessCode, string> = {
  O: 'The module works as the role sheet says.',
  A: 'Nobody can give this module’s final approval. Roles that need approval rights for their own steps (e.g. ERGO creating OFP orders) lose those too.',
  V: 'Read-only everywhere: nobody and nothing can change this module’s records - not its users, not other modules (a quote cannot move its RFQ, a handover cannot mark its NPD project), not comments, document lock/approval/replace, status changes or scheduled jobs. Records stay readable. Reminders and expiry pause and catch up when it is switched back on.',
  NV: 'Hidden everywhere: closed for every role and every internal path (menu, pages, API, dashboards, queues, comments, documents, notifications, other modules, scheduled jobs). Data is kept. Quality hidden also stops order dispatch and NPD Final Sample Readiness (their gate cannot be checked).',
}

const keyOf = (role: Role, module: string) => `${role}|${module}`

/**
 * Permission Management (ERGO Admin / Super Admin only). The grid edits what the backend enforces: a saved change
 * applies on the very next request of every affected user, with no redeploy. The server re-checks every edit; this
 * screen only offers what the server will accept.
 */
export function PermissionManagementPage() {
  const [tab, setTab] = useState<CompanyType | 'SRS'>('ERGO')
  return (
    <Stack spacing={2}>
      <Stack spacing={0.5}>
        <Typography variant="h5" component="h1">
          Permission Management
        </Typography>
        <Typography variant="body2" color="text.secondary">
          Choose, for every role and module, what the role may do. Changes are saved on the server and apply to
          signed-in users on their next request. Which records a role can see (own company, assigned accounts) is not
          changed by any level.
        </Typography>
      </Stack>
      <Paper variant="outlined" sx={{ p: 2 }}>
        <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
          {(Object.keys(LEVEL_LABEL) as AccessCode[]).map((code) => (
            <Tooltip key={code} title={LEVEL_MEANING[code]}>
              <Chip size="small" color={LEVEL_COLOR[code]} label={`${code} — ${LEVEL_LABEL[code]}`} />
            </Tooltip>
          ))}
        </Stack>
      </Paper>
      <Tabs value={tab} onChange={(_event, value) => setTab(value)} variant="scrollable">
        {SIDES.map((s) => (
          <Tab key={s.side} value={s.side} label={s.label} />
        ))}
        <Tab value="SRS" label="SRS reference" />
      </Tabs>
      {tab === 'SRS' ? <PermissionMatrixPage /> : <SideGrid key={tab} side={tab} />}
    </Stack>
  )
}

function SideGrid({ side }: { side: CompanyType }) {
  const { data, isFetching, error } = useEditablePermissionsQuery(side)
  if (error) return <Alert severity="error">{problemMessage(error)}</Alert>
  if (!data) return <LinearProgress />
  // Re-key on the server version so the draft starts clean after every save, reset or reload.
  return <Grid key={data.version} side={side} data={data} refreshing={isFetching} />
}

function Grid({ side, data, refreshing }: { side: CompanyType; data: EditablePermissions; refreshing: boolean }) {
  const [draft, setDraft] = useState<Record<string, AccessCode>>({})
  const [confirming, setConfirming] = useState(false)
  const [resetting, setResetting] = useState(false)
  const [historyOpen, setHistoryOpen] = useState(false)
  const [switching, setSwitching] = useState<{ module: PermissionColumn; level: AccessCode } | null>(null)

  const changes = useMemo<(PermissionChange & { from: AccessCode; roleName: string })[]>(
    () =>
      data.roles.flatMap((row) =>
        data.modules.flatMap((m) => {
          const cell = row.cells[m.code]
          const next = draft[keyOf(row.code, m.code)]
          return cell && next && next !== cell.level
            ? [{ role: row.code, module: m.code, level: next, from: cell.level, roleName: row.name }]
            : []
        }),
      ),
    [data, draft],
  )
  const configurable = data.modules.filter((m) => m.configurable)
  const fixed = data.modules.filter((m) => !m.configurable)

  const set = (role: Role, module: string, level: AccessCode) =>
    setDraft((d) => {
      const current = data.roles.find((r) => r.code === role)?.cells[module]?.level
      const next = { ...d, [keyOf(role, module)]: level }
      if (level === current) delete next[keyOf(role, module)]
      return next
    })

  return (
    <Stack spacing={2}>
      {refreshing && <LinearProgress />}
      <Paper variant="outlined" sx={{ p: 2 }}>
        <Typography variant="subtitle2" sx={{ mb: 0.5 }}>
          Module switches (every role, every company)
        </Typography>
        <Typography variant="caption" color="text.secondary" component="p" sx={{ mb: 1.5 }}>
          A switch caps what every role can do in a module, whatever the grid below says: On = as the grid, No final
          approvals, Read-only, or Hidden. Switching back to On restores exactly the grid.
        </Typography>
        <Stack direction="row" spacing={2} useFlexGap sx={{ flexWrap: 'wrap' }}>
          {configurable.map((m) => (
            <Tooltip key={m.code} title={SWITCH_MEANING[m.cap]}>
              <TextField
                select
                size="small"
                label={m.label}
                value={m.cap}
                onChange={(event) => setSwitching({ module: m, level: event.target.value as AccessCode })}
                sx={{ minWidth: 190 }}
                color={m.cap === 'O' ? 'primary' : 'warning'}
                focused={m.cap !== 'O'}
              >
                {data.levels.map((level) => (
                  <MenuItem key={level} value={level}>
                    {level} — {SWITCH_LABEL[level]}
                  </MenuItem>
                ))}
              </TextField>
            </Tooltip>
          ))}
        </Stack>
      </Paper>
      <TableContainer component={Paper} variant="outlined">
        <Table size="small" stickyHeader>
          <TableHead>
            <TableRow>
              <TableCell rowSpan={2} sx={{ minWidth: 200 }}>
                Role
              </TableCell>
              <TableCell colSpan={configurable.length} align="center">
                Configurable modules
              </TableCell>
              {fixed.length > 0 && (
                <TableCell colSpan={fixed.length} align="center">
                  Fixed by design
                </TableCell>
              )}
            </TableRow>
            <TableRow>
              {[...configurable, ...fixed].map((m) => (
                <TableCell key={m.code} align="center" sx={{ minWidth: m.configurable ? 130 : 90 }}>
                  {m.label}
                </TableCell>
              ))}
            </TableRow>
          </TableHead>
          <TableBody>
            {data.roles.map((row) => (
              <TableRow key={row.code} hover>
                <TableCell>
                  <Stack spacing={0.25}>
                    <Typography variant="body2" sx={{ fontWeight: 600 }}>
                      {row.name}
                    </Typography>
                    {!row.active && (
                      <Typography variant="caption" color="text.secondary">
                        Inactive role: no user can hold it yet
                      </Typography>
                    )}
                  </Stack>
                </TableCell>
                {[...configurable, ...fixed].map((m) => {
                  const cell = row.cells[m.code]
                  const value = draft[keyOf(row.code, m.code)] ?? cell.level
                  if (!cell.editable) {
                    return (
                      <TableCell key={m.code} align="center">
                        <Tooltip title="Fixed by design: this module's rules are not configurable here yet.">
                          <Chip size="small" variant="outlined" icon={<LockOutlinedIcon />} label={cell.level} />
                        </Tooltip>
                      </TableCell>
                    )
                  }
                  const changed = value !== cell.level
                  return (
                    <TableCell key={m.code} align="center" sx={changed ? { bgcolor: 'warning.light' } : undefined}>
                      <Select
                        size="small"
                        value={value}
                        onChange={(event) => set(row.code, m.code, event.target.value as AccessCode)}
                        inputProps={{ 'aria-label': `${row.name} on ${m.label}` }}
                        sx={{ minWidth: 100 }}
                        renderValue={(v) => (
                          <Chip
                            size="small"
                            color={LEVEL_COLOR[v]}
                            label={v}
                            variant={v === cell.defaultLevel ? 'filled' : 'outlined'}
                          />
                        )}
                      >
                        {cell.allowed.map((level) => (
                          <MenuItem key={level} value={level}>
                            {level} — {LEVEL_LABEL[level]}
                            {level === cell.defaultLevel ? ' (default)' : ''}
                          </MenuItem>
                        ))}
                      </Select>
                      {cell.effective !== cell.level && (
                        <Tooltip title={`The ${m.label} module switch (${SWITCH_LABEL[m.cap]}) lowers this role to ${cell.effective}.`}>
                          <Typography variant="caption" color="warning.main" component="div">
                            capped → {cell.effective}
                          </Typography>
                        </Tooltip>
                      )}
                    </TableCell>
                  )
                })}
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>
      {side === 'ERGO' && (
        <Typography variant="caption" color="text.secondary">
          ERGO Admin manages accounts, companies and these permissions, so it stays view-only on the business modules.
          Every module keeps at least one ERGO role at Owner. An outlined level differs from the signed-off default.
        </Typography>
      )}
      <Paper
        variant="outlined"
        sx={{ p: 1.5, position: 'sticky', bottom: 8, display: 'flex', gap: 1, alignItems: 'center', flexWrap: 'wrap' }}
      >
        <Typography variant="body2" sx={{ flexGrow: 1 }}>
          {changes.length === 0 ? 'No unsaved changes' : `${changes.length} unsaved change${changes.length > 1 ? 's' : ''}`}
        </Typography>
        <Button onClick={() => setHistoryOpen(true)}>History</Button>
        <Button color="warning" onClick={() => setResetting(true)}>
          Reset {side.toLowerCase()} to defaults
        </Button>
        <Button disabled={changes.length === 0} onClick={() => setDraft({})}>
          Discard
        </Button>
        <Button variant="contained" disabled={changes.length === 0} onClick={() => setConfirming(true)}>
          Save changes
        </Button>
      </Paper>
      {confirming && (
        <SaveDialog side={side} version={data.version} changes={changes} onClose={() => setConfirming(false)} />
      )}
      {resetting && <ResetDialog side={side} version={data.version} onClose={() => setResetting(false)} />}
      {historyOpen && <HistoryDialog onClose={() => setHistoryOpen(false)} />}
      {switching && (
        <SwitchDialog
          side={side}
          version={data.version}
          module={switching.module}
          level={switching.level}
          onClose={() => setSwitching(null)}
        />
      )}
    </Stack>
  )
}

function SaveDialog({
  side,
  version,
  changes,
  onClose,
}: {
  side: CompanyType
  version: number
  changes: (PermissionChange & { from: AccessCode; roleName: string })[]
  onClose: () => void
}) {
  const [reason, setReason] = useState('')
  const [save, { isLoading, error }] = useSavePermissionsMutation()
  const submit = async () => {
    try {
      await save({
        side,
        expectedVersion: version,
        reason: reason.trim(),
        changes: changes.map(({ role, module, level }) => ({ role, module, level })),
      }).unwrap()
      onClose()
    } catch {
      // shown below
    }
  }
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Save {changes.length} permission change{changes.length > 1 ? 's' : ''}?</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          <Box component="ul" sx={{ m: 0, pl: 2 }}>
            {changes.map((c) => (
              <li key={keyOf(c.role, c.module)}>
                <Typography variant="body2">
                  {c.roleName} · {c.module}: {c.from} → <strong>{c.level}</strong>
                </Typography>
              </li>
            ))}
          </Box>
          <Alert severity="info">
            These apply to every signed-in user of the affected roles on their next request, and are recorded in the
            audit trail with the reason below.
          </Alert>
          <TextField
            label="Reason"
            required
            multiline
            minRows={2}
            value={reason}
            onChange={(event) => setReason(event.target.value)}
            slotProps={{ htmlInput: { maxLength: 500 } }}
          />
          {error && <Alert severity="error">{problemMessage(error)}</Alert>}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={reason.trim().length < 3 || isLoading} onClick={submit}>
          Save
        </Button>
      </DialogActions>
    </Dialog>
  )
}

function SwitchDialog({
  side,
  version,
  module,
  level,
  onClose,
}: {
  side: CompanyType
  version: number
  module: PermissionColumn
  level: AccessCode
  onClose: () => void
}) {
  const [reason, setReason] = useState('')
  const [switchModule, { isLoading, error }] = useSwitchModuleMutation()
  const submit = async () => {
    try {
      await switchModule({ side, module: module.code, expectedVersion: version, reason: reason.trim(), level }).unwrap()
      onClose()
    } catch {
      // shown below
    }
  }
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>
        Switch {module.label}: {SWITCH_LABEL[module.cap]} → {SWITCH_LABEL[level]}?
      </DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          <Alert severity={level === 'O' ? 'info' : 'warning'}>
            {SWITCH_MEANING[level]} This applies to every role of every company on their next request. Open records are
            kept as they are and stay waiting.
          </Alert>
          <TextField
            label="Reason"
            required
            multiline
            minRows={2}
            value={reason}
            onChange={(event) => setReason(event.target.value)}
            slotProps={{ htmlInput: { maxLength: 500 } }}
          />
          {error && <Alert severity="error">{problemMessage(error)}</Alert>}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={reason.trim().length < 3 || isLoading} onClick={submit}>
          Apply switch
        </Button>
      </DialogActions>
    </Dialog>
  )
}

function ResetDialog({ side, version, onClose }: { side: CompanyType; version: number; onClose: () => void }) {
  const [reason, setReason] = useState('')
  const [reset, { isLoading, error }] = useResetPermissionsMutation()
  const submit = async () => {
    try {
      await reset({ side, expectedVersion: version, reason: reason.trim() }).unwrap()
      onClose()
    } catch {
      // shown below
    }
  }
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Reset {side.toLowerCase()} roles to the signed-off defaults?</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          <Alert severity="warning">
            Every configurable module of every {side.toLowerCase()} role goes back to the role access sheet the client
            signed off. Unsaved edits on this tab are discarded.
          </Alert>
          <TextField
            label="Reason"
            required
            multiline
            minRows={2}
            value={reason}
            onChange={(event) => setReason(event.target.value)}
            slotProps={{ htmlInput: { maxLength: 500 } }}
          />
          {error && <Alert severity="error">{problemMessage(error)}</Alert>}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button color="warning" variant="contained" disabled={reason.trim().length < 3 || isLoading} onClick={submit}>
          Reset
        </Button>
      </DialogActions>
    </Dialog>
  )
}

function HistoryDialog({ onClose }: { onClose: () => void }) {
  const { data, isFetching, error } = usePermissionHistoryQuery()
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="md">
      <DialogTitle>Permission change history</DialogTitle>
      <DialogContent>
        {isFetching && <LinearProgress />}
        {error && <Alert severity="error">{problemMessage(error)}</Alert>}
        {data && data.length === 0 && <Typography variant="body2">No permission has been changed yet.</Typography>}
        {data && data.length > 0 && (
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>When</TableCell>
                <TableCell>By</TableCell>
                <TableCell>Role</TableCell>
                <TableCell>Module</TableCell>
                <TableCell>Change</TableCell>
                <TableCell>Reason</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {data.map((e, i) => (
                <TableRow key={i}>
                  <TableCell>{formatDateTime(e.at)}</TableCell>
                  <TableCell>{e.changedBy ?? '—'}</TableCell>
                  <TableCell>{e.role ?? 'Module switch'}</TableCell>
                  <TableCell>{e.module}</TableCell>
                  <TableCell>
                    {e.oldLevel} → <strong>{e.newLevel}</strong>
                  </TableCell>
                  <TableCell>{e.reason ?? '—'}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Close</Button>
      </DialogActions>
    </Dialog>
  )
}
