import {
  Alert,
  Box,
  Button,
  Checkbox,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  Link,
  LinearProgress,
  MenuItem,
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
  TextField,
  Typography,
} from '@mui/material'
import { useState } from 'react'
import { Link as RouterLink, useParams } from 'react-router'
import { useUsersQuery, type User } from '../../admin/adminApi'
import { CommentDialog } from '../../../shared/components/CommentDialog'
import { Facts } from '../../../shared/components/Facts'
import { num, str, text } from '../../../shared/form'
import { problemMessage } from '../../../shared/problem'
import { formatDate } from '../../rfq/labels'
import { ACTIVITY_STATUS, NPD_STAGE, NPD_STAGE_ORDER, RISK_LEVEL, stageProgress } from '../labels'
import {
  useAddNpdActivityMutation,
  useBlockNpdActivityMutation,
  useCloseNpdStageMutation,
  useNpdProjectQuery,
  useUnblockNpdActivityMutation,
  useUpdateNpdActivityMutation,
  useUpdateNpdProjectMutation,
  type ActivityForm,
  type ActivityStatus,
  type ActivityView,
  type NpdDetailView,
  type RiskLevel,
  type StageHistoryView,
} from '../npdApi'

export function NpdDetailPage() {
  const id = Number(useParams().id)
  const { data: npd, isLoading, error } = useNpdProjectQuery(id)
  if (isLoading) return <LinearProgress />
  if (!npd) return <Alert severity="error">{problemMessage(error)}</Alert>
  return <NpdDetail npd={npd} />
}

function NpdDetail({ npd }: { npd: NpdDetailView }) {
  const delayDays = npd.delayDays
  const [tab, setTab] = useState('overview')
  const [closing, setClosing] = useState(false)
  const [editingProject, setEditingProject] = useState(false)
  const [closeStage, closeState] = useCloseNpdStageMutation()
  const isEditor = npd.actions.includes('UPDATE_PROJECT')
  // Shared by the project-edit and activity dialogs; both gate their form on `usersLoading` (see ProjectEditDialog).
  // Skipped for non-editors: their buttons that open those dialogs never render, so the list is never needed.
  const { data: ergoUsers = [], isLoading: usersLoading } = useUsersQuery(undefined, { skip: !isEditor })
  const stage = NPD_STAGE[npd.stage]
  const progress = stageProgress(npd.stage)

  async function doCloseStage(comment: string) {
    try {
      await closeStage({ id: npd.id, comment: comment || undefined }).unwrap()
      setClosing(false)
    } catch {
      // shown in the dialog
    }
  }

  return (
    <Stack spacing={2}>
      <Stack direction={{ xs: 'column', md: 'row' }} sx={{ justifyContent: 'space-between', gap: 2 }}>
        <Stack spacing={0.5}>
          <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
            <Typography variant="h5" component="h1">
              {npd.npdNumber}
            </Typography>
            <Chip label={stage.label} color={stage.color} size="small" />
            <Typography variant="caption" color="text.secondary">
              {progress.label}
            </Typography>
          </Stack>
          <Typography color="text.secondary">
            RFQ{' '}
            <Link component={RouterLink} to={`/rfqs/${npd.rfqId}`}>
              {npd.rfqNumber}
            </Link>{' '}
            · {npd.customer.name} → {npd.supplier.name} · {npd.partNumber}
            {npd.revision ? ` rev ${npd.revision}` : ''}
          </Typography>
          <Typography variant="body2" color="text.secondary">
            Target sample {formatDate(npd.targetSampleDate)}
            {delayDays != null && delayDays > 0 && ` · ${delayDays}d delay`} · Owner {npd.owner ?? 'not assigned'}
          </Typography>
        </Stack>
        <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap', alignItems: 'flex-start' }}>
          {npd.actions.includes('UPDATE_PROJECT') && <Button onClick={() => setEditingProject(true)}>Edit project</Button>}
          {npd.actions.includes('CLOSE_STAGE') && (
            <Button variant="contained" onClick={() => setClosing(true)}>
              Close stage
            </Button>
          )}
        </Stack>
      </Stack>

      <Tabs value={tab} onChange={(_e, value: string) => setTab(value)}>
        <Tab value="overview" label="Overview" />
        <Tab value="activities" label="Activities" />
        <Tab value="history" label="Stage History" />
      </Tabs>

      {tab === 'overview' && (
        <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', md: '1fr 1fr' } }}>
          <Facts
            title="Project"
            facts={[
              ['NPD number', npd.npdNumber],
              ['RFQ', npd.rfqNumber],
              ['Customer', npd.customer.name],
              ['Supplier', npd.supplier.name],
              ['Part number', npd.partNumber],
              ['Revision', npd.revision ?? '—'],
              ['Quote reference', npd.quoteReference ?? '—'],
              ['Stage', stage.label],
            ]}
          />
          <Facts
            title="Schedule and ownership"
            facts={[
              ['Owner', npd.owner ?? 'Not assigned'],
              ['Risk level', npd.riskLevel ? RISK_LEVEL[npd.riskLevel].label : '—'],
              ['Target sample date', formatDate(npd.targetSampleDate)],
              ['Actual sample submission date', formatDate(npd.actualSampleSubmissionDate)],
            ]}
          />
          <Facts title="Description" facts={[['', npd.description ?? '—']]} />
        </Box>
      )}

      {tab === 'activities' && <ActivitiesTab npd={npd} ergoUsers={ergoUsers} usersLoading={usersLoading} />}
      {tab === 'history' && <StageHistoryTab rows={npd.stageHistory} />}

      {editingProject && <ProjectEditDialog npd={npd} ergoUsers={ergoUsers} usersLoading={usersLoading} onClose={() => setEditingProject(false)} />}

      {closing && (
        <CommentDialog
          title="Close current stage"
          label="Comment (optional)"
          required={false}
          confirmLabel="Close stage"
          intro="Moves the project to the next stage. Blocked by incomplete mandatory activities."
          busy={closeState.isLoading}
          error={closeState.error}
          onConfirm={(comment) => void doCloseStage(comment)}
          onClose={() => setClosing(false)}
        />
      )}
    </Stack>
  )
}

/**
 * SRS §17.4 project-header fields: owner, risk level and sample dates. Full replace (SRS §12.14), not a patch.
 * Only mounts its form once `ergoUsers` has loaded — pre-selecting the current owner needs the list first (the view
 * only carries the owner's display name, not their id), the same "wait for master data" rule RfqEditPage follows.
 */
function ProjectEditDialog({ npd, ergoUsers, usersLoading, onClose }: { npd: NpdDetailView; ergoUsers: User[]; usersLoading: boolean; onClose: () => void }) {
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Update project</DialogTitle>
      {usersLoading ? (
        <DialogContent>
          <LinearProgress />
        </DialogContent>
      ) : (
        <ProjectEditForm npd={npd} ergoUsers={ergoUsers} onClose={onClose} />
      )}
    </Dialog>
  )
}

function ProjectEditForm({ npd, ergoUsers, onClose }: { npd: NpdDetailView; ergoUsers: User[]; onClose: () => void }) {
  const [ownerUserId, setOwnerUserId] = useState(() => text(npd.ownerUserId))
  const [riskLevel, setRiskLevel] = useState<RiskLevel | ''>(npd.riskLevel ?? '')
  const [targetSampleDate, setTargetSampleDate] = useState(text(npd.targetSampleDate))
  const [actualSampleSubmissionDate, setActualSampleSubmissionDate] = useState(text(npd.actualSampleSubmissionDate))
  const [save, saving] = useUpdateNpdProjectMutation()

  async function doSave() {
    try {
      await save({
        id: npd.id,
        form: {
          ownerUserId: num(ownerUserId),
          riskLevel: (riskLevel || null) as RiskLevel | null,
          targetSampleDate: str(targetSampleDate),
          actualSampleSubmissionDate: str(actualSampleSubmissionDate),
        },
      }).unwrap()
      onClose()
    } catch {
      // shown below
    }
  }

  return (
    <>
      <DialogContent sx={{ display: 'grid', gap: 2, pt: '8px !important' }}>
        <TextField select label="Owner" value={ownerUserId} onChange={(e) => setOwnerUserId(e.target.value)}>
          <MenuItem value="">Not assigned</MenuItem>
          {ergoUsers.map((u) => (
            <MenuItem key={u.id} value={String(u.id)}>
              {u.firstName} {u.lastName}
            </MenuItem>
          ))}
        </TextField>
        <TextField select label="Risk level" value={riskLevel} onChange={(e) => setRiskLevel(e.target.value as RiskLevel | '')}>
          <MenuItem value="">Not set</MenuItem>
          {(Object.keys(RISK_LEVEL) as RiskLevel[]).map((r) => (
            <MenuItem key={r} value={r}>
              {RISK_LEVEL[r].label}
            </MenuItem>
          ))}
        </TextField>
        <TextField
          label="Target sample date"
          type="date"
          value={targetSampleDate}
          onChange={(e) => setTargetSampleDate(e.target.value)}
          slotProps={{ inputLabel: { shrink: true } }}
        />
        <TextField
          label="Actual sample submission date"
          type="date"
          value={actualSampleSubmissionDate}
          onChange={(e) => setActualSampleSubmissionDate(e.target.value)}
          slotProps={{ inputLabel: { shrink: true } }}
        />
        {saving.error != null && <Alert severity="error">{problemMessage(saving.error)}</Alert>}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" loading={saving.isLoading} onClick={() => void doSave()}>
          Save
        </Button>
      </DialogActions>
    </>
  )
}

/** SRS §12.8.4 activity columns, one table sorted by phase then planned date (no Kanban/Gantt in 3a). */
function ActivitiesTab({ npd, ergoUsers, usersLoading }: { npd: NpdDetailView; ergoUsers: User[]; usersLoading: boolean }) {
  const [dialog, setDialog] = useState<'new' | ActivityView | null>(null)
  const [blocking, setBlocking] = useState<ActivityView | null>(null)
  const [unblocking, setUnblocking] = useState<ActivityView | null>(null)
  const [block, blockState] = useBlockNpdActivityMutation()
  const [unblock, unblockState] = useUnblockNpdActivityMutation()

  const byId = new Map(npd.activities.map((a) => [a.id, a]))
  const sorted = [...npd.activities].sort(
    (a, b) => NPD_STAGE_ORDER.indexOf(a.phaseName) - NPD_STAGE_ORDER.indexOf(b.phaseName) || (a.plannedDate ?? '').localeCompare(b.plannedDate ?? ''),
  )

  async function doBlock(comment: string) {
    if (!blocking) return
    try {
      await block({ activityId: blocking.id, comment }).unwrap()
      setBlocking(null)
    } catch {
      // shown in the dialog
    }
  }

  async function doUnblock(comment: string) {
    if (!unblocking) return
    try {
      await unblock({ activityId: unblocking.id, comment: comment || undefined }).unwrap()
      setUnblocking(null)
    } catch {
      // shown in the dialog
    }
  }

  return (
    <Stack spacing={2}>
      <Stack direction="row" sx={{ justifyContent: 'flex-end' }}>
        {npd.actions.includes('ADD_ACTIVITY') && (
          <Button variant="contained" onClick={() => setDialog('new')}>
            Add activity
          </Button>
        )}
      </Stack>
      <Typography variant="body2" color="text.secondary">
        * marks a mandatory activity: the stage cannot close while one is incomplete.
      </Typography>
      <TableContainer component={Paper} variant="outlined">
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>Phase</TableCell>
              <TableCell>Activity</TableCell>
              <TableCell>Responsible</TableCell>
              <TableCell>Planned</TableCell>
              <TableCell>Baseline</TableCell>
              <TableCell>Actual</TableCell>
              <TableCell>Depends on</TableCell>
              <TableCell>Status</TableCell>
              <TableCell>Flags</TableCell>
              <TableCell align="right">Actions</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {sorted.map((a) => (
              <TableRow key={a.id}>
                <TableCell>{NPD_STAGE[a.phaseName].label}</TableCell>
                <TableCell sx={{ fontWeight: a.mandatoryFlag ? 600 : 400 }}>
                  {a.mandatoryFlag ? '* ' : ''}
                  {a.activityName}
                  {a.activityDescription && (
                    <Typography variant="caption" color="text.secondary" sx={{ display: 'block', fontWeight: 400 }}>
                      {a.activityDescription}
                    </Typography>
                  )}
                </TableCell>
                <TableCell>{a.responsibleUser ?? '—'}</TableCell>
                <TableCell>{formatDate(a.plannedDate)}</TableCell>
                <TableCell>{formatDate(a.baselineDate)}</TableCell>
                <TableCell>{formatDate(a.actualDate)}</TableCell>
                <TableCell>{a.dependencyActivityId != null ? (byId.get(a.dependencyActivityId)?.activityName ?? '—') : '—'}</TableCell>
                <TableCell>
                  <Stack spacing={0.5} sx={{ alignItems: 'flex-start' }}>
                    <Chip size="small" label={ACTIVITY_STATUS[a.status].label} color={ACTIVITY_STATUS[a.status].color} />
                    {a.blockedFlag && <Chip size="small" label="Blocked" color="error" />}
                  </Stack>
                </TableCell>
                <TableCell>
                  <Stack direction="row" spacing={0.5}>
                    {a.riskFlag && <Chip size="small" variant="outlined" color="warning" label="Risk" />}
                    {a.issueFlag && <Chip size="small" variant="outlined" color="error" label="Issue" />}
                  </Stack>
                </TableCell>
                <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                  <Stack direction="row" spacing={0.5} sx={{ justifyContent: 'flex-end' }}>
                    {a.actions.includes('EDIT_ACTIVITY') && (
                      <Button size="small" onClick={() => setDialog(a)}>
                        Edit
                      </Button>
                    )}
                    {a.actions.includes('MARK_BLOCKED') && (
                      <Button size="small" color="error" onClick={() => setBlocking(a)}>
                        Mark blocked
                      </Button>
                    )}
                    {a.actions.includes('UNBLOCK') && (
                      <Button size="small" onClick={() => setUnblocking(a)}>
                        Unblock
                      </Button>
                    )}
                  </Stack>
                </TableCell>
              </TableRow>
            ))}
            {sorted.length === 0 && (
              <TableRow>
                <TableCell colSpan={10} sx={{ color: 'text.secondary' }}>
                  No activities yet.
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </TableContainer>

      {dialog && (
        <ActivityDialog
          npdId={npd.id}
          activity={dialog === 'new' ? null : dialog}
          activities={npd.activities}
          ergoUsers={ergoUsers}
          usersLoading={usersLoading}
          onClose={() => setDialog(null)}
        />
      )}
      {blocking && (
        <CommentDialog
          title={`Mark "${blocking.activityName}" blocked`}
          label="Reason"
          required
          danger
          confirmLabel="Mark blocked"
          busy={blockState.isLoading}
          error={blockState.error}
          onConfirm={(comment) => void doBlock(comment)}
          onClose={() => setBlocking(null)}
        />
      )}
      {unblocking && (
        <CommentDialog
          title={`Unblock "${unblocking.activityName}"`}
          label="Comment (optional)"
          required={false}
          confirmLabel="Unblock"
          busy={unblockState.isLoading}
          error={unblockState.error}
          onConfirm={(comment) => void doUnblock(comment)}
          onClose={() => setUnblocking(null)}
        />
      )}
    </Stack>
  )
}

/** Same "wait for the users list before mounting the form" guard as ProjectEditDialog, and for the same reason. */
function ActivityDialog(props: { npdId: number; activity: ActivityView | null; activities: ActivityView[]; ergoUsers: User[]; usersLoading: boolean; onClose: () => void }) {
  const { activity, usersLoading, onClose } = props
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{activity ? `Edit ${activity.activityName}` : 'Add activity'}</DialogTitle>
      {usersLoading ? (
        <DialogContent>
          <LinearProgress />
        </DialogContent>
      ) : (
        <ActivityForm {...props} />
      )}
    </Dialog>
  )
}

function ActivityForm({
  npdId,
  activity,
  activities,
  ergoUsers,
  onClose,
}: {
  npdId: number
  /** null = create */
  activity: ActivityView | null
  activities: ActivityView[]
  ergoUsers: User[]
  onClose: () => void
}) {
  const isNew = activity === null
  const [addActivity, adding] = useAddNpdActivityMutation()
  const [updateActivity, updating] = useUpdateNpdActivityMutation()
  const [state, setState] = useState({
    activityName: text(activity?.activityName),
    activityDescription: text(activity?.activityDescription),
    responsibleUserId: text(activity?.responsibleUserId),
    plannedDate: text(activity?.plannedDate),
    baselineDate: text(activity?.baselineDate),
    actualDate: text(activity?.actualDate),
    dependencyActivityId: text(activity?.dependencyActivityId),
    // ActivityForm.status may never be BLOCKED; blockedFlag (set only via Mark Blocked/Unblock) tracks that separately.
    status: (activity && activity.status !== 'BLOCKED' ? activity.status : 'NOT_STARTED') as Exclude<ActivityStatus, 'BLOCKED'>,
    // A new activity gates the stage by default (matches the column's own DB default): missing the checkbox should
    // never silently make an activity toothless.
    mandatory: activity?.mandatoryFlag ?? true,
    riskFlag: activity?.riskFlag ?? false,
    issueFlag: activity?.issueFlag ?? false,
  })
  const error = adding.error ?? updating.error
  const busy = adding.isLoading || updating.isLoading

  async function save() {
    const form: ActivityForm = {
      activityName: state.activityName.trim(),
      activityDescription: str(state.activityDescription),
      responsibleUserId: num(state.responsibleUserId),
      plannedDate: str(state.plannedDate),
      baselineDate: str(state.baselineDate),
      actualDate: str(state.actualDate),
      dependencyActivityId: num(state.dependencyActivityId),
      status: state.status,
      mandatory: state.mandatory,
      riskFlag: state.riskFlag,
      issueFlag: state.issueFlag,
    }
    try {
      if (isNew) await addActivity({ id: npdId, form }).unwrap()
      else await updateActivity({ activityId: activity.id, form }).unwrap()
      onClose()
    } catch {
      // shown below
    }
  }

  return (
    <>
      <DialogContent sx={{ display: 'grid', gap: 2, pt: '8px !important' }}>
        <TextField
          label="Activity name"
          required
          value={state.activityName}
          onChange={(e) => setState((s) => ({ ...s, activityName: e.target.value }))}
          slotProps={{ htmlInput: { maxLength: 200 } }}
        />
        <TextField
          label="Description"
          multiline
          minRows={2}
          value={state.activityDescription}
          onChange={(e) => setState((s) => ({ ...s, activityDescription: e.target.value }))}
          slotProps={{ htmlInput: { maxLength: 4000 } }}
        />
        <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: 'repeat(2, 1fr)' }}>
          <TextField select label="Responsible" value={state.responsibleUserId} onChange={(e) => setState((s) => ({ ...s, responsibleUserId: e.target.value }))}>
            <MenuItem value="">Unassigned</MenuItem>
            {ergoUsers.map((u) => (
              <MenuItem key={u.id} value={String(u.id)}>
                {u.firstName} {u.lastName}
              </MenuItem>
            ))}
          </TextField>
          <TextField
            select
            label="Status"
            value={state.status}
            onChange={(e) => setState((s) => ({ ...s, status: e.target.value as Exclude<ActivityStatus, 'BLOCKED'> }))}
          >
            {(['NOT_STARTED', 'IN_PROGRESS', 'DONE'] as const).map((s) => (
              <MenuItem key={s} value={s}>
                {ACTIVITY_STATUS[s].label}
              </MenuItem>
            ))}
          </TextField>
          <TextField
            label="Planned date"
            type="date"
            value={state.plannedDate}
            onChange={(e) => setState((s) => ({ ...s, plannedDate: e.target.value }))}
            slotProps={{ inputLabel: { shrink: true } }}
          />
          <TextField
            label="Baseline date"
            type="date"
            value={state.baselineDate}
            onChange={(e) => setState((s) => ({ ...s, baselineDate: e.target.value }))}
            slotProps={{ inputLabel: { shrink: true } }}
          />
          <TextField
            label="Actual date"
            type="date"
            value={state.actualDate}
            onChange={(e) => setState((s) => ({ ...s, actualDate: e.target.value }))}
            slotProps={{ inputLabel: { shrink: true } }}
          />
          <TextField select label="Depends on" value={state.dependencyActivityId} onChange={(e) => setState((s) => ({ ...s, dependencyActivityId: e.target.value }))}>
            <MenuItem value="">None</MenuItem>
            {activities
              .filter((a) => isNew || a.id !== activity.id)
              .map((a) => (
                <MenuItem key={a.id} value={String(a.id)}>
                  {a.activityName}
                </MenuItem>
              ))}
          </TextField>
        </Box>
        <Stack direction="row" spacing={2}>
          <FormControlLabel
            control={<Checkbox checked={state.mandatory} onChange={(e) => setState((s) => ({ ...s, mandatory: e.target.checked }))} />}
            label="Mandatory"
          />
          <FormControlLabel
            control={<Checkbox checked={state.riskFlag} onChange={(e) => setState((s) => ({ ...s, riskFlag: e.target.checked }))} />}
            label="At risk"
          />
          <FormControlLabel
            control={<Checkbox checked={state.issueFlag} onChange={(e) => setState((s) => ({ ...s, issueFlag: e.target.checked }))} />}
            label="Has issue"
          />
        </Stack>
        {error != null && <Alert severity="error">{problemMessage(error)}</Alert>}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" loading={busy} disabled={!state.activityName.trim()} onClick={() => void save()}>
          {isNew ? 'Add' : 'Save changes'}
        </Button>
      </DialogActions>
    </>
  )
}

function StageHistoryTab({ rows }: { rows: StageHistoryView[] }) {
  return (
    <TableContainer component={Paper} variant="outlined">
      <Table size="small">
        <TableHead>
          <TableRow>
            <TableCell>When</TableCell>
            <TableCell>Who</TableCell>
            <TableCell>From</TableCell>
            <TableCell>To</TableCell>
            <TableCell>Remarks</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {rows.map((h, index) => (
            <TableRow key={index}>
              <TableCell sx={{ whiteSpace: 'nowrap' }}>{new Date(h.changedAt).toLocaleString()}</TableCell>
              <TableCell>{h.changedBy ?? 'System'}</TableCell>
              <TableCell>{h.previousStage ? NPD_STAGE[h.previousStage].label : '—'}</TableCell>
              <TableCell>{NPD_STAGE[h.stage].label}</TableCell>
              <TableCell sx={{ maxWidth: 360, wordBreak: 'break-word' }}>{h.remarks ?? '—'}</TableCell>
            </TableRow>
          ))}
          {rows.length === 0 && (
            <TableRow>
              <TableCell colSpan={5} sx={{ color: 'text.secondary' }}>
                No stage changes yet.
              </TableCell>
            </TableRow>
          )}
        </TableBody>
      </Table>
    </TableContainer>
  )
}
