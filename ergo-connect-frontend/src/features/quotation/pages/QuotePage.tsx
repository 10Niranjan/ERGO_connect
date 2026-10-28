import { Alert, Box, Button, Chip, LinearProgress, Link, Stack, Tab, Tabs, Typography } from '@mui/material'
import { useState } from 'react'
import { Link as RouterLink, useParams } from 'react-router'
import { QUOTATION_VIEW, useCan } from '@/features/permissions'
import { CommentsPanel } from '../../communication/components/CommentsPanel'
import { CommentDialog } from '../../../shared/components/CommentDialog'
import { Facts } from '../../../shared/components/Facts'
import { problemMessage } from '../../../shared/problem'
import { formatDate, formatNumber } from '../../rfq/labels'
import { QuoteEditor } from '../components/QuoteEditor'
import { QuoteFilesPanel } from '../components/QuoteFilesPanel'
import { QuoteSummary } from '../components/QuoteSummary'
import { VersionsPanel } from '../components/VersionsPanel'
import { QUOTE_STATUS } from '../labels'
import { useQuoteCommandMutation, useQuoteQuery, type QuoteView, type SupplierAssignedRfqView, type SupplierRfqItem } from '../quoteApi'
import { formatDateTime } from '@/utils/date'

type DialogCommand = 'revision' | 'recall'

/** Both need a comment: it is the message to the supplier and goes into the audit trail (flow doc §4). */
const COMMANDS: Record<DialogCommand, { title: string; label: string; confirm: string; intro?: string; danger?: boolean }> = {
  revision: {
    title: 'Revision required',
    label: 'What the supplier should change',
    confirm: 'Send back',
    intro: 'The quote goes back to the supplier with your comments. They edit and resubmit; the submitted version stays on record.',
  },
  recall: {
    title: 'Take this part back',
    label: 'Why the part is taken back from this supplier',
    confirm: 'Take back',
    intro: 'The supplier no longer sees this part, and you can send it to another supplier. Their quote stays on record.',
    danger: true,
  },
}

/** One supplier's quote on one part of an RFQ: the supplier's working page, or ERGO's view of what was submitted. */
export function QuotePage() {
  const id = Number(useParams().id)
  const { data: view, isLoading, error } = useQuoteQuery(id)
  if (isLoading) return <LinearProgress />
  if (!view) return <Alert severity="error">{problemMessage(error)}</Alert>
  if (view.audience === 'SUPPLIER_RFQ') return <AssignedRfq key={view.id} view={view} />
  return <Quote key={view.id} view={view} />
}

function Quote({ view }: { view: Exclude<QuoteView, SupplierAssignedRfqView> }) {
  const isSupplier = view.audience === 'SUPPLIER'
  // A supplier role with RFQ access but none on Quotation (the role sheet) sees the assigned part only, never the quote.
  const canSeeQuote = useCan(QUOTATION_VIEW)
  const [tab, setTab] = useState(canSeeQuote ? 'quote' : 'rfq')
  const [dialog, setDialog] = useState<DialogCommand | null>(null)
  const [run, running] = useQuoteCommandMutation()
  const can = (action: QuoteView['actions'][number]) => view.actions.includes(action)
  const status = QUOTE_STATUS[view.status]
  const part = view.part
  const partNumber = part.partNumber
  const partRevision = part.revision
  const partDescription = part.description

  async function command(name: DialogCommand | 'accept', comment?: string) {
    try {
      await run({ id: view.id, command: name, comment }).unwrap()
      setDialog(null)
    } catch {
      // shown in the dialog
    }
  }

  return (
    <Stack spacing={2}>
      <Stack direction={{ xs: 'column', md: 'row' }} sx={{ justifyContent: 'space-between', gap: 2 }}>
        <Stack spacing={0.5}>
          <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
            <Typography variant="h5" component="h1">
              {isSupplier ? `Quote for ${view.part.rfqNumber} · ${partNumber}` : `${view.supplier.name} · ${view.rfqNumber} · ${partNumber}`}
            </Typography>
            <Chip label={status.label} color={status.color} size="small" />
            {view.version > 0 && <Chip label={`Version ${view.version}`} size="small" variant="outlined" />}
          </Stack>
          {isSupplier ? (
            <Typography color="text.secondary">
              {partNumber}
              {partRevision ? ` rev ${partRevision}` : ''} · {partDescription ?? 'No description'} · sub-RFQ {view.subRfqNo}
            </Typography>
          ) : (
            <Link component={RouterLink} to={`/rfqs/${view.rfqId}`}>
              Back to {view.rfqNumber}
            </Link>
          )}
          <Typography variant="body2" color="text.secondary">
            Quote due {formatDate(view.responseDueDate)}
            {view.submittedAt && ` · last submitted ${formatDateTime(view.submittedAt)}`}
            {!isSupplier && ` · sub-RFQ ${view.subRfqNo} · sent by ${view.invitedBy ?? '—'} on ${formatDate(view.invitedAt)}`}
          </Typography>
        </Stack>
        <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap', alignItems: 'flex-start' }}>
          {can('ACCEPT') && (
            <Button variant="contained" onClick={() => void command('accept')} loading={running.isLoading}>
              Accept for customer quotation
            </Button>
          )}
          {can('REQUEST_REVISION') && <Button onClick={() => setDialog('revision')}>Revision required</Button>}
          {can('RECALL') && (
            <Button color="error" onClick={() => setDialog('recall')}>
              Take part back
            </Button>
          )}
        </Stack>
      </Stack>

      {isSupplier && !view.part.quotingOpen && <Alert severity="info">This RFQ is no longer open for quotes.</Alert>}
      {view.revisionReason && (
        <Alert severity="warning">
          <strong>Revision required by ERGO:</strong> {view.revisionReason}
        </Alert>
      )}
      {!isSupplier && view.recallReason && (
        <Alert severity="error">
          <strong>Taken back from this supplier:</strong> {view.recallReason}
        </Alert>
      )}

      <Tabs value={tab} onChange={(_e, value: string) => setTab(value)}>
        {canSeeQuote && <Tab value="quote" label={isSupplier ? 'Your quote' : 'Submitted quote'} />}
        {isSupplier && <Tab value="rfq" label="Part details" />}
        <Tab value="files" label="Files" />
        {canSeeQuote && <Tab value="versions" label="Versions" />}
        {canSeeQuote && <Tab value="comments" label="Comments" />}
      </Tabs>

      {/* hidden rather than unmounted, so switching tabs keeps unsaved edits */}
      <Box hidden={tab !== 'quote'}>
        {isSupplier && can('EDIT') ? (
          <QuoteEditor view={view} />
        ) : view.quote ? (
          <QuoteSummary quote={view.quote} />
        ) : (
          <Typography color="text.secondary">{isSupplier ? 'You have not started a quote.' : 'The supplier has not submitted a quote yet.'}</Typography>
        )}
      </Box>
      {tab === 'rfq' && isSupplier && <PartDetails part={view.part} />}
      {tab === 'files' && <QuoteFilesPanel quoteId={view.id} canUpload={can('UPLOAD')} isErgo={!isSupplier} />}
      {tab === 'versions' && canSeeQuote && <VersionsPanel quoteId={view.id} />}
      {tab === 'comments' && canSeeQuote && <CommentsPanel linkedModule="quotation" recordId={view.id} />}

      {dialog && (
        <CommentDialog
          title={COMMANDS[dialog].title}
          label={COMMANDS[dialog].label}
          required
          confirmLabel={COMMANDS[dialog].confirm}
          intro={COMMANDS[dialog].intro}
          danger={COMMANDS[dialog].danger}
          busy={running.isLoading}
          error={running.error}
          onConfirm={(comment) => void command(dialog, comment)}
          onClose={() => setDialog(null)}
        />
      )}
    </Stack>
  )
}

/** What the supplier may see of its part: no customer, targets or ERGO notes, and no other part (client decision). */
function PartDetails({ part }: { part: SupplierRfqItem }) {
  return (
    <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', md: '1fr 1fr' } }}>
      <Facts
        title="Part and technical"
        facts={[
          ['Part number', part.partNumber],
          ['Revision', part.revision ?? '—'],
          ['Description', part.description ?? '—'],
          ['EAU', formatNumber(part.eau, ' pcs/year')],
          ['Net / gross weight', `${formatNumber(part.netWeightKg)} / ${formatNumber(part.grossWeightKg)} kg`],
          ['Material', [part.material, part.materialSpec].filter(Boolean).join(' · ') || '—'],
          ['Coating / surface', [part.coating, part.coatingSpec].filter(Boolean).join(' · ') || '—'],
        ]}
      />
      <Facts
        title="ERGO's planned process route"
        note="Your quote starts with a copy of this route."
        facts={[
          [
            '',
            part.processSteps
              .map((s, i) => `${i + 1}. ${s.stepName}${s.processName ? ` (${s.processName})` : ''}`)
              .join('\n') || '—',
          ],
        ]}
      />
    </Box>
  )
}

/** A supplier role with RFQ access but none on Quotation: the part and its shared files, nothing of the quote. */
function AssignedRfq({ view }: { view: SupplierAssignedRfqView }) {
  const [tab, setTab] = useState('rfq')
  return (
    <Stack spacing={2}>
      <Stack spacing={0.5}>
        <Typography variant="h5" component="h1">
          {view.part.rfqNumber} · {view.part.partNumber}
        </Typography>
        <Typography color="text.secondary">
          {view.part.partNumber}
          {view.part.revision ? ` rev ${view.part.revision}` : ''} · {view.part.description ?? 'No description'}
        </Typography>
        <Typography variant="body2" color="text.secondary">
          Quote due {formatDate(view.responseDueDate)}
        </Typography>
      </Stack>
      <Tabs value={tab} onChange={(_e, value: string) => setTab(value)}>
        <Tab value="rfq" label="Part details" />
        <Tab value="files" label="Files" />
      </Tabs>
      {tab === 'rfq' && <PartDetails part={view.part} />}
      {tab === 'files' && <QuoteFilesPanel quoteId={view.id} canUpload={false} isErgo={false} />}
    </Stack>
  )
}
