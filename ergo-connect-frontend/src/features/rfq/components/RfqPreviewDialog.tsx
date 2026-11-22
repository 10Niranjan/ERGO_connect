import { Alert, Box, Button, Dialog, DialogActions, DialogContent, DialogTitle, Table, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material'
import { Facts } from '../../../shared/components/Facts'
import type { FileKind } from '../rfqApi'
import { FILE_KIND_LABELS } from '../utils/labels'

/** One part as typed in the form: everything is text; the files are names (stored or still waiting to upload). */
export interface PreviewPart {
  partNumber: string
  revision: string
  description: string
  eau: string
  targetPrice: string
  remarks: string
  /** The optional part details that were filled in, as label / value pairs. */
  details: [string, string][]
  files: Record<FileKind, { name: string; pending: boolean }[]>
}

interface Props {
  header: [string, string][]
  parts: PreviewPart[]
  /** "Submit RFQ" or "Save and answer". */
  confirmLabel: string
  onEdit: () => void
  onConfirm: () => void
}

const dash = (value: string) => (value.trim() ? value : '—')

/** The customer's last look at the RFQ before it goes to ERGO: the form read back, with the files by slot. */
export function RfqPreviewDialog({ header, parts, confirmLabel, onEdit, onConfirm }: Props) {
  return (
    <Dialog open onClose={onEdit} fullWidth maxWidth="lg">
      <DialogTitle>Check your RFQ before submitting</DialogTitle>
      <DialogContent sx={{ display: 'grid', gap: 2 }}>
        <Typography variant="body2" color="text.secondary">
          Read it through once more. Choose Edit to change anything; nothing is lost.
        </Typography>
        <Facts title="Request" facts={header} />
        <Box sx={{ overflowX: 'auto' }}>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>#</TableCell>
                <TableCell>Part #</TableCell>
                <TableCell>Revision</TableCell>
                <TableCell>Description</TableCell>
                <TableCell>Annual volume</TableCell>
                <TableCell>Target price</TableCell>
                <TableCell>Remarks</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {parts.map((p, i) => (
                <TableRow key={i}>
                  <TableCell>{i + 1}</TableCell>
                  <TableCell>{p.partNumber}</TableCell>
                  <TableCell>{dash(p.revision)}</TableCell>
                  <TableCell>{dash(p.description)}</TableCell>
                  <TableCell>{dash(p.eau)}</TableCell>
                  <TableCell>{dash(p.targetPrice)}</TableCell>
                  <TableCell>{dash(p.remarks)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Box>
        {parts.map((p, i) => {
          const files = (Object.keys(FILE_KIND_LABELS) as FileKind[]).map((kind): [string, string] => [
            FILE_KIND_LABELS[kind],
            p.files[kind].map((f) => (f.pending ? `${f.name} (to be uploaded)` : f.name)).join(', ') || '—',
          ])
          return <Facts key={i} title={`Part ${i + 1}: ${p.partNumber}`} facts={[...p.details, ...files]} />
        })}
        {parts.some((p) => Object.values(p.files).some((list) => list.some((f) => f.pending))) && (
          <Alert severity="info">Files marked "to be uploaded" are sent when you confirm.</Alert>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={onEdit}>Edit</Button>
        <Button variant="contained" onClick={onConfirm}>
          {confirmLabel}
        </Button>
      </DialogActions>
    </Dialog>
  )
}
