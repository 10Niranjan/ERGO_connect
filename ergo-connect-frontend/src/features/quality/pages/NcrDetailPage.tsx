import { Alert, Box, Button, Chip, LinearProgress, Paper, Stack, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Typography } from '@mui/material'
import { useState, type ReactNode } from 'react'
import { useParams } from 'react-router'
import { CommentsSection } from '../../communication/components/CommentsPanel'
import { CommentDialog } from '../../../shared/components/CommentDialog'
import { Facts } from '../../../shared/components/Facts'
import { problemMessage } from '../../../shared/problem'
import { formatDate } from '../../rfq/labels'
import { CapaActionDialog, NcrUpdateDialog } from '../components/NcrDialogs'
import { CAPA_ACTION_STATUS, CAPA_ACTION_TYPE, LINKED_MODULE, NCR_STATUS } from '../labels'
import {
  useCloseCapaActionMutation,
  useCloseNcrMutation,
  useDownloadCapaEvidenceMutation,
  useNcrQuery,
  useReopenNcrMutation,
  useSendBackNcrMutation,
  useUploadCapaEvidenceMutation,
  type NcrAction,
  type NcrDetail,
} from '../qualityApi'

export function NcrDetailPage() {
  const id = Number(useParams().id)
  const { data: ncr, isLoading, error } = useNcrQuery(id)
  if (isLoading) return <LinearProgress />
  if (!ncr) return <Alert severity="error">{problemMessage(error)}</Alert>
  return <NcrDetailView ncr={ncr} />
}

/** The four form-based transitions plus EDIT open NcrUpdateDialog (NcrUpdateForm carries the progressive fields);
 * the three below take only a reason/comment, same split as InspectionDetailPage's WORKFLOW/ReasonAction. */
const UPDATE_MODES = ['EDIT', 'MOVE_TO_CONTAINMENT', 'MOVE_TO_ROOT_CAUSE', 'MOVE_TO_CAPA', 'SUBMIT_FOR_CLOSURE'] as const

type ReasonAction = 'CLOSE' | 'SEND_BACK' | 'REOPEN'

const REASON_WORKFLOW: { action: ReasonAction; label: string; required: boolean; danger?: boolean; intro?: string }[] = [
  { action: 'CLOSE', label: 'Close', required: false, intro: 'Closing requires at least one CAPA action with evidence attached.' },
  { action: 'SEND_BACK', label: 'Send back', required: true, danger: true, intro: 'Sends the NCR back to CAPA in progress for more work.' },
  { action: 'REOPEN', label: 'Reopen', required: true, intro: 'Reopening returns the NCR to Open.' },
]

const UPDATE_LABEL: Record<(typeof UPDATE_MODES)[number], string> = {
  EDIT: 'Edit',
  MOVE_TO_CONTAINMENT: 'Move to containment',
  MOVE_TO_ROOT_CAUSE: 'Move to root cause',
  MOVE_TO_CAPA: 'Move to CAPA',
  SUBMIT_FOR_CLOSURE: 'Submit for closure',
}

type Dialog = { kind: 'update'; action: (typeof UPDATE_MODES)[number] } | { kind: 'reason'; action: ReasonAction } | { kind: 'capa'; row: NcrDetail['capaActions'][number] | null }

function NcrDetailView({ ncr }: { ncr: NcrDetail }) {
  const [dialog, setDialog] = useState<Dialog | null>(null)
  const [close, closing] = useCloseNcrMutation()
  const [sendBack, sendingBack] = useSendBackNcrMutation()
  const [reopen, reopening] = useReopenNcrMutation()
  const [closeCapa, closingCapa] = useCloseCapaActionMutation()
  const [uploadEvidence, uploading] = useUploadCapaEvidenceMutation()
  const [download, downloading] = useDownloadCapaEvidenceMutation()

  const runners: Record<ReasonAction, { run: (reason: string) => Promise<unknown>; state: { isLoading: boolean; error?: unknown } }> = {
    CLOSE: { run: (r) => close({ id: ncr.id, reason: r || undefined }).unwrap(), state: closing },
    SEND_BACK: { run: (r) => sendBack({ id: ncr.id, reason: r }).unwrap(), state: sendingBack },
    REOPEN: { run: (r) => reopen({ id: ncr.id, reason: r }).unwrap(), state: reopening },
  }

  // Audience is told apart structurally (no discriminant field), same as InspectionDetailPage.
  const ergo = 'customerCompanyId' in ncr ? ncr : null
  const has = (a: NcrAction) => ncr.actions.includes(a)
  // CAPA actions track exactly the NCR's own EDIT action (NcrService.requireCapaEditable == allowed(EDIT, ...) by
  // construction - see qualityApi.ts's NcrAction comment), so the section's own controls reuse that one check.
  const capaEditable = has('EDIT')
  const status = NCR_STATUS[ncr.currentStatus]
  const closeDialog = () => setDialog(null)

  async function confirmReason(action: ReasonAction, reason: string) {
    try {
      await runners[action].run(reason)
      closeDialog()
    } catch {
      // shown in the dialog
    }
  }

  async function doCloseCapa(capaActionId: number) {
    try {
      await closeCapa({ id: ncr.id, capaActionId }).unwrap()
    } catch {
      // surfaced via the table's own row error below would need per-row state; kept simple, RTK Query devtools/console show it
    }
  }

  async function doUpload(capaActionId: number, file: File) {
    try {
      await uploadEvidence({ id: ncr.id, capaActionId, file }).unwrap()
    } catch {
      // as above
    }
  }

  async function doDownload(capaActionId: number, fallbackName: string) {
    try {
      const { url, fileName } = await download({ id: ncr.id, capaActionId }).unwrap()
      const a = document.createElement('a')
      a.href = url
      a.download = fileName || fallbackName
      a.click()
      setTimeout(() => URL.revokeObjectURL(url), 10_000)
    } catch {
      // no per-row error slot; RTK Query keeps the mutation's own error for the caller to inspect if needed
    }
  }

  const facts: [string, ReactNode][] = [
    ['Issue category', ncr.issueCategory],
    ['Linked module', `${LINKED_MODULE[ncr.linkedModule]} #${ncr.linkedRecordId}`],
    ['Target closure date', formatDate(ncr.targetClosureDate)],
    ['Closure date', formatDate(ncr.closureDate)],
  ]
  // NcrView carries no customerVisible field (backend gap, flagged in NcrDialogs.tsx) - not shown here since there
  // is nothing to read; the edit dialog's own checkbox is the only place that value can be set going forward.
  if (ergo) facts.push(['Customer', ergo.customer ?? '—'], ['Supplier', ergo.supplier ?? '—'], ['Owner', ergo.owner ?? '—'], ['Effectiveness', ergo.effectivenessStatus ?? '—'])

  const progress: [string, ReactNode][] = [
    ['Containment action', ncr.containmentAction ?? '—'],
    ['Root cause', ncr.rootCause ?? '—'],
    ['Corrective action', ncr.correctiveAction ?? '—'],
    ['Preventive action', ncr.preventiveAction ?? '—'],
  ]

  return (
    <Stack spacing={2}>
      <Stack direction={{ xs: 'column', md: 'row' }} sx={{ justifyContent: 'space-between', gap: 2 }}>
        <Stack spacing={0.5}>
          <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
            <Typography variant="h5" component="h1">
              {ncr.ncrNumber}
            </Typography>
            <Chip label={status.label} color={status.color} size="small" />
          </Stack>
          <Typography color="text.secondary">
            {LINKED_MODULE[ncr.linkedModule]} #{ncr.linkedRecordId} · {ncr.issueCategory}
          </Typography>
        </Stack>
        <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap', alignItems: 'flex-start' }}>
          {UPDATE_MODES.filter(has).map((action) => (
            <Button key={action} variant={action === 'EDIT' ? 'text' : 'contained'} onClick={() => setDialog({ kind: 'update', action })}>
              {UPDATE_LABEL[action]}
            </Button>
          ))}
          {REASON_WORKFLOW.filter((w) => has(w.action)).map((w) => (
            <Button key={w.action} variant={w.danger ? 'outlined' : 'contained'} color={w.danger ? 'error' : 'primary'} onClick={() => setDialog({ kind: 'reason', action: w.action })}>
              {w.label}
            </Button>
          ))}
        </Stack>
      </Stack>

      <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', md: '1fr 1fr' } }}>
        <Facts title="NCR details" facts={facts} />
        <Facts title="Containment, root cause and CAPA plan" facts={progress} />
      </Box>

      <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center' }}>
        <Typography variant="h6" component="h2">
          CAPA actions
        </Typography>
        {capaEditable && (
          <Button size="small" variant="outlined" onClick={() => setDialog({ kind: 'capa', row: null })}>
            Add CAPA action
          </Button>
        )}
      </Stack>
      <TableContainer component={Paper} variant="outlined">
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>Type</TableCell>
              <TableCell>Description</TableCell>
              <TableCell>Target date</TableCell>
              {ergo && <TableCell>Owner</TableCell>}
              <TableCell>Status</TableCell>
              <TableCell>Evidence</TableCell>
              <TableCell>Closed</TableCell>
              <TableCell />
            </TableRow>
          </TableHead>
          <TableBody>
            {ncr.capaActions.map((row) => {
              const st = CAPA_ACTION_STATUS[row.status]
              const rowClosed = row.status === 'CLOSED'
              const canEditRow = capaEditable && !rowClosed
              return (
                <TableRow key={row.id}>
                  <TableCell>{CAPA_ACTION_TYPE[row.actionType]}</TableCell>
                  <TableCell sx={{ maxWidth: 320, wordBreak: 'break-word' }}>{row.actionDescription}</TableCell>
                  <TableCell>{formatDate(row.targetDate)}</TableCell>
                  {ergo && <TableCell>{'owner' in row ? (row.owner ?? '—') : '—'}</TableCell>}
                  <TableCell>
                    <Chip size="small" label={st.label} color={st.color} />
                  </TableCell>
                  <TableCell>
                    {'evidenceAttached' in row ? (
                      <Chip size="small" label={row.evidenceAttached ? 'Attached' : 'Not attached'} color={row.evidenceAttached ? 'success' : 'default'} />
                    ) : row.evidenceDocumentId != null ? (
                      <Button size="small" loading={downloading.isLoading} onClick={() => void doDownload(row.id, row.evidenceFileName ?? 'evidence')}>
                        {row.evidenceFileName ?? 'Download'}
                      </Button>
                    ) : (
                      '—'
                    )}
                  </TableCell>
                  <TableCell>{formatDate(row.closedAt)}</TableCell>
                  <TableCell align="right">
                    {canEditRow && (
                      <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap', justifyContent: 'flex-end' }}>
                        <Button size="small" onClick={() => setDialog({ kind: 'capa', row })}>
                          Edit
                        </Button>
                        <Button size="small" component="label">
                          Upload evidence
                          <input hidden type="file" onChange={(e) => e.target.files?.[0] && void doUpload(row.id, e.target.files[0])} />
                        </Button>
                        <Button size="small" loading={closingCapa.isLoading} onClick={() => void doCloseCapa(row.id)}>
                          Close
                        </Button>
                      </Stack>
                    )}
                  </TableCell>
                </TableRow>
              )
            })}
            {ncr.capaActions.length === 0 && (
              <TableRow>
                <TableCell colSpan={ergo ? 8 : 7} sx={{ color: 'text.secondary' }}>
                  No CAPA actions recorded yet.
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </TableContainer>
      {uploading.error != null && <Alert severity="error">{problemMessage(uploading.error)}</Alert>}
      {closingCapa.error != null && <Alert severity="error">{problemMessage(closingCapa.error)}</Alert>}
      {downloading.error != null && <Alert severity="error">{problemMessage(downloading.error)}</Alert>}

      {dialog?.kind === 'update' && <NcrUpdateDialog mode={dialog.action} ncr={ncr} onClose={closeDialog} />}
      {dialog?.kind === 'capa' && <CapaActionDialog ncr={ncr} row={dialog.row} onClose={closeDialog} />}
      {dialog?.kind === 'reason' &&
        (() => {
          const w = REASON_WORKFLOW.find((x) => x.action === dialog.action)
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
      <CommentsSection linkedModule="ncr" recordId={ncr.id} />
    </Stack>
  )
}
