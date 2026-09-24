import {
  Alert,
  Button,
  IconButton,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material'
import { useRef, useState } from 'react'
import { saveObjectUrl } from '../../../shared/download'
import { problemMessage } from '../../../shared/problem'
import { formatDate } from '../../rfq/labels'
import {
  useDeleteQuoteDocumentMutation,
  useDownloadQuoteDocumentMutation,
  useQuoteDocumentsQuery,
  useUploadQuoteDocumentMutation,
  type QuoteDocument,
} from '../quoteApi'

interface Props {
  quoteId: number
  canUpload: boolean
  isErgo: boolean
}

/** Suppliers: ERGO's shared RFQ files plus their own. ERGO: the supplier's submitted files plus ERGO's internal ones. */
export function QuoteFilesPanel({ quoteId, canUpload, isErgo }: Props) {
  const { data: documents = [], error } = useQuoteDocumentsQuery(quoteId)
  const [upload, uploading] = useUploadQuoteDocumentMutation()
  const [download] = useDownloadQuoteDocumentMutation()
  const [remove] = useDeleteQuoteDocumentMutation()
  const [actionError, setActionError] = useState<unknown>(null)
  const fileInput = useRef<HTMLInputElement>(null)

  async function onFile(file: File | undefined) {
    if (!file) return
    setActionError(null)
    await upload({ id: quoteId, file }).unwrap().catch(setActionError)
    if (fileInput.current) fileInput.current.value = ''
  }

  async function save(d: QuoteDocument) {
    try {
      saveObjectUrl(await download({ id: quoteId, documentId: d.id, source: d.source }).unwrap(), d.fileName)
    } catch (e) {
      setActionError(e)
    }
  }

  const from = (d: QuoteDocument) =>
    d.source === 'RFQ' ? 'ERGO (RFQ file)' : d.ownCompany ? (isErgo ? 'ERGO, internal' : 'Your company') : isErgo ? 'Supplier' : 'ERGO'

  return (
    <Stack spacing={2}>
      {canUpload && (
        <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
          <Button variant="outlined" loading={uploading.isLoading} onClick={() => fileInput.current?.click()}>
            Upload file
          </Button>
          <input ref={fileInput} type="file" hidden onChange={(e) => void onFile(e.target.files?.[0])} aria-label="File to upload" />
          <Typography variant="body2" color="text.secondary">
            {isErgo
              ? 'Files you add here stay inside ERGO.'
              : 'ERGO sees your files once you submit the quote. Submitted files stay with that version and can no longer be removed.'}
          </Typography>
        </Stack>
      )}
      {(error ?? actionError) != null && <Alert severity="error">{problemMessage(error ?? actionError)}</Alert>}
      <TableContainer component={Paper} variant="outlined">
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>File</TableCell>
              <TableCell>From</TableCell>
              <TableCell>Size</TableCell>
              <TableCell>Uploaded</TableCell>
              <TableCell align="right" />
            </TableRow>
          </TableHead>
          <TableBody>
            {documents.map((d) => (
              <TableRow key={`${d.source}-${d.id}`} hover>
                <TableCell>
                  <Button size="small" onClick={() => void save(d)} sx={{ textTransform: 'none' }}>
                    {d.fileName}
                  </Button>
                </TableCell>
                <TableCell>{from(d)}</TableCell>
                <TableCell>{(d.size / 1024).toFixed(0)} KB</TableCell>
                <TableCell>{formatDate(d.createdAt)}</TableCell>
                <TableCell align="right">
                  {d.canDelete && (
                    <IconButton
                      aria-label={`Remove ${d.fileName}`}
                      onClick={() => window.confirm(`Remove ${d.fileName}?`) && void remove({ id: quoteId, documentId: d.id }).unwrap().catch(setActionError)}
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
    </Stack>
  )
}
