import { Button, LinearProgress, Paper, Stack, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Typography } from '@mui/material'
import { useState } from 'react'
import { useNavigate } from 'react-router'
import { CommentDialog } from '@/components/ui/CommentDialog'
import { formatDate } from '@/features/rfq/utils/labels'
import { useConversionQueueQuery, useConvertRfqMutation, type ConversionQueueRow } from '@/features/npd/services/npdApi'
import { ProblemAlert } from '@/components/ui/ProblemAlert'

// SRS §12.8.2. Customer-approved RFQs eligible for a one-way, manual conversion into an NPD project.
export function ConversionQueuePage() {
  const { data: rows = [], isLoading, error } = useConversionQueueQuery()
  const [converting, setConverting] = useState<ConversionQueueRow | null>(null)
  const [convert, converted] = useConvertRfqMutation()
  const navigate = useNavigate()

  async function doConvert(comment: string) {
    if (!converting) return
    try {
      const project = await convert({ rfqId: converting.rfqId, comment: comment || undefined }).unwrap()
      setConverting(null)
      navigate(`/npd/${project.id}`)
    } catch {
      // shown in the dialog
    }
  }

  return (
    <Stack spacing={2}>
      <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center' }}>
        <Typography variant="h5" component="h1">
          Conversion queue
        </Typography>
        <Button onClick={() => navigate('/npd')}>NPD projects</Button>
      </Stack>
      <Typography variant="body2" color="text.secondary">
        Customer-approved RFQs waiting to become NPD projects. Conversion is manual and one-way.
      </Typography>
      {error && <ProblemAlert error={error} />}
      <TableContainer component={Paper} variant="outlined">
        {isLoading && <LinearProgress />}
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>RFQ</TableCell>
              <TableCell>Customer</TableCell>
              <TableCell>Part</TableCell>
              <TableCell>Description</TableCell>
              <TableCell>Approved</TableCell>
              <TableCell align="right">Actions</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {rows.map((r) => (
              <TableRow key={r.rfqId} hover>
                <TableCell>{r.rfqNumber}</TableCell>
                <TableCell>{r.customerName}</TableCell>
                <TableCell>
                  {r.partNumber}
                  {r.revision ? ` rev ${r.revision}` : ''}
                </TableCell>
                <TableCell sx={{ maxWidth: 320, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{r.description ?? '—'}</TableCell>
                <TableCell>{formatDate(r.approvedAt)}</TableCell>
                <TableCell align="right">
                  {r.actions.includes('CONVERT') && (
                    <Button size="small" variant="contained" onClick={() => setConverting(r)}>
                      Convert
                    </Button>
                  )}
                </TableCell>
              </TableRow>
            ))}
            {!isLoading && rows.length === 0 && (
              <TableRow>
                <TableCell colSpan={6} sx={{ color: 'text.secondary' }}>
                  No approved RFQs are waiting for conversion.
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </TableContainer>
      {converting && (
        <CommentDialog
          title={`Convert ${converting.rfqNumber} to an NPD project`}
          label="Comment (optional)"
          required={false}
          confirmLabel="Convert"
          busy={converted.isLoading}
          error={converted.error}
          onConfirm={(comment) => void doConvert(comment)}
          onClose={() => setConverting(null)}
        />
      )}
    </Stack>
  )
}
