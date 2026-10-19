import {
  Button,
  Chip,
  IconButton,
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
import { useRef, useState } from 'react'
import { saveObjectUrl } from '@/utils/download'
import { VISIBILITY, formatDate } from '@/features/rfq/utils/labels'
import {
  useDeleteRfqDocumentMutation,
  useDownloadRfqDocumentMutation,
  useRfqDocumentsQuery,
  useSetRfqDocumentVisibilityMutation,
  useUploadRfqDocumentMutation,
  type Visibility,
} from '@/features/rfq/services/rfqApi'
import { useConfirm } from '@/components/ui/useConfirm'
import { ProblemAlert } from '@/components/ui/ProblemAlert'

interface Props {
  rfqId: number
  canUpload: boolean
  /** ERGO chooses who sees each file; customers' uploads are visible to ERGO and themselves. */
  isErgo: boolean
}

export function DocumentsPanel({ rfqId, canUpload, isErgo }: Props) {
  const { confirm, dialog: confirmDialog } = useConfirm()
  const { data: documents = [], error } = useRfqDocumentsQuery(rfqId)
  const [upload, uploading] = useUploadRfqDocumentMutation()
  const [download] = useDownloadRfqDocumentMutation()
  const [setVisibility] = useSetRfqDocumentVisibilityMutation()
  const [remove] = useDeleteRfqDocumentMutation()
  const [visibility, setNewVisibility] = useState<Visibility>('INTERNAL')
  const [actionError, setActionError] = useState<unknown>(null)
  const fileInput = useRef<HTMLInputElement>(null)

  async function onFile(file: File | undefined) {
    if (!file) return
    setActionError(null)
    await upload({ id: rfqId, file, visibility: isErgo ? visibility : undefined }).unwrap().catch(setActionError)
    if (fileInput.current) fileInput.current.value = ''
  }

  async function save(documentId: number, fileName: string) {
    try {
      saveObjectUrl(await download({ id: rfqId, documentId }).unwrap(), fileName)
    } catch (e) {
      setActionError(e)
    }
  }

  return (
    <Stack spacing={2}>
      {canUpload && (
        <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
          {isErgo && (
            <TextField select size="small" label="Visible to" value={visibility} onChange={(e) => setNewVisibility(e.target.value as Visibility)} sx={{ minWidth: 220 }}>
              {Object.entries(VISIBILITY).map(([value, label]) => (
                <MenuItem key={value} value={value}>
                  {label}
                </MenuItem>
              ))}
            </TextField>
          )}
          <Button variant="outlined" loading={uploading.isLoading} onClick={() => fileInput.current?.click()}>
            Upload file
          </Button>
          <input ref={fileInput} type="file" hidden onChange={(e) => void onFile(e.target.files?.[0])} aria-label="File to upload" />
          <Typography variant="body2" color="text.secondary">
            {isErgo
              ? 'Share files with suppliers only when they carry no customer identification.'
              : 'Your files are visible to ERGO. ERGO decides what suppliers receive.'}
          </Typography>
        </Stack>
      )}
      {(error ?? actionError) != null && <ProblemAlert error={error ?? actionError} />}
      <TableContainer component={Paper} variant="outlined">
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>File</TableCell>
              <TableCell>Size</TableCell>
              <TableCell>Visible to</TableCell>
              <TableCell>Uploaded</TableCell>
              <TableCell align="right" />
            </TableRow>
          </TableHead>
          <TableBody>
            {documents.map((d) => (
              <TableRow key={d.id} hover>
                <TableCell>
                  <Button size="small" onClick={() => void save(d.id, d.fileName)} sx={{ textTransform: 'none' }}>
                    {d.fileName}
                  </Button>
                  {d.ownCompany && <Chip size="small" label="yours" sx={{ ml: 1 }} />}
                </TableCell>
                <TableCell>{(d.size / 1024).toFixed(0)} KB</TableCell>
                <TableCell>
                  {isErgo ? (
                    <TextField
                      select
                      size="small"
                      value={d.visibility}
                      onChange={(e) => void setVisibility({ id: rfqId, documentId: d.id, visibility: e.target.value as Visibility }).unwrap().catch(setActionError)}
                      slotProps={{ htmlInput: { 'aria-label': `Who sees ${d.fileName}` } }}
                    >
                      {Object.entries(VISIBILITY).map(([value, label]) => (
                        <MenuItem key={value} value={value}>
                          {label}
                        </MenuItem>
                      ))}
                    </TextField>
                  ) : (
                    VISIBILITY[d.visibility]
                  )}
                </TableCell>
                <TableCell>{formatDate(d.createdAt)}</TableCell>
                <TableCell align="right">
                  {d.canDelete && (
                    <IconButton
                      aria-label={`Remove ${d.fileName}`}
                      onClick={async () => (await confirm({ title: 'Remove file?', message: `Remove ${d.fileName}?`, confirmLabel: 'Remove' })) && void remove({ id: rfqId, documentId: d.id }).unwrap().catch(setActionError)}
                    >
                      ×
                    </IconButton>
                  )}
                </TableCell>
              </TableRow>
            ))}
            {documents.length === 0 && (
              <TableRow>
                <TableCell colSpan={5} sx={{ color: 'text.secondary' }}>
                  No files yet.
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </TableContainer>
      {confirmDialog}
    </Stack>
  )
}
