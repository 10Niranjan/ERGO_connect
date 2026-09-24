import { Alert, Box, Button, Chip, LinearProgress, Link, Stack, Tab, Tabs, Typography } from '@mui/material'
import { useState } from 'react'
import { Link as RouterLink, useParams } from 'react-router'
import { CommentDialog } from '../../../shared/components/CommentDialog'
import { Facts } from '../../../shared/components/Facts'
import { problemMessage } from '../../../shared/problem'
import { formatDate, formatNumber } from '../../rfq/labels'
import { QuoteEditor } from '../components/QuoteEditor'
import { QuoteFilesPanel } from '../components/QuoteFilesPanel'
import { QuoteSummary } from '../components/QuoteSummary'
import { VersionsPanel } from '../components/VersionsPanel'
import { QUOTE_STATUS } from '../labels'
import { useQuoteCommandMutation, useQuoteQuery, type QuoteCommand, type QuoteView, type SupplierRfq } from '../quoteApi'

type DialogCommand = Exclude<QuoteCommand, 'submit'>

/** Every one of these needs a comment: it is the message to the other side and goes into the audit trail. */
const COMMANDS: Record<DialogCommand, { title: string; label: string; confirm: string; intro?: string; danger?: boolean }> = {
  'request-clarification': { title: 'Ask ERGO', label: 'Your question', confirm: 'Send question' },
  regret: { title: 'Regret this RFQ', label: 'Why you cannot quote', confirm: 'Regret', intro: 'ERGO is told you will not quote. This cannot be undone.', danger: true },
  'answer-clarification': { title: 'Answer the supplier', label: 'Your answer', confirm: 'Send answer' },
  reopen: {
    title: 'Re-open the quote',
    label: 'What the supplier should revise',
    confirm: 'Re-open',
    intro: 'The supplier can then edit and resubmit. The submitted version stays on record.',
  },
}

/** One supplier's quote on one RFQ: the supplier's working page, or ERGO's view of what was submitted. */
export function QuotePage() {
  const id = Number(useParams().id)
  const { data: view, isLoading, error } = useQuoteQuery(id)
  if (isLoading) return <LinearProgress />
  if (!view) return <Alert severity="error">{problemMessage(error)}</Alert>
  return <Quote key={view.id} view={view} />
}

function Quote({ view }: { view: QuoteView }) {
  const isSupplier = view.audience === 'SUPPLIER'
  const [tab, setTab] = useState('quote')
  const [dialog, setDialog] = useState<DialogCommand | null>(null)
  const [run, running] = useQuoteCommandMutation()
  const can = (action: QuoteView['actions'][number]) => view.actions.includes(action)
  const status = QUOTE_STATUS[view.status]

  async function command(name: DialogCommand, comment: string) {
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
              {isSupplier ? `Quote for ${view.rfq.number}` : `${view.supplier.name} · ${view.rfqNumber}`}
            </Typography>
            <Chip label={status.label} color={status.color} size="small" />
            {view.version > 0 && <Chip label={`Version ${view.version}`} size="small" variant="outlined" />}
          </Stack>
          {isSupplier ? (
            <Typography color="text.secondary">
              {view.rfq.partNumber}
              {view.rfq.revision ? ` rev ${view.rfq.revision}` : ''} · {view.rfq.description ?? 'No description'}
            </Typography>
          ) : (
            <Link component={RouterLink} to={`/rfqs/${view.rfqId}`}>
              Back to {view.rfqNumber}
            </Link>
          )}
          <Typography variant="body2" color="text.secondary">
            Quote due {formatDate(view.responseDueDate)}
            {view.submittedAt && ` · last submitted ${new Date(view.submittedAt).toLocaleString()}`}
            {!isSupplier && ` · invited by ${view.invitedBy ?? '—'} on ${formatDate(view.invitedAt)}`}
          </Typography>
        </Stack>
        <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: 'wrap', alignItems: 'flex-start' }}>
          {can('REQUEST_CLARIFICATION') && <Button onClick={() => setDialog('request-clarification')}>Ask ERGO</Button>}
          {can('ANSWER_CLARIFICATION') && (
            <Button variant="contained" onClick={() => setDialog('answer-clarification')}>
              Answer supplier
            </Button>
          )}
          {can('REOPEN') && <Button onClick={() => setDialog('reopen')}>Re-open for revision</Button>}
          {can('REGRET') && (
            <Button color="error" onClick={() => setDialog('regret')}>
              Regret
            </Button>
          )}
        </Stack>
      </Stack>

      {isSupplier && !view.rfq.quotingOpen && <Alert severity="info">This RFQ is no longer open for quotes.</Alert>}
      {view.clarificationQuestion && (
        <Alert severity={view.status === 'CLARIFICATION_REQUESTED' ? 'warning' : 'info'}>
          <strong>{isSupplier ? 'You asked' : 'The supplier asked'}:</strong> {view.clarificationQuestion}
          {view.clarificationAnswer && (
            <>
              <br />
              <strong>ERGO answered:</strong> {view.clarificationAnswer}
            </>
          )}
        </Alert>
      )}
      {view.reopenReason && (
        <Alert severity="warning">
          <strong>Re-opened by ERGO:</strong> {view.reopenReason}
        </Alert>
      )}
      {view.regretReason && (
        <Alert severity="error">
          <strong>{isSupplier ? 'You regretted this RFQ' : 'The supplier regretted'}:</strong> {view.regretReason}
        </Alert>
      )}

      <Tabs value={tab} onChange={(_e, value: string) => setTab(value)}>
        <Tab value="quote" label={isSupplier ? 'Your quote' : 'Submitted quote'} />
        {isSupplier && <Tab value="rfq" label="RFQ details" />}
        <Tab value="files" label="Files" />
        <Tab value="versions" label="Versions" />
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
      {tab === 'rfq' && isSupplier && <RfqDetails rfq={view.rfq} />}
      {tab === 'files' && <QuoteFilesPanel quoteId={view.id} canUpload={can('UPLOAD')} isErgo={!isSupplier} />}
      {tab === 'versions' && <VersionsPanel quoteId={view.id} />}

      {dialog && (
        <CommentDialog
          title={COMMANDS[dialog].title}
          label={COMMANDS[dialog].label}
          required
          confirmLabel={COMMANDS[dialog].confirm}
          intro={dialog === 'answer-clarification' ? (view.clarificationQuestion ?? undefined) : COMMANDS[dialog].intro}
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

/** What the supplier may see of the RFQ: no customer, targets or ERGO notes (client decision). */
function RfqDetails({ rfq }: { rfq: SupplierRfq }) {
  return (
    <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', md: '1fr 1fr' } }}>
      <Facts
        title="Part and technical"
        facts={[
          ['Part number', rfq.partNumber],
          ['Revision', rfq.revision ?? '—'],
          ['Description', rfq.description ?? '—'],
          ['EAU', formatNumber(rfq.eau, ' pcs/year')],
          ['Net / gross weight', `${formatNumber(rfq.netWeightKg)} / ${formatNumber(rfq.grossWeightKg)} kg`],
          ['Material', [rfq.material, rfq.materialSpec].filter(Boolean).join(' · ') || '—'],
          ['Coating / surface', [rfq.coating, rfq.coatingSpec].filter(Boolean).join(' · ') || '—'],
        ]}
      />
      <Facts
        title="ERGO's planned process route"
        note="Your quote starts with a copy of this route."
        facts={[
          [
            '',
            rfq.processSteps
              .map((s, i) => `${i + 1}. ${s.stepName}${s.processName ? ` (${s.processName})` : ''}`)
              .join('\n') || '—',
          ],
        ]}
      />
    </Box>
  )
}
