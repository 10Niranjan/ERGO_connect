import {
  Alert,
  Chip,
  LinearProgress,
  Link,
  MenuItem,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TablePagination,
  TableRow,
  TextField,
  ToggleButton,
  ToggleButtonGroup,
  Typography,
} from '@mui/material'
import { Fragment, useState } from 'react'
import { Link as RouterLink, useNavigate } from 'react-router'
import { useAppSelector } from '../../../app/store'
import { problemMessage } from '../../../shared/problem'
import { useCompaniesQuery } from '../../companies/companiesApi'
import { VISIBILITY, formatDate } from '../../rfq/labels'
import {
  useDocumentCategoriesQuery,
  useDocumentLibraryQuery,
  type ApprovalStatus,
  type DocumentLinkedModule,
  type LibraryRow,
} from '../documentsApi'
import { APPROVAL_STATUS, LINKED_MODULE_LABEL, LOCK_STATUS, linkedRecordLabel, linkedRecordPath } from '../labels'

type View = 'table' | 'module' | 'record'

/** The start/end of a local calendar day as an instant, which is what the API filters on. */
const dayStart = (value: string) => (value ? new Date(`${value}T00:00:00`).toISOString() : undefined)
const dayEnd = (value: string) => (value ? new Date(`${value}T23:59:59.999`).toISOString() : undefined)

function groupRows(rows: LibraryRow[], view: View): { key: string; label: string; rows: LibraryRow[] }[] {
  if (view === 'table') return [{ key: 'all', label: '', rows }]
  const groups = new Map<string, { key: string; label: string; rows: LibraryRow[] }>()
  for (const row of rows) {
    const key = view === 'module' ? row.linkedModule : `${row.linkedModule}:${row.linkedRecordId}`
    const label = view === 'module' ? LINKED_MODULE_LABEL[row.linkedModule] : linkedRecordLabel(row.linkedModule, row.linkedRecordId)
    const group = groups.get(key) ?? { key, label, rows: [] }
    group.rows.push(row)
    groups.set(key, group)
  }
  return [...groups.values()]
}

// SRS §12.11.1. Upload stays on each owning module's own screen; this is the cross-module read, filter and
// manage surface. The backend scopes every row (record scope + visibility); nothing here hides data by itself.
export function DocumentLibraryPage() {
  const companyType = useAppSelector((state) => state.auth.session?.user.companyType)
  const isErgo = companyType === 'ERGO'
  const navigate = useNavigate()
  const [view, setView] = useState<View>('table')
  const [linkedModule, setLinkedModule] = useState('')
  const [categoryId, setCategoryId] = useState('')
  const [approvalStatus, setApprovalStatus] = useState('')
  const [uploaderCompanyId, setUploaderCompanyId] = useState('')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(25)

  const { data: categories = [] } = useDocumentCategoriesQuery()
  // Uploader filter: only meaningful to ERGO (the backend ignores it for external callers, so it is not offered to them).
  const { data: companies = [] } = useCompaniesQuery(undefined, { skip: !isErgo })
  const { data, isLoading, isFetching, error } = useDocumentLibraryQuery(
    {
      linkedModule: linkedModule || undefined,
      categoryId: categoryId ? Number(categoryId) : undefined,
      approvalStatus: (approvalStatus || undefined) as ApprovalStatus | undefined,
      uploaderCompanyId: isErgo && uploaderCompanyId ? Number(uploaderCompanyId) : undefined,
      from: dayStart(from),
      to: dayEnd(to),
      page,
      size,
    },
    // Files are uploaded from the RFQ, quote, OFP and Quality screens, which this screen's cache cannot see.
    { refetchOnMountOrArgChange: true },
  )
  const rows = data?.items ?? []
  const groups = groupRows(rows, view)

  // Any filter change goes back to the first page.
  const filter = (set: (v: string) => void) => (value: string) => {
    set(value)
    setPage(0)
  }

  return (
    <Stack spacing={2}>
      <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap' }} spacing={2} useFlexGap>
        <Typography variant="h5" component="h1">
          Documents
        </Typography>
        <ToggleButtonGroup size="small" exclusive value={view} onChange={(_e, v: View | null) => v && setView(v)} aria-label="Library view">
          <ToggleButton value="table">Table</ToggleButton>
          <ToggleButton value="module">By module</ToggleButton>
          <ToggleButton value="record">By linked record</ToggleButton>
        </ToggleButtonGroup>
      </Stack>
      <Typography variant="body2" color="text.secondary">
        Files are added from the screen they belong to (an RFQ, a quote, a shipment or a CAPA action). Open one here to see its versions, or to manage it.
      </Typography>
      <Stack direction="row" spacing={2} sx={{ alignItems: 'center', flexWrap: 'wrap' }} useFlexGap>
        <TextField select size="small" label="Module" value={linkedModule} onChange={(e) => filter(setLinkedModule)(e.target.value)} sx={{ minWidth: 200 }}>
          <MenuItem value="">All</MenuItem>
          {(Object.keys(LINKED_MODULE_LABEL) as DocumentLinkedModule[]).map((m) => (
            <MenuItem key={m} value={m}>
              {LINKED_MODULE_LABEL[m]}
            </MenuItem>
          ))}
        </TextField>
        <TextField select size="small" label="Category" value={categoryId} onChange={(e) => filter(setCategoryId)(e.target.value)} sx={{ minWidth: 180 }}>
          <MenuItem value="">All</MenuItem>
          {categories.map((c) => (
            <MenuItem key={c.id} value={String(c.id)}>
              {c.name}
            </MenuItem>
          ))}
        </TextField>
        <TextField select size="small" label="Status" value={approvalStatus} onChange={(e) => filter(setApprovalStatus)(e.target.value)} sx={{ minWidth: 180 }}>
          <MenuItem value="">All</MenuItem>
          {(Object.keys(APPROVAL_STATUS) as ApprovalStatus[]).map((s) => (
            <MenuItem key={s} value={s}>
              {APPROVAL_STATUS[s].label}
            </MenuItem>
          ))}
        </TextField>
        {isErgo && (
          <TextField select size="small" label="Uploaded by" value={uploaderCompanyId} onChange={(e) => filter(setUploaderCompanyId)(e.target.value)} sx={{ minWidth: 220 }}>
            <MenuItem value="">Anyone</MenuItem>
            {companies.map((c) => (
              <MenuItem key={c.id} value={String(c.id)}>
                {c.profile.name}
              </MenuItem>
            ))}
          </TextField>
        )}
        <TextField size="small" type="date" label="Uploaded from" value={from} onChange={(e) => filter(setFrom)(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
        <TextField size="small" type="date" label="Uploaded to" value={to} onChange={(e) => filter(setTo)(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
      </Stack>
      {error && <Alert severity="error">{problemMessage(error)}</Alert>}
      <TableContainer component={Paper} variant="outlined">
        {(isLoading || isFetching) && <LinearProgress />}
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>Document</TableCell>
              <TableCell>Category</TableCell>
              <TableCell>Linked module</TableCell>
              <TableCell>Linked record</TableCell>
              <TableCell>Version</TableCell>
              <TableCell>Uploaded by</TableCell>
              <TableCell>Uploaded</TableCell>
              <TableCell>Status</TableCell>
              <TableCell>Visible to</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {groups.map((group) => (
              <Fragment key={group.key}>
                {view !== 'table' && (
                  <TableRow>
                    <TableCell colSpan={9} sx={{ bgcolor: 'action.hover', fontWeight: 600 }}>
                      {group.label} ({group.rows.length})
                    </TableCell>
                  </TableRow>
                )}
                {group.rows.map((r) => {
                  const status = APPROVAL_STATUS[r.approvalStatus]
                  const path = linkedRecordPath(r.linkedModule, r.linkedRecordId)
                  return (
                    <TableRow key={r.id} hover sx={{ cursor: 'pointer' }} onClick={() => navigate(`/documents/${r.id}`)}>
                      <TableCell>{r.fileName}</TableCell>
                      <TableCell>{r.category ?? '—'}</TableCell>
                      <TableCell>{LINKED_MODULE_LABEL[r.linkedModule]}</TableCell>
                      <TableCell>
                        {path ? (
                          <Link component={RouterLink} to={path} onClick={(e) => e.stopPropagation()}>
                            {linkedRecordLabel(r.linkedModule, r.linkedRecordId)}
                          </Link>
                        ) : (
                          linkedRecordLabel(r.linkedModule, r.linkedRecordId)
                        )}
                      </TableCell>
                      <TableCell>
                        v{r.versionNo}
                        {r.hasLaterVersion && ' (superseded)'}
                      </TableCell>
                      <TableCell>{r.uploadedBy ?? '—'}</TableCell>
                      <TableCell>{formatDate(r.createdAt)}</TableCell>
                      <TableCell>
                        <Stack direction="row" spacing={0.5}>
                          <Chip size="small" label={status.label} color={status.color} />
                          {r.lockStatus === 'LOCKED' && <Chip size="small" variant="outlined" label={LOCK_STATUS.LOCKED} />}
                        </Stack>
                      </TableCell>
                      <TableCell>{VISIBILITY[r.visibility]}</TableCell>
                    </TableRow>
                  )
                })}
              </Fragment>
            ))}
            {!isLoading && rows.length === 0 && (
              <TableRow>
                <TableCell colSpan={9} sx={{ color: 'text.secondary' }}>
                  No documents match.
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
        <TablePagination
          component="div"
          count={data?.totalElements ?? 0}
          page={page}
          rowsPerPage={size}
          rowsPerPageOptions={[10, 25, 50, 100]}
          onPageChange={(_e, p) => setPage(p)}
          onRowsPerPageChange={(e) => {
            setSize(Number(e.target.value))
            setPage(0)
          }}
        />
      </TableContainer>
    </Stack>
  )
}
