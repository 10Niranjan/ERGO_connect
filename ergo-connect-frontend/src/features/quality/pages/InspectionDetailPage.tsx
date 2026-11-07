import { Alert, Box, Button, Chip, LinearProgress, Paper, Stack, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Typography } from '@mui/material'
import { useState, type ReactNode } from 'react'
import { useParams } from 'react-router'
import { CommentsSection } from '../../communication/components/CommentsPanel'
import { CommentDialog } from '../../../shared/components/CommentDialog'
import { Facts } from '../../../shared/components/Facts'
import { problemMessage } from '../../../shared/problem'
import { formatDate } from '../../rfq/labels'
import { InspectionUpdateDialog } from '../components/InspectionDialogs'
import { DEFECT_SEVERITY, LINKED_MODULE, RESULT_STATUS } from '../labels'
import {
  useCloseInspectionMutation,
  useInspectionQuery,
  usePassInspectionMutation,
  useRejectInspectionMutation,
  useReleaseInspectionWithDeviationMutation,
  useReopenInspectionMutation,
  type InspectionAction,
  type InspectionDetail,
} from '../qualityApi'

export function InspectionDetailPage() {
  const id = Number(useParams().id)
  // currentData, not data: data keeps the previous record while a new id fails to load.
  const { currentData: inspection, isLoading, isFetching, error } = useInspectionQuery(id)
  if (isLoading || (isFetching && !inspection)) return <LinearProgress />
  if (!inspection) return <Alert severity="error">{problemMessage(error)}</Alert>
  return <InspectionDetailView inspection={inspection} />
}

/** Status-changing buttons that take a reason/comment; EDIT/SUBMIT have their own dialog (InspectionUpdateDialog)
 * so are not in this set. Each renders only when the API listed its action (QualityDtos.InspectionAction) -
 * `required` = a reason is mandatory (CLAUDE.md non-negotiable for reject/reopen). */
type ReasonAction = 'PASS' | 'REJECT' | 'RELEASE_WITH_DEVIATION' | 'CLOSE' | 'REOPEN'

const WORKFLOW: { action: ReasonAction; label: string; required: boolean; danger?: boolean; intro?: string }[] = [
  { action: 'PASS', label: 'Pass', required: false },
  { action: 'REJECT', label: 'Reject', required: true, danger: true },
  { action: 'RELEASE_WITH_DEVIATION', label: 'Release with deviation', required: true },
  { action: 'CLOSE', label: 'Close', required: false, intro: 'Closing is final unless reopened.' },
  { action: 'REOPEN', label: 'Reopen', required: true, intro: 'Reopening returns the inspection to Open.' },
]

type Dialog = { kind: 'workflow'; action: ReasonAction } | { kind: 'edit' } | { kind: 'submit' }

function InspectionDetailView({ inspection }: { inspection: InspectionDetail }) {
  const [dialog, setDialog] = useState<Dialog | null>(null)
  const [pass, passing] = usePassInspectionMutation()
  const [reject, rejecting] = useRejectInspectionMutation()
  const [release, releasing] = useReleaseInspectionWithDeviationMutation()
  const [close, closing] = useCloseInspectionMutation()
  const [reopen, reopening] = useReopenInspectionMutation()

  const runners: Record<ReasonAction, { run: (reason: string) => Promise<unknown>; state: { isLoading: boolean; error?: unknown } }> = {
    PASS: { run: (r) => pass({ id: inspection.id, reason: r || undefined }).unwrap(), state: passing },
    REJECT: { run: (r) => reject({ id: inspection.id, reason: r }).unwrap(), state: rejecting },
    RELEASE_WITH_DEVIATION: { run: (r) => release({ id: inspection.id, reason: r }).unwrap(), state: releasing },
    CLOSE: { run: (r) => close({ id: inspection.id, reason: r || undefined }).unwrap(), state: closing },
    REOPEN: { run: (r) => reopen({ id: inspection.id, reason: r }).unwrap(), state: reopening },
  }

  // Audience is told apart structurally (no discriminant field). `in` narrows to the shape that has the field.
  // Only ERGO's InspectionView carries remarks (security review H1 dropped it from both supplier and customer).
  const ergo = 'customerCompanyId' in inspection ? inspection : null
  const has = (a: InspectionAction) => inspection.actions.includes(a)
  const status = RESULT_STATUS[inspection.resultStatus]
  const workflowDialog = dialog?.kind === 'workflow' ? WORKFLOW.find((w) => w.action === dialog.action) : undefined
  const runner = dialog?.kind === 'workflow' ? runners[dialog.action] : undefined
  const closeDialog = () => setDialog(null)

  async function confirm(reason: string) {
    try {
      await runner?.run(reason)
      closeDialog()
    } catch {
      // shown in the dialog
    }
  }

  const facts: [string, ReactNode][] = [
    ['Part number', inspection.partNumber],
    ['Batch / lot no.', inspection.batchLotNo ?? '—'],
    ['Linked module', `${LINKED_MODULE[inspection.linkedModule]} #${inspection.linkedRecordId}`],
  ]
  if (ergo) facts.push(['Customer', ergo.customer ?? '—'], ['Supplier', ergo.supplier ?? '—'], ['Visible to customer', ergo.customerVisible ? 'Yes' : 'No'])
  const results: [string, ReactNode][] = [
    ['Quantity checked', inspection.quantityChecked ?? '—'],
    ['Quantity rejected', inspection.quantityRejected ?? '—'],
    ['Inspection date', formatDate(inspection.inspectionDate)],
  ]
  if (ergo) results.push(['Inspected by', ergo.inspectedBy ?? '—'], ['Remarks', ergo.remarks ?? '—'])

  return (
    <Stack spacing={2}>
      <Stack direction={{ xs: 'column', md: 'row' }} sx={{ justifyContent: 'space-between', gap: 2 }}>
        <Stack spacing={0.5}>
          <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
            <Typography variant="h5" component="h1">
              {inspection.partNumber}
            </Typography>
            <Chip label={status.label} color={status.color} size="small" />
          </Stack>
          <Typography color="text.secondary">
            {LINKED_MODULE[inspection.linkedModule]} #{inspection.linkedRecordId}
            {inspection.batchLotNo ? ` · Batch ${inspection.batchLotNo}` : ''}
          </Typography>
        </Stack>
        <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap', alignItems: 'flex-start' }}>
          {has('EDIT') && <Button onClick={() => setDialog({ kind: 'edit' })}>Edit</Button>}
          {has('SUBMIT') && (
            <Button variant="contained" onClick={() => setDialog({ kind: 'submit' })}>
              Submit
            </Button>
          )}
          {WORKFLOW.filter((w) => has(w.action)).map((w) => (
            <Button key={w.action} variant={w.danger ? 'outlined' : 'contained'} color={w.danger ? 'error' : 'primary'} onClick={() => setDialog({ kind: 'workflow', action: w.action })}>
              {w.label}
            </Button>
          ))}
        </Stack>
      </Stack>

      <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', md: '1fr 1fr' } }}>
        <Facts title="Part and lot" facts={facts} />
        <Facts title="Inspection result" facts={results} />
      </Box>

      <Typography variant="h6" component="h2">
        Defects
      </Typography>
      <TableContainer component={Paper} variant="outlined">
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>Category</TableCell>
              <TableCell>Description</TableCell>
              <TableCell align="right">Quantity</TableCell>
              <TableCell>Severity</TableCell>
              <TableCell>Recorded</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {inspection.defects.map((d) => (
              <TableRow key={d.id}>
                <TableCell>{'defectCategory' in d ? d.defectCategory : '—'}</TableCell>
                <TableCell sx={{ maxWidth: 320, wordBreak: 'break-word' }}>{'defectDescription' in d ? (d.defectDescription ?? '—') : '—'}</TableCell>
                <TableCell align="right">{d.defectQuantity}</TableCell>
                <TableCell>
                  <Chip size="small" label={DEFECT_SEVERITY[d.severity].label} color={DEFECT_SEVERITY[d.severity].color} />
                </TableCell>
                <TableCell>{formatDate(d.createdAt)}</TableCell>
              </TableRow>
            ))}
            {inspection.defects.length === 0 && (
              <TableRow>
                <TableCell colSpan={5} sx={{ color: 'text.secondary' }}>
                  No defects recorded.
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </TableContainer>

      <CommentsSection linkedModule="inspection" recordId={inspection.id} />

      {(dialog?.kind === 'edit' || dialog?.kind === 'submit') && (
        <InspectionUpdateDialog mode={dialog.kind} inspection={inspection} onClose={closeDialog} />
      )}
      {workflowDialog && runner && (
        <CommentDialog
          title={workflowDialog.label}
          label={workflowDialog.required ? 'Reason' : 'Comment (optional)'}
          required={workflowDialog.required}
          danger={workflowDialog.danger}
          intro={workflowDialog.intro}
          confirmLabel={workflowDialog.label}
          busy={runner.state.isLoading}
          error={runner.state.error}
          onConfirm={(c) => void confirm(c)}
          onClose={closeDialog}
        />
      )}
    </Stack>
  )
}
