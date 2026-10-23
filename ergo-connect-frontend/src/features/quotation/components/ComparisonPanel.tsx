import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  LinearProgress,
  Link,
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
import { Fragment, useState, type ReactNode } from 'react'
import { Link as RouterLink } from 'react-router'
import { CommentDialog } from '../../../shared/components/CommentDialog'
import { num, text } from '../../../shared/form'
import { problemMessage } from '../../../shared/problem'
import { formatDate, formatMoney, formatNumber } from '../../rfq/labels'
import {
  useComparisonQuery,
  useCustomerQuotesQuery,
  useDecideQuoteMutation,
  useEvaluateQuoteMutation,
  useSaveComparisonSummaryMutation,
  type ComparisonLine,
  type ComparisonView,
  type Decision,
  type Scores,
} from '../comparisonApi'
import { QUOTE_STATUS, RESPONSE_STATUS } from '../labels'
import { useQuoteCommandMutation } from '../quoteApi'
import { SendQuoteDialog } from './SendQuoteDialog'

const BEST = { bgcolor: 'rgba(46, 125, 50, 0.12)', fontWeight: 600 }

/** One matrix row; `value` is the number compared across quotes (money already in the RFQ currency). */
interface Row {
  label: string
  target?: ReactNode
  cell: (line: ComparisonLine) => ReactNode
  value?: (line: ComparisonLine) => number | null | undefined
  best?: 'low' | 'high'
}

const DECISIONS: { decision: Decision; label: string }[] = [
  { decision: 'SHORTLIST', label: 'Shortlist' },
  { decision: 'SELECT_FOR_CUSTOMER', label: 'Select for customer' },
  { decision: 'MARK_NOT_SELECTED', label: 'Not selected' },
  { decision: 'CLEAR_DECISION', label: 'Clear decision' },
]

const SCORE_LABELS: Record<keyof Scores, string> = {
  commercial: 'Commercial',
  technical: 'Technical',
  quality: 'Quality',
  risk: 'Risk (10 = lowest risk)',
}

/** SRS §12.7.1 / §17.3: ERGO's side-by-side view of the submitted quotes. Everything here stays inside ERGO. */
export function ComparisonPanel({ rfqId }: { rfqId: number }) {
  const { data: view, isLoading, error } = useComparisonQuery(rfqId)
  if (isLoading) return <LinearProgress />
  if (!view) return <Alert severity="error">{problemMessage(error)}</Alert>
  return <Comparison key={view.rfqId} view={view} />
}

function Comparison({ view }: { view: ComparisonView }) {
  const [decide, deciding] = useDecideQuoteMutation()
  const [command, commanding] = useQuoteCommandMutation()
  const [scoring, setScoring] = useState<ComparisonLine | null>(null)
  const [sending, setSending] = useState<ComparisonLine | null>(null)
  const [reopening, setReopening] = useState<ComparisonLine | null>(null)
  const [notSelecting, setNotSelecting] = useState<ComparisonLine | null>(null)
  const cur = view.currency.code
  const t = view.targets
  const today = new Date().toLocaleDateString('en-CA')

  /** The amount in the quote's currency, plus its value in the RFQ currency when that differs. */
  const money = (line: ComparisonLine, amount: number | null, converted: number | null) => {
    const code = line.quote.currency?.code
    if (amount == null) return '—'
    if (code === cur) return formatMoney(amount, cur)
    return `${formatMoney(amount, code)}\n${converted != null ? `≈ ${formatMoney(converted, cur)}` : `(no ${code}→${cur} rate)`}`
  }
  const score = (key: keyof Scores): Row => ({
    label: SCORE_LABELS[key],
    cell: (l) => formatNumber(l.scores?.[key] ?? null),
    value: (l) => l.scores?.[key],
    best: 'high',
  })
  const lead = (label: string, key: 'materialLeadTimeWeeks' | 'toolingLeadTimeDays' | 'sampleLeadTimeDays' | 'productionLeadTimeDays'): Row => ({
    label,
    target: formatNumber(t[key]),
    cell: (l) => formatNumber(l.quote[key]),
    value: (l) => l.quote[key],
    best: 'low',
  })

  const sections: [string, Row[]][] = [
    [
      `Prices (compared in ${cur})`,
      [
        {
          label: 'Production piece price',
          target: formatMoney(t.productionPiecePrice, cur),
          cell: (l) => money(l, l.quote.productionPiecePrice, l.converted.productionPiecePrice),
          value: (l) => l.converted.productionPiecePrice,
          best: 'low',
        },
        {
          label: 'PPAP piece price',
          target: formatMoney(t.ppapPiecePrice, cur),
          cell: (l) => money(l, l.quote.ppapPiecePrice, l.converted.ppapPiecePrice),
          value: (l) => l.converted.ppapPiecePrice,
          best: 'low',
        },
        {
          label: 'Tooling cost',
          target: formatMoney(t.toolingCost, cur),
          cell: (l) => money(l, l.quote.toolingCostTotal, l.converted.toolingCost),
          value: (l) => l.converted.toolingCost,
          best: 'low',
        },
      ],
    ],
    [
      'Terms',
      [
        { label: 'MOQ', target: formatNumber(t.moq, ' pcs'), cell: (l) => formatNumber(l.quote.moq, ' pcs'), value: (l) => l.quote.moq, best: 'low' },
        { label: 'Payment terms', target: t.paymentTerm?.name ?? '—', cell: (l) => l.quote.paymentTerm?.name ?? '—' },
        { label: 'Incoterm', cell: (l) => l.quote.incoterm?.code ?? '—' },
        {
          label: 'Valid until',
          cell: (l) =>
            l.quote.validityDate && l.quote.validityDate < today ? (
              <Box component="span" sx={{ color: 'error.main' }}>
                {formatDate(l.quote.validityDate)} (expired)
              </Box>
            ) : (
              formatDate(l.quote.validityDate)
            ),
        },
      ],
    ],
    [
      'Lead times',
      [
        lead('Material (weeks)', 'materialLeadTimeWeeks'),
        lead('Tooling (days)', 'toolingLeadTimeDays'),
        lead('Samples (days)', 'sampleLeadTimeDays'),
        lead('Production from PO (days)', 'productionLeadTimeDays'),
      ],
    ],
    [
      'Technical',
      [
        {
          label: 'Process route',
          cell: (l) => l.quote.processSteps.map((s) => s.stepName + (s.sourcingType === 'OUTSOURCED' ? ' (outsourced)' : '')).join(' → ') || '—',
        },
        { label: 'Assumptions / deviations', cell: (l) => l.quote.assumptionsDeviations ?? '—' },
      ],
    ],
    [
      'ERGO evaluation (internal)',
      [
        score('commercial'),
        score('technical'),
        score('quality'),
        score('risk'),
        { label: 'Total (average)', cell: (l) => formatNumber(l.totalScore), value: (l) => l.totalScore, best: 'high' },
        { label: 'Internal remarks', cell: (l) => l.internalRemarks ?? '—' },
      ],
    ],
  ]

  const bestOf = (row: Row) => {
    const values = row.value ? view.lines.map(row.value).filter((v): v is number => v != null) : []
    const low = Math.min(...values)
    const high = Math.max(...values)
    if (!row.best || values.length < 2 || low === high) return null // nothing to beat: one quote, or all equal
    return row.best === 'low' ? low : high
  }

  if (view.lines.length === 0) {
    return <Typography color="text.secondary">No quotes have been submitted yet. Drafts are never compared.</Typography>
  }

  return (
    <Stack spacing={2}>
      <Typography variant="body2" color="text.secondary">
        The last submitted version of each quote. Amounts in other currencies are converted to {cur} at the latest exchange rate in master data. The best value in each row is highlighted.
      </Typography>
      {(deciding.error ?? commanding.error) != null && <Alert severity="error">{problemMessage(deciding.error ?? commanding.error)}</Alert>}
      <TableContainer component={Paper} variant="outlined">
        <Table size="small" aria-label="Quote comparison" sx={{ '& td, & th': { verticalAlign: 'top', whiteSpace: 'pre-wrap' } }}>
          <TableHead>
            <TableRow>
              <TableCell sx={{ minWidth: 180 }} />
              <TableCell sx={{ minWidth: 130, color: 'text.secondary' }}>Customer target</TableCell>
              {view.lines.map((l) => (
                <TableCell key={l.quoteId} sx={{ minWidth: 200 }}>
                  <Stack spacing={0.5} sx={{ alignItems: 'flex-start' }}>
                    <Link component={RouterLink} to={`/quotes/${l.quoteId}`} sx={{ fontWeight: 600 }}>
                      {l.supplier.name}
                    </Link>
                    <Chip size="small" label={QUOTE_STATUS[l.status].label} color={QUOTE_STATUS[l.status].color} />
                    <Typography variant="caption" color="text.secondary">
                      Version {l.version} · {formatDate(l.submittedAt)}
                    </Typography>
                  </Stack>
                </TableCell>
              ))}
            </TableRow>
          </TableHead>
          <TableBody>
            {sections.map(([title, rows]) => (
              <Fragment key={title}>
                <TableRow>
                  <TableCell colSpan={view.lines.length + 2} sx={{ bgcolor: 'action.hover', fontWeight: 600 }}>
                    {title}
                  </TableCell>
                </TableRow>
                {rows.map((row) => {
                  const best = bestOf(row)
                  return (
                    <TableRow key={row.label}>
                      <TableCell>{row.label}</TableCell>
                      <TableCell sx={{ color: 'text.secondary' }}>{row.target ?? ''}</TableCell>
                      {view.lines.map((l) => (
                        <TableCell key={l.quoteId} sx={best != null && row.value?.(l) === best ? BEST : undefined}>
                          {row.cell(l)}
                        </TableCell>
                      ))}
                    </TableRow>
                  )
                })}
              </Fragment>
            ))}
            <TableRow>
              <TableCell sx={{ fontWeight: 600 }}>Actions</TableCell>
              <TableCell />
              {view.lines.map((l) => (
                <TableCell key={l.quoteId}>
                  <Stack spacing={0.5} sx={{ alignItems: 'flex-start' }}>
                    {l.actions.includes('SEND_TO_CUSTOMER') && (
                      <Button size="small" variant="contained" onClick={() => setSending(l)}>
                        Generate quotation for customer
                      </Button>
                    )}
                    {l.actions.includes('EVALUATE') && (
                      <Button size="small" onClick={() => setScoring(l)}>
                        Score
                      </Button>
                    )}
                    {DECISIONS.filter((d) => l.actions.includes(d.decision)).map((d) => (
                      <Button
                        key={d.decision}
                        size="small"
                        disabled={deciding.isLoading}
                        onClick={() =>
                          d.decision === 'MARK_NOT_SELECTED' ? setNotSelecting(l) : void decide({ quoteId: l.quoteId, decision: d.decision })
                        }
                      >
                        {d.label}
                      </Button>
                    ))}
                    {l.actions.includes('REOPEN') && (
                      <Button size="small" onClick={() => setReopening(l)}>
                        Request revision
                      </Button>
                    )}
                  </Stack>
                </TableCell>
              ))}
            </TableRow>
          </TableBody>
        </Table>
      </TableContainer>

      <Summary view={view} />
      <SentQuotes rfqId={view.rfqId} />

      {scoring && <ScoreDialog line={scoring} onClose={() => setScoring(null)} />}
      {sending && <SendQuoteDialog view={view} line={sending} onClose={() => setSending(null)} />}
      {notSelecting && (
        <CommentDialog
          title={`Mark ${notSelecting.supplier.name} as not selected`}
          label="Why this quote was not selected"
          intro="ERGO-internal only: kept in the audit trail, never shown to the supplier or the customer."
          required
          confirmLabel="Not selected"
          busy={deciding.isLoading}
          error={deciding.error}
          onConfirm={(comment) =>
            void decide({ quoteId: notSelecting.quoteId, decision: 'MARK_NOT_SELECTED', comment })
              .unwrap()
              .then(() => setNotSelecting(null))
              .catch(() => {})
          }
          onClose={() => setNotSelecting(null)}
        />
      )}
      {reopening && (
        <CommentDialog
          title={`Ask ${reopening.supplier.name} for a revision`}
          label="What the supplier should revise"
          intro="The quote is re-opened for the supplier. Its submitted versions stay on record."
          required
          confirmLabel="Re-open quote"
          busy={commanding.isLoading}
          error={commanding.error}
          onConfirm={(comment) =>
            void command({ id: reopening.quoteId, command: 'reopen', comment })
              .unwrap()
              .then(() => setReopening(null))
              .catch(() => {})
          }
          onClose={() => setReopening(null)}
        />
      )}
    </Stack>
  )
}

/** ERGO's written assessment (SRS §13.3.7): internal summary and recommendation. */
function Summary({ view }: { view: ComparisonView }) {
  const [internalSummary, setInternalSummary] = useState(view.internalSummary ?? '')
  const [recommendation, setRecommendation] = useState(view.recommendationSummary ?? '')
  const [save, saving] = useSaveComparisonSummaryMutation()
  const dirty = internalSummary !== (view.internalSummary ?? '') || recommendation !== (view.recommendationSummary ?? '')
  const area = (label: string, value: string, onChange: (value: string) => void) => (
    <TextField label={label} value={value} onChange={(e) => onChange(e.target.value)} multiline minRows={3} disabled={!view.canEdit} slotProps={{ htmlInput: { maxLength: 4000 } }} />
  )
  return (
    <Card variant="outlined">
      <CardContent sx={{ display: 'grid', gap: 1.5 }}>
        <Typography variant="h6" component="h2">
          Assessment and recommendation
        </Typography>
        <Typography variant="body2" color="text.secondary">
          ERGO only.{view.preparedBy && ` Last saved by ${view.preparedBy} on ${formatDate(view.preparedAt)}.`}
        </Typography>
        <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', md: '1fr 1fr' } }}>
          {area('Internal summary', internalSummary, setInternalSummary)}
          {area('Recommendation', recommendation, setRecommendation)}
        </Box>
        {saving.error != null && <Alert severity="error">{problemMessage(saving.error)}</Alert>}
        {view.canEdit && (
          <Button
            sx={{ justifySelf: 'start' }}
            disabled={!dirty}
            loading={saving.isLoading}
            onClick={() => void save({ rfqId: view.rfqId, internalSummary: internalSummary.trim() || null, recommendationSummary: recommendation.trim() || null })}
          >
            Save assessment
          </Button>
        )}
      </CardContent>
    </Card>
  )
}

/** Every quote sent to the customer on this RFQ, with its basis, ERGO's margin and the customer's answer. */
function SentQuotes({ rfqId }: { rfqId: number }) {
  const { data, error } = useCustomerQuotesQuery(rfqId)
  if (error) return <Alert severity="error">{problemMessage(error)}</Alert>
  if (!data || data.audience !== 'ERGO' || data.quotes.length === 0) return null
  return (
    <Card variant="outlined">
      <CardContent sx={{ display: 'grid', gap: 1.5 }}>
        <Typography variant="h6" component="h2">
          Quotes sent to the customer
        </Typography>
        <TableContainer component={Paper} variant="outlined">
          <Table size="small" aria-label="Quotes sent to the customer" sx={{ '& td': { verticalAlign: 'top' } }}>
            <TableHead>
              <TableRow>
                <TableCell>Sent</TableCell>
                <TableCell>Quote</TableCell>
                <TableCell>Based on</TableCell>
                <TableCell align="right">Piece price</TableCell>
                <TableCell align="right">ERGO margin (internal)</TableCell>
                <TableCell>Status</TableCell>
                <TableCell>Customer&apos;s answer</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {data.quotes.map((q) => {
                const terms = q.content.ergoQuote
                const code = terms?.currency.code
                return (
                  <TableRow key={q.id}>
                    <TableCell>
                      {formatDate(q.sentAt)}
                      <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
                        {q.sentBy}
                      </Typography>
                    </TableCell>
                    <TableCell>{q.type === 'ERGO_QUOTE' ? 'ERGO quotation' : 'Supplier quote (sent before the generate rule)'}</TableCell>
                    <TableCell>
                      {q.supplier.name}, version {q.supplierVersion}
                    </TableCell>
                    <TableCell align="right">{formatMoney(terms?.productionPiecePrice ?? null, code)}</TableCell>
                    <TableCell align="right">{q.marginAmount == null ? '—' : `${formatMoney(q.marginAmount, code)} (${q.marginPercentage}%)`}</TableCell>
                    <TableCell>
                      <Chip size="small" label={RESPONSE_STATUS[q.status].label} color={RESPONSE_STATUS[q.status].color} />
                    </TableCell>
                    <TableCell sx={{ whiteSpace: 'pre-wrap' }}>
                      {q.respondedAt ? `${q.respondedBy ?? ''}, ${formatDate(q.respondedAt)}${q.responseComment ? `\n${q.responseComment}` : ''}` : '—'}
                    </TableCell>
                  </TableRow>
                )
              })}
            </TableBody>
          </Table>
        </TableContainer>
      </CardContent>
    </Card>
  )
}

/** SRS §17.3 manual scoring: 0-10 each, higher is better; risk 10 means lowest risk. */
function ScoreDialog({ line, onClose }: { line: ComparisonLine; onClose: () => void }) {
  const [evaluate, evaluating] = useEvaluateQuoteMutation()
  const [scores, setScores] = useState<Record<keyof Scores, string>>({
    commercial: text(line.scores?.commercial),
    technical: text(line.scores?.technical),
    quality: text(line.scores?.quality),
    risk: text(line.scores?.risk),
  })
  const [remarks, setRemarks] = useState(line.internalRemarks ?? '')

  async function save() {
    try {
      await evaluate({
        quoteId: line.quoteId,
        scores: { commercial: num(scores.commercial), technical: num(scores.technical), quality: num(scores.quality), risk: num(scores.risk) },
        internalRemarks: remarks.trim() || null,
      }).unwrap()
      onClose()
    } catch {
      // shown below
    }
  }

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Score {line.supplier.name}</DialogTitle>
      <DialogContent sx={{ display: 'grid', gap: 2, pt: '8px !important' }}>
        <Typography variant="body2" color="text.secondary">
          0 to 10, higher is better. ERGO only: suppliers and customers never see scores.
        </Typography>
        <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: 'repeat(2, 1fr)' }}>
          {(Object.keys(SCORE_LABELS) as (keyof Scores)[]).map((key) => (
            <TextField
              key={key}
              label={SCORE_LABELS[key]}
              type="number"
              value={scores[key]}
              onChange={(e) => setScores((s) => ({ ...s, [key]: e.target.value }))}
              slotProps={{ htmlInput: { min: 0, max: 10, step: 1 } }}
            />
          ))}
        </Box>
        <TextField label="Internal remarks" multiline minRows={3} value={remarks} onChange={(e) => setRemarks(e.target.value)} slotProps={{ htmlInput: { maxLength: 4000 } }} />
        {evaluating.error != null && <Alert severity="error">{problemMessage(evaluating.error)}</Alert>}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" loading={evaluating.isLoading} onClick={() => void save()}>
          Save scores
        </Button>
      </DialogActions>
    </Dialog>
  )
}
