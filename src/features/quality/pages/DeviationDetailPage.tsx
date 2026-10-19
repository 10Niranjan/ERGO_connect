import { Box, Button, Chip, LinearProgress, Paper, Stack, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Typography } from '@mui/material'
import { useState, type ReactNode } from 'react'
import { useParams } from 'react-router'
import { CommentsSection } from '../../communication/components/CommentsPanel'
import type { Role } from '@/features/auth/store/authSlice'
import { CommentDialog } from '@/components/ui/CommentDialog'
import { Facts } from '@/components/ui/Facts'
import { formatDate } from '@/features/rfq/utils/labels'
import { DeviationUpdateDialog } from '../components/DeviationDialogs'
import {
  useApproveDeviationMutation,
  useCloseDeviationMutation,
  useDeviationQuery,
  useRejectDeviationMutation,
  useSendBackDeviationMutation,
  useStartReviewDeviationMutation,
  type DeviationAction,
  type DeviationDetail,
} from '@/features/quality/services/deviationApi'
import { DEVIATION_DECISION, DEVIATION_STATUS, LINKED_MODULE } from '@/features/quality/utils/labels'
import { formatDateTime } from '@/utils/date'
import { LinkedRecordLink } from '@/features/quality/components/LinkedRecordLink'
import { ProblemAlert } from '@/components/ui/ProblemAlert'

export function DeviationDetailPage() {
  const id = Number(useParams().id)
  const { data: deviation, isLoading, error } = useDeviationQuery(id)
  if (isLoading) return <LinearProgress />
  if (!deviation) return <ProblemAlert error={error} />
  return <DeviationDetailView deviation={deviation} />
}

/** Only ERGO_QUALITY and CUSTOMER_QUALITY rows ever exist on a deviation (DeviationService.createApprovalRow). */
const APPROVAL_ROLE_LABEL: Partial<Record<Role, string>> = { ERGO_QUALITY: 'ERGO Quality', CUSTOMER_QUALITY: 'Customer Quality' }

/** The four status-changing buttons that take an optional/required reason (APPROVE/CLOSE optional, REJECT/SEND_BACK
 * required - CLAUDE.md non-negotiable). EDIT has its own dialog (DeviationUpdateDialog); START_REVIEW takes no body
 * at all (DeviationController.startReview) so is fired directly with no dialog. */
type ReasonAction = 'APPROVE' | 'REJECT' | 'SEND_BACK' | 'CLOSE'

const WORKFLOW: { action: ReasonAction; label: string; required: boolean; danger?: boolean; intro?: string }[] = [
  { action: 'APPROVE', label: 'Approve', required: false },
  { action: 'REJECT', label: 'Reject', required: true, danger: true },
  { action: 'SEND_BACK', label: 'Send back for clarification', required: true, danger: true },
  { action: 'CLOSE', label: 'Close', required: false, intro: 'Closing is final; there is no reopen path.' },
]

type Dialog = { kind: 'edit' } | { kind: 'reason'; action: ReasonAction }

function DeviationDetailView({ deviation }: { deviation: DeviationDetail }) {
  const [dialog, setDialog] = useState<Dialog | null>(null)
  const [approve, approving] = useApproveDeviationMutation()
  const [reject, rejecting] = useRejectDeviationMutation()
  const [sendBack, sendingBack] = useSendBackDeviationMutation()
  const [close, closing] = useCloseDeviationMutation()
  const [startReview, startingReview] = useStartReviewDeviationMutation()

  const runners: Record<ReasonAction, { run: (reason: string) => Promise<unknown>; state: { isLoading: boolean; error?: unknown } }> = {
    APPROVE: { run: (r) => approve({ id: deviation.id, reason: r || undefined }).unwrap(), state: approving },
    REJECT: { run: (r) => reject({ id: deviation.id, reason: r }).unwrap(), state: rejecting },
    SEND_BACK: { run: (r) => sendBack({ id: deviation.id, reason: r }).unwrap(), state: sendingBack },
    CLOSE: { run: (r) => close({ id: deviation.id, reason: r || undefined }).unwrap(), state: closing },
  }

  // Audience is told apart structurally (no discriminant field), same as NcrDetailPage/InspectionDetailPage.
  const ergo = 'customerCompanyId' in deviation ? deviation : null
  const has = (a: DeviationAction) => deviation.actions.includes(a)
  const status = DEVIATION_STATUS[deviation.currentStatus]
  const closeDialog = () => setDialog(null)

  async function confirmReason(action: ReasonAction, reason: string) {
    try {
      await runners[action].run(reason)
      closeDialog()
    } catch {
      // shown in the dialog
    }
  }

  async function doStartReview() {
    try {
      await startReview({ id: deviation.id }).unwrap()
    } catch {
      // surfaced via startingReview.error below
    }
  }

  const linkedRecord = <LinkedRecordLink module={deviation.linkedModule} id={deviation.linkedRecordId} />
  const details: [string, ReactNode][] = [
    ['Linked record', linkedRecord],
    ['Customer approval required', deviation.customerApprovalRequiredFlag ? 'Yes' : 'No'],
    ['Validity quantity', deviation.validityQuantity],
    ['Validity end date', formatDate(deviation.validityEndDate)],
  ]
  if (ergo) details.push(['Requested by', ergo.requestedBy ?? '—'], ['Customer', ergo.customer ?? '—'], ['Supplier', ergo.supplier ?? '—'], ['Visible to customer', ergo.customerVisible ? 'Yes' : 'No'], ['Visible to supplier', ergo.supplierVisible ? 'Yes' : 'No'])

  const impact: [string, ReactNode][] = [
    ['Quality impact', deviation.qualityImpact ?? '—'],
    ['Technical impact', deviation.technicalImpact ?? '—'],
  ]

  return (
    <Stack spacing={2}>
      <Stack direction={{ xs: 'column', md: 'row' }} sx={{ justifyContent: 'space-between', gap: 2 }}>
        <Stack spacing={0.5}>
          <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
            <Typography variant="h5" component="h1">
              {deviation.deviationNumber}
            </Typography>
            <Chip label={status.label} color={status.color} size="small" />
          </Stack>
          <Typography color="text.secondary">
            {LINKED_MODULE[deviation.linkedModule]} #{deviation.linkedRecordId} · {deviation.partNumber}
          </Typography>
        </Stack>
        <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap', alignItems: 'flex-start' }}>
          {has('EDIT') && <Button onClick={() => setDialog({ kind: 'edit' })}>Edit</Button>}
          {has('START_REVIEW') && (
            <Button variant="contained" loading={startingReview.isLoading} onClick={() => void doStartReview()}>
              Start review
            </Button>
          )}
          {WORKFLOW.filter((w) => has(w.action)).map((w) => (
            <Button key={w.action} variant={w.danger ? 'outlined' : 'contained'} color={w.danger ? 'error' : 'primary'} onClick={() => setDialog({ kind: 'reason', action: w.action })}>
              {w.label}
            </Button>
          ))}
        </Stack>
      </Stack>
      {startingReview.error != null && <ProblemAlert error={startingReview.error} />}

      <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', md: '1fr 1fr' } }}>
        <Facts title="Deviation details" facts={details} />
        <Facts title="Impact assessment" facts={impact} />
      </Box>

      <Facts title="Description" facts={[['', deviation.deviationDescription]]} />

      <Typography variant="h6" component="h2">
        Approvals
      </Typography>
      <TableContainer component={Paper} variant="outlined">
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>Approver</TableCell>
              <TableCell>Decision</TableCell>
              {ergo && <TableCell>Decided by</TableCell>}
              <TableCell>Comment</TableCell>
              <TableCell>Decided at</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {deviation.approvals.map((row) => {
              const d = DEVIATION_DECISION[row.decision]
              return (
                <TableRow key={`${row.approverRole}-${row.decidedAt ?? 'open'}`}>
                  <TableCell>{APPROVAL_ROLE_LABEL[row.approverRole] ?? row.approverRole}</TableCell>
                  <TableCell>
                    <Chip size="small" label={d.label} color={d.color} />
                  </TableCell>
                  {ergo && <TableCell>{'approverName' in row ? (row.approverName ?? '—') : '—'}</TableCell>}
                  <TableCell sx={{ maxWidth: 320, wordBreak: 'break-word' }}>{row.comment ?? '—'}</TableCell>
                  <TableCell>{row.decidedAt ? formatDateTime(row.decidedAt) : '—'}</TableCell>
                </TableRow>
              )
            })}
            {deviation.approvals.length === 0 && (
              <TableRow>
                <TableCell colSpan={ergo ? 5 : 4} sx={{ color: 'text.secondary' }}>
                  No approval rows recorded.
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </TableContainer>

      {dialog?.kind === 'edit' && <DeviationUpdateDialog deviation={deviation} onClose={closeDialog} />}
      {dialog?.kind === 'reason' &&
        (() => {
          const w = WORKFLOW.find((x) => x.action === dialog.action)
          if (!w) return null
          const runner = runners[dialog.action]
          return (
            <CommentDialog
              title={w.label}
              label={w.required ? 'Reason' : 'Comment (optional)'}
              required={w.required}
              danger={w.danger}
              intro={w.intro}
              confirmLabel={w.label}
              busy={runner.state.isLoading}
              error={runner.state.error}
              onConfirm={(c) => void confirmReason(dialog.action, c)}
              onClose={closeDialog}
            />
          )
        })()}
      <CommentsSection linkedModule="deviation" recordId={deviation.id} />
    </Stack>
  )
}
