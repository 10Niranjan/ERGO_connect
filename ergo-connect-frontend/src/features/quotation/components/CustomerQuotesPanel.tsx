import { Alert, Box, Button, Card, CardContent, Chip, LinearProgress, Stack, Typography } from '@mui/material'
import { useState } from 'react'
import { CommentDialog } from '../../../shared/components/CommentDialog'
import { Facts } from '../../../shared/components/Facts'
import { problemMessage } from '../../../shared/problem'
import { formatDate } from '../../rfq/labels'
import { useCustomerQuotesQuery, useRespondCustomerQuoteMutation, type CustomerQuoteView, type CustomerResponse } from '../comparisonApi'
import { customerSupplierQuoteSections, ergoQuoteSections, RESPONSE_STATUS } from '../labels'

/** How each answer asks for its comment (SRS §14.4: required to request a revision or decline). */
const ANSWERS: Record<CustomerResponse, { title: string; label: string; confirm: string; required: boolean; danger?: boolean }> = {
  APPROVED: { title: 'Approve the quote', label: 'Comment (optional)', confirm: 'Approve', required: false },
  REVISION_REQUESTED: { title: 'Request a revision', label: 'What should change', confirm: 'Request revision', required: true },
  DECLINED: { title: 'Decline the quote', label: 'Reason for declining', confirm: 'Decline', required: true, danger: true },
}

/** SRS §12.7.2: the quotes ERGO sent this customer, newest first, and the customer's answer. */
export function CustomerQuotesPanel({ rfqId }: { rfqId: number }) {
  const { data, isLoading, error } = useCustomerQuotesQuery(rfqId)
  if (isLoading) return <LinearProgress />
  if (!data || data.audience !== 'CUSTOMER') return <Alert severity="error">{problemMessage(error)}</Alert>
  if (data.quotes.length === 0) return <Typography color="text.secondary">ERGO has not sent you a quote for this RFQ yet.</Typography>
  return (
    <Stack spacing={2}>
      {data.quotes.map((q) => (
        <SentQuote key={q.id} quote={q} />
      ))}
    </Stack>
  )
}

function SentQuote({ quote }: { quote: CustomerQuoteView }) {
  const [answer, setAnswer] = useState<CustomerResponse | null>(null)
  const [respond, responding] = useRespondCustomerQuoteMutation()
  const { content } = quote
  const status = RESPONSE_STATUS[quote.status]

  async function send(response: CustomerResponse, comment: string) {
    try {
      await respond({ id: quote.id, response, comment: comment || null }).unwrap()
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
              {content.type === 'SUPPLIER_QUOTE' ? `Quote from ${content.supplierName}` : 'Quote from ERGO'}
            </Typography>
            <Chip size="small" label={status.customer} color={status.color} />
          </Stack>
          <Typography variant="body2" color="text.secondary">
            Sent {formatDate(quote.sentAt)}
          </Typography>
        </Stack>

        {content.remarks && (
          <Alert severity="info">
            <strong>Remarks from ERGO:</strong> {content.remarks}
          </Alert>
        )}
        {content.supplierQuote && (
          <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', md: 'repeat(3, 1fr)' } }}>
            {customerSupplierQuoteSections(content.supplierQuote).map((section) => (
              <Facts key={section.title} title={section.title} facts={section.facts} />
            ))}
          </Box>
        )}
        {content.ergoQuote && (
          <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', md: 'repeat(3, 1fr)' } }}>
            {ergoQuoteSections(content.ergoQuote).map((section) => (
              <Facts key={section.title} title={section.title} facts={section.facts} />
            ))}
          </Box>
        )}

        {quote.respondedAt && (
          <Typography sx={{ whiteSpace: 'pre-wrap' }}>
            <strong>{status.customer}</strong> by {quote.respondedBy ?? 'your company'} on {formatDate(quote.respondedAt)}
            {quote.responseComment && `: ${quote.responseComment}`}
          </Typography>
        )}
        {quote.actions.length > 0 && (
          <Stack direction="row" spacing={1}>
            {quote.actions.includes('APPROVE') && (
              <Button variant="contained" onClick={() => setAnswer('APPROVED')}>
                Approve
              </Button>
            )}
            {quote.actions.includes('REQUEST_REVISION') && <Button onClick={() => setAnswer('REVISION_REQUESTED')}>Request revision</Button>}
            {quote.actions.includes('DECLINE') && (
              <Button color="error" onClick={() => setAnswer('DECLINED')}>
                Decline
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
