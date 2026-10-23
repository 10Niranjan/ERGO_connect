import {
  Alert,
  Autocomplete,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
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
import { saveObjectUrl } from '../../../shared/download'
import { problemMessage } from '../../../shared/problem'
import { VISIBILITY, formatDate } from '../labels'
import {
  useDeleteRfqDocumentMutation,
  useDownloadRfqDocumentMutation,
  useRfqDocumentsQuery,
  useRfqQuery,
  useSetRfqDocumentVisibilityMutation,
  useUploadRfqDocumentMutation,
  type ItemView,
  type Visibility,
} from '../rfqApi'

interface Props {
  rfqId: number
  canUpload: boolean
  /** ERGO chooses who sees each file; customers' uploads are visible to ERGO and themselves. */
  isErgo: boolean
}

/** A file shared with suppliers is shared for chosen parts: a supplier sees only the parts sent to it. */
const sharedWithSuppliers = (v: Visibility) => v === 'SUPPLIER' || v === 'ALL'

export function DocumentsPanel({ rfqId, canUpload, isErgo }: Props) {
  const { data: documents = [], error } = useRfqDocumentsQuery(rfqId)
  const { data: rfq } = useRfqQuery(rfqId, { skip: !isErgo })
  const parts = rfq?.items ?? []
  const [upload, uploading] = useUploadRfqDocumentMutation()
  const [download] = useDownloadRfqDocumentMutation()
  const [setVisibility] = useSetRfqDocumentVisibilityMutation()
  const [remove] = useDeleteRfqDocumentMutation()
  const [visibility, setNewVisibility] = useState<Visibility>('INTERNAL')
  const [uploadParts, setUploadParts] = useState<ItemView[]>([])
  const [choosing, setChoosing] = useState<{ documentId: number; visibility: Visibility; current: number[] } | null>(null)
  const [actionError, setActionError] = useState<unknown>(null)
  const fileInput = useRef<HTMLInputElement>(null)

  async function onFile(file: File | undefined) {
    if (!file) return
    setActionError(null)
    await upload({
      id: rfqId,
      file,
      visibility: isErgo ? visibility : undefined,
      itemIds: isErgo && sharedWithSuppliers(visibility) ? uploadParts.map((p) => p.id) : undefined,
    })
      .unwrap()
      .catch(setActionError)
    if (fileInput.current) fileInput.current.value = ''
  }

  async function save(documentId: number, fileName: string) {
    try {
      saveObjectUrl(await download({ id: rfqId, documentId }).unwrap(), fileName)
    } catch (e) {
      setActionError(e)
    }
  }

  function changeVisibility(documentId: number, next: Visibility, current: number[]) {
    if (sharedWithSuppliers(next)) setChoosing({ documentId, visibility: next, current })
    else void setVisibility({ id: rfqId, documentId, visibility: next }).unwrap().catch(setActionError)
  }

  const partName = (id: number) => parts.find((p) => p.id === id)?.partNumber ?? String(id)

  return (
    <Stack spacing={2}>
      {canUpload && (
        <Stack direction="row" spacing={1} useFlexGap sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
          {isErgo && (
            <TextField select size="small" label="Visible to" value={visibility} onChange={(e) => setNewVisibility(e.target.value as Visibility)} sx={{ minWidth: 220 }}>
              {Object.entries(VISIBILITY).map(([value, label]) => (
                <MenuItem key={value} value={value}>
                  {label}
                </MenuItem>
              ))}
            </TextField>
          )}
          {isErgo && sharedWithSuppliers(visibility) && (
            <Autocomplete
              multiple
              size="small"
              sx={{ minWidth: 280 }}
              options={parts}
              value={uploadParts}
              onChange={(_e, value) => setUploadParts(value)}
              getOptionLabel={(p) => p.partNumber}
              isOptionEqualToValue={(a, b) => a.id === b.id}
              renderInput={(params) => <TextField {...params} label="Shared for parts" />}
            />
          )}
          <Button
            variant="outlined"
            loading={uploading.isLoading}
            disabled={isErgo && sharedWithSuppliers(visibility) && uploadParts.length === 0}
            onClick={() => fileInput.current?.click()}
          >
            Upload file
          </Button>
          <input ref={fileInput} type="file" hidden onChange={(e) => void onFile(e.target.files?.[0])} aria-label="File to upload" />
          <Typography variant="body2" color="text.secondary">
            {isErgo
              ? 'Share files with suppliers only when they carry no customer identification. A supplier sees only the files shared for the parts it holds.'
              : 'Your files are visible to ERGO. ERGO decides what suppliers receive.'}
          </Typography>
        </Stack>
      )}
      {(error ?? actionError) != null && <Alert severity="error">{problemMessage(error ?? actionError)}</Alert>}
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
                    <Stack spacing={0.5}>
                      <TextField
                        select
                        size="small"
                        value={d.visibility}
                        onChange={(e) => changeVisibility(d.id, e.target.value as Visibility, d.itemIds)}
                        slotProps={{ htmlInput: { 'aria-label': `Who sees ${d.fileName}` } }}
                      >
                        {Object.entries(VISIBILITY).map(([value, label]) => (
                          <MenuItem key={value} value={value}>
                            {label}
                          </MenuItem>
                        ))}
                      </TextField>
                      {sharedWithSuppliers(d.visibility) && (
                        <Typography variant="caption" color="text.secondary">
                          Suppliers of: {d.itemIds.map(partName).join(', ') || 'no part (not shared)'}
                        </Typography>
                      )}
                    </Stack>
                  ) : (
                    VISIBILITY[d.visibility]
                  )}
                </TableCell>
                <TableCell>{formatDate(d.createdAt)}</TableCell>
                <TableCell align="right">
                  {d.canDelete && (
                    <IconButton
                      aria-label={`Remove ${d.fileName}`}
                      onClick={() => window.confirm(`Remove ${d.fileName}?`) && void remove({ id: rfqId, documentId: d.id }).unwrap().catch(setActionError)}
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
      {choosing && (
        <ChoosePartsDialog
          parts={parts}
          initial={choosing.current}
          onClose={() => setChoosing(null)}
          onConfirm={(itemIds) => {
            void setVisibility({ id: rfqId, documentId: choosing.documentId, visibility: choosing.visibility, itemIds })
              .unwrap()
              .catch(setActionError)
            setChoosing(null)
          }}
        />
      )}
    </Stack>
  )
}

/** Which parts a file shared with suppliers is shared for. */
function ChoosePartsDialog({ parts, initial, onClose, onConfirm }: { parts: ItemView[]; initial: number[]; onClose: () => void; onConfirm: (itemIds: number[]) => void }) {
  const [selected, setSelected] = useState<ItemView[]>(() => parts.filter((p) => initial.includes(p.id)))
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Share with suppliers for which parts?</DialogTitle>
      <DialogContent sx={{ display: 'grid', gap: 2, pt: '8px !important' }}>
        <Typography color="text.secondary">A supplier sees this file only if it holds one of these parts.</Typography>
        <Autocomplete
          multiple
          options={parts}
          value={selected}
          onChange={(_e, value) => setSelected(value)}
          getOptionLabel={(p) => p.partNumber}
          isOptionEqualToValue={(a, b) => a.id === b.id}
          renderInput={(params) => <TextField {...params} label="Parts" autoFocus />}
        />
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={selected.length === 0} onClick={() => onConfirm(selected.map((p) => p.id))}>
          Share
        </Button>
      </DialogActions>
    </Dialog>
  )
}
