import DeleteOutlineIcon from '@mui/icons-material/DeleteOutlined'
import ExpandLessIcon from '@mui/icons-material/ExpandLess'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import {
  Alert,
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  IconButton,
  LinearProgress,
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
import { useState } from 'react'
import { Link as RouterLink, useNavigate, useParams } from 'react-router'
import { useAppSelector } from '../../../app/store'
import { saveObjectUrl } from '../../../shared/download'
import { FormSection as Section } from '../../../shared/components/FormSection'
import { num, str, text } from '../../../shared/form'
import { useMasterOptionsQuery, type MasterOption } from '../../../shared/masterData'
import { problemMessage } from '../../../shared/problem'
import { useCompaniesQuery } from '../../companies/companiesApi'
import { AttachmentCell } from '../components/AttachmentCell'
import { ProcessFlowTable } from '../components/ProcessFlowTable'
import { EMPTY_STEP, toProcessSteps, toStepRows, type StepRow } from '../processFlow'
import { PRIORITIES, formatDate } from '../labels'
import {
  useAttachmentRulesQuery,
  useCreateRfqMutation,
  useDeleteRfqDocumentMutation,
  useDownloadRfqDocumentMutation,
  useRfqCommandMutation,
  useRfqQuery,
  useUpdateRfqMutation,
  useUploadItemFilesMutation,
  type AttachmentRule,
  type FileKind,
  type ItemFile,
  type ItemForm,
  type Priority,
  type RfqForm,
  type RfqView,
} from '../rfqApi'

/** The request-wide fields. The ERGO-only ones (priority, payment terms, notes) are not shown to customers. */
interface HeaderState {
  customerCompanyId: string
  title: string
  targetQuoteDate: string
  currencyId: string
  description: string
  specialInstructions: string
  priority: Priority
  paymentTermId: string
  customerRemarks: string
  internalNotes: string
}

type PartFields = Exclude<keyof ItemForm, 'id' | 'processSteps'>

const KINDS: { kind: FileKind; label: string }[] = [
  { kind: 'DRAWING_2D', label: '2D Drawing' },
  { kind: 'MODEL_3D', label: '3D Models' },
  { kind: 'SPECIFICATION', label: 'Specifications' },
  { kind: 'ADDITIONAL', label: 'Additional Data File' },
]

type Slots<T> = Record<FileKind, T>

/** One part row. `id` is kept for a part that already exists, so its quotes and files stay with it. */
type PartState = Record<PartFields, string> & {
  id: number | null
  key: number
  steps: StepRow[]
  existing: Slots<ItemFile[]>
  pending: Slots<File[]>
}

let nextKey = 1

const noFiles = <T,>(): Slots<T[]> => ({ DRAWING_2D: [], MODEL_3D: [], SPECIFICATION: [], ADDITIONAL: [] })

function emptyPart(): PartState {
  return {
    id: null,
    key: nextKey++,
    partNumber: '',
    revision: '',
    description: '',
    eau: '',
    netWeightKg: '',
    grossWeightKg: '',
    materialId: '',
    materialSpec: '',
    coatingId: '',
    coatingSpec: '',
    targetToolingCost: '',
    targetPpapPiecePrice: '',
    targetProductionPiecePrice: '',
    materialLeadTimeWeeks: '',
    toolingLeadTimeDays: '',
    sampleLeadTimeDays: '',
    productionLeadTimeDays: '',
    moq: '',
    remarks: '',
    steps: [{ ...EMPTY_STEP }, { ...EMPTY_STEP }],
    existing: noFiles<ItemFile>(),
    pending: noFiles<File>(),
  }
}

function headerFromView(view: RfqView | undefined): HeaderState {
  return {
    customerCompanyId: view?.audience === 'ERGO' ? String(view.customer.id) : '',
    title: text(view?.title),
    targetQuoteDate: text(view?.targetQuoteDate),
    currencyId: text(view?.currency.id),
    description: text(view?.description),
    specialInstructions: text(view?.specialInstructions),
    priority: view?.audience === 'ERGO' ? view.priority : 'NORMAL',
    paymentTermId: view?.audience === 'ERGO' ? text(view.paymentTerm?.id) : '',
    customerRemarks: text(view?.customerRemarks),
    internalNotes: view?.audience === 'ERGO' ? text(view.internalNotes) : '',
  }
}

function partsFromView(view: RfqView | undefined): PartState[] {
  if (!view) return [emptyPart()]
  if (view.audience === 'CUSTOMER') {
    // The customer's own view carries only what its form has; the rest stays as ERGO set it (the server keeps it).
    return view.items.map((i) => {
      const existing = noFiles<ItemFile>()
      i.files.forEach((f) => existing[f.kind].push(f))
      return {
        ...emptyPart(),
        id: i.id,
        partNumber: text(i.partNumber),
        description: text(i.description),
        eau: text(i.eau),
        targetProductionPiecePrice: text(i.targetPrice),
        remarks: text(i.remarks),
        existing,
      }
    })
  }
  return view.items.map((i) => {
    const existing = noFiles<ItemFile>()
    i.files.forEach((f) => existing[f.kind].push(f))
    return {
      id: i.id,
      key: nextKey++,
      partNumber: text(i.partNumber),
      revision: text(i.revision),
      description: text(i.description),
      eau: text(i.eau),
      netWeightKg: text(i.netWeightKg),
      grossWeightKg: text(i.grossWeightKg),
      materialId: text(i.material?.id),
      materialSpec: text(i.materialSpec),
      coatingId: text(i.coating?.id),
      coatingSpec: text(i.coatingSpec),
      targetToolingCost: text(i.targets.targetToolingCost),
      targetPpapPiecePrice: text(i.targets.targetPpapPiecePrice),
      targetProductionPiecePrice: text(i.targets.targetProductionPiecePrice),
      materialLeadTimeWeeks: text(i.targets.materialLeadTimeWeeks),
      toolingLeadTimeDays: text(i.targets.toolingLeadTimeDays),
      sampleLeadTimeDays: text(i.targets.sampleLeadTimeDays),
      productionLeadTimeDays: text(i.targets.productionLeadTimeDays),
      moq: text(i.targets.moq),
      remarks: text(i.remarks),
      steps: toStepRows(i.processSteps.map((s) => ({ ...s, processId: s.process?.id ?? null }))),
      existing,
      pending: noFiles<File>(),
    }
  })
}

function toForm(header: HeaderState, parts: PartState[]): RfqForm {
  return {
    customerCompanyId: num(header.customerCompanyId),
    title: str(header.title),
    description: str(header.description),
    specialInstructions: str(header.specialInstructions),
    targetQuoteDate: str(header.targetQuoteDate),
    priority: header.priority,
    currencyId: num(header.currencyId),
    paymentTermId: num(header.paymentTermId),
    customerRemarks: str(header.customerRemarks),
    internalNotes: str(header.internalNotes),
    items: parts.map((p) => ({
      id: p.id,
      partNumber: p.partNumber.trim(),
      revision: str(p.revision),
      description: str(p.description),
      eau: num(p.eau),
      netWeightKg: num(p.netWeightKg),
      grossWeightKg: num(p.grossWeightKg),
      materialId: num(p.materialId),
      materialSpec: str(p.materialSpec),
      coatingId: num(p.coatingId),
      coatingSpec: str(p.coatingSpec),
      targetToolingCost: num(p.targetToolingCost),
      targetPpapPiecePrice: num(p.targetPpapPiecePrice),
      targetProductionPiecePrice: num(p.targetProductionPiecePrice),
      materialLeadTimeWeeks: num(p.materialLeadTimeWeeks),
      toolingLeadTimeDays: num(p.toolingLeadTimeDays),
      sampleLeadTimeDays: num(p.sampleLeadTimeDays),
      productionLeadTimeDays: num(p.productionLeadTimeDays),
      moq: num(p.moq),
      remarks: str(p.remarks),
      processSteps: toProcessSteps(p.steps),
    })),
  }
}

/** Today as the date input writes it (local time, yyyy-mm-dd). */
const today = () => new Date().toLocaleDateString('en-CA')

/** New RFQ (no id) or edit (id). */
export function RfqEditPage() {
  const { id } = useParams()
  const { data: view, isLoading, error } = useRfqQuery(Number(id), { skip: id === undefined })
  // Whether the RFQ may be edited is decided when the page opens: saving or submitting refreshes it into a read-only
  // state, and that must not replace the form (and its confirmation) underneath the user.
  const [openedEditable, setOpenedEditable] = useState<boolean | null>(null)
  if (view && openedEditable === null) setOpenedEditable(view.actions.includes('EDIT'))
  if (id !== undefined && isLoading) return <LinearProgress />
  if (error) return <Alert severity="error">{problemMessage(error)}</Alert>
  if (view && openedEditable === false) {
    return (
      <Stack spacing={2} sx={{ maxWidth: 800 }}>
        <Alert severity="info">{view.number} can no longer be edited. It is read-only unless ERGO asks for a clarification.</Alert>
        <Box>
          <Button component={RouterLink} to={`/rfqs/${view.id}`}>
            Back to the RFQ
          </Button>
        </Box>
      </Stack>
    )
  }
  return <RfqFormEditor key={view?.id ?? 'new'} view={view} />
}

function RfqFormEditor({ view }: { view: RfqView | undefined }) {
  const isErgo = useAppSelector((state) => state.auth.session?.user.companyType === 'ERGO')
  const navigate = useNavigate()
  const [header, setHeader] = useState<HeaderState>(() => headerFromView(view))
  const [parts, setParts] = useState<PartState[]>(() => partsFromView(view))
  const [rfqId, setRfqId] = useState<number | null>(view?.id ?? null)
  const [removed, setRemoved] = useState<number[]>([]) // stored files to delete when the RFQ is saved
  const [expanded, setExpanded] = useState<number[]>([])
  const [busy, setBusy] = useState(false)
  const [progress, setProgress] = useState<string | null>(null)
  const [problems, setProblems] = useState<string[]>([])
  const [failure, setFailure] = useState<unknown>(null)
  const [rejections, setRejections] = useState<string[]>([])
  const [submittedNumber, setSubmittedNumber] = useState<string | null>(null)
  const [createRfq] = useCreateRfqMutation()
  const [updateRfq] = useUpdateRfqMutation()
  const [rfqCommand] = useRfqCommandMutation()
  const [uploadItemFiles] = useUploadItemFilesMutation()
  const [deleteDocument] = useDeleteRfqDocumentMutation()
  const [downloadDocument] = useDownloadRfqDocumentMutation()
  const { data: rules } = useAttachmentRulesQuery()
  const { data: companies = [] } = useCompaniesQuery(undefined, { skip: !isErgo || view !== undefined })
  const lists = [
    useMasterOptionsQuery('materials'),
    useMasterOptionsQuery('coatings'),
    useMasterOptionsQuery('currencies'),
    useMasterOptionsQuery('payment-terms'),
    useMasterOptionsQuery('processes'),
  ]
  const [materials, coatings, currencies, paymentTerms, processes] = lists.map((list) => list.data ?? [])

  // a select cannot show a saved value before its options arrive
  if (lists.some((list) => list.isLoading)) return <LinearProgress />

  if (!view && !header.currencyId && currencies.length > 0) {
    const inr = currencies.find((c) => c.code === 'INR') ?? currencies[0]
    setHeader((h) => ({ ...h, currencyId: String(inr.id) }))
  }

  const currencyCode = currencies.find((c) => String(c.id) === header.currencyId)?.code
  const setH = (key: keyof HeaderState) => (event: { target: { value: string } }) => setHeader((h) => ({ ...h, [key]: event.target.value }))
  const updatePart = (key: number, patch: Partial<PartState>) => setParts((list) => list.map((p) => (p.key === key ? { ...p, ...patch } : p)))
  const customers = companies.filter((c) => c.type === 'CUSTOMER' && c.status === 'ACTIVE')
  const ruleOf = (kind: FileKind) => rules?.find((r) => r.kind === kind)

  /** What is wrong with the form: a draft only needs the basics, a submission everything the client's format requires. */
  function check(forSubmit: boolean): string[] {
    const found: string[] = []
    if (isErgo && !view && !rfqId && !header.customerCompanyId) found.push('Choose the customer')
    if (!header.currencyId) found.push('Choose the quote currency')
    parts.forEach((p, i) => {
      if (!p.partNumber.trim()) found.push(`Part ${i + 1}: enter the part number`)
    })
    const partNumbers = parts.map((p) => p.partNumber.trim().toLowerCase()).filter(Boolean)
    if (new Set(partNumbers).size !== partNumbers.length) found.push('A part number is listed twice')
    if (forSubmit) {
      if (!header.title.trim()) found.push('Enter the RFQ title')
      if (!header.targetQuoteDate) found.push('Enter the expected quote receipt date')
      else if (header.targetQuoteDate < today()) found.push('The expected quote receipt date cannot be in the past')
      parts.forEach((p, i) => {
        if (!(Number(p.eau) > 0)) found.push(`Part ${i + 1}${p.partNumber.trim() ? ` (${p.partNumber.trim()})` : ''}: enter a positive annual volume`)
      })
    }
    parts.forEach((p, i) => {
      if (p.targetProductionPiecePrice.trim() && Number(p.targetProductionPiecePrice) < 0) found.push(`Part ${i + 1}: the target price cannot be negative`)
    })
    return found
  }

  /**
   * Saves the RFQ, then its files (they need the RFQ's and the parts' ids), then submits when asked. Each step
   * waits for the one before, so a submission never goes out with an upload still pending. If a step fails the RFQ
   * stays a draft and what is already saved is kept: the next save updates it, it does not create a second one.
   */
  async function persist(submit: boolean) {
    if (busy) return // no double submission
    const found = check(submit)
    setProblems(found)
    setFailure(null)
    if (found.length > 0) return
    setBusy(true)
    try {
      const form = toForm(header, parts)
      const saved = rfqId ? await updateRfq({ id: rfqId, form }).unwrap() : await createRfq(form).unwrap()
      setRfqId(saved.id)
      // the saved parts come back in the form's order: line each row up with its id
      const withIds = parts.map((p, i) => ({ ...p, id: saved.items[i].id }))
      setParts(withIds)

      for (const documentId of removed) {
        try {
          await deleteDocument({ id: saved.id, documentId }).unwrap()
        } catch (e) {
          if ((e as { status?: number }).status !== 404) throw e // already gone with its removed part
        }
      }
      setRemoved([])

      for (const part of withIds) {
        for (const { kind, label } of KINDS) {
          const files = part.pending[kind]
          if (files.length === 0) continue
          setProgress(`Uploading ${label} for ${part.partNumber.trim()}: ${files.map((f) => f.name).join(', ')}`)
          const stored = await uploadItemFiles({ id: saved.id, itemId: part.id as number, kind, files }).unwrap()
          setParts((list) =>
            list.map((p) =>
              p.key === part.key ? { ...p, existing: { ...p.existing, [kind]: [...p.existing[kind], ...stored] }, pending: { ...p.pending, [kind]: [] } } : p,
            ),
          )
        }
      }
      setProgress(null)

      // Answering a clarification needs the written answer, which the RFQ page asks for: save, then go there.
      if (submit && view?.status !== 'CLARIFICATION_REQUIRED') {
        const done = await rfqCommand({ id: saved.id, command: 'submit' }).unwrap()
        setSubmittedNumber(done.number)
      } else {
        navigate(`/rfqs/${saved.id}`)
      }
    } catch (e) {
      setFailure(e)
    } finally {
      setBusy(false)
      setProgress(null)
    }
  }

  async function download(file: ItemFile) {
    if (rfqId == null) return
    try {
      saveObjectUrl(await downloadDocument({ id: rfqId, documentId: file.id }).unwrap(), file.fileName)
    } catch (e) {
      setFailure(e)
    }
  }

  const stickyHead = { position: 'sticky', top: 0, bgcolor: 'background.paper', zIndex: 3 } as const
  const stickyNo = { position: 'sticky', left: 0, bgcolor: 'background.paper', zIndex: 2, minWidth: 44, width: 44 } as const
  const stickyPart = { position: 'sticky', left: 44, bgcolor: 'background.paper', zIndex: 2, minWidth: 150, boxShadow: '2px 0 0 rgba(0,0,0,0.08)' } as const

  return (
    <Stack spacing={2} sx={{ maxWidth: 1400 }}>
      <Typography variant="h5" component="h1">
        {view ? `Edit ${view.number}` : 'New Request for Quotation (RFQ)'}
      </Typography>

      <Section title="Section 1 | General information" note="Fields marked * are mandatory. The quote currency applies to every target price in Section 2.">
        <TextField label="RFQ Number" value={view?.number ?? 'Auto-generated'} disabled fullWidth />
        <TextField label="RFQ Date" value={view ? formatDate(view.createdAt) : 'Auto-generated'} disabled fullWidth />
        {isErgo ? (
          view ? (
            <TextField label="Customer" value={view.audience === 'ERGO' ? view.customer.name : ''} disabled fullWidth />
          ) : (
            <TextField select label="Customer" value={header.customerCompanyId} onChange={setH('customerCompanyId')} required fullWidth>
              {customers.map((c) => (
                <MenuItem key={c.id} value={String(c.id)}>
                  {c.profile.name}
                </MenuItem>
              ))}
            </TextField>
          )
        ) : (
          <Box />
        )}
        <TextField label="RFQ Title" value={header.title} onChange={setH('title')} required fullWidth slotProps={{ htmlInput: { maxLength: 200 } }} placeholder="Enter RFQ title" />
        <TextField
          label="Expected Quote Receipt Date"
          type="date"
          value={header.targetQuoteDate}
          onChange={setH('targetQuoteDate')}
          required
          fullWidth
          slotProps={{ inputLabel: { shrink: true }, htmlInput: { min: today() } }}
        />
        <TextField select label="Quote Currency" value={header.currencyId} onChange={setH('currencyId')} required fullWidth>
          {currencies.map((c: MasterOption) => (
            <MenuItem key={c.id} value={String(c.id)}>
              {c.code ? `${c.code} – ${c.name}` : c.name}
            </MenuItem>
          ))}
        </TextField>
        <TextField
          label="RFQ Description"
          value={header.description}
          onChange={setH('description')}
          multiline
          minRows={3}
          fullWidth
          sx={{ gridColumn: '1 / -1' }}
          slotProps={{ htmlInput: { maxLength: 4000 } }}
          placeholder="Enter description"
        />
        <TextField
          label="Special Instructions"
          value={header.specialInstructions}
          onChange={setH('specialInstructions')}
          multiline
          minRows={3}
          fullWidth
          sx={{ gridColumn: '1 / -1' }}
          slotProps={{ htmlInput: { maxLength: 4000 } }}
          placeholder="Enter instructions"
        />
      </Section>

      <Paper variant="outlined" sx={{ p: 2 }}>
        <Typography variant="h6" component="h2">
          Section 2 | Part details
        </Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
          One row per part. Scroll sideways to reach every column; the part number stays in view. The file columns take files only, several per cell.
        </Typography>
        <TableContainer sx={{ maxHeight: '70vh', overflow: 'auto' }}>
          <Table size="small" stickyHeader sx={{ minWidth: 1700 }}>
            <TableHead>
              <TableRow>
                <TableCell sx={{ ...stickyHead, ...stickyNo, zIndex: 4 }}>#</TableCell>
                <TableCell sx={{ ...stickyHead, ...stickyPart, zIndex: 4 }}>Part # *</TableCell>
                <TableCell sx={{ ...stickyHead, minWidth: 220 }}>Part Description</TableCell>
                <TableCell sx={{ ...stickyHead, minWidth: 140 }}>Annual Volume (EAU) *</TableCell>
                {KINDS.map(({ kind, label }) => (
                  <TableCell key={kind} sx={{ ...stickyHead, minWidth: 190 }}>
                    {label}
                  </TableCell>
                ))}
                <TableCell sx={{ ...stickyHead, minWidth: 140 }}>Target Price{currencyCode ? ` (${currencyCode})` : ''}</TableCell>
                <TableCell sx={{ ...stickyHead, minWidth: 220 }}>Remarks</TableCell>
                <TableCell sx={{ ...stickyHead, minWidth: isErgo ? 130 : 90 }}>Action</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {parts.map((part, index) => (
                <PartRows
                  key={part.key}
                  index={index}
                  part={part}
                  isErgo={isErgo}
                  open={expanded.includes(part.key)}
                  canRemove={parts.length > 1}
                  disabled={busy}
                  stickyNo={stickyNo}
                  stickyPart={stickyPart}
                  ruleOf={ruleOf}
                  materials={materials}
                  coatings={coatings}
                  processes={processes}
                  onChange={(patch) => updatePart(part.key, patch)}
                  onToggle={() => setExpanded((list) => (list.includes(part.key) ? list.filter((k) => k !== part.key) : [...list, part.key]))}
                  onRemove={() => setParts((list) => list.filter((p) => p.key !== part.key))}
                  onRemoveExisting={(file) => {
                    setRemoved((list) => [...list, file.id])
                    updatePart(part.key, { existing: { ...part.existing, [file.kind]: part.existing[file.kind].filter((f) => f.id !== file.id) } })
                  }}
                  onDownload={(file) => void download(file)}
                  onReject={(message) => setRejections((list) => [...list, message])}
                />
              ))}
            </TableBody>
          </Table>
        </TableContainer>
        {isErgo &&
          parts.map((part, index) =>
            expanded.includes(part.key) ? (
              <ErgoPartPanel key={part.key} index={index} part={part} materials={materials} coatings={coatings} processes={processes} onChange={(patch) => updatePart(part.key, patch)} />
            ) : null,
          )}
        <Button sx={{ mt: 1.5 }} variant="outlined" onClick={() => setParts((list) => [...list, emptyPart()])} disabled={busy || parts.length >= 100}>
          + Add Another Part
        </Button>
      </Paper>

      {isErgo && (
        <Section title="ERGO only" note="Not part of the customer's form. Never shown to suppliers.">
          <TextField select label="Priority" value={header.priority} onChange={setH('priority')} fullWidth>
            {Object.entries(PRIORITIES).map(([value, label]) => (
              <MenuItem key={value} value={value}>
                {label}
              </MenuItem>
            ))}
          </TextField>
          <TextField select label="Payment terms" value={header.paymentTermId} onChange={setH('paymentTermId')} fullWidth>
            <MenuItem value="">—</MenuItem>
            {paymentTerms.map((t) => (
              <MenuItem key={t.id} value={String(t.id)}>
                {t.name}
              </MenuItem>
            ))}
          </TextField>
          <Box />
          <TextField
            label="Internal notes (ERGO only)"
            value={header.internalNotes}
            onChange={setH('internalNotes')}
            multiline
            minRows={3}
            fullWidth
            sx={{ gridColumn: '1 / -1' }}
            slotProps={{ htmlInput: { maxLength: 4000 } }}
          />
        </Section>
      )}

      {problems.length > 0 && (
        <Alert severity="warning">
          Please complete:
          <ul style={{ margin: 0, paddingLeft: 20 }}>
            {problems.map((p) => (
              <li key={p}>{p}</li>
            ))}
          </ul>
        </Alert>
      )}
      {rejections.length > 0 && (
        <Alert severity="warning" onClose={() => setRejections([])}>
          {rejections.map((r) => (
            <div key={r}>{r}</div>
          ))}
        </Alert>
      )}
      {failure != null && (
        <Alert severity="error">
          {problemMessage(failure)}
          {rfqId != null && !view && ' The RFQ was saved as a draft; press Save as Draft or Submit to continue where it stopped.'}
        </Alert>
      )}
      {busy && (
        <Box>
          <LinearProgress />
          {progress && (
            <Typography variant="caption" color="text.secondary">
              {progress}
            </Typography>
          )}
        </Box>
      )}

      <Typography variant="body2" color="text.secondary">
        Section 3 | Actions. A submitted RFQ is read-only until ERGO asks for a clarification.
      </Typography>
      <Stack direction="row" spacing={1}>
        <Button onClick={() => navigate(view ? `/rfqs/${view.id}` : rfqId ? `/rfqs/${rfqId}` : '/rfqs')} disabled={busy}>
          Cancel
        </Button>
        <Button variant="outlined" onClick={() => void persist(false)} disabled={busy}>
          {view && view.status !== 'DRAFT' ? 'Save changes' : 'Save as Draft'}
        </Button>
        {(!view || view.actions.includes('SUBMIT')) && (
          <Button variant="contained" onClick={() => void persist(true)} disabled={busy}>
            {view?.status === 'CLARIFICATION_REQUIRED' ? 'Save and answer →' : 'Submit RFQ →'}
          </Button>
        )}
      </Stack>

      <Dialog open={submittedNumber != null} maxWidth="xs" fullWidth>
        <DialogTitle>RFQ submitted</DialogTitle>
        <DialogContent>
          <Typography>Your RFQ has been submitted to ERGO.</Typography>
          <Typography variant="h6" sx={{ mt: 1 }}>
            RFQ number: {submittedNumber}
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button variant="contained" onClick={() => navigate(`/rfqs/${rfqId}`)}>
            View RFQ
          </Button>
        </DialogActions>
      </Dialog>
    </Stack>
  )
}

interface PartRowsProps {
  index: number
  part: PartState
  isErgo: boolean
  open: boolean
  canRemove: boolean
  disabled: boolean
  stickyNo: object
  stickyPart: object
  ruleOf: (kind: FileKind) => AttachmentRule | undefined
  materials: MasterOption[]
  coatings: MasterOption[]
  processes: MasterOption[]
  onChange: (patch: Partial<PartState>) => void
  onToggle: () => void
  onRemove: () => void
  onRemoveExisting: (file: ItemFile) => void
  onDownload: (file: ItemFile) => void
  onReject: (message: string) => void
}

/** One part: its table row, and for ERGO an expandable panel with the technical data ERGO adds during the review. */
function PartRows({
  index,
  part,
  isErgo,
  open,
  canRemove,
  disabled,
  stickyNo,
  stickyPart,
  ruleOf,
  onChange,
  onToggle,
  onRemove,
  onRemoveExisting,
  onDownload,
  onReject,
}: PartRowsProps) {
  const cell = (key: PartFields, extra: object = {}) => (
    <TextField size="small" value={part[key]} onChange={(e) => onChange({ [key]: e.target.value })} fullWidth disabled={disabled} {...extra} />
  )
  return (
    <>
      <TableRow hover>
        <TableCell sx={stickyNo}>{index + 1}</TableCell>
        <TableCell sx={stickyPart}>{cell('partNumber', { required: true, placeholder: 'Part #', slotProps: { htmlInput: { maxLength: 100, 'aria-label': `Part # ${index + 1}` } } })}</TableCell>
        <TableCell>{cell('description', { placeholder: 'Description', multiline: true, slotProps: { htmlInput: { maxLength: 1000, 'aria-label': `Description ${index + 1}` } } })}</TableCell>
        <TableCell>
          {cell('eau', { type: 'number', required: true, placeholder: 'Qty', slotProps: { htmlInput: { min: 1, step: 1, 'aria-label': `Annual volume ${index + 1}` } } })}
        </TableCell>
        {KINDS.map(({ kind, label }) => (
          <TableCell key={kind} sx={{ verticalAlign: 'top' }}>
            <AttachmentCell
              rule={ruleOf(kind)}
              label={label}
              disabled={disabled}
              existing={part.existing[kind]}
              pending={part.pending[kind]}
              onAdd={(files) => onChange({ pending: { ...part.pending, [kind]: [...part.pending[kind], ...files] } })}
              onRemovePending={(i) => onChange({ pending: { ...part.pending, [kind]: part.pending[kind].filter((_, n) => n !== i) } })}
              onRemoveExisting={onRemoveExisting}
              onDownload={onDownload}
              onReject={onReject}
            />
          </TableCell>
        ))}
        <TableCell>{cell('targetProductionPiecePrice', { type: 'number', placeholder: 'Amount', slotProps: { htmlInput: { min: 0, step: 'any', 'aria-label': `Target price ${index + 1}` } } })}</TableCell>
        <TableCell>{cell('remarks', { placeholder: 'Remarks', multiline: true, slotProps: { htmlInput: { maxLength: 1000, 'aria-label': `Remarks ${index + 1}` } } })}</TableCell>
        <TableCell sx={{ whiteSpace: 'nowrap' }}>
          {isErgo && (
            <IconButton size="small" onClick={onToggle} aria-label={open ? 'Hide ERGO details' : 'Show ERGO details'} title="ERGO details below the table: material, process flow and other technical data">
              {open ? <ExpandLessIcon /> : <ExpandMoreIcon />}
            </IconButton>
          )}
          <IconButton size="small" color="error" onClick={onRemove} disabled={!canRemove || disabled} aria-label={`Delete part ${index + 1}`} title="Delete">
            <DeleteOutlineIcon />
          </IconButton>
        </TableCell>
      </TableRow>
    </>
  )
}

/** What ERGO adds to a part during the review. Outside the table so it never scrolls out of view with the wide rows. */
function ErgoPartPanel({ index, part, materials, coatings, processes, onChange }: Pick<PartRowsProps, 'index' | 'part' | 'materials' | 'coatings' | 'processes' | 'onChange'>) {
  const cell = (key: PartFields, extra: object = {}) => (
    <TextField size="small" value={part[key]} onChange={(e) => onChange({ [key]: e.target.value })} fullWidth {...extra} />
  )
  return (
    <Box sx={{ mt: 2, p: 2, border: 1, borderColor: 'divider', borderRadius: 1, display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', md: 'repeat(4, 1fr)' } }}>
      <Typography variant="subtitle2" sx={{ gridColumn: '1 / -1' }}>
        ERGO details for part {index + 1}{part.partNumber.trim() ? ` (${part.partNumber.trim()})` : ''}: not part of the customer's form. Suppliers see only the material and the process route.
      </Typography>
      {cell('revision', { label: 'Revision', slotProps: { htmlInput: { maxLength: 30 } } })}
      {cell('netWeightKg', { label: 'Net weight (kg)', type: 'number', slotProps: { htmlInput: { min: 0, step: 'any' } } })}
      {cell('grossWeightKg', { label: 'Gross weight (kg)', type: 'number', slotProps: { htmlInput: { min: 0, step: 'any' } } })}
      {cell('moq', { label: 'MOQ (pieces)', type: 'number', slotProps: { htmlInput: { min: 1 } } })}
      {cell('materialId', { label: 'Material', select: true, children: optionItems(materials) })}
      {cell('materialSpec', { label: 'Material specification (if not in the list)', slotProps: { htmlInput: { maxLength: 500 } } })}
      {cell('coatingId', { label: 'Coating / surface treatment', select: true, children: optionItems(coatings) })}
      {cell('coatingSpec', { label: 'Coating specification', slotProps: { htmlInput: { maxLength: 500 } } })}
      {cell('targetToolingCost', { label: 'Target tooling cost', type: 'number', slotProps: { htmlInput: { min: 0, step: 'any' } } })}
      {cell('targetPpapPiecePrice', { label: 'Target PPAP piece price', type: 'number', slotProps: { htmlInput: { min: 0, step: 'any' } } })}
      {cell('materialLeadTimeWeeks', { label: 'Material lead time (weeks)', type: 'number', slotProps: { htmlInput: { min: 0 } } })}
      {cell('toolingLeadTimeDays', { label: 'Tooling lead time (days)', type: 'number', slotProps: { htmlInput: { min: 0 } } })}
      {cell('sampleLeadTimeDays', { label: 'Sample lead time (days)', type: 'number', slotProps: { htmlInput: { min: 0 } } })}
      {cell('productionLeadTimeDays', { label: 'Production lead time from PO (days)', type: 'number', slotProps: { htmlInput: { min: 0 } } })}
      <Box sx={{ gridColumn: '1 / -1' }}>
        <Typography variant="subtitle2" sx={{ mb: 1 }}>
          Process flow
        </Typography>
        <ProcessFlowTable steps={part.steps} processes={processes} onChange={(steps) => onChange({ steps })} />
      </Box>
    </Box>
  )
}

function optionItems(options: MasterOption[]) {
  return [
    <MenuItem key="" value="">
      —
    </MenuItem>,
    ...options.map((o) => (
      <MenuItem key={o.id} value={String(o.id)}>
        {o.code ? `${o.code} – ${o.name}` : o.name}
      </MenuItem>
    )),
  ]
}
