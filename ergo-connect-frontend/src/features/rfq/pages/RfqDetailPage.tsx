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
import { useState, type ReactNode } from 'react'
import { useNavigate, useParams } from 'react-router'
import { AuditTrail } from '../../../shared/components/AuditTrail'
import { CommentDialog } from '../../../shared/components/CommentDialog'
import { problemMessage } from '../../../shared/problem'
import { DocumentsPanel } from '../components/DocumentsPanel'
import { PRIORITIES, STATUS, formatDate, formatMoney, formatNumber } from '../labels'
import {
  useCloneRfqMutation,
  useRfqCommandMutation,
  useRfqHistoryQuery,
  useRfqQuery,
  useUpdateRfqNotesMutation,
  type RfqCommand,
  type RfqView,
} from '../rfqApi'

/** How each workflow action asks for its comment (reasons are required for clarification, re-open, cancel). */
const COMMANDS: Record<Exclude<RfqCommand, 'start-review'>, { title: string; label: string; confirm: string; required: boolean; danger?: boolean }> = {
  submit: { title: 'Submit RFQ', label: 'Comment (optional)', confirm: 'Submit', required: false },
  'request-clarification': { title: 'Ask the customer', label: 'Question for the customer', confirm: 'Send question', required: true },
  validate: { title: 'Validate RFQ', label: 'Comment (optional)', confirm: 'Validate', required: false },
  reopen: { title: 'Re-open RFQ', label: 'Reason for re-opening', confirm: 'Re-open', required: true },
  cancel: { title: 'Cancel RFQ', label: 'Reason for cancelling', confirm: 'Cancel RFQ', required: true, danger: true },
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

  const currency = rfq.targets.currency.code
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
            {rfq.part.partNumber}
            {rfq.part.revision ? ` rev ${rfq.part.revision}` : ''} · {rfq.part.description ?? 'No description yet'}
          </Typography>
          <Typography variant="body2" color="text.secondary">
            {isErgo && `${rfq.customer.name} · `}Quote needed by {formatDate(rfq.part.targetQuoteDate)} · Priority {PRIORITIES[rfq.part.priority]}
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
          {can('VALIDATE') && (
            <Button variant="contained" onClick={() => setDialog('validate')}>
              Validate
            </Button>
          )}
          {can('SUBMIT') && (
            <Button variant="contained" onClick={() => setDialog('submit')}>
              {answering ? 'Answer and resubmit' : 'Submit to ERGO'}
            </Button>
          )}
          {can('REOPEN') && <Button onClick={() => setDialog('reopen')}>Re-open</Button>}
          {can('CLONE') && <Button onClick={() => void clone()}>Clone</Button>}
          {can('CANCEL') && (
            <Button color="error" onClick={() => setDialog('cancel')}>
              Cancel RFQ
            </Button>
          )}
        </Stack>
      </Stack>

      {actionError != null && <Alert severity="error">{problemMessage(actionError)}</Alert>}
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
        {isErgo && <Tab value="history" label="Audit trail" />}
      </Tabs>

      {tab === 'overview' && (
        <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', md: '1fr 1fr' } }}>
          <Facts
            title="Part and technical"
            facts={[
              ['Part number', rfq.part.partNumber],
              ['Revision', rfq.part.revision ?? '—'],
              ['EAU', formatNumber(rfq.part.eau, ' pcs/year')],
              ['Net / gross weight', `${formatNumber(rfq.part.netWeightKg)} / ${formatNumber(rfq.part.grossWeightKg)} kg`],
              ['Material', [rfq.part.material?.name, rfq.part.materialSpec].filter(Boolean).join(' · ') || '—'],
              ['Coating / surface', [rfq.part.coating?.name, rfq.part.coatingSpec].filter(Boolean).join(' · ') || '—'],
            ]}
          />
          <Facts
            title="Commercial targets"
            note="Never shown to suppliers"
            facts={[
              ['Currency', `${rfq.targets.currency.code} – ${rfq.targets.currency.name}`],
              ['Tooling cost', formatMoney(rfq.targets.targetToolingCost, currency)],
              ['PPAP piece price', formatMoney(rfq.targets.targetPpapPiecePrice, currency)],
              ['Production piece price', formatMoney(rfq.targets.targetProductionPiecePrice, currency)],
              ['Payment terms', rfq.targets.paymentTerm?.name ?? '—'],
              ['MOQ', formatNumber(rfq.targets.moq, ' pcs')],
              ['Lead times', `material ${formatNumber(rfq.targets.materialLeadTimeWeeks, ' wk')} · tooling ${formatNumber(rfq.targets.toolingLeadTimeDays, ' d')} · sample ${formatNumber(rfq.targets.sampleLeadTimeDays, ' d')} · production ${formatNumber(rfq.targets.productionLeadTimeDays, ' d')}`],
            ]}
          />
          <Facts title="Requirements and remarks" facts={[['', rfq.customerRemarks ?? '—']]} />
          {isErgo && <InternalNotes rfq={rfq} />}
        </Box>
      )}

      {tab === 'process' && (
        <TableContainer component={Paper} variant="outlined">
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
              {rfq.part.processSteps.map((step, index) => (
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
              {rfq.part.processSteps.length === 0 && (
                <TableRow>
                  <TableCell colSpan={7} sx={{ color: 'text.secondary' }}>
                    No process steps yet (needed before submitting).
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
        </TableContainer>
      )}

      {tab === 'documents' && <DocumentsPanel rfqId={rfq.id} canUpload={can('UPLOAD')} isErgo={isErgo} />}
      {tab === 'history' && isErgo && <History rfqId={rfq.id} />}

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

function Facts({ title, note, facts }: { title: string; note?: string; facts: [string, ReactNode][] }) {
  return (
    <Card variant="outlined">
      <CardContent>
        <Typography variant="h6" component="h2">
          {title}
        </Typography>
        {note && (
          <Typography variant="body2" color="text.secondary" gutterBottom>
            {note}
          </Typography>
        )}
        <Box sx={{ display: 'grid', gridTemplateColumns: facts.length === 1 && !facts[0][0] ? '1fr' : '170px 1fr', rowGap: 1, columnGap: 2, mt: 1 }}>
          {facts.map(([label, value], index) => (
            <Box key={index} sx={{ display: 'contents' }}>
              {label && <Typography color="text.secondary">{label}</Typography>}
              <Typography sx={{ whiteSpace: 'pre-wrap' }}>{value}</Typography>
            </Box>
          ))}
        </Box>
      </CardContent>
    </Card>
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
