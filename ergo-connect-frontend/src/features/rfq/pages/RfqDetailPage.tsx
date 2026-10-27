import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  LinearProgress,
  Paper,
  Stack,
  Tab,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Tabs,
  TextField,
  Typography,
} from '@mui/material'
import { useState } from 'react'
import { useNavigate, useParams } from 'react-router'
import { QUOTATION_CUSTOMER_VIEW, QUOTATION_VIEW, useCan } from '@/features/permissions'
import { AuditTrail } from '../../../shared/components/AuditTrail'
import { CommentsPanel } from '../../communication/components/CommentsPanel'
import { CommentDialog } from '../../../shared/components/CommentDialog'
import { Facts } from '../../../shared/components/Facts'
import { problemMessage } from '../../../shared/problem'
import { CustomerQuotesPanel, ErgoQuotationsPanel } from '../../quotation/components/CustomerQuotesPanel'
import { PartsPanel } from '../../quotation/components/PartsPanel'
import { partStatusLabel } from '../../quotation/utils/labels'
import { useRfqPartsQuery } from '../../quotation/services/quoteApi'
import { DocumentsPanel } from '../components/DocumentsPanel'
import { PRIORITIES, STATUS, formatDate, formatMoney, formatNumber } from '../labels'
import {
  useCloneRfqMutation,
  useRfqCommandMutation,
  useRfqHistoryQuery,
  useRfqQuery,
  useUpdateRfqNotesMutation,
  type ItemView,
  type RfqCommand,
  type RfqView,
} from '../rfqApi'

/** How each workflow action asks for its comment (reasons are required for clarification, cancel and close). */
const COMMANDS: Record<Exclude<RfqCommand, 'start-review'>, { title: string; label: string; confirm: string; required: boolean; danger?: boolean }> = {
  submit: { title: 'Submit RFQ', label: 'Comment (optional)', confirm: 'Submit', required: false },
  'request-clarification': { title: 'Ask the customer', label: 'Question for the customer', confirm: 'Send question', required: true },
  cancel: { title: 'Cancel RFQ', label: 'Reason for cancelling', confirm: 'Cancel RFQ', required: true, danger: true },
  close: { title: 'Close RFQ', label: 'Reason for closing', confirm: 'Close RFQ', required: true },
}

export function RfqDetailPage() {
  const id = Number(useParams().id)
  const { data: rfq, isLoading, error } = useRfqQuery(id)
  if (isLoading) return <LinearProgress />
  if (!rfq) return <Alert severity="error">{problemMessage(error)}</Alert>
  return <RfqDetail rfq={rfq} />
}

function RfqDetail({ rfq }: { rfq: RfqView }) {
  const navigate = useNavigate()
  const [tab, setTab] = useState('overview')
  const [dialog, setDialog] = useState<Exclude<RfqCommand, 'start-review'> | null>(null)
  const [run, running] = useRfqCommandMutation()
  const [cloneRfq] = useCloneRfqMutation()
  const [actionError, setActionError] = useState<unknown>(null)
  const isErgo = rfq.audience === 'ERGO'
  // The role sheet: a customer role with RFQ access but none on Quotation (e.g. Customer NPD) never sees the quotations ERGO sends.
  const canSeeQuotes = useCan(QUOTATION_CUSTOMER_VIEW)
  const canSeeParts = useCan(QUOTATION_VIEW) && (isErgo || canSeeQuotes)
  const can = (action: RfqView['actions'][number]) => rfq.actions.includes(action)
  const answering = rfq.status === 'CLARIFICATION_REQUIRED' && can('SUBMIT')

  async function command(name: RfqCommand, comment?: string) {
    setActionError(null)
    try {
      await run({ id: rfq.id, command: name, comment }).unwrap()
      setDialog(null)
    } catch (e) {
      if (!dialog) setActionError(e)
    }
  }

  async function clone() {
    if (!window.confirm(`Create a new draft RFQ from ${rfq.number}?`)) return
    try {
      const copy = await cloneRfq(rfq.id).unwrap()
      navigate(`/rfqs/${copy.id}`)
    } catch (e) {
      setActionError(e)
    }
  }

  const status = STATUS[rfq.status]

  return (
    <Stack spacing={2}>
      <Stack direction={{ xs: 'column', md: 'row' }} sx={{ justifyContent: 'space-between', gap: 2 }}>
        <Stack spacing={0.5}>
          <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
            <Typography variant="h5" component="h1">
              {rfq.number}
            </Typography>
            <Chip label={status.label} color={status.color} size="small" />
          </Stack>
          <Typography color="text.secondary">
            {rfq.items.length} part{rfq.items.length === 1 ? '' : 's'}: {rfq.items.map((i) => i.partNumber).join(', ') || 'none yet'}
          </Typography>
          <Typography variant="body2" color="text.secondary">
            {isErgo && `${rfq.customer.name} · `}Quote needed by {formatDate(rfq.targetQuoteDate)} · Priority {PRIORITIES[rfq.priority]}
            {isErgo && ` · Owner ${rfq.owner ?? 'not assigned'}`}
          </Typography>
        </Stack>
        <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap', alignItems: 'flex-start' }}>
          {can('EDIT') && <Button onClick={() => navigate(`/rfqs/${rfq.id}/edit`)}>Edit</Button>}
          {can('START_REVIEW') && (
            <Button variant="contained" onClick={() => void command('start-review')} loading={running.isLoading}>
              Start review
            </Button>
          )}
          {can('REQUEST_CLARIFICATION') && <Button onClick={() => setDialog('request-clarification')}>Ask customer</Button>}
          {can('SUBMIT') && (
            <Button variant="contained" onClick={() => setDialog('submit')}>
              {answering ? 'Answer and resubmit' : 'Submit to ERGO'}
            </Button>
          )}
          {can('CLONE') && <Button onClick={() => void clone()}>Clone</Button>}
          {can('CLOSE') && <Button onClick={() => setDialog('close')}>Close RFQ</Button>}
          {can('CANCEL') && (
            <Button color="error" onClick={() => setDialog('cancel')}>
              Cancel RFQ
            </Button>
          )}
        </Stack>
      </Stack>

      {actionError != null && <Alert severity="error">{problemMessage(actionError)}</Alert>}
      {rfq.status === 'UNDER_REVIEW' && isErgo && (
        <Alert severity="info" action={<Button color="inherit" size="small" onClick={() => setTab('parts')}>Parts &amp; suppliers</Button>}>
          Review the RFQ and change it where needed, then send the whole RFQ to one supplier, or split its parts into sub-RFQs for different suppliers.
        </Alert>
      )}
      {(rfq.status === 'QUOTE_RECEIVED' || rfq.status === 'ON_HOLD') && !isErgo && canSeeQuotes && (
        <Alert severity="info" action={<Button color="inherit" size="small" onClick={() => setTab('quotes')}>Open quotation</Button>}>
          ERGO has sent you a quotation. Approve it, request a correction, put it on hold or reject it - each with a comment.
        </Alert>
      )}
      {rfq.clarificationQuestion && (
        <Alert severity={rfq.status === 'CLARIFICATION_REQUIRED' ? 'warning' : 'info'}>
          <strong>ERGO asked:</strong> {rfq.clarificationQuestion}
          {rfq.clarificationAnswer && (
            <>
              <br />
              <strong>Answer:</strong> {rfq.clarificationAnswer}
            </>
          )}
        </Alert>
      )}

      <Tabs value={tab} onChange={(_e, value: string) => setTab(value)}>
        <Tab value="overview" label="Overview" />
        <Tab value="process" label="Process flow" />
        <Tab value="documents" label="Documents" />
        {!isErgo && canSeeQuotes && <Tab value="quotes" label="Quotation" />}
        {isErgo && <Tab value="parts" label="Parts & suppliers" />}
        {isErgo && <Tab value="quotation" label="Customer quotation" />}
        {isErgo && <Tab value="history" label="Audit trail" />}
        <Tab value="comments" label="Comments" />
      </Tabs>

      {tab === 'overview' && (
        <Stack spacing={2}>
          <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', md: '1fr 1fr' } }}>
            <Facts
              title="Request"
              facts={[
                ['Quote needed by', formatDate(rfq.targetQuoteDate)],
                ['Priority', PRIORITIES[rfq.priority]],
                ['Currency of the targets', `${rfq.currency.code} – ${rfq.currency.name}`],
                ['Payment terms', rfq.paymentTerm?.name ?? '—'],
              ]}
            />
            <Facts title="Requirements and remarks" facts={[['', rfq.customerRemarks ?? '—']]} />
            {isErgo && <InternalNotes rfq={rfq} />}
          </Box>
          {canSeeParts && (isErgo || !NOT_YET_QUOTED.has(rfq.status)) && <PartStatus rfqId={rfq.id} />}
          {rfq.items.map((item) => (
            <ItemOverview key={item.id} item={item} currency={rfq.currency.code} />
          ))}
        </Stack>
      )}

      {tab === 'process' && (
        <Stack spacing={2}>
          {rfq.items.map((item) => (
            <TableContainer component={Paper} variant="outlined" key={item.id}>
              <Typography variant="subtitle1" component="h2" sx={{ p: 1.5, pb: 0 }}>
                {item.partNumber}
              </Typography>
              <Table size="small">
                <TableHead>
                  <TableRow>
                    <TableCell>#</TableCell>
                    <TableCell>Process</TableCell>
                    <TableCell>Step</TableCell>
                    <TableCell>Cycle time (s)</TableCell>
                    <TableCell>Sourcing</TableCell>
                    <TableCell>Sub-supplier</TableCell>
                    <TableCell>Remarks</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {item.processSteps.map((step, index) => (
                    <TableRow key={index}>
                      <TableCell>{index + 1}</TableCell>
                      <TableCell>{step.process?.name ?? '—'}</TableCell>
                      <TableCell>{step.stepName}</TableCell>
                      <TableCell>{formatNumber(step.cycleTimeSeconds)}</TableCell>
                      <TableCell>{step.sourcingType === 'INHOUSE' ? 'In-house' : step.sourcingType === 'OUTSOURCED' ? 'Outsourced' : '—'}</TableCell>
                      <TableCell>{step.outsourcedSupplierName ?? '—'}</TableCell>
                      <TableCell>{step.remarks ?? '—'}</TableCell>
                    </TableRow>
                  ))}
                  {item.processSteps.length === 0 && (
                    <TableRow>
                      <TableCell colSpan={7} sx={{ color: 'text.secondary' }}>
                        No process steps yet (needed before submitting).
                      </TableCell>
                    </TableRow>
                  )}
                </TableBody>
              </Table>
            </TableContainer>
          ))}
        </Stack>
      )}

      {tab === 'documents' && <DocumentsPanel rfqId={rfq.id} canUpload={can('UPLOAD')} isErgo={isErgo} />}
      {tab === 'quotes' && !isErgo && canSeeQuotes && <CustomerQuotesPanel rfqId={rfq.id} />}
      {tab === 'parts' && isErgo && <PartsPanel rfqId={rfq.id} />}
      {tab === 'quotation' && isErgo && <ErgoQuotationsPanel rfqId={rfq.id} />}
      {tab === 'history' && isErgo && <History rfqId={rfq.id} />}
      {tab === 'comments' && <CommentsPanel linkedModule="rfq" recordId={rfq.id} />}

      {dialog && (
        <CommentDialog
          danger={COMMANDS[dialog].danger}
          title={dialog === 'submit' && answering ? 'Answer the clarification' : COMMANDS[dialog].title}
          label={dialog === 'submit' && answering ? 'Your answer' : COMMANDS[dialog].label}
          required={COMMANDS[dialog].required || (dialog === 'submit' && answering)}
          confirmLabel={COMMANDS[dialog].confirm}
          intro={dialog === 'submit' && answering ? rfq.clarificationQuestion ?? undefined : undefined}
          busy={running.isLoading}
          error={running.error}
          onConfirm={(comment) => void command(dialog, comment)}
          onClose={() => setDialog(null)}
        />
      )}
    </Stack>
  )
}

/** Before ERGO sends the RFQ out nothing is being quoted, so the customer has no part status to read yet. */
const NOT_YET_QUOTED = new Set(['DRAFT', 'SUBMITTED', 'UNDER_REVIEW', 'CLARIFICATION_REQUIRED'])

/** Where each part stands (flow doc §4): ERGO sees the quote status, the customer where its quotation stands. */
function PartStatus({ rfqId }: { rfqId: number }) {
  const { data = [], error } = useRfqPartsQuery(rfqId)
  if (error) return null
  return (
    <TableContainer component={Paper} variant="outlined">
      <Typography variant="subtitle1" component="h2" sx={{ p: 1.5, pb: 0 }}>
        Status of each part
      </Typography>
      <Table size="small">
        <TableHead>
          <TableRow>
            <TableCell>#</TableCell>
            <TableCell>Part</TableCell>
            <TableCell>Status</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {data.map((p) => {
            const s = partStatusLabel(p.status)
            return (
              <TableRow key={p.itemId}>
                <TableCell>{p.lineNo}</TableCell>
                <TableCell>
                  {p.partNumber}
                  {p.revision ? ` rev ${p.revision}` : ''}
                </TableCell>
                <TableCell>
                  <Chip size="small" label={s.label} color={s.color} />
                </TableCell>
              </TableRow>
            )
          })}
        </TableBody>
      </Table>
    </TableContainer>
  )
}

function ItemOverview({ item, currency }: { item: ItemView; currency: string }) {
  const t = item.targets
  return (
    <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', md: '1fr 1fr' } }}>
      <Facts
        title={`Part ${item.lineNo}: ${item.partNumber}`}
        facts={[
          ['Part number', item.partNumber],
          ['Revision', item.revision ?? '—'],
          ['Description', item.description ?? '—'],
          ['EAU', formatNumber(item.eau, ' pcs/year')],
          ['Net / gross weight', `${formatNumber(item.netWeightKg)} / ${formatNumber(item.grossWeightKg)} kg`],
          ['Material', [item.material?.name, item.materialSpec].filter(Boolean).join(' · ') || '—'],
          ['Coating / surface', [item.coating?.name, item.coatingSpec].filter(Boolean).join(' · ') || '—'],
        ]}
      />
      <Facts
        title={`Commercial targets: ${item.partNumber}`}
        note="Never shown to suppliers"
        facts={[
          ['Tooling cost', formatMoney(t.targetToolingCost, currency)],
          ['PPAP piece price', formatMoney(t.targetPpapPiecePrice, currency)],
          ['Production piece price', formatMoney(t.targetProductionPiecePrice, currency)],
          ['MOQ', formatNumber(t.moq, ' pcs')],
          ['Lead times', `material ${formatNumber(t.materialLeadTimeWeeks, ' wk')} · tooling ${formatNumber(t.toolingLeadTimeDays, ' d')} · sample ${formatNumber(t.sampleLeadTimeDays, ' d')} · production ${formatNumber(t.productionLeadTimeDays, ' d')}`],
        ]}
      />
    </Box>
  )
}

function InternalNotes({ rfq }: { rfq: RfqView & { audience: 'ERGO' } }) {
  const [notes, setNotes] = useState(rfq.internalNotes ?? '')
  const [save, saving] = useUpdateRfqNotesMutation()
  const editable = rfq.actions.includes('EDIT_NOTES')
  return (
    <Card variant="outlined">
      <CardContent sx={{ display: 'grid', gap: 1 }}>
        <Typography variant="h6" component="h2">
          Internal notes
        </Typography>
        <Typography variant="body2" color="text.secondary">
          ERGO only. Never visible to customers or suppliers.
        </Typography>
        <TextField multiline minRows={3} value={notes} disabled={!editable} onChange={(e) => setNotes(e.target.value)} slotProps={{ htmlInput: { maxLength: 4000, 'aria-label': 'Internal notes' } }} />
        {saving.error != null && <Alert severity="error">{problemMessage(saving.error)}</Alert>}
        {editable && (
          <Button sx={{ justifySelf: 'start' }} disabled={notes === (rfq.internalNotes ?? '')} loading={saving.isLoading} onClick={() => void save({ id: rfq.id, internalNotes: notes || null })}>
            Save notes
          </Button>
        )}
      </CardContent>
    </Card>
  )
}

function History({ rfqId }: { rfqId: number }) {
  const { data = [], error } = useRfqHistoryQuery(rfqId)
  if (error) return <Alert severity="error">{problemMessage(error)}</Alert>
  return <AuditTrail rows={data} />
}
