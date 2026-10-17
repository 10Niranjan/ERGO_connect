import {
  Box,
  Button,
  Card,
  CardContent,
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
import { useAppSelector } from '../../../app/store'
import { OFP_EDITORS } from '@/features/auth/utils/access'
import { HandoverDialog } from '../../ofp/components/HandoverDialog'
import { Link as RouterLink, useParams } from 'react-router'
import { CommentsPanel } from '../../communication/components/CommentsPanel'
import { useUsersQuery, type User } from '@/features/admin/services/adminApi'
import { AuditTrail } from '@/components/ui/AuditTrail'
import { CommentDialog } from '@/components/ui/CommentDialog'
import { Facts } from '@/components/ui/Facts'
import { num, str, text } from '@/utils/form'
import { problemMessage } from '@/utils/problem'
import type { CompanyType } from '@/features/auth/store/authSlice'
import type { Named } from '@/features/rfq/services/rfqApi'
import { formatDate } from '@/features/rfq/utils/labels'
import {
  ACTIVITY_STATUS,
  APPROVAL_STATUS,
  APPROVAL_TYPE,
  ISSUE_RISK_STATUS,
  ISSUE_RISK_TYPE,
  NPD_STAGE,
  NPD_STAGE_ORDER,
  PROMPT_RESPONSE_TYPE,
  RISK_LEVEL,
  stageProgress,
} from '@/features/npd/utils/labels'
import {
  useAddNpdActivityMutation,
  useAddPromptChecklistMutation,
  useBlockNpdActivityMutation,
  useCloseNpdStageMutation,
  useDecideApprovalMutation,
  useNpdHistoryQuery,
  useNpdProjectQuery,
  useRaiseIssueMutation,
  useRequestApprovalMutation,
  useRespondToPromptMutation,
  useUnblockNpdActivityMutation,
  useUpdateIssueStatusMutation,
  useUpdateNpdActivityMutation,
  useUpdateNpdProjectMutation,
  useUpdateOwnNpdActivityMutation,
  useUpdatePromptChecklistMutation,
  type ActivityForm,
  type ActivityProgressForm,
  type ActivityStatus,
  type ActivityView,
  type ApprovalForm,
  type ApprovalType,
  type ApprovalView,
  type CustomerNpdView,
  type ErgoNpdView,
  type ExternalStageHistoryView,
  type IssueRiskForm,
  type IssueRiskStatus,
  type IssueRiskType,
  type IssueRiskView,
  type NpdView,
  type PromptChecklistForm,
  type PromptChecklistView,
  type PromptResponseType,
  type RiskLevel,
  type StageHistoryView,
} from '@/features/npd/services/npdApi'
import { formatDateTime } from '@/utils/date'
import { ProblemAlert } from '@/components/ui/ProblemAlert'
import { roleTitle } from '@/features/auth/utils/demoAccounts'

export function NpdDetailPage() {
  const id = Number(useParams().id)
  const { data: npd, isLoading, error } = useNpdProjectQuery(id)
  if (isLoading) return <LinearProgress />
  if (!npd) return <ProblemAlert error={error} />
  return <NpdDetail npd={npd} />
}

function NpdDetail({ npd }: { npd: NpdView }) {
  const delayDays = npd.delayDays
  const [tab, setTab] = useState('overview')
  const [closing, setClosing] = useState(false)
  const [handingOver, setHandingOver] = useState(false)
  // Server enforces stage + role + once-only; this only avoids showing the button where it can never work.
  const canHandover = useAppSelector((state) => OFP_EDITORS.includes(state.auth.session?.activeRole ?? 'CUSTOMER_RFQ')) && npd.audience === 'ERGO' && npd.stage === 'APPROVED_FOR_PRODUCTION_HANDOVER'
  const [editingProject, setEditingProject] = useState(false)
  const [closeStage, closeState] = useCloseNpdStageMutation()
  // Only ERGO Admin/NPD ever hold UPDATE_PROJECT; skipped otherwise so the users list is never fetched for nothing.
  const isEditor = npd.actions.includes('UPDATE_PROJECT')
  const { data: ergoUsers = [], isLoading: usersLoading } = useUsersQuery(undefined, { skip: !isEditor })
  const stage = NPD_STAGE[npd.stage]
  const progress = stageProgress(npd.stage)
  const ergo = npd.audience === 'ERGO' ? npd : null
  const hasApprovals = npd.audience === 'ERGO' || npd.audience === 'CUSTOMER'

  async function doCloseStage(comment: string) {
    try {
      await closeStage({ id: npd.id, comment: comment || undefined }).unwrap()
      setClosing(false)
    } catch {
      // shown in the dialog
    }
  }

  const projectFacts: [string, string][] = [
    ['NPD number', npd.npdNumber],
    ['RFQ', npd.rfqNumber],
  ]
  if (npd.audience !== 'SUPPLIER') projectFacts.push(['Customer', npd.customer.name])
  if (npd.audience === 'ERGO') projectFacts.push(['Supplier', npd.supplier.name])
  projectFacts.push(['Part number', npd.partNumber], ['Revision', npd.revision ?? '—'])
  if ('quoteReference' in npd) projectFacts.push(['Quote reference', npd.quoteReference ?? '—'])
  projectFacts.push(['Stage', stage.label])

  const scheduleFacts: [string, string][] = []
  // Risk level is ERGO's own internal project rating (CustomerNpdView/SupplierNpdView carry no such field).
  if (npd.audience === 'ERGO') {
    scheduleFacts.push(['Owner', npd.owner ?? 'Not assigned'], ['Risk level', npd.riskLevel ? RISK_LEVEL[npd.riskLevel].label : '—'])
  }
  scheduleFacts.push(
    ['Target sample date', formatDate(npd.targetSampleDate)],
    ['Actual sample submission date', formatDate(npd.actualSampleSubmissionDate)],
  )

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
            </Link>
            {npd.audience === 'ERGO' && ` · ${npd.customer.name} → ${npd.supplier.name}`}
            {npd.audience === 'CUSTOMER' && ` · ${npd.customer.name}`}
            {` · ${npd.partNumber}`}
            {npd.revision ? ` rev ${npd.revision}` : ''}
          </Typography>
          <Typography variant="body2" color="text.secondary">
            Target sample {formatDate(npd.targetSampleDate)}
            {delayDays != null && delayDays > 0 && ` · ${delayDays}d delay`}
            {npd.audience === 'ERGO' && ` · Owner ${npd.owner ?? 'not assigned'}`}
          </Typography>
        </Stack>
        <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap', alignItems: 'flex-start' }}>
          {ergo && ergo.actions.includes('UPDATE_PROJECT') && <Button onClick={() => setEditingProject(true)}>Edit project</Button>}
          {canHandover && <Button variant="contained" onClick={() => setHandingOver(true)}>Handover to OFP</Button>}
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
        <Tab value="checklist" label="Prompt Checklist" />
        <Tab value="issues" label="Issues & Risks" />
        {hasApprovals && <Tab value="approvals" label="Approvals" />}
        <Tab value="history" label="Stage History" />
        <Tab value="comments" label="Comments" />
        {npd.audience === 'ERGO' && <Tab value="audit" label="Audit trail" />}
      </Tabs>

      {tab === 'overview' && (
        <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', md: '1fr 1fr' } }}>
          <Facts title="Project" facts={projectFacts} />
          <Facts title="Schedule and ownership" facts={scheduleFacts} />
          <Facts title="Description" facts={[['', npd.description ?? '—']]} />
        </Box>
      )}

      {tab === 'activities' && <ActivitiesTab npd={npd} ergoUsers={ergoUsers} usersLoading={usersLoading} />}
      {tab === 'checklist' && <PromptChecklistTab npd={npd} />}
      {tab === 'issues' && <IssuesTab npd={npd} />}
      {tab === 'approvals' && (npd.audience === 'ERGO' || npd.audience === 'CUSTOMER') && <ApprovalsTab npd={npd} />}
      {tab === 'history' && <StageHistoryTab npd={npd} />}
      {tab === 'comments' && <CommentsPanel linkedModule="npd" recordId={npd.id} />}
      {tab === 'audit' && npd.audience === 'ERGO' && <NpdHistoryTab npdId={npd.id} />}

      {editingProject && ergo && (
        <ProjectEditDialog npd={ergo} ergoUsers={ergoUsers} usersLoading={usersLoading} onClose={() => setEditingProject(false)} />
      )}

      {handingOver && <HandoverDialog npdProjectId={npd.id} onClose={() => setHandingOver(false)} />}
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
 * ERGO only: only ErgoNpdView carries `ownerUserId`.
 */
function ProjectEditDialog({ npd, ergoUsers, usersLoading, onClose }: { npd: ErgoNpdView; ergoUsers: User[]; usersLoading: boolean; onClose: () => void }) {
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

function ProjectEditForm({ npd, ergoUsers, onClose }: { npd: ErgoNpdView; ergoUsers: User[]; onClose: () => void }) {
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
          {/* NPD project owner is an ERGO-internal responsibility (backend rejects any other company). */}
          {ergoUsers
            .filter((u) => u.companyType === 'ERGO')
            .map((u) => (
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
        {saving.error != null && <ProblemAlert error={saving.error} />}
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

/** SRS §12.8.4 activity columns, one table sorted by phase then planned date. A customer/supplier's own view already
 * arrives filtered to their own company's activities only (NpdService.view), so no client-side filtering is needed. */
function ActivitiesTab({ npd, ergoUsers, usersLoading }: { npd: NpdView; ergoUsers: User[]; usersLoading: boolean }) {
  const ergo = npd.audience === 'ERGO' ? npd : null
  const [dialog, setDialog] = useState<'new' | ActivityView | null>(null)
  const [progressDialog, setProgressDialog] = useState<ActivityView | null>(null)
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
        {ergo && npd.actions.includes('ADD_ACTIVITY') && (
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
              <TableCell>Owner</TableCell>
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
                  {a.remarks && (
                    <Typography variant="caption" color="text.secondary" sx={{ display: 'block', fontWeight: 400, fontStyle: 'italic' }}>
                      Remarks: {a.remarks}
                    </Typography>
                  )}
                </TableCell>
                <TableCell>{a.ownerCompanyName ?? a.ownerOrganizationType}</TableCell>
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
                    {a.approvalRequiredFlag && (
                      <Chip
                        size="small"
                        variant="outlined"
                        color="info"
                        label={`Approval required${a.requiredApprovalType ? `: ${APPROVAL_TYPE[a.requiredApprovalType].label}` : ''}`}
                      />
                    )}
                  </Stack>
                </TableCell>
                <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                  <Stack direction="row" spacing={0.5} sx={{ justifyContent: 'flex-end' }}>
                    {a.actions.includes('EDIT_ACTIVITY') && (
                      <Button size="small" onClick={() => setDialog(a)}>
                        Edit
                      </Button>
                    )}
                    {!a.actions.includes('EDIT_ACTIVITY') && a.actions.includes('UPDATE_OWN_ACTIVITY') && (
                      <Button size="small" onClick={() => setProgressDialog(a)}>
                        Update progress
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
                <TableCell colSpan={11} sx={{ color: 'text.secondary' }}>
                  No activities yet.
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </TableContainer>

      {dialog && ergo && (
        <ActivityDialog
          npdId={npd.id}
          activity={dialog === 'new' ? null : dialog}
          activities={npd.activities}
          ergoUsers={ergoUsers}
          usersLoading={usersLoading}
          customer={ergo.customer}
          supplier={ergo.supplier}
          onClose={() => setDialog(null)}
        />
      )}
      {progressDialog && <ActivityProgressDialog activity={progressDialog} onClose={() => setProgressDialog(null)} />}
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

/** Same "wait for the users list before mounting the form" guard as ProjectEditDialog, and for the same reason.
 * ERGO only (EDIT_ACTIVITY/ADD_ACTIVITY are never granted to a customer/supplier): `customer`/`supplier` name the
 * project's own two companies, for the "assign to" selector — auto-wiring the company id, never a free picker. */
function ActivityDialog(props: {
  npdId: number
  activity: ActivityView | null
  activities: ActivityView[]
  ergoUsers: User[]
  usersLoading: boolean
  customer: Named
  supplier: Named
  onClose: () => void
}) {
  const { activity, usersLoading, onClose } = props
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{activity ? `Edit ${activity.activityName}` : 'Add activity'}</DialogTitle>
      {usersLoading ? (
        <DialogContent>
          <LinearProgress />
        </DialogContent>
      ) : (
        <ActivityDialogForm {...props} />
      )}
    </Dialog>
  )
}

function ActivityDialogForm({
  npdId,
  activity,
  activities,
  ergoUsers,
  customer,
  supplier,
  onClose,
}: {
  npdId: number
  /** null = create */
  activity: ActivityView | null
  activities: ActivityView[]
  ergoUsers: User[]
  customer: Named
  supplier: Named
  onClose: () => void
}) {
  const isNew = activity === null
  const [addActivity, adding] = useAddNpdActivityMutation()
  const [updateActivity, updating] = useUpdateNpdActivityMutation()
  const [state, setState] = useState({
    activityName: text(activity?.activityName),
    activityDescription: text(activity?.activityDescription),
    ownerOrganizationType: (activity?.ownerOrganizationType ?? 'ERGO') as CompanyType,
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
    // Unlike `mandatory`, the DB default for this one is false — a checkbox that silently defaulted to true here
    // would gate stage-close on an approval nobody asked for. Deliberately false for a new activity too.
    approvalRequired: activity?.approvalRequiredFlag ?? false,
    requiredApprovalType: (activity?.requiredApprovalType ?? '') as ApprovalType | '',
  })
  const error = adding.error ?? updating.error
  const busy = adding.isLoading || updating.isLoading

  async function save() {
    const ownerCompanyId =
      state.ownerOrganizationType === 'CUSTOMER' ? customer.id : state.ownerOrganizationType === 'SUPPLIER' ? supplier.id : null
    const form: ActivityForm = {
      activityName: state.activityName.trim(),
      activityDescription: str(state.activityDescription),
      ownerOrganizationType: state.ownerOrganizationType,
      ownerCompanyId,
      responsibleUserId: num(state.responsibleUserId),
      plannedDate: str(state.plannedDate),
      baselineDate: str(state.baselineDate),
      actualDate: str(state.actualDate),
      dependencyActivityId: num(state.dependencyActivityId),
      status: state.status,
      mandatory: state.mandatory,
      riskFlag: state.riskFlag,
      issueFlag: state.issueFlag,
      approvalRequired: state.approvalRequired,
      // Cleared to null whenever the checkbox is off, regardless of any value left over in state from before it was
      // unchecked — the backend rejects any other pairing of the two fields.
      requiredApprovalType: state.approvalRequired ? state.requiredApprovalType || null : null,
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
          <TextField
            select
            label="Assign to"
            value={state.ownerOrganizationType}
            onChange={(e) =>
              setState((s) => ({
                ...s,
                ownerOrganizationType: e.target.value as CompanyType,
                // The previously picked Responsible person almost certainly belongs to the old owner company.
                responsibleUserId: '',
              }))
            }
          >
            <MenuItem value="ERGO">ERGO</MenuItem>
            <MenuItem value="CUSTOMER">{customer.name}</MenuItem>
            <MenuItem value="SUPPLIER">{supplier.name}</MenuItem>
          </TextField>
          <TextField select label="Responsible" value={state.responsibleUserId} onChange={(e) => setState((s) => ({ ...s, responsibleUserId: e.target.value }))}>
            <MenuItem value="">Unassigned</MenuItem>
            {/* Backend rejects a responsible user whose company isn't this activity's own owner company. */}
            {ergoUsers
              .filter((u) =>
                state.ownerOrganizationType === 'ERGO'
                  ? u.companyType === 'ERGO'
                  : state.ownerOrganizationType === 'CUSTOMER'
                    ? u.companyId === customer.id
                    : u.companyId === supplier.id,
              )
              .map((u) => (
                <MenuItem key={u.id} value={String(u.id)}>
                  {u.firstName} {u.lastName} ({u.companyName})
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
        <Stack direction="row" spacing={2} useFlexGap sx={{ flexWrap: 'wrap' }}>
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
          <FormControlLabel
            control={<Checkbox checked={state.approvalRequired} onChange={(e) => setState((s) => ({ ...s, approvalRequired: e.target.checked }))} />}
            label="Approval required"
          />
        </Stack>
        {state.approvalRequired && (
          <TextField
            select
            label="Required approval type"
            required
            value={state.requiredApprovalType}
            onChange={(e) => setState((s) => ({ ...s, requiredApprovalType: e.target.value as ApprovalType }))}
          >
            {(Object.keys(APPROVAL_TYPE) as ApprovalType[]).map((t) => (
              <MenuItem key={t} value={t}>
                {APPROVAL_TYPE[t].label}
              </MenuItem>
            ))}
          </TextField>
        )}
        {error != null && <ProblemAlert error={error} />}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button
          variant="contained"
          loading={busy}
          disabled={!state.activityName.trim() || (state.approvalRequired && !state.requiredApprovalType)}
          onClick={() => void save()}
        >
          {isNew ? 'Add' : 'Save changes'}
        </Button>
      </DialogActions>
    </>
  )
}

/** The owning company's own narrower update (SRS §17.4): status, actual date, issue/risk flags and remarks only —
 * never the name, owner, mandatory/approval flags or dependency, which stay ERGO's classification via ActivityForm. */
function ActivityProgressDialog({ activity, onClose }: { activity: ActivityView; onClose: () => void }) {
  const [update, updating] = useUpdateOwnNpdActivityMutation()
  const [state, setState] = useState({
    status: (activity.status !== 'BLOCKED' ? activity.status : 'NOT_STARTED') as Exclude<ActivityStatus, 'BLOCKED'>,
    actualDate: text(activity.actualDate),
    riskFlag: activity.riskFlag,
    issueFlag: activity.issueFlag,
    remarks: text(activity.remarks),
  })

  async function save() {
    const form: ActivityProgressForm = {
      status: state.status,
      actualDate: str(state.actualDate),
      issueFlag: state.issueFlag,
      riskFlag: state.riskFlag,
      remarks: str(state.remarks),
    }
    try {
      await update({ activityId: activity.id, form }).unwrap()
      onClose()
    } catch {
      // shown below
    }
  }

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Update progress: {activity.activityName}</DialogTitle>
      <DialogContent sx={{ display: 'grid', gap: 2, pt: '8px !important' }}>
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
          label="Actual date"
          type="date"
          value={state.actualDate}
          onChange={(e) => setState((s) => ({ ...s, actualDate: e.target.value }))}
          slotProps={{ inputLabel: { shrink: true } }}
        />
        <Stack direction="row" spacing={2}>
          <FormControlLabel
            control={<Checkbox checked={state.riskFlag} onChange={(e) => setState((s) => ({ ...s, riskFlag: e.target.checked }))} />}
            label="At risk"
          />
          <FormControlLabel
            control={<Checkbox checked={state.issueFlag} onChange={(e) => setState((s) => ({ ...s, issueFlag: e.target.checked }))} />}
            label="Has issue"
          />
        </Stack>
        <TextField
          label="Remarks"
          multiline
          minRows={2}
          value={state.remarks}
          onChange={(e) => setState((s) => ({ ...s, remarks: e.target.value }))}
          slotProps={{ htmlInput: { maxLength: 2000 } }}
        />
        {updating.error != null && <ProblemAlert error={updating.error} />}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" loading={updating.isLoading} onClick={() => void save()}>
          Save
        </Button>
      </DialogActions>
    </Dialog>
  )
}

/** SRS §12.8.5: left pane = activity list, right pane = the selected activity's own questions/answers (already
 * embedded per-activity by the API — no separate fetch). */
function PromptChecklistTab({ npd }: { npd: NpdView }) {
  const [selectedId, setSelectedId] = useState<number | null>(npd.activities[0]?.id ?? null)
  const selected = npd.activities.find((a) => a.id === selectedId) ?? null
  const canManage = npd.actions.includes('MANAGE_PROMPT_CHECKLIST')
  const [dialog, setDialog] = useState<'new' | PromptChecklistView | null>(null)

  return (
    <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', md: '280px 1fr' }, alignItems: 'start' }}>
      <TableContainer component={Paper} variant="outlined">
        <Table size="small">
          <TableBody>
            {npd.activities.map((a) => (
              <TableRow key={a.id} hover selected={a.id === selectedId} sx={{ cursor: 'pointer' }} onClick={() => setSelectedId(a.id)}>
                <TableCell>{a.activityName}</TableCell>
                <TableCell align="right">{a.promptChecklists.length}</TableCell>
              </TableRow>
            ))}
            {npd.activities.length === 0 && (
              <TableRow>
                <TableCell sx={{ color: 'text.secondary' }}>No activities yet.</TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </TableContainer>
      <Stack spacing={1.5}>
        {selected ? (
          <>
            <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center' }}>
              <Typography variant="h6" component="h2">
                {selected.activityName}
              </Typography>
              {canManage && (
                <Button size="small" variant="contained" onClick={() => setDialog('new')}>
                  Add question
                </Button>
              )}
            </Stack>
            <Stack spacing={1.5}>
              {selected.promptChecklists.map((q) => (
                <PromptQuestionCard key={q.id} question={q} activity={selected} canManage={canManage} onEdit={() => setDialog(q)} />
              ))}
              {selected.promptChecklists.length === 0 && <Typography color="text.secondary">No questions yet for this activity.</Typography>}
            </Stack>
          </>
        ) : (
          <Typography color="text.secondary">Select an activity to see its checklist.</Typography>
        )}
      </Stack>
      {dialog && selected && (
        <PromptChecklistDialog npdId={npd.id} activityId={selected.id} checklist={dialog === 'new' ? null : dialog} onClose={() => setDialog(null)} />
      )}
    </Box>
  )
}

function PromptQuestionCard({
  question,
  activity,
  canManage,
  onEdit,
}: {
  question: PromptChecklistView
  activity: ActivityView
  canManage: boolean
  onEdit: () => void
}) {
  const canRespond = activity.actions.includes('RESPOND_TO_PROMPT')
  const [value, setValue] = useState(question.responseValue ?? (question.responseType === 'YES_NO' ? 'YES' : ''))
  const [remarks, setRemarks] = useState(text(question.responseRemarks))
  const [respond, responding] = useRespondToPromptMutation()

  async function save() {
    try {
      await respond({ checklistId: question.id, form: { responseValue: value.trim(), remarks: str(remarks) } }).unwrap()
    } catch {
      // shown below
    }
  }

  return (
    <Card variant="outlined">
      <CardContent sx={{ display: 'grid', gap: 1 }}>
        <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'flex-start', gap: 1 }}>
          <Typography sx={{ fontWeight: question.mandatoryFlag ? 600 : 400 }}>
            {question.mandatoryFlag ? '* ' : ''}
            {question.questionText}
          </Typography>
          <Stack direction="row" spacing={1} sx={{ alignItems: 'center', flexShrink: 0 }}>
            <Chip size="small" label={PROMPT_RESPONSE_TYPE[question.responseType].label} />
            {canManage && (
              <Button size="small" onClick={onEdit}>
                Edit
              </Button>
            )}
          </Stack>
        </Stack>
        {canRespond ? (
          <Stack direction="row" spacing={2} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
            {question.responseType === 'YES_NO' ? (
              <TextField select size="small" label="Answer" value={value} onChange={(e) => setValue(e.target.value)} sx={{ minWidth: 140 }}>
                <MenuItem value="YES">Yes</MenuItem>
                <MenuItem value="NO">No</MenuItem>
              </TextField>
            ) : (
              <TextField
                size="small"
                label="Answer"
                value={value}
                onChange={(e) => setValue(e.target.value)}
                sx={{ minWidth: 240 }}
                slotProps={{ htmlInput: { maxLength: 4000 } }}
              />
            )}
            <TextField size="small" label="Remarks (optional)" value={remarks} onChange={(e) => setRemarks(e.target.value)} sx={{ minWidth: 240 }} />
            <Button size="small" variant="contained" disabled={!value.trim()} loading={responding.isLoading} onClick={() => void save()}>
              Save answer
            </Button>
          </Stack>
        ) : (
          <Typography variant="body2">
            {question.responseValue ?? 'Not answered yet'}
            {question.responseRemarks ? ` — ${question.responseRemarks}` : ''}
          </Typography>
        )}
        {question.respondedBy && (
          <Typography variant="caption" color="text.secondary">
            Answered by {question.respondedBy}
            {question.respondedAt && ` · ${formatDateTime(question.respondedAt)}`}
          </Typography>
        )}
        {responding.error != null && <ProblemAlert error={responding.error} />}
      </CardContent>
    </Card>
  )
}

/** Author (create/edit) a checklist question. ERGO only (MANAGE_PROMPT_CHECKLIST). */
function PromptChecklistDialog({
  npdId,
  activityId,
  checklist,
  onClose,
}: {
  npdId: number
  activityId: number
  checklist: PromptChecklistView | null
  onClose: () => void
}) {
  const isNew = checklist === null
  const [add, adding] = useAddPromptChecklistMutation()
  const [update, updating] = useUpdatePromptChecklistMutation()
  const [state, setState] = useState({
    questionText: text(checklist?.questionText),
    responseType: (checklist?.responseType ?? 'TEXT') as PromptResponseType,
    mandatory: checklist?.mandatoryFlag ?? true,
    sequenceNo: text(checklist?.sequenceNo ?? 1),
  })
  const error = adding.error ?? updating.error
  const busy = adding.isLoading || updating.isLoading

  async function save() {
    const form: PromptChecklistForm = {
      activityId,
      questionText: state.questionText.trim(),
      responseType: state.responseType,
      mandatory: state.mandatory,
      sequenceNo: num(state.sequenceNo) ?? 1,
    }
    try {
      if (isNew) await add({ id: npdId, form }).unwrap()
      else await update({ checklistId: checklist.id, form }).unwrap()
      onClose()
    } catch {
      // shown below
    }
  }

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{isNew ? 'Add question' : 'Edit question'}</DialogTitle>
      <DialogContent sx={{ display: 'grid', gap: 2, pt: '8px !important' }}>
        <TextField
          label="Question"
          required
          multiline
          minRows={2}
          value={state.questionText}
          onChange={(e) => setState((s) => ({ ...s, questionText: e.target.value }))}
          slotProps={{ htmlInput: { maxLength: 2000 } }}
        />
        <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: 'repeat(2, 1fr)' }}>
          <TextField
            select
            label="Response type"
            value={state.responseType}
            onChange={(e) => setState((s) => ({ ...s, responseType: e.target.value as PromptResponseType }))}
          >
            {(Object.keys(PROMPT_RESPONSE_TYPE) as PromptResponseType[]).map((t) => (
              <MenuItem key={t} value={t}>
                {PROMPT_RESPONSE_TYPE[t].label}
              </MenuItem>
            ))}
          </TextField>
          <TextField
            label="Sequence #"
            type="number"
            value={state.sequenceNo}
            onChange={(e) => setState((s) => ({ ...s, sequenceNo: e.target.value }))}
            slotProps={{ htmlInput: { min: 1 } }}
          />
        </Box>
        <FormControlLabel
          control={<Checkbox checked={state.mandatory} onChange={(e) => setState((s) => ({ ...s, mandatory: e.target.checked }))} />}
          label="Mandatory"
        />
        {error != null && <ProblemAlert error={error} />}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" loading={busy} disabled={!state.questionText.trim()} onClick={() => void save()}>
          {isNew ? 'Add' : 'Save changes'}
        </Button>
      </DialogActions>
    </Dialog>
  )
}

/** SRS §13.4.6 issues/risks table. "Raise issue" is project-level and ERGO only; each row's status control is gated
 * by that row's own UPDATE_ISSUE action (ERGO on anything, the owning company on its own only). */
function IssuesTab({ npd }: { npd: NpdView }) {
  const ergo = npd.audience === 'ERGO' ? npd : null
  const [raising, setRaising] = useState(false)

  return (
    <Stack spacing={2}>
      <Stack direction="row" sx={{ justifyContent: 'flex-end' }}>
        {ergo && npd.actions.includes('RAISE_ISSUE') && (
          <Button variant="contained" onClick={() => setRaising(true)}>
            Raise issue
          </Button>
        )}
      </Stack>
      <TableContainer component={Paper} variant="outlined">
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>Type</TableCell>
              <TableCell>Title</TableCell>
              <TableCell>Severity</TableCell>
              <TableCell>Owner</TableCell>
              <TableCell>Target date</TableCell>
              <TableCell>Status</TableCell>
              <TableCell>Closed</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {npd.issuesRisks.map((i) => (
              <TableRow key={i.id}>
                <TableCell>
                  <Chip size="small" label={ISSUE_RISK_TYPE[i.type].label} color={ISSUE_RISK_TYPE[i.type].color} />
                </TableCell>
                <TableCell>
                  {i.title}
                  {i.description && (
                    <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
                      {i.description}
                    </Typography>
                  )}
                </TableCell>
                <TableCell>{i.severity ? RISK_LEVEL[i.severity].label : '—'}</TableCell>
                <TableCell>{i.ownerCompanyName ?? i.ownerOrganizationType}</TableCell>
                <TableCell>{formatDate(i.targetDate)}</TableCell>
                <TableCell>
                  {i.actions.includes('UPDATE_ISSUE') ? (
                    <IssueStatusControl issue={i} />
                  ) : (
                    <Chip size="small" label={ISSUE_RISK_STATUS[i.status].label} color={ISSUE_RISK_STATUS[i.status].color} />
                  )}
                </TableCell>
                <TableCell>{formatDate(i.closureDate)}</TableCell>
              </TableRow>
            ))}
            {npd.issuesRisks.length === 0 && (
              <TableRow>
                <TableCell colSpan={7} sx={{ color: 'text.secondary' }}>
                  No issues or risks raised yet.
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </TableContainer>
      {raising && ergo && <RaiseIssueDialog npd={ergo} onClose={() => setRaising(false)} />}
    </Stack>
  )
}

/** Leaving CLOSED (CLOSED → OPEN or CLOSED → IN_PROGRESS) is a reopen and needs a reason, the same rule this app
 * already applies to reject/reopen flows elsewhere; any other change is an optional audit note sent as `null`. */
function IssueStatusControl({ issue }: { issue: IssueRiskView }) {
  const [update, updating] = useUpdateIssueStatusMutation()
  const [reopenTo, setReopenTo] = useState<IssueRiskStatus | null>(null)

  function onChange(next: IssueRiskStatus) {
    if (issue.status === 'CLOSED' && next !== 'CLOSED') {
      setReopenTo(next)
      return
    }
    void update({ issueId: issue.id, form: { status: next, comment: null } })
  }

  async function doReopen(comment: string) {
    if (!reopenTo) return
    try {
      await update({ issueId: issue.id, form: { status: reopenTo, comment } }).unwrap()
      setReopenTo(null)
    } catch {
      // shown in the dialog
    }
  }

  return (
    <Stack spacing={0.5}>
      <TextField
        select
        size="small"
        value={issue.status}
        disabled={updating.isLoading}
        onChange={(e) => onChange(e.target.value as IssueRiskStatus)}
        slotProps={{ htmlInput: { 'aria-label': `Status for ${issue.title}` } }}
      >
        {(['OPEN', 'IN_PROGRESS', 'CLOSED'] as const).map((s) => (
          <MenuItem key={s} value={s}>
            {ISSUE_RISK_STATUS[s].label}
          </MenuItem>
        ))}
      </TextField>
      {updating.error != null && !reopenTo && (
        <Typography variant="caption" color="error">
          {problemMessage(updating.error)}
        </Typography>
      )}
      {reopenTo && (
        <CommentDialog
          title={`Reopen "${issue.title}"`}
          label="Reason"
          required
          confirmLabel="Reopen"
          busy={updating.isLoading}
          error={updating.error}
          onConfirm={(comment) => void doReopen(comment)}
          onClose={() => setReopenTo(null)}
        />
      )}
    </Stack>
  )
}

/** Raise an issue/risk. ERGO only; the owner-organization selector auto-wires the company id from the project's own
 * customer/supplier, the same "assign to" pattern as ActivityDialogForm. */
function RaiseIssueDialog({ npd, onClose }: { npd: ErgoNpdView; onClose: () => void }) {
  const [raise, raising] = useRaiseIssueMutation()
  const [state, setState] = useState({
    type: 'ISSUE' as IssueRiskType,
    title: '',
    description: '',
    severity: '' as RiskLevel | '',
    ownerOrganizationType: 'ERGO' as CompanyType,
    targetDate: '',
    linkedActivityId: '',
  })

  async function save() {
    const ownerCompanyId =
      state.ownerOrganizationType === 'CUSTOMER' ? npd.customer.id : state.ownerOrganizationType === 'SUPPLIER' ? npd.supplier.id : null
    const form: IssueRiskForm = {
      type: state.type,
      title: state.title.trim(),
      description: str(state.description),
      severity: (state.severity || null) as RiskLevel | null,
      ownerOrganizationType: state.ownerOrganizationType,
      ownerCompanyId,
      targetDate: str(state.targetDate),
      linkedActivityId: num(state.linkedActivityId),
    }
    try {
      await raise({ id: npd.id, form }).unwrap()
      onClose()
    } catch {
      // shown below
    }
  }

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Raise an issue or risk</DialogTitle>
      <DialogContent sx={{ display: 'grid', gap: 2, pt: '8px !important' }}>
        <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: 'repeat(2, 1fr)' }}>
          <TextField select label="Type" value={state.type} onChange={(e) => setState((s) => ({ ...s, type: e.target.value as IssueRiskType }))}>
            <MenuItem value="ISSUE">Issue</MenuItem>
            <MenuItem value="RISK">Risk</MenuItem>
          </TextField>
          <TextField
            select
            label="Severity"
            value={state.severity}
            onChange={(e) => setState((s) => ({ ...s, severity: e.target.value as RiskLevel | '' }))}
          >
            <MenuItem value="">Not set</MenuItem>
            {(Object.keys(RISK_LEVEL) as RiskLevel[]).map((r) => (
              <MenuItem key={r} value={r}>
                {RISK_LEVEL[r].label}
              </MenuItem>
            ))}
          </TextField>
        </Box>
        <TextField
          label="Title"
          required
          value={state.title}
          onChange={(e) => setState((s) => ({ ...s, title: e.target.value }))}
          slotProps={{ htmlInput: { maxLength: 200 } }}
        />
        <TextField
          label="Description"
          multiline
          minRows={2}
          value={state.description}
          onChange={(e) => setState((s) => ({ ...s, description: e.target.value }))}
          slotProps={{ htmlInput: { maxLength: 4000 } }}
        />
        <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: 'repeat(2, 1fr)' }}>
          <TextField
            select
            label="Owner"
            value={state.ownerOrganizationType}
            onChange={(e) => setState((s) => ({ ...s, ownerOrganizationType: e.target.value as CompanyType }))}
          >
            <MenuItem value="ERGO">ERGO</MenuItem>
            <MenuItem value="CUSTOMER">{npd.customer.name}</MenuItem>
            <MenuItem value="SUPPLIER">{npd.supplier.name}</MenuItem>
          </TextField>
          <TextField
            label="Target date"
            type="date"
            value={state.targetDate}
            onChange={(e) => setState((s) => ({ ...s, targetDate: e.target.value }))}
            slotProps={{ inputLabel: { shrink: true } }}
          />
        </Box>
        <TextField
          select
          label="Linked activity (optional)"
          value={state.linkedActivityId}
          onChange={(e) => setState((s) => ({ ...s, linkedActivityId: e.target.value }))}
        >
          <MenuItem value="">None</MenuItem>
          {npd.activities.map((a) => (
            <MenuItem key={a.id} value={String(a.id)}>
              {a.activityName}
            </MenuItem>
          ))}
        </TextField>
        {raising.error != null && <ProblemAlert error={raising.error} />}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" loading={raising.isLoading} disabled={!state.title.trim()} onClick={() => void save()}>
          Raise
        </Button>
      </DialogActions>
    </Dialog>
  )
}

/** SRS §13.4.7 approvals table. Only ever rendered for ERGO/Customer (a supplier's view has no `approvals` field at
 * all — SRS §10.6 gives supplier roles zero Approve rights). */
function ApprovalsTab({ npd }: { npd: ErgoNpdView | CustomerNpdView }) {
  const [requesting, setRequesting] = useState(false)
  const [deciding, setDeciding] = useState<{ approval: ApprovalView; approved: boolean } | null>(null)
  const [decide, decideState] = useDecideApprovalMutation()

  async function doDecide(comment: string) {
    if (!deciding) return
    try {
      await decide({ approvalId: deciding.approval.id, form: { approved: deciding.approved, comments: comment || null } }).unwrap()
      setDeciding(null)
    } catch {
      // shown in the dialog
    }
  }

  return (
    <Stack spacing={2}>
      <Stack direction="row" sx={{ justifyContent: 'flex-end' }}>
        {npd.audience === 'ERGO' && npd.actions.includes('REQUEST_APPROVAL') && (
          <Button variant="contained" onClick={() => setRequesting(true)}>
            Request approval
          </Button>
        )}
      </Stack>
      <TableContainer component={Paper} variant="outlined">
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>Type</TableCell>
              <TableCell>Stage</TableCell>
              <TableCell>Requested by</TableCell>
              <TableCell>Requested at</TableCell>
              <TableCell>Approver role</TableCell>
              <TableCell>Status</TableCell>
              <TableCell>Decided by</TableCell>
              <TableCell>Comments</TableCell>
              <TableCell align="right">Actions</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {npd.approvals.map((a) => (
              <TableRow key={a.id}>
                <TableCell>{APPROVAL_TYPE[a.approvalType].label}</TableCell>
                <TableCell>{NPD_STAGE[a.stageName].label}</TableCell>
                <TableCell>{a.requestedBy ?? '—'}</TableCell>
                <TableCell sx={{ whiteSpace: 'nowrap' }}>{formatDateTime(a.requestedAt)}</TableCell>
                <TableCell>{roleTitle(a.approverRole)}</TableCell>
                <TableCell>
                  <Chip size="small" label={APPROVAL_STATUS[a.approvalStatus].label} color={APPROVAL_STATUS[a.approvalStatus].color} />
                </TableCell>
                <TableCell>
                  {a.approvedBy ?? '—'}
                  {a.approvedAt && (
                    <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
                      {formatDateTime(a.approvedAt)}
                    </Typography>
                  )}
                </TableCell>
                <TableCell sx={{ maxWidth: 240, wordBreak: 'break-word' }}>{a.approvalComments ?? '—'}</TableCell>
                <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                  {a.actions.includes('DECIDE_APPROVAL') && (
                    <Stack direction="row" spacing={0.5} sx={{ justifyContent: 'flex-end' }}>
                      <Button size="small" variant="contained" onClick={() => setDeciding({ approval: a, approved: true })}>
                        Approve
                      </Button>
                      <Button size="small" color="error" onClick={() => setDeciding({ approval: a, approved: false })}>
                        Reject
                      </Button>
                    </Stack>
                  )}
                </TableCell>
              </TableRow>
            ))}
            {npd.approvals.length === 0 && (
              <TableRow>
                <TableCell colSpan={9} sx={{ color: 'text.secondary' }}>
                  No approvals requested yet.
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </TableContainer>
      {requesting && npd.audience === 'ERGO' && <RequestApprovalDialog npd={npd} onClose={() => setRequesting(false)} />}
      {deciding && (
        <CommentDialog
          title={`${deciding.approved ? 'Approve' : 'Reject'} ${APPROVAL_TYPE[deciding.approval.approvalType].label}`}
          label={deciding.approved ? 'Comment (optional)' : 'Reason for rejecting'}
          required={!deciding.approved}
          danger={!deciding.approved}
          confirmLabel={deciding.approved ? 'Approve' : 'Reject'}
          busy={decideState.isLoading}
          error={decideState.error}
          onConfirm={(comment) => void doDecide(comment)}
          onClose={() => setDeciding(null)}
        />
      )}
    </Stack>
  )
}

/** Request an approval. ERGO only; `approverRole` is computed server-side from `approvalType`, never sent here. */
function RequestApprovalDialog({ npd, onClose }: { npd: ErgoNpdView; onClose: () => void }) {
  const [request, requesting] = useRequestApprovalMutation()
  const [approvalType, setApprovalType] = useState<ApprovalType>('STAGE_GATE')
  const [linkedActivityId, setLinkedActivityId] = useState('')

  async function save() {
    const form: ApprovalForm = { approvalType, linkedActivityId: num(linkedActivityId) }
    try {
      await request({ id: npd.id, form }).unwrap()
      onClose()
    } catch {
      // shown below
    }
  }

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Request approval</DialogTitle>
      <DialogContent sx={{ display: 'grid', gap: 2, pt: '8px !important' }}>
        <TextField select label="Approval type" value={approvalType} onChange={(e) => setApprovalType(e.target.value as ApprovalType)}>
          {(Object.keys(APPROVAL_TYPE) as ApprovalType[]).map((t) => (
            <MenuItem key={t} value={t}>
              {APPROVAL_TYPE[t].label}
            </MenuItem>
          ))}
        </TextField>
        <TextField select label="Linked activity (optional)" value={linkedActivityId} onChange={(e) => setLinkedActivityId(e.target.value)}>
          <MenuItem value="">None</MenuItem>
          {npd.activities.map((a) => (
            <MenuItem key={a.id} value={String(a.id)}>
              {a.activityName}
            </MenuItem>
          ))}
        </TextField>
        {requesting.error != null && <ProblemAlert error={requesting.error} />}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" loading={requesting.isLoading} onClick={() => void save()}>
          Request
        </Button>
      </DialogActions>
    </Dialog>
  )
}

/** ERGO's stage history carries internal remarks that could name the other party (SRS §13.4.2); the customer/
 * supplier variant never carries that field at all. Narrowing `npd` first (rather than casting) lets TypeScript
 * enforce that the Remarks column can only ever be rendered from the ERGO-only row type. */
function StageHistoryTab({ npd }: { npd: NpdView }) {
  if (npd.audience === 'ERGO') return <StageHistoryTable rows={npd.stageHistory} />
  return <ExternalStageHistoryTable rows={npd.stageHistory} />
}

/** SRS §12.8.3/§15.5 audit trail tab, ERGO only (server-enforced; the tab itself is also hidden for other audiences). */
function NpdHistoryTab({ npdId }: { npdId: number }) {
  const { data = [], error } = useNpdHistoryQuery(npdId)
  if (error) return <ProblemAlert error={error} />
  return <AuditTrail rows={data} />
}

function StageHistoryTable({ rows }: { rows: StageHistoryView[] }) {
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
          {rows.map((h) => (
            <TableRow key={`${h.changedAt}-${h.stage}`}>
              <TableCell sx={{ whiteSpace: 'nowrap' }}>{formatDateTime(h.changedAt)}</TableCell>
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

/** The customer/supplier-safe variant: same columns minus Remarks — `ExternalStageHistoryView` has no `remarks` to
 * render. */
function ExternalStageHistoryTable({ rows }: { rows: ExternalStageHistoryView[] }) {
  return (
    <TableContainer component={Paper} variant="outlined">
      <Table size="small">
        <TableHead>
          <TableRow>
            <TableCell>When</TableCell>
            <TableCell>Who</TableCell>
            <TableCell>From</TableCell>
            <TableCell>To</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {rows.map((h) => (
            <TableRow key={`${h.changedAt}-${h.stage}`}>
              <TableCell sx={{ whiteSpace: 'nowrap' }}>{formatDateTime(h.changedAt)}</TableCell>
              <TableCell>{h.changedBy ?? 'System'}</TableCell>
              <TableCell>{h.previousStage ? NPD_STAGE[h.previousStage].label : '—'}</TableCell>
              <TableCell>{NPD_STAGE[h.stage].label}</TableCell>
            </TableRow>
          ))}
          {rows.length === 0 && (
            <TableRow>
              <TableCell colSpan={4} sx={{ color: 'text.secondary' }}>
                No stage changes yet.
              </TableCell>
            </TableRow>
          )}
        </TableBody>
      </Table>
    </TableContainer>
  )
}
