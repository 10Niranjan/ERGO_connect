import { Alert, Button, Chip, LinearProgress, Paper, Stack, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Typography } from '@mui/material'
import { useState } from 'react'
import { useNavigate } from 'react-router'
import { SAMPLE_PO_RAISE, useCan } from '@/features/permissions'
import { useAppSelector } from '@/hooks/useAppSelector'
import { problemMessage } from '../../../shared/problem'
import { formatDate } from '../../rfq/labels'
import { EditSamplePoDialog, RaiseSamplePoDialog } from '../components/SamplePoDialogs'
import { SAMPLE_PO_STATUS } from '../labels'
import { useEligiblePartsQuery, useSamplePosQuery, type EligiblePart, type SamplePoView } from '../services/samplePoApi'

// Flow doc steps 6-7. The customer raises a Sample PO for each approved part; the ERGO KAM decides it (open one to act).
export function SamplePoListPage() {
  const navigate = useNavigate()
  const canRaise = useCan(SAMPLE_PO_RAISE)
  const { data: rows = [], isLoading, error } = useSamplePosQuery()
  // Only the customer's NPD owner role may raise; the endpoint refuses everyone else, so it is not asked.
  const { data: parts = [], error: partsError } = useEligiblePartsQuery(undefined, { skip: !canRaise })
  const [raising, setRaising] = useState<EligiblePart | null>(null)
  const [editing, setEditing] = useState<SamplePoView | null>(null)
  const showCustomer = useAppSelector((state) => state.auth.session?.user.companyType) === 'ERGO'

  return (
    <Stack spacing={2}>
      <Typography variant="h5" component="h1">
        Sample POs
      </Typography>
      <Typography variant="body2" color="text.secondary">
        {canRaise
          ? 'Once ERGO sends you a quotation you approve, raise a Sample PO for the part. Your ERGO account manager reviews it and starts the sample project.'
          : 'Sample POs raised by customers for approved parts. Accepting one creates the sample development project.'}
      </Typography>
      {error && <Alert severity="error">{problemMessage(error)}</Alert>}
      {partsError && <Alert severity="error">{problemMessage(partsError)}</Alert>}

      {canRaise && (
        <>
          <Typography variant="h6" component="h2">
            Approved parts without a Sample PO
          </Typography>
          <TableContainer component={Paper} variant="outlined">
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Quotation</TableCell>
                  <TableCell>Part</TableCell>
                  <TableCell>Approved</TableCell>
                  <TableCell align="right">Actions</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {parts.map((p) => (
                  <TableRow key={p.rfqItemId} hover>
                    <TableCell>{p.rfqNumber ?? '—'}</TableCell>
                    <TableCell>{p.partNumber}</TableCell>
                    <TableCell>{formatDate(p.approvedAt)}</TableCell>
                    <TableCell align="right">
                      {p.actions.includes('RAISE') && (
                        <Button size="small" variant="contained" onClick={() => setRaising(p)}>
                          Raise Sample PO
                        </Button>
                      )}
                    </TableCell>
                  </TableRow>
                ))}
                {parts.length === 0 && (
                  <TableRow>
                    <TableCell colSpan={4} sx={{ color: 'text.secondary' }}>
                      No approved parts are waiting for a Sample PO.
                    </TableCell>
                  </TableRow>
                )}
              </TableBody>
            </Table>
          </TableContainer>
          <Typography variant="h6" component="h2">
            Your Sample POs
          </Typography>
        </>
      )}

      <TableContainer component={Paper} variant="outlined">
        {isLoading && <LinearProgress />}
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>Sample PO</TableCell>
              {showCustomer && <TableCell>Customer</TableCell>}
              <TableCell>Part</TableCell>
              <TableCell>PO number</TableCell>
              <TableCell>Qty</TableCell>
              <TableCell>Required by</TableCell>
              <TableCell>Status</TableCell>
              <TableCell>Project</TableCell>
              <TableCell align="right">Actions</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {rows.map((r) => (
              <TableRow key={r.id} hover sx={{ cursor: 'pointer' }} onClick={() => navigate(`/npd/sample-pos/${r.id}`)}>
                <TableCell>{r.samplePoNumber}</TableCell>
                {showCustomer && <TableCell>{r.customerName ?? '—'}</TableCell>}
                <TableCell>{r.partNumber}</TableCell>
                <TableCell>{r.customerPoNumber}</TableCell>
                <TableCell>{r.quantity}</TableCell>
                <TableCell>{formatDate(r.requiredDate)}</TableCell>
                <TableCell>
                  <Chip size="small" label={SAMPLE_PO_STATUS[r.status].label} color={SAMPLE_PO_STATUS[r.status].color} />
                </TableCell>
                <TableCell>{r.npdNumber ?? '—'}</TableCell>
                <TableCell align="right">
                  {r.actions.includes('EDIT') && (
                    <Button
                      size="small"
                      onClick={(e) => {
                        e.stopPropagation()
                        setEditing(r)
                      }}
                    >
                      Correct &amp; resubmit
                    </Button>
                  )}
                  {r.actions.includes('CREATE_PROJECT') && <Button size="small">Review</Button>}
                </TableCell>
              </TableRow>
            ))}
            {!isLoading && rows.length === 0 && (
              <TableRow>
                <TableCell colSpan={showCustomer ? 9 : 8} sx={{ color: 'text.secondary' }}>
                  No Sample POs yet.
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </TableContainer>

      {raising && <RaiseSamplePoDialog part={raising} onClose={() => setRaising(null)} />}
      {editing && <EditSamplePoDialog samplePo={editing} onClose={() => setEditing(null)} />}
    </Stack>
  )
}
