import { Alert, Box, Button, Chip, LinearProgress, Link, Paper, Stack, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Typography } from '@mui/material'
import { useEffect, useState } from 'react'
import { Link as RouterLink, useNavigate, useParams } from 'react-router'
import { useAppSelector } from '../../../app/store'
import { Facts } from '@/components/ui/Facts'
import { CommentDialog } from '@/components/ui/CommentDialog'
import { saveObjectUrl } from '@/utils/download'
import { VISIBILITY, formatDate } from '@/features/rfq/utils/labels'
import { EditDocumentDialog, ReplaceDocumentDialog } from '../components/DocumentDialogs'
import {
  useApproveDocumentMutation,
  useDocumentQuery,
  useDownloadDocumentMutation,
  useLockDocumentMutation,
  useRejectDocumentMutation,
  useUnlockDocumentMutation,
  type DocumentAction,
  type DocumentDetail,
} from '@/features/documents/services/documentsApi'
import { APPROVAL_STATUS, LINKED_MODULE_LABEL, LOCK_STATUS, formatSize, linkedRecordLabel, linkedRecordPath } from '@/features/documents/utils/labels'
import { ProblemAlert } from '@/components/ui/ProblemAlert'

export function DocumentDetailPage() {
  const id = Number(useParams().id)
  // currentData, not data: while another version loads, `data` still holds the previous document, and its buttons
  // must never be live for the wrong id.
  const { currentData: document, isFetching, error } = useDocumentQuery(id)
  if (isFetching && !document) return <LinearProgress />
  if (!document) return <ProblemAlert error={error} />
  // key: moving between versions (same route) must reset the preview and any open dialog.
  return <DocumentDetailView key={document.id} document={document} />
}

type Dialog = 'edit' | 'replace' | 'approve' | 'reject' | 'unlock'

const BUTTONS: { action: DocumentAction; label: string; dialog?: Dialog; danger?: boolean }[] = [
  { action: 'EDIT', label: 'Edit', dialog: 'edit' },
  { action: 'REPLACE', label: 'Upload new version', dialog: 'replace' },
  { action: 'APPROVE', label: 'Approve', dialog: 'approve' },
  { action: 'REJECT', label: 'Reject', dialog: 'reject', danger: true },
  { action: 'LOCK', label: 'Lock' },
  { action: 'UNLOCK', label: 'Unlock', dialog: 'unlock' },
]

function DocumentDetailView({ document }: { document: DocumentDetail }) {
  const navigate = useNavigate()
  const isErgo = useAppSelector((state) => state.auth.session?.user.companyType) === 'ERGO'
  const [dialog, setDialog] = useState<Dialog | null>(null)
  const [actionError, setActionError] = useState<unknown>(null)
  const [preview, setPreview] = useState<string | null>(null)
  const [download] = useDownloadDocumentMutation()
  useEffect(() => () => { if (preview) URL.revokeObjectURL(preview) }, [preview])
  const [lock, locking] = useLockDocumentMutation()
  const [unlock, unlocking] = useUnlockDocumentMutation()
  const [approve, approving] = useApproveDocumentMutation()
  const [reject, rejecting] = useRejectDocumentMutation()

  const status = APPROVAL_STATUS[document.approvalStatus]
  const path = linkedRecordPath(document.linkedModule, document.linkedRecordId)
  const isImage = document.contentType === 'image/png' || document.contentType === 'image/jpeg'
  const hasLaterVersion = document.versionHistory.some((v) => v.versionNo > document.versionNo)

  async function save() {
    setActionError(null)
    try {
      saveObjectUrl(await download(document.id).unwrap(), document.fileName)
    } catch (e) {
      setActionError(e)
    }
  }

  async function showPreview() {
    setActionError(null)
    try {
      setPreview(await download(document.id).unwrap())
    } catch (e) {
      setActionError(e)
    }
  }

  const reasonDialogs = {
    approve: { run: (r: string) => approve({ id: document.id, reason: r || undefined }).unwrap(), state: approving, title: 'Approve document', label: 'Comment (optional)', required: false, confirm: 'Approve', intro: 'Approving also locks the document: it becomes final.' },
    reject: { run: (r: string) => reject({ id: document.id, reason: r }).unwrap(), state: rejecting, title: 'Reject document', label: 'Reason', required: true, confirm: 'Reject', intro: undefined },
    unlock: {
      run: (r: string) => unlock({ id: document.id, reason: r || undefined }).unwrap(),
      state: unlocking,
      title: document.approvalStatus === 'APPROVED' ? 'Reopen approved document' : 'Unlock document',
      label: document.approvalStatus === 'APPROVED' ? 'Reason' : 'Comment (optional)',
      required: document.approvalStatus === 'APPROVED',
      confirm: 'Unlock',
      intro: document.approvalStatus === 'APPROVED' ? 'Unlocking an approved document sends it back to pending approval.' : undefined,
    },
  }

  return (
    <Stack spacing={2}>
      <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap' }} spacing={2} useFlexGap>
        <Box>
          <Link component={RouterLink} to="/documents" variant="body2">
            ← Documents
          </Link>
          <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
            <Typography variant="h5" component="h1">
              {document.fileName}
            </Typography>
            <Chip size="small" label={`v${document.versionNo}`} />
            <Chip size="small" label={status.label} color={status.color} />
            {document.lockStatus === 'LOCKED' && <Chip size="small" variant="outlined" label="Locked" />}
          </Stack>
        </Box>
        <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap' }}>
          <Button variant="outlined" onClick={() => void save()}>
            Download
          </Button>
          {BUTTONS.filter((b) => document.actions.includes(b.action)).map((b) => (
            <Button
              key={b.action}
              variant="outlined"
              color={b.danger ? 'error' : 'primary'}
              loading={b.action === 'LOCK' && locking.isLoading}
              onClick={() => {
                setActionError(null)
                if (b.dialog) setDialog(b.dialog)
                else void lock(document.id).unwrap().catch(setActionError)
              }}
            >
              {b.label}
            </Button>
          ))}
        </Stack>
      </Stack>
      {hasLaterVersion && (
        <Alert severity="info">
          A newer version of this document exists. This version is kept for the record and can no longer be changed.
        </Alert>
      )}
      {actionError != null && <ProblemAlert error={actionError} />}

      <Facts
        title="Preview"
        note="Files are served as downloads and never opened in the browser, except images, which you can preview here."
        facts={[
          ['File', `${document.fileName} (${document.contentType}, ${formatSize(document.fileSize)})`],
          ...(isImage ? [['', preview ? <img key="p" src={preview} alt={document.fileName} style={{ maxWidth: '100%', maxHeight: 360 }} /> : <Button key="b" size="small" onClick={() => void showPreview()}>Show preview</Button>] as [string, React.ReactNode]] : []),
        ]}
      />
      <Facts
        title="Metadata"
        facts={[
          ['Category', document.category ?? '—'],
          ['Version', `v${document.versionNo}`],
          ['Uploaded by', document.uploadedBy ?? '—'],
          ['Uploaded', formatDate(document.createdAt)],
          ['Last updated', formatDate(document.updatedAt)],
        ]}
      />
      <Facts
        title="Access visibility"
        facts={[
          ['Visible to', VISIBILITY[document.visibility]],
          ['Lock', LOCK_STATUS[document.lockStatus]],
        ]}
      />
      <Facts title="Approval state" facts={[['Status', status.label]]} />
      <Facts
        title="Linked records"
        facts={[
          [
            LINKED_MODULE_LABEL[document.linkedModule],
            path ? (
              <Link key="l" component={RouterLink} to={path}>
                {linkedRecordLabel(document.linkedModule, document.linkedRecordId)}
              </Link>
            ) : (
              linkedRecordLabel(document.linkedModule, document.linkedRecordId)
            ),
          ],
          ...document.secondaryLinks.map((l, i): [string, React.ReactNode] => [i === 0 ? 'Also linked to' : '', `${l.linkedModule} #${l.linkedRecordId}`]),
        ]}
      />
      <Typography variant="h6" component="h2">
        Version history
      </Typography>
      <TableContainer component={Paper} variant="outlined">
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>Version</TableCell>
              <TableCell>File</TableCell>
              <TableCell>Uploaded</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {document.versionHistory.map((v) => (
              <TableRow key={v.id} selected={v.current}>
                <TableCell>
                  v{v.versionNo}
                  {v.current && ' (this version)'}
                </TableCell>
                <TableCell>
                  {v.current ? (
                    v.fileName
                  ) : (
                    <Link component={RouterLink} to={`/documents/${v.id}`}>
                      {v.fileName}
                    </Link>
                  )}
                </TableCell>
                <TableCell>{formatDate(v.createdAt)}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>

      {dialog === 'edit' && <EditDocumentDialog document={document} onClose={() => setDialog(null)} />}
      {dialog === 'replace' && (
        <ReplaceDocumentDialog
          document={document}
          isErgo={isErgo}
          onClose={(newId) => {
            setDialog(null)
            if (newId !== undefined) navigate(`/documents/${newId}`)
          }}
        />
      )}
      {(dialog === 'approve' || dialog === 'reject' || dialog === 'unlock') && (
        <CommentDialog
          title={reasonDialogs[dialog].title}
          label={reasonDialogs[dialog].label}
          required={reasonDialogs[dialog].required}
          confirmLabel={reasonDialogs[dialog].confirm}
          intro={reasonDialogs[dialog].intro}
          danger={dialog === 'reject'}
          busy={reasonDialogs[dialog].state.isLoading}
          error={reasonDialogs[dialog].state.error}
          onClose={() => setDialog(null)}
          onConfirm={(reason) => void reasonDialogs[dialog].run(reason).then(() => setDialog(null), () => undefined)}
        />
      )}
    </Stack>
  )
}
