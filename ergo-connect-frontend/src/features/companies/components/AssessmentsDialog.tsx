import {
  Alert,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  LinearProgress,
  Link,
  MenuItem,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  TextField,
  Typography,
} from '@mui/material'
import { useState, type FormEvent } from 'react'
import { problemMessage } from '../../../shared/problem'
import type { Company } from '../companiesApi'
import {
  AUDIT_TYPE_LABELS,
  RECOMMENDATION_LABELS,
  RISK_LABELS,
  useAddAssessmentMutation,
  useAssessmentsQuery,
  useDownloadAssessmentFileMutation,
  type AssessmentForm,
  type AuditType,
  type Recommendation,
  type RiskRating,
} from '../services/assessmentsApi'

const today = () => new Date().toISOString().slice(0, 10)

/**
 * A supplier's audit reports (report file plus summary, added by ERGO Quality) or a customer's assessments (desk review
 * notes, risk rating, optional file). ERGO-internal history: entries are never changed, a correction is a new entry.
 */
export function AssessmentsDialog({ company, onClose }: { company: Company; onClose: () => void }) {
  const { data, isLoading, error } = useAssessmentsQuery(company.id)
  const [add, adding] = useAddAssessmentMutation()
  const [download] = useDownloadAssessmentFileMutation()
  const [file, setFile] = useState<File | null>(null)
  const [formKey, setFormKey] = useState(0)
  const audit = company.type === 'SUPPLIER'
  const title = audit ? 'Audit reports' : 'Customer assessments'

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const f = new FormData(event.currentTarget)
    const text = (name: string) => String(f.get(name) ?? '').trim()
    const form: AssessmentForm = audit
      ? {
          assessmentDate: text('assessmentDate'),
          auditType: text('auditType') as AuditType,
          auditor: text('auditor'),
          findings: text('findings'),
          recommendation: text('recommendation') as Recommendation,
        }
      : { assessmentDate: text('assessmentDate'), findings: text('findings'), riskRating: text('riskRating') as RiskRating }
    try {
      await add({ companyId: company.id, form, file }).unwrap()
      setFile(null)
      setFormKey((k) => k + 1) // clears the form for the next entry
    } catch {
      // shown below
    }
  }

  async function open(assessmentId: number, fallbackName: string) {
    try {
      const { url, fileName } = await download({ companyId: company.id, assessmentId }).unwrap()
      const a = document.createElement('a')
      a.href = url
      a.download = fileName || fallbackName
      a.click()
      setTimeout(() => URL.revokeObjectURL(url), 10_000)
    } catch {
      // the list stays usable; a failed download changes nothing
    }
  }

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="md">
      <DialogTitle>
        {title}: {company.profile.name}
      </DialogTitle>
      <DialogContent sx={{ display: 'grid', gap: 2 }}>
        <Typography variant="body2" color="text.secondary">
          {audit
            ? 'Seen only inside ERGO. A supplier needs at least one audit report before it can be sent for approval.'
            : 'Seen only inside ERGO. A desk review of the customer with a risk rating.'}
        </Typography>
        {isLoading && <LinearProgress />}
        {error != null && <Alert severity="error">{problemMessage(error)}</Alert>}
        {data && data.items.length === 0 && (
          <Typography variant="body2" color="text.secondary">
            {audit ? 'No audit reports yet.' : 'No assessments yet.'}
          </Typography>
        )}
        {data && data.items.length > 0 && (
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Date</TableCell>
                <TableCell>{audit ? 'Type and auditor' : 'Risk'}</TableCell>
                <TableCell>{audit ? 'Findings' : 'Notes'}</TableCell>
                {audit && <TableCell>Recommendation</TableCell>}
                <TableCell>File</TableCell>
                <TableCell>Added by</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {data.items.map((a) => (
                <TableRow key={a.id}>
                  <TableCell sx={{ whiteSpace: 'nowrap' }}>{a.assessmentDate}</TableCell>
                  <TableCell>
                    {audit ? (
                      <>
                        {a.auditType && AUDIT_TYPE_LABELS[a.auditType]}
                        <br />
                        <Typography variant="caption">{a.auditor}</Typography>
                      </>
                    ) : (
                      a.riskRating && (
                        <Chip size="small" label={RISK_LABELS[a.riskRating]} color={a.riskRating === 'HIGH' ? 'error' : a.riskRating === 'MEDIUM' ? 'warning' : 'success'} />
                      )
                    )}
                  </TableCell>
                  <TableCell sx={{ whiteSpace: 'pre-wrap', maxWidth: 320 }}>{a.findings}</TableCell>
                  {audit && (
                    <TableCell>
                      {a.recommendation && (
                        <Chip size="small" label={RECOMMENDATION_LABELS[a.recommendation]} color={a.recommendation === 'APPROVE' ? 'success' : 'error'} />
                      )}
                    </TableCell>
                  )}
                  <TableCell>
                    {a.file ? (
                      <Link component="button" type="button" onClick={() => void open(a.id, a.file!.fileName)}>
                        {a.file.fileName}
                      </Link>
                    ) : (
                      '—'
                    )}
                  </TableCell>
                  <TableCell>
                    {a.createdBy}
                    <br />
                    <Typography variant="caption" color="text.secondary">
                      {new Date(a.createdAt).toLocaleString()}
                    </Typography>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
        {data?.canAdd && (
          <>
            <Divider />
            <Typography variant="subtitle2">{audit ? 'Add an audit report' : 'Add an assessment'}</Typography>
            <Stack component="form" key={formKey} onSubmit={handleSubmit} spacing={2}>
              <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
                <TextField name="assessmentDate" label="Date" type="date" required defaultValue={today()} slotProps={{ inputLabel: { shrink: true }, htmlInput: { max: today() } }} />
                {audit ? (
                  <>
                    <TextField name="auditType" label="Audit type" select required defaultValue="ON_SITE" sx={{ minWidth: 160 }}>
                      {(Object.keys(AUDIT_TYPE_LABELS) as AuditType[]).map((t) => (
                        <MenuItem key={t} value={t}>
                          {AUDIT_TYPE_LABELS[t]}
                        </MenuItem>
                      ))}
                    </TextField>
                    <TextField name="auditor" label="Auditor" required slotProps={{ htmlInput: { maxLength: 200 } }} sx={{ flex: 1 }} />
                    <TextField name="recommendation" label="Recommendation" select required defaultValue="APPROVE" sx={{ minWidth: 160 }}>
                      {(Object.keys(RECOMMENDATION_LABELS) as Recommendation[]).map((r) => (
                        <MenuItem key={r} value={r}>
                          {RECOMMENDATION_LABELS[r]}
                        </MenuItem>
                      ))}
                    </TextField>
                  </>
                ) : (
                  <TextField name="riskRating" label="Risk rating" select required defaultValue="LOW" sx={{ minWidth: 160 }}>
                    {(Object.keys(RISK_LABELS) as RiskRating[]).map((r) => (
                      <MenuItem key={r} value={r}>
                        {RISK_LABELS[r]}
                      </MenuItem>
                    ))}
                  </TextField>
                )}
              </Stack>
              <TextField name="findings" label={audit ? 'Findings' : 'Desk review notes'} required multiline minRows={3} slotProps={{ htmlInput: { maxLength: 4000 } }} />
              <Stack direction="row" spacing={2} sx={{ alignItems: 'center' }}>
                <Button component="label" variant="outlined" size="small">
                  {audit ? 'Choose report file' : 'Choose file (optional)'}
                  <input hidden type="file" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
                </Button>
                <Typography variant="body2" color={file ? 'text.primary' : 'text.secondary'}>
                  {file ? file.name : audit ? 'Required' : 'No file'}
                </Typography>
              </Stack>
              {adding.error != null && <Alert severity="error">{problemMessage(adding.error)}</Alert>}
              <Stack direction="row" sx={{ justifyContent: 'flex-end' }}>
                <Button type="submit" variant="contained" loading={adding.isLoading} disabled={audit && !file}>
                  {audit ? 'Add audit report' : 'Add assessment'}
                </Button>
              </Stack>
            </Stack>
          </>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Close</Button>
      </DialogActions>
    </Dialog>
  )
}
