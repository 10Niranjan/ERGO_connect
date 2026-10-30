import { Alert, Button, Chip, LinearProgress, Paper, Stack, Table, TableBody, TableCell, TableContainer, TableHead, TablePagination, TableRow, TextField, MenuItem, Typography } from '@mui/material'
import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router'
import { PRODUCTION_PO_RAISE, useCan } from '@/features/permissions'
import { useAppSelector } from '@/hooks/useAppSelector'
import { problemMessage } from '../../../shared/problem'
import { formatDate } from '../../rfq/labels'
import { EditProductionPoDialog, RaiseProductionPoDialog } from '../components/ProductionPoDialogs'
import { PRODUCTION_PO_STATUS } from '../utils/labels'
import { useEligibleProjectsQuery, useProductionPosQuery, type EligibleProject, type ProductionPoView } from '../services/productionPoApi'

const PAGE_SIZES = [25, 50, 100]

// Flow doc step 11. The customer releases a production order for each approved sample; the ERGO KAM decides it (open one to act).
export function ProductionPoListPage() {
  const navigate = useNavigate()
  const canRaise = useCan(PRODUCTION_PO_RAISE)
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(PAGE_SIZES[0])
  const [status, setStatus] = useState('')
  const [search, setSearch] = useState('')
  const [q, setQ] = useState('') // what the server is asked, a moment after typing stops
  const { data, isLoading, error } = useProductionPosQuery({ page, size, status, q })
  const rows = data?.items ?? []
  const total = data?.total ?? 0
  // Only the customer's OFP owner role may release; the endpoint refuses everyone else, so it is not asked.
  const { data: projects = [], error: projectsError } = useEligibleProjectsQuery(undefined, { skip: !canRaise })
  const [raising, setRaising] = useState<EligibleProject | null>(null)
  const [editing, setEditing] = useState<ProductionPoView | null>(null)
  const showCustomer = useAppSelector((state) => state.auth.session?.user.companyType) === 'ERGO'

  useEffect(() => {
    const timer = window.setTimeout(() => {
      setQ(search.trim())
      setPage(0)
    }, 300)
    return () => window.clearTimeout(timer)
  }, [search])

  // If rows vanished from under us (a filter, another user), fall back to the last page that still exists.
  useEffect(() => {
    if (data && data.items.length === 0 && data.total > 0 && page > 0) setPage(Math.max(Math.ceil(data.total / size) - 1, 0))
  }, [data, page, size])

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

      <Stack direction="row" spacing={2}>
        <TextField size="small" label="Search" value={search} onChange={(e) => setSearch(e.target.value)} sx={{ minWidth: 260 }} />
        <TextField
          select
          size="small"
          label="Status"
          value={status}
          onChange={(e) => {
            setStatus(e.target.value)
            setPage(0)
          }}
          sx={{ minWidth: 200 }}
        >
          <MenuItem value="">All</MenuItem>
          {Object.keys(PRODUCTION_PO_STATUS).map((s) => (
            <MenuItem key={s} value={s}>
              {PRODUCTION_PO_STATUS[s as keyof typeof PRODUCTION_PO_STATUS].label}
            </MenuItem>
          ))}
        </TextField>
      </Stack>
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
                  No production POs found.
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
        <TablePagination
          component="div"
          count={total}
          page={page}
          rowsPerPage={size}
          rowsPerPageOptions={PAGE_SIZES}
          onPageChange={(_e, p) => setPage(p)}
          onRowsPerPageChange={(e) => {
            setSize(Number(e.target.value))
            setPage(0)
          }}
        />
      </TableContainer>

      {raising && <RaiseProductionPoDialog project={raising} onClose={() => setRaising(null)} />}
      {editing && <EditProductionPoDialog productionPo={editing} onClose={() => setEditing(null)} />}
    </Stack>
  )
}
