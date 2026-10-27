import { Alert, Button, Chip, LinearProgress, Paper, Stack, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Typography } from '@mui/material'
import { useState } from 'react'
import { useNavigate } from 'react-router'
import { PRODUCTION_PO_RAISE, useCan } from '@/features/permissions'
import { useAppSelector } from '@/hooks/useAppSelector'
import { problemMessage } from '../../../shared/problem'
import { formatDate } from '../../rfq/labels'
import { EditProductionPoDialog, RaiseProductionPoDialog } from '../components/ProductionPoDialogs'
import { PRODUCTION_PO_STATUS } from '../utils/labels'
import { useEligibleProjectsQuery, useProductionPosQuery, type EligibleProject, type ProductionPoView } from '../services/productionPoApi'

// Flow doc step 11. The customer releases a production order for each approved sample; the ERGO KAM decides it (open one to act).
export function ProductionPoListPage() {
  const navigate = useNavigate()
  const canRaise = useCan(PRODUCTION_PO_RAISE)
  const { data: rows = [], isLoading, error } = useProductionPosQuery()
  // Only the customer's OFP owner role may release; the endpoint refuses everyone else, so it is not asked.
  const { data: projects = [], error: projectsError } = useEligibleProjectsQuery(undefined, { skip: !canRaise })
  const [raising, setRaising] = useState<EligibleProject | null>(null)
  const [editing, setEditing] = useState<ProductionPoView | null>(null)
  const showCustomer = useAppSelector((state) => state.auth.session?.user.companyType) === 'ERGO'

  return (
    <Stack spacing={2}>
      <Typography variant="h5" component="h1">
        Production POs
      </Typography>
      <Typography variant="body2" color="text.secondary">
        {canRaise
          ? 'Once a sample is approved, release a production order for the part. Your ERGO account manager reviews it and starts the OFP project.'
          : 'Production orders released by customers for approved samples. Accepting one creates the OFP project.'}
      </Typography>
      {error && <Alert severity="error">{problemMessage(error)}</Alert>}
      {projectsError && <Alert severity="error">{problemMessage(projectsError)}</Alert>}

      {canRaise && (
        <>
          <Typography variant="h6" component="h2">
            Approved samples without a production order
          </Typography>
          <TableContainer component={Paper} variant="outlined">
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Sample project</TableCell>
                  <TableCell>Part</TableCell>
                  <TableCell align="right">Actions</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {projects.map((p) => (
                  <TableRow key={p.projectId} hover>
                    <TableCell>{p.npdNumber}</TableCell>
                    <TableCell>
                      {p.partNumber}
                      {p.revision ? ` rev ${p.revision}` : ''}
                    </TableCell>
                    <TableCell align="right">
                      {p.actions.includes('RAISE') && (
                        <Button size="small" variant="contained" onClick={() => setRaising(p)}>
                          Release production PO
                        </Button>
                      )}
                    </TableCell>
                  </TableRow>
                ))}
                {projects.length === 0 && (
                  <TableRow>
                    <TableCell colSpan={3} sx={{ color: 'text.secondary' }}>
                      No approved samples are waiting for a production order.
                    </TableCell>
                  </TableRow>
                )}
              </TableBody>
            </Table>
          </TableContainer>
          <Typography variant="h6" component="h2">
            Your production POs
          </Typography>
        </>
      )}

      <TableContainer component={Paper} variant="outlined">
        {isLoading && <LinearProgress />}
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>Production PO</TableCell>
              {showCustomer && <TableCell>Customer</TableCell>}
              <TableCell>Part</TableCell>
              <TableCell>PO number</TableCell>
              <TableCell>Qty</TableCell>
              <TableCell>Required by</TableCell>
              <TableCell>Status</TableCell>
              <TableCell>OFP project</TableCell>
              <TableCell align="right">Actions</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {rows.map((r) => (
              <TableRow key={r.id} hover sx={{ cursor: 'pointer' }} onClick={() => navigate(`/ofp/production-pos/${r.id}`)}>
                <TableCell>{r.productionPoNumber}</TableCell>
                {showCustomer && <TableCell>{r.customerName ?? '—'}</TableCell>}
                <TableCell>{r.partNumber}</TableCell>
                <TableCell>{r.customerPoNumber}</TableCell>
                <TableCell>{r.quantity}</TableCell>
                <TableCell>{formatDate(r.requiredDate)}</TableCell>
                <TableCell>
                  <Chip size="small" label={PRODUCTION_PO_STATUS[r.status].label} color={PRODUCTION_PO_STATUS[r.status].color} />
                </TableCell>
                <TableCell>{r.projectNumber ?? '—'}</TableCell>
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
                  No production POs yet.
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </TableContainer>

      {raising && <RaiseProductionPoDialog project={raising} onClose={() => setRaising(null)} />}
      {editing && <EditProductionPoDialog productionPo={editing} onClose={() => setEditing(null)} />}
    </Stack>
  )
}
