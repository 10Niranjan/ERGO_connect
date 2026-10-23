import { Alert, Box, Button, Card, CardContent, Chip, LinearProgress, Paper, Stack, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Typography } from '@mui/material'
import { useState } from 'react'
import { CommentDialog } from '../../../shared/components/CommentDialog'
import { Facts } from '../../../shared/components/Facts'
import { problemMessage } from '../../../shared/problem'
import { formatDate, formatMoney } from '../../rfq/labels'
import {
  useCustomerQuotesQuery,
  useRespondCustomerQuoteMutation,
  type CustomerQuoteContent,
  type CustomerQuoteView,
  type CustomerResponse,
  type ErgoCustomerQuoteView,
} from '../customerQuoteApi'
import { ergoQuoteSections, RESPONSE_STATUS } from '../labels'

/** How each answer asks for its comment (flow doc §5: every decision carries a comment). */
const ANSWERS: Record<CustomerResponse, { title: string; label: string; confirm: string; required: boolean; danger?: boolean }> = {
  APPROVED: { title: 'Approve the quotation', label: 'Comment', confirm: 'Approve', required: true },
  REVISION_REQUESTED: { title: 'Correction required', label: 'What should be corrected', confirm: 'Request correction', required: true },
  ON_HOLD: { title: 'Put the quotation on hold', label: 'Why is the decision on hold', confirm: 'Put on hold', required: true },
  DECLINED: { title: 'Reject the quotation', label: 'Reason for rejecting', confirm: 'Reject', required: true, danger: true },
}

/** SRS §12.7.2 / flow doc §5: the quotations ERGO generated for this customer, newest first, and the customer's answer. */
export function CustomerQuotesPanel({ rfqId }: { rfqId: number }) {
  const { data, isLoading, error } = useCustomerQuotesQuery(rfqId)
  if (isLoading) return <LinearProgress />
  if (!data || data.audience !== 'CUSTOMER') return <Alert severity="error">{problemMessage(error)}</Alert>
  if (data.quotations.length === 0) return <Typography color="text.secondary">ERGO has not sent you a quotation for this RFQ yet.</Typography>
  return (
    <Stack spacing={2}>
      {data.quotations.map((q) => (
        <SentQuotation key={q.id} quotation={q} />
      ))}
    </Stack>
  )
}

/** The lines of a quotation: one block per part, ERGO's terms only. */
function QuotationLines({ content }: { content: CustomerQuoteContent }) {
  return (
    <Stack spacing={2}>
      {content.remarks && (
        <Alert severity="info">
          <strong>Remarks from ERGO:</strong> {content.remarks}
        </Alert>
      )}
      {content.lines.map((line) => (
        <Stack key={line.rfqItemId} spacing={1}>
          <Typography variant="subtitle1" component="h3">
            {line.partNumber}
            {line.revision ? ` rev ${line.revision}` : ''}
            {line.description ? ` · ${line.description}` : ''}
          </Typography>
          <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', md: 'repeat(3, 1fr)' } }}>
            {ergoQuoteSections(line.ergoQuote).map((section) => (
              <Facts key={section.title} title={section.title} facts={section.facts} />
            ))}
          </Box>
        </Stack>
      ))}
    </Stack>
  )
}

function SentQuotation({ quotation }: { quotation: CustomerQuoteView }) {
  const [answer, setAnswer] = useState<CustomerResponse | null>(null)
  const [respond, responding] = useRespondCustomerQuoteMutation()
  const status = RESPONSE_STATUS[quotation.status]

  async function send(response: CustomerResponse, comment: string) {
    try {
      await respond({ id: quotation.id, response, comment: comment || null }).unwrap()
      setAnswer(null)
    } catch {
      // shown in the dialog
    }
  }

  return (
    <Card variant="outlined">
      <CardContent sx={{ display: 'grid', gap: 2 }}>
        <Stack direction={{ xs: 'column', sm: 'row' }} sx={{ justifyContent: 'space-between', gap: 1 }}>
          <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
            <Typography variant="h6" component="h2">
              Quotation {quotation.version} from ERGO
            </Typography>
            <Chip size="small" label={status.customer} color={status.color} />
          </Stack>
          <Typography variant="body2" color="text.secondary">
            Sent {formatDate(quotation.sentAt)}
          </Typography>
        </Stack>

        <QuotationLines content={quotation.content} />

        {quotation.respondedAt && (
          <Typography sx={{ whiteSpace: 'pre-wrap' }}>
            <strong>{status.customer}</strong> by {quotation.respondedBy ?? 'your company'} on {formatDate(quotation.respondedAt)}
            {quotation.responseComment && `: ${quotation.responseComment}`}
          </Typography>
        )}
        {quotation.actions.length > 0 && (
          <Stack direction="row" spacing={1}>
            {quotation.actions.includes('APPROVE') && (
              <Button variant="contained" onClick={() => setAnswer('APPROVED')}>
                Approve
              </Button>
            )}
            {quotation.actions.includes('REQUEST_REVISION') && <Button onClick={() => setAnswer('REVISION_REQUESTED')}>Correction required</Button>}
            {quotation.actions.includes('PUT_ON_HOLD') && <Button onClick={() => setAnswer('ON_HOLD')}>Put on hold</Button>}
            {quotation.actions.includes('DECLINE') && (
              <Button color="error" onClick={() => setAnswer('DECLINED')}>
                Reject
              </Button>
            )}
          </Stack>
        )}
      </CardContent>
      {answer && (
        <CommentDialog
          title={ANSWERS[answer].title}
          label={ANSWERS[answer].label}
          required={ANSWERS[answer].required}
          confirmLabel={ANSWERS[answer].confirm}
          danger={ANSWERS[answer].danger}
          busy={responding.isLoading}
          error={responding.error}
          onConfirm={(comment) => void send(answer, comment)}
          onClose={() => setAnswer(null)}
        />
      )}
    </Card>
  )
}

/**
 * ERGO's record of the quotations it generated (flow doc §4): exactly what the customer received, the supplier quote
 * and ERGO's margin behind each part (ERGO only), and the customer's answer.
 */
export function ErgoQuotationsPanel({ rfqId }: { rfqId: number }) {
  const { data, isLoading, error } = useCustomerQuotesQuery(rfqId)
  if (isLoading) return <LinearProgress />
  if (!data || data.audience !== 'ERGO') return <Alert severity="error">{problemMessage(error)}</Alert>
  if (data.quotations.length === 0) {
    return <Typography color="text.secondary">No quotation has been generated for the customer yet. Accept supplier quotes on the Parts tab, then generate one.</Typography>
  }
  return (
    <Stack spacing={2}>
      {data.quotations.map((q) => (
        <ErgoQuotation key={q.id} quotation={q} />
      ))}
    </Stack>
  )
}

function ErgoQuotation({ quotation }: { quotation: ErgoCustomerQuoteView }) {
  const status = RESPONSE_STATUS[quotation.status]
  return (
    <Card variant="outlined">
      <CardContent sx={{ display: 'grid', gap: 2 }}>
        <Stack direction={{ xs: 'column', sm: 'row' }} sx={{ justifyContent: 'space-between', gap: 1 }}>
          <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
            <Typography variant="h6" component="h2">
              Quotation {quotation.version}
            </Typography>
            <Chip size="small" label={status.label} color={status.color} />
          </Stack>
          <Typography variant="body2" color="text.secondary">
            Generated {formatDate(quotation.sentAt)} by {quotation.sentBy ?? '—'}
          </Typography>
        </Stack>

        <TableContainer component={Paper} variant="outlined">
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Part</TableCell>
                <TableCell align="right">Quoted piece price</TableCell>
                <TableCell>Based on</TableCell>
                <TableCell align="right">Margin per piece (internal)</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {quotation.content.lines.map((line) => {
                const basis = quotation.basis.find((b) => b.rfqItemId === line.rfqItemId)
                return (
                  <TableRow key={line.rfqItemId}>
                    <TableCell>{line.partNumber}</TableCell>
                    <TableCell align="right">{formatMoney(line.ergoQuote.productionPiecePrice, line.ergoQuote.currency.code)}</TableCell>
                    <TableCell>{basis ? `${basis.supplier.name}, version ${basis.supplierVersion}` : '—'}</TableCell>
                    <TableCell align="right">
                      {basis?.marginAmount == null ? '—' : `${formatMoney(basis.marginAmount, line.ergoQuote.currency.code)} (${basis.marginPercentage ?? '—'}%)`}
                    </TableCell>
                  </TableRow>
                )
              })}
            </TableBody>
          </Table>
        </TableContainer>
        {quotation.content.remarks && (
          <Typography variant="body2">
            <strong>Remarks to the customer:</strong> {quotation.content.remarks}
          </Typography>
        )}
        {quotation.respondedAt && (
          <Typography sx={{ whiteSpace: 'pre-wrap' }}>
            <strong>{status.label}</strong> by {quotation.respondedBy ?? '—'} on {formatDate(quotation.respondedAt)}
            {quotation.responseComment && `: ${quotation.responseComment}`}
          </Typography>
        )}
      </CardContent>
    </Card>
  )
}
