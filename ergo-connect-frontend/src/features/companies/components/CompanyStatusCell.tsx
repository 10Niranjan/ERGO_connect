import { Chip, Stack, Typography } from '@mui/material'
import { STATUS_COLORS, STATUS_LABELS, type Company } from '../companiesApi'

/**
 * The status chip, plus for ERGO what is still needed before the company can be sent for approval (client questions
 * 15 and 19) and Management's latest comment when it sent the company back or rejected it. Both come only in ERGO's
 * view of a company, so a customer or supplier never sees them.
 */
export function CompanyStatusCell({ company }: { company: Company }) {
  const showComment = (company.status === 'INFO_REQUESTED' || company.status === 'REJECTED') && company.reviewComment
  return (
    <Stack spacing={0.5} sx={{ alignItems: 'flex-start', maxWidth: 260 }}>
      <Chip size="small" color={STATUS_COLORS[company.status]} label={STATUS_LABELS[company.status]} />
      {showComment && (
        <Typography variant="caption" color="text.secondary">
          Management: {company.reviewComment}
        </Typography>
      )}
      {company.missing && company.missing.length > 0 && (
        <Typography variant="caption" color="warning.main">
          Still needed: {company.missing.join(', ')}
        </Typography>
      )}
    </Stack>
  )
}
